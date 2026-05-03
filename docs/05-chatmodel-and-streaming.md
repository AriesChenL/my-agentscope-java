# 第 5 章 · ChatModel 抽象与流式响应

> 目标：理解 `ChatModel` 接口、`Flux<ChatChunk>` 流式协议、为什么用 `WebClient + JdkClientHttpConnector`，以及"chunk → 完整 Msg"是怎么聚合的。

## 5.1 接口定义

`core/model/ChatModel.java`：

```java
public interface ChatModel {
    String getModelName();

    Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                           GenerateOptions options, CancellationToken token,
                           String sessionId);

    // 便利方法：阻塞收集为 ChatResponse
    default ChatResponse chat(List<Msg> messages, List<ToolSchema> tools,
                              GenerateOptions options, CancellationToken token) {
        ChunkAccumulator acc = new ChunkAccumulator();
        stream(messages, tools, options, token).doOnNext(acc::accept).blockLast();
        // ... 把 acc 拼成 Msg 返回
    }
}
```

只有一个真正抽象方法 `stream(...)`，其它都是 default。设计上：

- **`stream` 是核心**：返回 Reactor 的 `Flux<ChatChunk>`
- **`chat` 是包装**：阻塞订阅，把 chunk 流聚合为单条 `ChatResponse`，给 `ReActAgent.call()` 用
- **取消传播**：`CancellationToken` 由调用方提供，用户点"停止"时框架 cancel 它，实现里需要中断 HTTP 请求

参数：

| 参数 | 含义 |
|------|------|
| `messages` | 完整的 prompt（system + 历史 + 工具结果） |
| `tools` | 当前可用工具的 schema 列表（可空） |
| `options` | `temperature` / `topP` / `maxTokens` 等 |
| `token` | 取消信号 |
| `sessionId` | 会话标识，仅 Gemini 显式缓存用 |

## 5.2 `ChatChunk` —— 流式单元

`core/model/ChatChunk.java`：

```java
public record ChatChunk(
    String textDelta,           // 文本增量
    String thinkingDelta,       // 思考链增量
    List<ToolCallDelta> toolCallDeltas, // 工具调用增量
    ChatUsage usage,            // 仅最终 chunk 携带
    String finishReason         // 仅最终 chunk 携带
) { ... }
```

典型的下发顺序：

```
[chunk 1]   textDelta = "你好"
[chunk 2]   textDelta = "！"
[chunk 3]   textDelta = "我帮你"
[chunk 4]   textDelta = "查一下"
[chunk 5]   toolCallDeltas = [(idx=0, id="call_1", name="weather")]
[chunk 6]   toolCallDeltas = [(idx=0, args="{\"city")]
[chunk 7]   toolCallDeltas = [(idx=0, args="\":\"北京\"}")]
[chunk 8]   finishReason="tool_calls", usage=ChatUsage(...)   ← 最终 chunk
```

注意：

- 工具调用的 JSON 参数是**逐字节切碎**下发的，需要在客户端拼接（这就是 `ToolCallDelta` 和 `ChunkAccumulator` 干的事）
- 最终 chunk 的 `finishReason` 非空，可能是 `"stop"` / `"tool_calls"` / `"length"` 等
- `isFinal()` 工具方法：`return finishReason != null;`

工厂方法把"造一个 chunk"简化到一行：

```java
ChatChunk.text("你好")
ChatChunk.thinking("用户问的是...")
ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "c1", "add", "{\"a\":1}")))
ChatChunk.finish("stop", usage)
```

测试里大量用这些构造预设的 chunk 序列模拟模型回复（见 `ScriptedModel`）。

## 5.3 `ChunkAccumulator` —— chunk → 完整 Msg

`core/model/ChunkAccumulator.java`（源码自行查阅）做的事：

1. 用 `StringBuilder` 累积所有 `textDelta` → 最终一个 `TextBlock`
2. 用另一个 `StringBuilder` 累积 `thinkingDelta` → `ThinkingBlock`
3. 用一个 `Map<Integer, ToolCallBuilder>` 按 `index` 累积每个工具调用的 id / name / arguments → 解析 JSON → `ToolUseBlock`
4. 记录最后一个非 null 的 `usage` 和 `finishReason`
5. `buildBlocks()` 按"thinking → text → tool_uses"顺序输出 `List<ContentBlock>`

`ChatModel.chat()` 默认实现就是用它把流聚合：

```java
ChunkAccumulator acc = new ChunkAccumulator();
stream(...).doOnNext(acc::accept).blockLast();
List<ContentBlock> blocks = acc.buildBlocks();
Msg msg = Msg.builder().role(ASSISTANT).content(blocks).build();
return new ChatResponse(msg, acc.usage(), acc.finishReason());
```

`ChatService.chatReactStream()` 也用它，但**同时**把每个 chunk 转成 SSE 事件下发给前端（详见第 9 章）—— 边累积边转发。

## 5.4 三个 provider 实现

每家 provider 一对类：

```
OpenAIChatModel + OpenAIMessageConverter
AnthropicChatModel + AnthropicMessageConverter
GeminiChatModel + GeminiMessageConverter
```

`*ChatModel` 负责：

1. 把 `List<Msg>` + `List<ToolSchema>` 转成该 provider 的 JSON 请求体
2. 用 `WebClient` 发 SSE POST
3. 把每个 SSE event 解析成 `ChatChunk`
4. 处理鉴权头、错误响应

`*MessageConverter` 负责：

- `Msg` ↔ provider JSON 对象的双向转
- 处理各家协议的 quirks（角色名映射、内容字段名差异、工具调用包装方式）

