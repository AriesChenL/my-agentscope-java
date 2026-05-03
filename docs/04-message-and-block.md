# 第 4 章 · Message & Block 模型

> 目标：理解一条 `Msg` 是怎么由多种 `ContentBlock` 拼起来的，为什么这样设计，以及它怎么映射到三个 provider 的 JSON。

## 4.1 为什么不用一个 `String content`？

最朴素的消息模型：

```java
record SimpleMsg(String role, String content) {}
```

OpenAI 早期 API 就是这样。问题出现在两类场景：

1. **工具调用**：模型一次回复里既有"我想调 weather 工具"又有"调完告诉用户"，String 装不下结构化的工具调用
2. **思考链**：DeepSeek R1 / Claude thinking 把思考过程和最终答案分两个字段返回，需要分别展示
3. **多模态**：图片、音频、文件 …（本项目暂未实现，但模型留了扩展空间）

所以本项目的消息模型是：

```
Msg
├── role        (USER / ASSISTANT / SYSTEM / TOOL)
├── id          (UUID)
├── name        (发送方名)
└── content: List<ContentBlock>
                ├── TextBlock        ← 普通文本
                ├── ThinkingBlock    ← 思考链
                ├── ToolUseBlock     ← 我要调工具
                └── ToolResultBlock  ← 工具的结果
```

一条 ASSISTANT 消息可以**同时**含 `ThinkingBlock` + `TextBlock` + 多个 `ToolUseBlock`，对应"先思考、再说话、最后调工具"。

## 4.2 `Msg` —— 不可变消息

`core/message/Msg.java`：

```java
public class Msg {
    private final String id;
    private final String name;
    private final MsgRole role;
    private final List<ContentBlock> content;
    // ... 构造器、getter
}
```

关键设计：

- **不可变**：所有字段 `final`，`content` 用 `List.copyOf` 防御
- **id 自动生成**：构造时若 `null` 则 `UUID.randomUUID()`
- **静态工厂**：`Msg.user(name, text)` / `Msg.assistant(name, text)` / `Msg.system(text)` 处理常见场景

便利方法：

```java
msg.getText()                    // 把所有 TextBlock 的文本拼起来
msg.getBlocks(ToolUseBlock.class) // 取出某种类型的所有 block
msg.hasBlock(ToolResultBlock.class)
```

`getText()` 的实现一行就能看懂：

```java
for (ContentBlock b : content) {
    if (b instanceof TextBlock t) sb.append(t.text());
}
```

注意它**只**拼 `TextBlock`，会忽略 `ThinkingBlock` 和 `ToolUseBlock` —— 这是有意的：调用方拿"用户可读的回复"时不应该误把思考链或工具调用拼进去。

## 4.3 `ContentBlock` —— sealed interface

`core/message/ContentBlock.java`：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
})
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock {
}
```

两个看点：

### 4.3.1 sealed interface

JDK 17 的特性。`permits` 显式列出所有子类，**编译器保证**不会有第 5 种实现偷偷出现。配合 `switch` 表达式可以做穷举模式匹配：

```java
switch (block) {
    case TextBlock t        -> ...;
    case ThinkingBlock t    -> ...;
    case ToolUseBlock u     -> ...;
    case ToolResultBlock r  -> ...;
}  // 不需要 default，编译器知道穷举完了
```

新增 block 类型时，所有 switch 立刻编译报错，强制你处理新类型。

### 4.3.2 Jackson 多态

`@JsonTypeInfo + @JsonSubTypes` 的作用：序列化时给每个 block 加一个 `@type` 字段，反序列化时按 `@type` 还原成对应实现类。

落盘的 JSON 长这样：

```json
{
  "id": "...",
  "role": "ASSISTANT",
  "content": [
    {"@type": "thinking", "thinking": "用户问的是…"},
    {"@type": "text", "text": "我帮你算一下"},
    {"@type": "tool_use", "id": "call_1", "name": "add", "input": {"a": 1, "b": 2}}
  ]
}
```

这是 `FileSystemSession` 写到磁盘的格式（详见第 12 章）。

## 4.4 四种 `ContentBlock`

### `TextBlock`

```java
public record TextBlock(String text) implements ContentBlock {
    public TextBlock {
        if (text == null) text = "";
    }
}
```

最简单。`null` 规范化为空串避免后续 NPE。

### `ThinkingBlock`

```java
public record ThinkingBlock(String thinking) implements ContentBlock { ... }
```

只有 thinking-mode 模型（DeepSeek R1、Claude 4.5 thinking、Gemini 3 thinking）会产生。和 TextBlock 分开是为了让前端可以"折叠/展开思考过程"独立控制。

### `ToolUseBlock`

```java
public record ToolUseBlock(String id, String name, Map<String, Object> input,
                           String providerSignature) implements ContentBlock { ... }
