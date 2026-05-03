package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.interruption.AgentInterruptedException;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 的流式聊天客户端。
 *
 * <p><b>协议要点</b>：
 * <ul>
 *   <li>Endpoint: {@code POST /v1/messages}</li>
 *   <li>Headers: {@code x-api-key} + {@code anthropic-version: 2023-06-01}</li>
 *   <li>System 提示走 top-level {@code system} 字段</li>
 *   <li>SSE 事件流：{@code message_start} / {@code content_block_start} /
 *       {@code content_block_delta} / {@code content_block_stop} /
 *       {@code message_delta}（带 usage 与 stop_reason） / {@code message_stop}</li>
 * </ul>
 *
 * <p><b>Prompt caching</b>（关键优化，固定 3 个断点）：
 * <ol>
 *   <li>system 块尾 —— 缓存系统提示（永久不变）</li>
 *   <li>tools 数组最后一个工具尾 —— 缓存全部工具定义（agent 生命周期内不变）</li>
 *   <li>最后一条消息最后一个 content block 尾 —— 缓存截至本轮的完整对话历史，
 *       下轮请求整段历史命中 cache_read</li>
 * </ol>
 * Anthropic 协议允许最多 4 个断点；用 3 个能让多轮对话第二轮起几乎全部走 cache_read，
 * 价格约正常输入价的 10%。
 *
 * <p>境内访问需要走代理；通过共享 WebClient bean 已配置好的 HttpClient.proxy 透传。
 */
