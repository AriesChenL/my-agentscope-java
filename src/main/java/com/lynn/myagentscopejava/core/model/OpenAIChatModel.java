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
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 OpenAI 兼容协议的流式聊天客户端。
 *
 * <p>适用于实现 {@code /v1/chat/completions} 协议的任何服务：
 * OpenAI、DeepSeek、Moonshot、OpenRouter、vLLM 等。切换 provider 只需修改 {@code baseUrl}。
 *
 * <p><b>HTTP 客户端：</b>使用 Spring 的 {@link WebClient}（reactor-native）。SSE 解析直接走
 * {@code bodyToFlux(String.class)}；订阅取消（包括 {@link CancellationToken#cancel()}）会自动
 * 级联取消上游 HTTP 请求。
 *
 * <p><b>构造方式：</b>由 Spring 配置（{@code AgentAutoConfiguration}）提供共享的 {@code WebClient}
 * bean 通过构造器注入。本类自身不带 {@code @Component} 注解，所有依赖显式经构造器传入。
 *
 * <p><b>Prompt cache：</b>DeepSeek / OpenAI 的 prompt cache 在服务端自动启用，客户端无需配置。
 * 本类会从 {@code usage} 块解析 cache 命中字段并暴露在 {@link ChatUsage} 上。
 */
public class OpenAIChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String CHAT_PATH = "/v1/chat/completions";

    private final WebClient webClient;
    private final String apiKey;
    private final String modelName;
    private final String endpoint;
    private final boolean logHttp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAIMessageConverter converter;

    private OpenAIChatModel(Builder b) {
        if (b.modelName == null || b.modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 必填");
        }
        this.apiKey = b.apiKey;
        this.modelName = b.modelName;
        String base = b.baseUrl != null ? stripTrailingSlash(b.baseUrl) : DEFAULT_BASE_URL;
        this.endpoint = base + CHAT_PATH;
        this.logHttp = b.logHttp;
        // 没传 WebClient 时构造一个默认的（基于 JDK HttpClient，不引 Netty）
        this.webClient = b.webClient != null ? b.webClient
                : WebClient.builder().clientConnector(new JdkClientHttpConnector()).build();
        this.converter = new OpenAIMessageConverter(mapper);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                  GenerateOptions options, CancellationToken token,
                                  String sessionId) {  // sessionId 在 OpenAI 协议下用不到，仅占位
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "apiKey 缺失：请在 application-local.properties 中配置 agentscope.model.api-key，"
                            + "或设置对应的环境变量。"));
        }

        final ObjectNode body;
        final String bodyJson;
        try {
            body = buildBody(messages, tools, options);
            bodyJson = mapper.writeValueAsString(body);
        } catch (IOException e) {
            return Flux.error(new RuntimeException("构造请求失败", e));
        }
        if (logHttp) {
            // 单行紧凑 JSON：bodyJson 已经是 mapper 序列化结果，不再 pretty-print
            log.info("[HTTP >>] POST {} {}", endpoint, bodyJson);
        }

        // bodyToFlux(String.class) 在 SSE 响应下会把每个 event 的 data 字段作为 String 单独发出
        Flux<String> sseFlux = webClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(errBody -> {
                            log.error("[HTTP <<] {} 错误响应：{}", resp.statusCode(), errBody);
                            return Mono.error(new RuntimeException(
                                    "Chat API 错误 " + resp.statusCode() + "：" + errBody));
                        }))
                .bodyToFlux(String.class);

        // 用 Flux.create 把 token 取消信号桥接到 reactor 的取消上
        return Flux.<ChatChunk>create(sink -> {
            Disposable disposable = sseFlux
                    .doOnNext(json -> { if (logHttp) log.info("[HTTP <<] {}", json); })
                    // 用 handle 而不是 map：parseChunk 对空/无意义的行会返回 null，
                    // map 不允许 null 返回值会抛 NPE；handle 允许"看情况选择 emit 或不 emit"。
                    .<ChatChunk>handle((json, downstream) -> {
                        ChatChunk chunk = parseChunk(json);
                        if (chunk != null) downstream.next(chunk);
                    })
                    .subscribe(sink::next, sink::error, sink::complete);

            if (token != null) {
                token.onCancel(() -> {
                    disposable.dispose();
                    // 关键：sink.error 必须切到独立线程异步分发，不能在 cancel 调用线程上同步执行
                    // 否则 Reactor 会把这个异常顺着同步链路一路抛回 POST /interrupt 控制器，
                    // 导致 Spring 当成未捕获异常打 ERROR stacktrace
                    Schedulers.parallel().schedule(() -> {
                        if (!sink.isCancelled()) {
                            sink.error(new AgentInterruptedException("Model stream cancelled"));
                        }
                    });
                });
            }
            // 下游主动取消时，把上游也断了
            sink.onCancel(disposable::dispose);
            sink.onDispose(disposable::dispose);
        });
    }

    /** 解析单条 SSE data 行为 ChatChunk；非法 JSON 返回 {@code null}。 */
    private ChatChunk parseChunk(String json) {
        if (json == null || json.isBlank() || "[DONE]".equals(json)) return null;
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                String finish = choice.path("finish_reason").isNull() ? null
                        : choice.path("finish_reason").asText(null);

                String text = delta.path("content").isMissingNode() || delta.path("content").isNull()
                        ? null : delta.path("content").asText();
                String thinking = delta.path("reasoning_content").isMissingNode()
                        || delta.path("reasoning_content").isNull()
                        ? null : delta.path("reasoning_content").asText(null);

                List<ToolCallDelta> toolDeltas = parseToolCallDeltas(delta.path("tool_calls"));

                if (finish != null) {
                    ChatUsage usage = parseUsage(root.path("usage"));
                    return new ChatChunk(text, thinking, toolDeltas, usage, finish);
                }
                if (text != null || thinking != null || !toolDeltas.isEmpty()) {
                    return new ChatChunk(text, thinking, toolDeltas, null, null);
                }
            }
            // 部分 provider 在 choices 之后单独发一条只含 usage 的 chunk
            if (!root.path("usage").isMissingNode() && !root.path("usage").isNull()) {
                return new ChatChunk(null, null, null, parseUsage(root.path("usage")), null);
            }
        } catch (IOException ignored) {
            // 忽略非法行
        }
        return null;
    }

    private List<ToolCallDelta> parseToolCallDeltas(JsonNode toolCalls) {
        if (!toolCalls.isArray()) return List.of();
        List<ToolCallDelta> out = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            int idx = tc.path("index").asInt(0);
            String id = tc.path("id").asText(null);
            JsonNode fn = tc.path("function");
            String name = fn.path("name").asText(null);
            String args = fn.path("arguments").asText("");
            out.add(new ToolCallDelta(idx, id, name, args));
        }
        return out;
    }

    /** 构造请求 JSON body。 */
    private ObjectNode buildBody(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);
        body.put("stream", true);
        body.set("stream_options", mapper.createObjectNode().put("include_usage", true));

        ArrayNode arr = body.putArray("messages");
        converter.convert(messages).forEach(arr::add);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = body.putArray("tools");
            for (ToolSchema t : tools) {
                ObjectNode tn = toolsArr.addObject();
                tn.put("type", "function");
                ObjectNode fn = tn.putObject("function");
                fn.put("name", t.name());
                if (t.description() != null) fn.put("description", t.description());
                if (t.parameters() != null) fn.set("parameters", mapper.valueToTree(t.parameters()));
            }
        }
        applyOptions(body, options);
        return body;
    }

    private void applyOptions(ObjectNode body, GenerateOptions opts) {
        if (opts == null) return;
        if (opts.getTemperature() != null) body.put("temperature", opts.getTemperature());
        if (opts.getTopP() != null) body.put("top_p", opts.getTopP());
        if (opts.getMaxTokens() != null) body.put("max_tokens", opts.getMaxTokens());
        if (opts.getFrequencyPenalty() != null) body.put("frequency_penalty", opts.getFrequencyPenalty());
        if (opts.getPresencePenalty() != null) body.put("presence_penalty", opts.getPresencePenalty());
        if (opts.getStopSequences() != null && !opts.getStopSequences().isEmpty()) {
            ArrayNode stop = body.putArray("stop");
            opts.getStopSequences().forEach(stop::add);
        }
        opts.getExtraParams().forEach((k, v) -> body.set(k, mapper.valueToTree(v)));
    }

    private static ChatUsage parseUsage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) return ChatUsage.builder().build();
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int cached = usage.path("prompt_cache_hit_tokens").asInt(0);
        if (cached == 0) cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        return ChatUsage.builder()
                .inputTokens(input)
                .outputTokens(output)
                .cachedInputTokens(cached)
                .build();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * OpenAIChatModel 的链式构造器。
     */
    public static class Builder {
        private WebClient webClient;
        private String apiKey;
        private String modelName;
        private String baseUrl;
        private boolean logHttp = false;

        /**
         * 共享的 WebClient 实例（建议由 Spring 注入）。不传则内部新建一个使用 JDK HttpClient 的实例。
         */
        public Builder webClient(WebClient webClient) { this.webClient = webClient; return this; }

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        /** 开启后会用 INFO 级别打印每次请求的完整 JSON body 和响应的每个 SSE chunk。 */
        public Builder logHttp(boolean v) { this.logHttp = v; return this; }

        public OpenAIChatModel build() {
            return new OpenAIChatModel(this);
        }
    }
}
