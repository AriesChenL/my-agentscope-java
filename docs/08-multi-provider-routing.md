# 第 8 章 · 多 Provider 路由

> 目标：理解为什么需要多 provider，三家 API 的协议差异长什么样，`ChatModelRouter` 是怎么按会话切的。

## 8.1 为什么要多 provider

- **能力差异**：DeepSeek 性价比，Claude 工具调用稳，Gemini 长上下文 + 多模态
- **可用性**：一家挂了切另一家
- **成本控制**：日常用便宜的，复杂任务上贵的
- **本地模型**：vLLM / Ollama 暴露 OpenAI 协议，等同一个 provider

本项目支持 3 家 + 任意 OpenAI 协议兼容服务（DeepSeek / Moonshot / OpenRouter / vLLM 等都走 `openai` 槽位）。

## 8.2 配置：三选 N

```properties
agentscope.providers.default-id=openai

agentscope.providers.openai.enabled=true
agentscope.providers.openai.display-name=DeepSeek
agentscope.providers.openai.name=deepseek-v4-flash
agentscope.providers.openai.base-url=https://api.deepseek.com
agentscope.providers.openai.api-key-env=DEEPSEEK_API_KEY

agentscope.providers.anthropic.enabled=true
agentscope.providers.anthropic.display-name=Claude
agentscope.providers.anthropic.name=claude-sonnet-4-6
agentscope.providers.anthropic.base-url=https://api.anthropic.com
agentscope.providers.anthropic.api-key-env=ANTHROPIC_API_KEY

agentscope.providers.gemini.enabled=true
agentscope.providers.gemini.display-name=Gemini
agentscope.providers.gemini.name=gemini-3-flash-preview
agentscope.providers.gemini.base-url=https://generativelanguage.googleapis.com
agentscope.providers.gemini.api-key-env=GEMINI_API_KEY
```

每个块独立：`enabled` 决定是否构造 ChatModel，`name` 是模型名（API 字段），`api-key-env` 是回退的环境变量。

`default-id` 决定"用户没指定 provider 的对话"走哪家。

## 8.3 三家 API 的协议差异

下表抓一些有代表性的差异，看完不需要记，知道"差异存在"就行：

| 维度 | OpenAI 协议 | Anthropic | Gemini |
|------|-----------|-----------|--------|
| Endpoint | `/v1/chat/completions` | `/v1/messages` | `/v1beta/models/{model}:streamGenerateContent` |
| 鉴权 | `Authorization: Bearer ${key}` | `x-api-key: ${key}` + `anthropic-version` | URL `?key=${key}` |
| 流式 | SSE，`data: ...` | SSE，event 类型多 | SSE，jsonl |
| Role 名 | `system / user / assistant / tool` | `system`（顶级字段，不在 messages 里） / `user / assistant` | `user / model`（无 system / tool） |
| System prompt | 一条 system 消息 | 顶级 `system` 字段 | 拼到 user 第一条里 / `systemInstruction` |
| Content 字段 | `content: "..."` 或 `content: [{type, ...}]` | `content: [{type, ...}]` | `parts: [{text} \| {functionCall}]` |
| 工具调用 | `tool_calls: [{id, type, function: {name, arguments}}]` | `content: [{type:"tool_use", id, name, input}]` | `parts: [{functionCall: {name, args}}]` |
| 工具结果 | role `tool`, `tool_call_id` | content `[{type:"tool_result", tool_use_id, content}]` | `parts: [{functionResponse: {name, response}}]` |
| Token 用量 | `usage: {prompt_tokens, completion_tokens, total_tokens}` | `usage: {input_tokens, output_tokens}` | `usageMetadata: {promptTokenCount, candidatesTokenCount}` |

每家 quirks：

- **OpenAI**：`tool_calls` 的 `arguments` 是字符串（不是对象），需要手动 `JSON.parse`
- **Anthropic**：错误响应是 200 + body 里 `{type: "error"}`（不是 4xx）
- **Gemini**：thinking 模式下要把 `thoughtSignature` 原样回传，否则报错

每家一个 `*MessageConverter` 解决这些转换 + quirks，对 `ChatModel` 的调用方完全透明。

## 8.4 `ChatModel` 实现

每个 ChatModel 实现的骨架几乎一样：

```java
public class OpenAIChatModel implements ChatModel {
    private final WebClient webClient;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;

    @Override
    public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                  GenerateOptions options, CancellationToken token,
                                  String sessionId) {
        Map<String, Object> body = OpenAIMessageConverter.toRequest(messages, tools, options, modelName);
        return webClient.post()
                .uri(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(ServerSentEvent.class)
                .takeWhile(e -> !"[DONE]".equals(e.data()))
                .mapNotNull(e -> OpenAIMessageConverter.parseChunk(e.data()))
                .doOnSubscribe(s -> { /* 关联 token */ });
    }
}
```

3 个职责：

1. **请求构造**：`*MessageConverter.toRequest(...)`
2. **HTTP 调用**：`WebClient` 发流式 POST
3. **响应解析**：每个 SSE event → `ChatChunk`

## 8.5 Bean 注册

`AgentAutoConfiguration.chatModels()`：

