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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Google Generative Language API 的流式聊天客户端（{@code generativelanguage.googleapis.com}）。
 *
 * <p><b>协议要点</b>：
 * <ul>
 *   <li>Endpoint: {@code POST /v1beta/models/{model}:streamGenerateContent?alt=sse&key=...}</li>
 *   <li>Auth: API key 走 query 参数（不是 header）</li>
 *   <li>SSE 数据：每个事件 data 是一个完整的 {@code GenerateContentResponse} JSON</li>
 *   <li>System 走 top-level {@code systemInstruction}</li>
 *   <li>Tools 用 {@code [{functionDeclarations:[...]}]} 包装</li>
 * </ul>
 *
 * <p>Gemini 没有 prompt cache 控制字段（自动在服务端处理），也不支持 OpenAI 的 reasoning_content。
 * 境内访问需走代理；通过共享 WebClient 的 HttpClient.proxy 透传。
 */
public class GeminiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final WebClient webClient;
    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final boolean logHttp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiMessageConverter converter;

    private GeminiChatModel(Builder b) {
        if (b.modelName == null || b.modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 必填");
        }
        this.apiKey = b.apiKey;
        this.modelName = b.modelName;
        this.baseUrl = b.baseUrl != null ? stripTrailingSlash(b.baseUrl) : DEFAULT_BASE_URL;
        this.logHttp = b.logHttp;
        this.webClient = b.webClient != null ? b.webClient
                : WebClient.builder().clientConnector(new JdkClientHttpConnector()).build();
        this.converter = new GeminiMessageConverter(mapper);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                  GenerateOptions options, CancellationToken token,
                                  String sessionId) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "apiKey 缺失：请配置 agentscope.model.api-key（Gemini）或对应环境变量"));
        }

        final String endpoint = baseUrl + "/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse&key=" + apiKey;
        final String endpointForLog = baseUrl + "/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse&key=***";

        // Gemini 2.5+ 默认开启隐式缓存（implicit caching）：服务端自动检测请求前缀重复并命中，
        // 命中信息通过响应的 usageMetadata.cachedContentTokenCount 反馈。客户端无需做任何事，
        // 只需保持稳定的 prompt 前缀（system / tools / 历史顺序固定）即可。
        final ObjectNode body;
        final String bodyJson;
        try {
            body = buildBody(messages, tools, options);
            bodyJson = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return Flux.error(new RuntimeException("构造 Gemini 请求失败", e));
        }
        if (logHttp) {
            log.info("[HTTP >>] POST {} {}", endpointForLog, bodyJson);
        }

        Flux<String> sseFlux = webClient.post()
                .uri(endpoint)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(errBody -> {
                            log.error("[HTTP <<] {} 错误响应：{}", resp.statusCode(), errBody);
                            return Mono.error(new RuntimeException(
                                    "Gemini API 错误 " + resp.statusCode() + "：" + errBody));
                        }))
                .bodyToFlux(String.class);

        // Gemini 把 functionCall 当成一次性完整对象（没有跨事件的 streaming args），
        // 我们仍要分配一个唯一 toolCall id；用一个共享计数器跨事件递增 index
        AtomicInteger toolIdx = new AtomicInteger(0);

        return Flux.<ChatChunk>create(sink -> {
            Disposable disposable = sseFlux
                    .doOnNext(json -> { if (logHttp) log.info("[HTTP <<] {}", json); })
                    .<ChatChunk>handle((json, downstream) -> {
                        for (ChatChunk c : parseEvent(json, toolIdx)) {
                            downstream.next(c);
                        }
                    })
                    .subscribe(sink::next, sink::error, sink::complete);

            if (token != null) {
                token.onCancel(() -> {
                    disposable.dispose();
                    Schedulers.parallel().schedule(() -> {
                        if (!sink.isCancelled()) {
                            sink.error(new AgentInterruptedException("Gemini stream cancelled"));
                        }
                    });
                });
            }
            sink.onCancel(disposable::dispose);
            sink.onDispose(disposable::dispose);
        });
    }

    /** 解析一条 Gemini SSE 事件 → 0..N 个 ChatChunk。一次响应可能同时含 text + functionCall + finish。
     *  包级可见以便单元测试直接验证 usage 累加逻辑（含 thinking model 的 thoughtsTokenCount）。 */
    List<ChatChunk> parseEvent(String json, AtomicInteger toolIdx) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode candidates = root.path("candidates");
            JsonNode usage = root.path("usageMetadata");
            String finishReason = null;

            List<ChatChunk> out = new java.util.ArrayList<>();
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode cand = candidates.get(0);
                JsonNode parts = cand.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            String t = part.path("text").asText("");
                            if (!t.isEmpty()) out.add(ChatChunk.text(t));
                        } else if (part.has("functionCall")) {
                            JsonNode fc = part.path("functionCall");
                            String name = fc.path("name").asText("");
                            String args = mapper.writeValueAsString(fc.path("args"));
                            int idx = toolIdx.getAndIncrement();
                            // Gemini 没有 id，用 "gemini-call-{idx}" 合成一个稳定 id
                            String id = "gemini-call-" + idx;
                            // thoughtSignature 是 functionCall 平级字段，必须在下一轮原样回写，
                            // 否则 Gemini thinking 模型会拒绝请求（400 missing thought_signature）
                            String thoughtSig = part.path("thoughtSignature").asText(null);
                            out.add(ChatChunk.toolCalls(List.of(
                                    new ToolCallDelta(idx, id, name, args, thoughtSig))));
                        }
                    }
                }
                String fr = cand.path("finishReason").asText(null);
                if (fr != null && !fr.isBlank()) finishReason = fr;
            }

            if (finishReason != null) {
                // Gemini thinking 模型（gemini-3-flash-preview / gemini-2.5-* 等）会单独报
                // thoughtsTokenCount，跟 candidatesTokenCount 平行；都是 output 计费。
                // 累加进 outputTokens 才能对齐 totalTokenCount，跟 OpenAI/DeepSeek 含 reasoning 的语义一致。
                int visibleOutput = usage.path("candidatesTokenCount").asInt(0);
                int thoughts = usage.path("thoughtsTokenCount").asInt(0);
                ChatUsage u = ChatUsage.builder()
                        .inputTokens(usage.path("promptTokenCount").asInt(0))
                        .outputTokens(visibleOutput + thoughts)
                        .cachedInputTokens(usage.path("cachedContentTokenCount").asInt(0))
                        .build();
                out.add(ChatChunk.finish(finishReason.toLowerCase(java.util.Locale.ROOT), u));
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 Gemini SSE 事件失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 把 messages/tools/options 组装成 Gemini streamGenerateContent 请求体。 */
    private ObjectNode buildBody(List<Msg> messages, List<ToolSchema> tools,
                                 GenerateOptions options) throws IOException {
        ObjectNode body = mapper.createObjectNode();

        // systemInstruction
        String system = converter.extractSystem(messages);
        if (system != null && !system.isBlank()) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }

        // tools
        if (tools != null && !tools.isEmpty()) {
            ArrayNode decls = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSchema t : tools) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                if (t.description() != null) d.put("description", t.description());
                if (t.parameters() != null) {
                    d.set("parameters", sanitizeJsonSchemaForGemini(mapper.valueToTree(t.parameters())));
                }
            }
        }

        // contents
        ArrayNode arr = body.putArray("contents");
        converter.convert(messages).forEach(arr::add);

        applyOptions(body, options);
        return body;
    }

    /**
     * Gemini 的 schema 比 OpenAI 严格：不接受 {@code additionalProperties}、{@code $schema}、
     * {@code title} 等额外字段；嵌套 object 也必须满足同样约束。这里递归剥掉这些字段，
     * 防止 400 错误。
     */
    private JsonNode sanitizeJsonSchemaForGemini(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return node;
        ObjectNode obj = (ObjectNode) node.deepCopy();
        obj.remove("$schema");
        obj.remove("additionalProperties");
        obj.remove("title");
        obj.remove("definitions");
        obj.remove("$defs");
        // 递归处理 properties / items
        JsonNode props = obj.path("properties");
        if (props.isObject()) {
            ObjectNode propsObj = (ObjectNode) props;
            propsObj.fieldNames().forEachRemaining(name ->
                    propsObj.set(name, sanitizeJsonSchemaForGemini(propsObj.get(name))));
        }
        JsonNode items = obj.path("items");
        if (items.isObject()) {
            obj.set("items", sanitizeJsonSchemaForGemini(items));
        }
        return obj;
    }

    private void applyOptions(ObjectNode body, GenerateOptions opts) {
        if (opts == null) return;
        ObjectNode gc = mapper.createObjectNode();
        if (opts.getTemperature() != null) gc.put("temperature", opts.getTemperature());
        if (opts.getTopP() != null) gc.put("topP", opts.getTopP());
        if (opts.getMaxTokens() != null) gc.put("maxOutputTokens", opts.getMaxTokens());
        if (opts.getStopSequences() != null && !opts.getStopSequences().isEmpty()) {
            ArrayNode stop = gc.putArray("stopSequences");
            opts.getStopSequences().forEach(stop::add);
        }
        if (!gc.isEmpty()) body.set("generationConfig", gc);
        // frequency_penalty / presence_penalty 在 Gemini 没有标准字段，忽略
        opts.getExtraParams().forEach((k, v) -> body.set(k, mapper.valueToTree(v)));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private WebClient webClient;
        private String apiKey;
        private String modelName;
        private String baseUrl;
        private boolean logHttp = false;

        public Builder webClient(WebClient webClient) { this.webClient = webClient; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder logHttp(boolean v) { this.logHttp = v; return this; }

        public GeminiChatModel build() { return new GeminiChatModel(this); }
    }
}