OpenAI 协议最简单 + 兼容生态最大，建议从 `OpenAIChatModel` 开始读。

## 5.5 为什么用 `WebClient + JdkClientHttpConnector`？

`AgentAutoConfiguration.agentscopeWebClient()` 这样构造：

```java
HttpClient.Builder hb = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(15));
return WebClient.builder()
        .clientConnector(new JdkClientHttpConnector(hb.build()))
        .filter(...)  // 重试过滤器
        .build();
```

几个选择：

### 5.5.1 为什么用 WebClient 而不是 RestTemplate / OkHttp？

需要**流式 SSE 响应**。`WebClient.get().retrieve().bodyToFlux(...)` 天然是流式的，每个 SSE event 都触发一次 onNext。RestTemplate 是同步阻塞，不适合。

### 5.5.2 为什么用 `JdkClientHttpConnector` 而不是默认 Reactor Netty？

- **不引 Netty**：默认 `WebClient` 用 Reactor Netty，会引一坨 Netty 依赖。本项目主体是同步 Spring MVC（`SseEmitter` 走 Servlet），引 Netty 没必要
- **JDK 11+ 自带 HttpClient**：性能足够，无额外依赖
- **HTTP/2 默认开启**：JDK HttpClient 默认就支持 HTTP/2

代价：JDK HttpClient 在某些"经 HTTP CONNECT 代理 + HTTP/2"组合下偶发 SSL 握手失败（详见 5.7）。

### 5.5.3 为什么不用 starter-webflux？

```xml
<!-- 仅引 spring-webflux 拿 WebClient 类，不引 starter-webflux 避免与 starter-web 冲突 -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webflux</artifactId>
</dependency>
```

如果引 `starter-webflux`，Spring Boot 会"看到 webflux 启动 Reactor Netty 服务器"和"看到 web 启动 Tomcat" → 冲突。

只引 `spring-webflux`（不带 starter）拿 `WebClient` 这一个类即可，服务器仍由 `starter-web` 的 Tomcat 提供。

## 5.6 重试过滤器

```java
.filter((req, next) -> next.exchange(req)
        .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                .maxBackoff(Duration.ofSeconds(3))
                .filter(AgentAutoConfiguration::isTransientNetworkError)))
```

只对**连接建立 + 请求发送阶段**的瞬时网络错误重试：

- `IOException`（含 `EOFException`、`ConnectException`）
- `SSLException`（含 `SSLHandshakeException`、`AEADBadTagException`）

不重试的：4xx/5xx 业务错误（避免重发烧 token）、流式 body 接收阶段的中断（避免重复消费）。

```java
private static boolean isTransientNetworkError(Throwable ex) {
    Throwable cur = ex;
    for (int i = 0; i < 5 && cur != null; i++) {
        if (cur instanceof IOException) return true;
        if (cur instanceof SSLException) return true;
        cur = cur.getCause();
    }
    return false;
}
```

向上找 5 层 cause —— Reactor Netty 之外的底层异常常被多层包装。

## 5.7 HTTP/2 vs HTTP/1.1

`agentscopeWebClient` 注释说：

> HTTP/2（默认）：经 HTTP CONNECT 代理时部分 endpoint（如 generativelanguage.googleapis.com）可能 ALPN 握手失败抛 `SSLHandshakeException: Remote host terminated the handshake`。如遇此问题改回 `.version(HTTP_1_1)`。

这是 JDK HttpClient 的已知问题。绝大多数情况下 HTTP/2 + 重试过滤器就够用；如果你的代理特别老，把 HTTP/2 改回 1.1 就能解决（但失去多路复用）。

## 5.8 取消传播

`CancellationToken` 是协作式的：

```java
public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    public void cancel(InterruptSource source) { ... }
    public boolean isCancelled() { ... }
    public void throwIfCancelled() {
        if (isCancelled()) throw new AgentInterruptedException(...);
    }
}
```

各 `ChatModel` 实现需要：

1. 把 `WebClient` 的 `Mono`/`Flux` 与 token 关联（典型用 `Flux.takeUntil(...)` 或 `disposable.dispose()`）
2. 在每个 chunk 下发前调 `token.throwIfCancelled()`
3. token cancel → HTTP 请求中断 → Flux 抛 `AgentInterruptedException` → 上游 `ReActAgent.reactLoop()` 接住

`ReActAgent` 在每次循环开头也调 `token.throwIfCancelled()`，保证即使 model.stream 已经返回完毕，下一轮 reasoning 也不会跑。

## 5.9 `GenerateOptions` & `ChatUsage`

```java
GenerateOptions.builder()
    .temperature(0.7)
    .topP(0.9)
    .maxTokens(2048)
    .frequencyPenalty(0.0)
    .presencePenalty(0.0)
    .build();
```

字段全是 `Double` / `Integer` 包装类型，**`null` 表示"用 provider 默认值"**。这样可以只设你关心的字段。

`ChatUsage`：

```java
record ChatUsage(int promptTokens, int completionTokens, int totalTokens) {}
```

每次推理结束都会打日志 `[Bot] iter 0 promptTokens=120 completionTokens=45 totalTokens=165`，便于成本监控。

## 5.10 自检

- [ ] 我能解释为什么核心方法是 `Flux<ChatChunk>` 而不是 `String`
- [ ] 我能描述一次工具调用在 chunk 流中的样子
- [ ] 我知道 `ChunkAccumulator` 是干嘛的
- [ ] 我能解释为什么不用 starter-webflux
- [ ] 我知道遇到 SSL 握手失败时第一步该改 HTTP/2 还是 1.1