public class AnthropicChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatModel.class);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final String apiKey;
    private final String modelName;
    private final String endpoint;
    private final boolean logHttp;
    private final boolean enablePromptCache;
    private final int defaultMaxTokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessageConverter converter;

    private AnthropicChatModel(Builder b) {
        if (b.modelName == null || b.modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 必填");
        }
        this.apiKey = b.apiKey;
        this.modelName = b.modelName;
        String base = b.baseUrl != null ? stripTrailingSlash(b.baseUrl) : DEFAULT_BASE_URL;
        this.endpoint = base + MESSAGES_PATH;
        this.logHttp = b.logHttp;
        this.enablePromptCache = b.enablePromptCache;
        this.defaultMaxTokens = b.defaultMaxTokens > 0 ? b.defaultMaxTokens : 4096;
        this.webClient = b.webClient != null ? b.webClient
                : WebClient.builder().clientConnector(new JdkClientHttpConnector()).build();
        this.converter = new AnthropicMessageConverter(mapper);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                  GenerateOptions options, CancellationToken token,
                                  String sessionId) {  // Anthropic 用 cache_control 标记，不需要 sessionId
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "apiKey 缺失：请配置 agentscope.model.api-key（Anthropic）或对应环境变量"));
        }

        final ObjectNode body;
        final String bodyJson;
        try {
            body = buildBody(messages, tools, options);
            bodyJson = mapper.writeValueAsString(body);
        } catch (IOException e) {
            return Flux.error(new RuntimeException("构造 Anthropic 请求失败", e));
        }
        if (logHttp) {
            log.info("[HTTP >>] POST {} {}", endpoint, bodyJson);
        }

        Flux<String> sseFlux = webClient.post()
                .uri(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(errBody -> {
                            log.error("[HTTP <<] {} 错误响应：{}", resp.statusCode(), errBody);
                            return Mono.error(new RuntimeException(
                                    "Anthropic API 错误 " + resp.statusCode() + "：" + errBody));
                        }))
                .bodyToFlux(String.class);

        // 跨多个 SSE 事件维护的解析状态：每个 content_block 的 index → 工具调用临时缓冲（用于 input_json_delta）
        StreamState state = new StreamState();

        return Flux.<ChatChunk>create(sink -> {
            Disposable disposable = sseFlux
                    .doOnNext(json -> { if (logHttp) log.info("[HTTP <<] {}", json); })
                    .<ChatChunk>handle((json, downstream) -> {
                        ChatChunk chunk = parseEvent(json, state);
                        if (chunk != null) downstream.next(chunk);
                    })
                    .subscribe(sink::next, sink::error, sink::complete);

            if (token != null) {
                token.onCancel(() -> {
                    disposable.dispose();
                    Schedulers.parallel().schedule(() -> {
                        if (!sink.isCancelled()) {
                            sink.error(new AgentInterruptedException("Anthropic stream cancelled"));
                        }
                    });
                });
            }
            sink.onCancel(disposable::dispose);
            sink.onDispose(disposable::dispose);
        });
    }

    /** 解析 Anthropic SSE 事件为 ChatChunk；非业务事件返回 {@code null}。 */
    private ChatChunk parseEvent(String json, StreamState state) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(json);
            String type = root.path("type").asText("");
            switch (type) {
                case "message_start" -> {
                    // 仅记录 input tokens（最终 chunk 一并下发 usage）
                    JsonNode usage = root.path("message").path("usage");
                    state.inputTokens = usage.path("input_tokens").asInt(0);
                    state.cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                    state.cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                    return null;
                }
                case "content_block_start" -> {
                    int idx = root.path("index").asInt(0);
                    JsonNode block = root.path("content_block");
                    String blockType = block.path("type").asText("");
                    if ("tool_use".equals(blockType)) {
                        // 记下这个 index 对应的工具调用 id+name，后续 input_json_delta 拼装
                        ToolBuf buf = new ToolBuf();
                        buf.id = block.path("id").asText("");
                        buf.name = block.path("name").asText("");
                        state.toolBufs.put(idx, buf);
                        return ChatChunk.toolCalls(List.of(
                                new ToolCallDelta(idx, buf.id, buf.name, "")));
                    }
                    return null;
                }
                case "content_block_delta" -> {
                    int idx = root.path("index").asInt(0);
                    JsonNode delta = root.path("delta");
                    String dType = delta.path("type").asText("");
                    return switch (dType) {
                        case "text_delta" -> ChatChunk.text(delta.path("text").asText(""));
                        case "thinking_delta" -> ChatChunk.thinking(delta.path("thinking").asText(""));
                        case "input_json_delta" -> {
                            ToolBuf buf = state.toolBufs.get(idx);
                            String partial = delta.path("partial_json").asText("");
                            // 后续片段只追加 args，id/name 已经在 content_block_start 给过了
                            yield ChatChunk.toolCalls(List.of(
                                    new ToolCallDelta(idx, null, null, partial)));
                        }
                        default -> null;
                    };
                }
                case "message_delta" -> {
                    // 这里携带 stop_reason 与最终的完整 usage（含 cache_*）
                    String stopReason = root.path("delta").path("stop_reason").asText(null);
                    JsonNode usage = root.path("usage");
                    // message_delta 的 usage 是最终值；4 个字段都要覆盖一次（message_start 时 cache_*
                    // 字段可能是 0，要靠这里更新）
                    if (usage.has("input_tokens")) {
                        state.inputTokens = usage.path("input_tokens").asInt(0);
                    }
                    if (usage.has("cache_read_input_tokens")) {
                        state.cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                    }
                    if (usage.has("cache_creation_input_tokens")) {
                        state.cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                    }
                    state.outputTokens = usage.path("output_tokens").asInt(0);
                    state.stopReason = stopReason;
                    return null;
                }
                case "message_stop" -> {
                    // 关键：Anthropic 的 input_tokens 仅含"非 cache、非 cache_creation"的纯新 token，
                    // 跟 OpenAI 的 prompt_tokens（= 总输入含 cache 命中）含义不同。这里把三段加起来，
                    // 对齐 OpenAI 语义，让前端的 cache 命中率（cachedInputTokens / inputTokens）正确。
                    int totalInput = state.inputTokens + state.cacheRead + state.cacheCreation;
                    ChatUsage usage = ChatUsage.builder()
                            .inputTokens(totalInput)
                            .outputTokens(state.outputTokens)
                            .cachedInputTokens(state.cacheRead)
                            .cacheCreationTokens(state.cacheCreation)
                            .build();
                    String reason = state.stopReason != null ? state.stopReason : "stop";
                    return ChatChunk.finish(reason, usage);
                }
                default -> { return null; }
            }
        } catch (Exception e) {
            log.warn("解析 Anthropic SSE 事件失败：{}", e.getMessage());
            return null;
        }
    }

    /** 包级可见以便单元测试直接验证请求 body 结构（缓存断点位置等）。 */
    ObjectNode buildBody(List<Msg> messages, List<ToolSchema> tools,
                         GenerateOptions options) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);
        body.put("stream", true);

        // max_tokens 在 Anthropic 是必填字段
        Integer mt = options != null ? options.getMaxTokens() : null;
        body.put("max_tokens", mt != null && mt > 0 ? mt : defaultMaxTokens);

        // System：用块数组形式，便于挂 cache_control
        String system = converter.extractSystem(messages);
        if (system != null && !system.isBlank()) {
            ArrayNode sysArr = body.putArray("system");
            ObjectNode sysBlock = sysArr.addObject();
            sysBlock.put("type", "text");
            sysBlock.put("text", system);
            if (enablePromptCache) {
                sysBlock.set("cache_control",
                        mapper.createObjectNode().put("type", "ephemeral"));
            }
        }

        ArrayNode arr = body.putArray("messages");
        converter.convert(messages).forEach(arr::add);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = body.putArray("tools");
            for (int i = 0; i < tools.size(); i++) {
                ToolSchema t = tools.get(i);
                ObjectNode tn = toolsArr.addObject();
                tn.put("name", t.name());
                if (t.description() != null) tn.put("description", t.description());
                if (t.parameters() != null) tn.set("input_schema", mapper.valueToTree(t.parameters()));
                // 断点 #2：标最后一个工具上 —— Anthropic 的缓存边界是"包含且向前"，
                // 等于把整个 tools 数组连同 system 一起进缓存
                if (enablePromptCache && i == tools.size() - 1) {
                    tn.set("cache_control",
                            mapper.createObjectNode().put("type", "ephemeral"));
                }
            }
        }

        // 断点 #3：标在最后一条消息的最后一个 content block 上 —— 这样本轮请求的完整上下文
        // 都进缓存，下一轮请求里整个历史段直接 cache_read 命中。多轮对话越长收益越大。
        if (enablePromptCache && !arr.isEmpty()) {
            applyConversationCacheBreakpoint(arr);
        }

        applyOptions(body, options);
        return body;
    }

    /**
     * 在 {@code messages} 数组最后一条消息的最后一个 content block 上挂 cache_control。
     * 这样下一轮请求里"截至本轮"的整个对话历史都属于稳定前缀，可被命中。
     */
    private void applyConversationCacheBreakpoint(ArrayNode messagesArr) {
        ObjectNode lastMsg = (ObjectNode) messagesArr.get(messagesArr.size() - 1);
        JsonNode content = lastMsg.path("content");
        if (!content.isArray() || content.isEmpty()) return;
        ArrayNode contentArr = (ArrayNode) content;
        ObjectNode lastBlock = (ObjectNode) contentArr.get(contentArr.size() - 1);
        lastBlock.set("cache_control",
                mapper.createObjectNode().put("type", "ephemeral"));
    }

    private void applyOptions(ObjectNode body, GenerateOptions opts) {
        if (opts == null) return;
        if (opts.getTemperature() != null) body.put("temperature", opts.getTemperature());
        if (opts.getTopP() != null) body.put("top_p", opts.getTopP());
        if (opts.getStopSequences() != null && !opts.getStopSequences().isEmpty()) {
            ArrayNode stop = body.putArray("stop_sequences");
            opts.getStopSequences().forEach(stop::add);
        }
        // frequency_penalty / presence_penalty 在 Anthropic 不存在，忽略
        opts.getExtraParams().forEach((k, v) -> body.set(k, mapper.valueToTree(v)));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 跨多条 SSE 事件累积的状态（每个 stream 一个）。 */
    private static final class StreamState {
        int inputTokens, outputTokens, cacheRead, cacheCreation;
        String stopReason;
        final Map<Integer, ToolBuf> toolBufs = new HashMap<>();
    }
    private static final class ToolBuf { String id; String name; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private WebClient webClient;
        private String apiKey;
        private String modelName;
        private String baseUrl;
        private boolean logHttp = false;
        private boolean enablePromptCache = true;
        private int defaultMaxTokens = 4096;

        public Builder webClient(WebClient webClient) { this.webClient = webClient; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder logHttp(boolean v) { this.logHttp = v; return this; }
        /** 是否在 system + tools 上挂 cache_control。默认开。 */
        public Builder enablePromptCache(boolean v) { this.enablePromptCache = v; return this; }
        /** 默认 max_tokens（请求内 GenerateOptions 未指定时使用）。 */
        public Builder defaultMaxTokens(int v) { this.defaultMaxTokens = v; return this; }

        public AnthropicChatModel build() { return new AnthropicChatModel(this); }
    }
}