```

模型说"我要调 `name` 工具，参数是 `input`"。

- `id` 必填，由模型分配 —— 后续的 `ToolResultBlock` 必须用同一个 id 才能匹配
- `input` 是 JSON 解析后的参数表
- `providerSignature` 仅 Gemini 用 —— Gemini 的 thinking 模型会下发一个不透明的 `thoughtSignature`，下一轮请求必须把它原样带回去，否则服务端会拒。OpenAI / Anthropic 留 null。

### `ToolResultBlock`

```java
public record ToolResultBlock(String id, String name, String output,
                              boolean isError, boolean pending) implements ContentBlock {
    public static ToolResultBlock success(String id, String name, String output) { ... }
    public static ToolResultBlock error(String id, String name, String message) { ... }
    public static ToolResultBlock pending(String id, String name, String reason) { ... }
}
```

**三种状态**：

| 状态 | 工厂方法 | 含义 |
|------|---------|------|
| 成功 | `success()` | 工具正常返回，`output` 是返回值字符串 |
| 错误 | `error()` | 工具抛异常，`output` 是错误描述 |
| **挂起** | `pending()` | HITL：等外部提供真实结果。详见第 11 章 |

`pending` 状态是本项目的核心扩展，原 OpenAI / Anthropic API 没有这个概念。框架靠 `pending()` 标记 + memory 末尾消息来识别"agent 是不是在等人工"。

`name` 字段是工具名 —— **必须**与 `ToolUseBlock.name` 一致：

- OpenAI / Anthropic：用 `id` 匹配，`name` 仅元数据
- Gemini：必须用 `name` 匹配 `functionCall.name`，否则 cachedContents 等接口会 400

## 4.5 `MsgRole` —— 4 种角色

```java
public enum MsgRole {
    SYSTEM,     // 系统提示词
    USER,       // 用户输入
    ASSISTANT,  // 模型生成的回复（含可能的 tool_calls）
    TOOL        // 工具调用的输出
}
```

`TOOL` 角色的消息一般含一或多个 `ToolResultBlock`。它**对应**上一条 ASSISTANT 消息的 `ToolUseBlock`，靠 `id` 关联。

## 4.6 一次完整 ReAct 的消息序列

ReAct 循环结束后，memory 里典型的消息序列：

```
[0] SYSTEM:    "You are a helpful assistant"
[1] USER:      "123 加 456 等于多少？"
[2] ASSISTANT: [ThinkingBlock("用户要算加法…"),
                ToolUseBlock(id="c1", name="add", input={a:123, b:456})]
[3] TOOL:      [ToolResultBlock.success(id="c1", name="add", output="579")]
[4] ASSISTANT: [TextBlock("123 加 456 等于 579")]
```

注意：

- 索引 [2] 的 ASSISTANT 和 [3] 的 TOOL 是**同一轮 ReAct 迭代**的两步
- 索引 [4] 是第二轮 reasoning 的输出 —— 模型看到工具结果后给出最终答案
- 模型 API 看到的是完整序列 [0..3]，让它生成 [4]

## 4.7 与三家 provider JSON 的映射

下面看一个最简 USER 消息在不同 provider 的表达：

| provider | JSON |
|----------|------|
| OpenAI | `{"role": "user", "content": "你好"}` |
| Anthropic | `{"role": "user", "content": [{"type": "text", "text": "你好"}]}` |
| Gemini | `{"role": "user", "parts": [{"text": "你好"}]}` |

带工具调用的 ASSISTANT：

| provider | JSON 结构 |
|----------|----------|
| OpenAI | `{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\"a\":1}"}}]}` |
| Anthropic | `{"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"add","input":{"a":1}}]}` |
| Gemini | `{"role":"model","parts":[{"functionCall":{"name":"add","args":{"a":1}}}]}` |

3 套 JSON 格式 → 1 套 Java 模型 = `*MessageConverter` 类负责双向转。这就是 `core/model/` 下 `OpenAIMessageConverter` / `AnthropicMessageConverter` / `GeminiMessageConverter` 的工作。

## 4.8 孤儿 tool_calls 自动修复

解决一个边界问题：

> 流式 reasoning 时被中断（用户点了停止 / 网络断了），可能留下"半截"的 ASSISTANT 消息 —— 它含 ToolUseBlock 但没有对应的 TOOL 消息。**下一轮请求带上这条历史时，模型 API 会返回 400**（OpenAI 严格检查 tool_calls 必须配对 tool 消息）。

由 `PendingToolRecoveryHook`（`core/hook/PendingToolRecoveryHook.java`）在 `PreCallEvent` 时机自动修复 —— 扫描 memory，给最近一条 ASSISTANT 中没有对应 ToolResultBlock 的 ToolUseBlock 补一条合成错误 TOOL 消息（"[已中断] 上一轮工具调用未完成…"），让会话能继续推进。

注册为 Spring bean 后会自动加入 `ReActAgent` 与 `ChatService` 的 hooks 列表，无需业务代码关心。设计与上游 agentscope-java 的 `PendingToolRecoveryHook` 完全一致；详见第 10 章 §10.x。

## 4.9 自检

- [ ] 我能列出 `ContentBlock` 的 4 种实现
- [ ] 我知道为什么用 sealed interface 而不是普通 interface
- [ ] 我能解释 `ToolResultBlock` 的三种状态
- [ ] 我能口头描述一次"加法 ReAct"在 memory 中产生的 4-5 条消息

下一章看 ChatModel 怎么把这些 Msg 翻译成 HTTP 请求并把响应流式拆回来。