```java
@Bean
public Map<String, ChatModel> chatModels(AgentProperties props, WebClient webClient) {
    Map<String, AgentProperties.Provider> enabled = props.getProviders().enabledMap();
    if (enabled.isEmpty()) throw new IllegalStateException("至少要启用一个 provider");
    Map<String, ChatModel> models = new LinkedHashMap<>();
    for (var e : enabled.entrySet()) {
        models.put(e.getKey(), buildChatModel(e.getKey(), e.getValue(), webClient));
    }
    return models;
}

private ChatModel buildChatModel(String providerId, Provider p, WebClient webClient) {
    String apiKey = p.getApiKey();
    if ((apiKey == null || apiKey.isBlank()) && p.getApiKeyEnv() != null) {
        apiKey = System.getenv(p.getApiKeyEnv());
    }
    return switch (providerId) {
        case "anthropic" -> AnthropicChatModel.builder()
                .webClient(webClient).baseUrl(p.getBaseUrl()).apiKey(apiKey).modelName(p.getName()).build();
        case "gemini" -> GeminiChatModel.builder()...build();
        case "openai" -> OpenAIChatModel.builder()...build();
        default -> throw new IllegalArgumentException("未知 provider id: " + providerId);
    };
}
```

返回的是 `Map<String, ChatModel>`，key 是 provider id（`"openai"` / `"anthropic"` / `"gemini"`）。

API key 解析优先级：

1. `properties` 里显式 `api-key=...`
2. `api-key-env` 指定的环境变量

这样开发用文件、CI 用环境变量都能 cover。

## 8.6 `ChatModelRouter` —— 按会话路由

```java
public class ChatModelRouter {
    private final Map<String, ChatModel> models;
    private final String defaultProviderId;
    private final Function<SessionKey, String> keyToProvider;

    public ChatModel resolve(SessionKey key) {
        String pid = keyToProvider.apply(key);
        if (pid == null || !models.containsKey(pid)) {
            pid = defaultProviderId;
        }
        return models.get(pid);
    }
}
```

`keyToProvider` 是一个函数，作用是"从 SessionKey 反查应该用哪个 provider"。`AgentAutoConfiguration` 注入的实现是查 `ConversationDirectory`：

```java
return new ChatModelRouter(chatModels, defaultId, key -> {
    String s = key.value();
    int sep = s.indexOf("__");
    if (sep < 0) return null;
    String userId = s.substring(0, sep);
    String convId = s.substring(sep + 2);
    return directory.get(userId, convId).map(c -> c.provider()).orElse(null);
});
```

`SessionKey` 形如 `"alice__conv-001"`，从中提取 `userId` 和 `convId`，查目录拿到该对话的 provider 字段。

新建对话时前端选择 provider，落到 `Conversation.provider`，之后这个对话的所有调用都走那家。

## 8.7 `ChatService` 用 Router 的位置

```java
private ReActAgent newAgentForSession(SessionKey key) {
    Memory memory = memoryFactory.get();
    ReActAgent.Builder b = ReActAgent.builder()
            .name(agentName)
            .sysPrompt(sysPrompt)
            .model(modelRouter.resolve(key))   // ← 这里按会话切
            .toolkit(toolkit)
            .memory(memory)
            ...;
    ReActAgent agent = b.build();
    agent.loadFrom(session, key);
    return agent;
}
```

每次请求新建 agent 时按 `SessionKey` 选 model。同一会话永远走同一家（除非用户手动改对话设置）。

`runReactLoop()` 流式版本同理：

```java
ChatModel chatModel = modelRouter.resolve(key);
chatModel.stream(messages, toolkit.getSchemas(), options, token, key.value())
```

注意流式版本传了 `sessionId = key.value()`，给 Gemini 的显式缓存用（OpenAI / Anthropic 实现忽略此参数）。

## 8.8 切换 provider 的 UX

前端：

1. 用户点"新建对话" → 弹框选 provider → 后端 `POST /api/conversations/{user}` 带 `provider` 字段
2. `Conversation.provider` 落盘
3. 之后这个 `convId` 的所有 chat 调用，路由器自动选对应 ChatModel

前端如何拿到 provider 列表？`GET /api/providers` 返回 `[{id, displayName, modelName}]`。

## 8.9 加一个新 provider 需要改哪些

任务：加 Mistral。

1. **Maven**：不需要。Mistral 用 OpenAI 兼容协议，直接用 `openai` 槽位即可！

如果是真正新协议（如 Cohere）：

1. 写 `CohereChatModel implements ChatModel` + `CohereMessageConverter`
2. `AgentProperties.Providers` 加 `cohere` 槽位
3. `AgentAutoConfiguration.buildChatModel()` 加 `case "cohere" -> ...`
4. 前端 `chat.js` 在 provider 列表 UI 加显示

## 8.10 自检

- [ ] 我能解释为什么需要"多 provider"
- [ ] 我能列出三家 API 至少一个明显差异
- [ ] 我能描述 `ChatModelRouter` 解析 provider 的两步
- [ ] 我能解释 `SessionKey` 形如 `"alice__conv-001"` 的设计
- [ ] 我知道加一个 OpenAI 兼容服务（如自己部署的 vLLM）几乎不需要写代码
