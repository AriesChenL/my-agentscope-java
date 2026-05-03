# 第 3 章 · 代码地图

> 目标：30 分钟之内建立"想改 X 该看哪个文件"的导航能力。本章不深入实现，只做"地图"。

## 3.1 顶层包结构

```
com.lynn.myagentscopejava
├── AgentScopeApplication.java   ← Spring Boot 入口
├── config/                      ← 自动装配 + 属性绑定
├── core/                        ← 框架核心（不依赖 web）
│   ├── agent/
│   ├── conversation/
│   ├── hook/
│   ├── interruption/
│   ├── memory/
│   ├── message/
│   ├── model/
│   ├── service/
│   ├── session/
│   └── tool/
├── tools/                       ← 业务工具的具体实现
├── demo/                        ← 命令行 demo
└── web/                         ← HTTP / SSE 控制器
```

**最重要的认知**：`core/` 是纯 Java 框架，**不依赖 Spring**（除了 SLF4J 日志）。Spring 的部分集中在 `config/` 和 `web/`。这意味着：

- `core/` 里的类可以直接 new 出来用，不需要 Spring 上下文
- 想换框架（比如换成 Quarkus / Micronaut）只需重写 `config/` 和 `web/`

## 3.2 `core/` 详解

### `core.message` —— 数据模型

| 文件 | 一句话 |
|------|--------|
| `Msg.java` | 一条消息（user/assistant/system/tool），不可变 |
| `MsgRole.java` | 4 种角色枚举 |
| `ContentBlock.java` | sealed interface，4 种实现 |
| `TextBlock.java` | 普通文本 |
| `ThinkingBlock.java` | 思考链（DeepSeek R1 等） |
| `ToolUseBlock.java` | 模型说"调这个工具" |
| `ToolResultBlock.java` | 框架说"这是工具结果"（含 success/error/pending 三种） |

第 4 章会详细讲。

### `core.model` —— LLM 抽象

| 文件 | 一句话 |
|------|--------|
| `ChatModel.java` | LLM 接口，主方法 `stream(...)` 返回 `Flux<ChatChunk>` |
| `ChatChunk.java` | 流式响应的单个分片（text/thinking/toolCalls/finish 之一） |
| `ChunkAccumulator.java` | 把 chunk 流聚合成完整 `Msg` |
| `ToolCallDelta.java` | 工具调用的增量片段（流式拼装用） |
| `OpenAIChatModel.java` | OpenAI 协议实现（含 DeepSeek/Moonshot 兼容） |
| `OpenAIMessageConverter.java` | 框架 `Msg` ↔ OpenAI JSON |
| `AnthropicChatModel.java` + Converter | Anthropic Claude 协议 |
| `GeminiChatModel.java` + Converter | Google Gemini 协议 |
| `ChatModelRouter.java` | 多 provider 路由器，按会话切 |
| `ChatResponse.java` | 一次完整回复（reply + usage + finishReason） |
| `ChatUsage.java` | token 用量 |
| `GenerateOptions.java` | temperature / topP / maxTokens 等生成参数 |

第 5 章讲抽象，第 8 章讲多 provider。

### `core.tool` —— 工具系统

| 文件 | 一句话 |
|------|--------|
| `Tool.java` | `@Tool` 注解 |
| `ToolParam.java` | `@ToolParam` 参数注解 |
| `ToolProvider.java` | marker 接口（避免循环依赖，无方法） |
| `ToolSchema.java` | 一个工具的元数据（name + description + JSON Schema） |
| `ToolSchemaGenerator.java` | 反射 → JSON Schema 生成器 |
| `RegisteredTool.java` | schema + 持有对象 + 反射 Method 三元组 |
| `Toolkit.java` | 工具注册表，提供 `registerObject()` / `invoke()` |
| `ToolSuspendException.java` | 工具抛此异常 → HITL 挂起 |

第 6 章详解。

### `core.agent` —— ReAct 主循环

| 文件 | 一句话 |
|------|--------|
| `ReActAgent.java` | 全部 400 行就这一个文件 —— 整个框架的心脏 |

第 7 章带你逐行读。

### `core.hook` —— 拦截器

| 文件 | 一句话 |
|------|--------|
| `Hook.java` | `@FunctionalInterface`，唯一方法 `onEvent(HookEvent)` |
| `HookEvent.java` | 抽象基类，含 `requestStop()` |
| `PreReasoningEvent.java` | 调模型前，可改写发给模型的消息列表 |
| `PostReasoningEvent.java` | 模型回完后，可改写 assistant 消息 / 请求停止 |
| `PreActingEvent.java` | 调工具前，可改写 ToolUse / 调 `suspend()` 短路 |
| `PostActingEvent.java` | 工具调完后，可改写结果 |
| `PreCallEvent.java` | 调 `agent.call()` 入口，可在 reasoning 之前预处理 memory |
| `ToolConfirmationHook.java` | 内置：危险工具拦截，HITL 模式 2 |
| `PendingToolRecoveryHook.java` | 内置：在 PreCallEvent 修复"孤儿 tool_calls"（崩溃恢复用） |

第 10 章详解。

### `core.memory` —— 记忆

| 文件 | 一句话 |
|------|--------|
| `Memory.java` | 接口：addMessage / getMessages / clear / saveTo / loadFrom |
| `InMemoryMemory.java` | 进程内 ArrayList 实现 |
| `CompactingMemory.java` | 装饰器：超阈值触发压缩 |
| `MemoryCompactor.java` | 压缩策略接口 |
| `SummarizingCompactor.java` | 用 LLM 摘要旧历史的实现 |
| `TokenEstimator.java` | 字符数 → token 数的近似估算 |

第 12 章详解。

### `core.session` —— 持久化

| 文件 | 一句话 |
|------|--------|
| `Session.java` | 接口：save / get / delete / exists（按 key + slot） |
| `SessionKey.java` | record，文件系统安全的 slug |
| `InMemorySession.java` | 进程内 Map 实现（测试用） |
| `FileSystemSession.java` | 落盘实现：每个 slot 一个 JSON 文件 |

### `core.conversation` —— 多对话目录

| 文件 | 一句话 |
|------|--------|
| `Conversation.java` | record：id + name + provider + 创建时间 |
| `ConversationDirectory.java` | 接口：list / get / create / delete |
| `FileSystemConversationDirectory.java` | 落盘实现，与 `FileSystemSession` 同根目录 |

### `core.service` —— 业务入口

| 文件 | 一句话 |
|------|--------|
| `ChatService.java` | **多用户安全的 chat 入口**，对外的"业务 API" |
| `SessionLockManager.java` | per-key `ReentrantLock` 管理 |
| `ReactEvent.java` | sealed interface，流式 ReAct 事件类型 |

第 12 章会拆 ChatService，第 9 章用 ReactEvent 做 SSE。

### `core.interruption` —— 取消

| 文件 | 一句话 |
|------|--------|
| `CancellationToken.java` | 协作式取消令牌 |
| `InterruptSource.java` | 中断来源（USER / TOOL / SYSTEM） |
| `AgentInterruptedException.java` | 被取消时抛出 |

## 3.3 `tools/` —— 业务工具

| 文件 | 一句话 |
|------|--------|
| `CalculatorTool.java` | `add` / `multiply` 两个方法，演示 `@Tool` 用法 |
| `CurrentTimeTool.java` | 返回当前时间字符串 |
| `WebSearchTool.java` | Tavily 网页搜索（要 key） |
| `UserInteractionTool.java` | `ask_user` 内置工具，HITL 模式 1 |

`@Component` 标注的工具会被 Spring 扫到，`AgentAutoConfiguration` 把它们注册进默认 `Toolkit`。`UserInteractionTool` / `WebSearchTool` 由 `@Bean` 显式注册（受配置开关控制）。

## 3.4 `web/` —— HTTP 控制器

| 文件 | 一句话 |
|------|--------|
| `ChatController.java` | 所有对外 REST/SSE 接口都在这里 |

主要接口（详细在第 9 章）：

- `POST /api/chat/{user}/{conv}` —— 流式对话（返回 SSE）
- `POST /api/chat/{user}/{conv}/approve-tool` —— HITL 批准
- `POST /api/chat/{user}/{conv}/interrupt` —— 中断
- `POST /api/chat/{user}/{conv}/compact` —— 手动压缩
- `GET /api/conversations/{user}` —— 对话列表
- `POST /api/conversations/{user}` —— 新建对话
- `GET /api/hitl/tools` / `GET|PUT /api/hitl/dangerous-tools` —— HITL 设置
- `GET /api/providers` —— 可用 provider 列表

## 3.5 `config/` —— 自动装配

| 文件 | 一句话 |
|------|--------|
| `AgentProperties.java` | `@ConfigurationProperties(prefix = "agentscope")`，所有配置的 Java 镜像 |
| `AgentAutoConfiguration.java` | 所有 `@Bean` 定义在这里 |

读这两个文件就能看懂"配置项 → 框架行为"的所有连接。

## 3.6 静态资源

```
src/main/resources/
├── application.properties              ← 公开配置
├── application-local.properties.example ← 本地 key 模板
└── static/
    ├── chat.html
    ├── chat.css
    └── chat.js                          ← 原生 JS，约 800 行，第 9 章详解
```

## 3.7 测试

```
src/test/java/com/lynn/myagentscopejava/
├── core/...    ← 核心组件单测（约 100+ 个）
└── ...
```

测试用 JUnit 5 + Reactor Test，无 Spring 上下文（除了少数集成测试），跑得很快：

```bash
./mvnw test
```

## 3.8 "我想改 X，看哪里？" 速查表

| 想改 / 想做 | 看 / 改这里 |
|------------|------------|
| 加一个新工具 | 在 `tools/` 下加类，标 `@Tool`，注册为 `@Component` |
| 加一个新 LLM provider | 在 `core/model/` 下仿 `OpenAIChatModel` 写 + 在 `AgentAutoConfiguration` 加分支 |
| 改对话 system prompt | `application.properties` 的 `agentscope.sys-prompt` |
| 改 ReAct 最大循环数 | `agentscope.session.max-iters` |
| 拦截危险工具 | `agentscope.hitl.dangerous-tools` 加工具名 |
| 自定义压缩策略 | 实现 `MemoryCompactor` 接口，替换 `AgentAutoConfiguration` 里的 bean |
| 加新的 SSE 事件类型 | `ReactEvent` 加新的 `record`，`ChatController` 里 emit |
| 改前端 UI | `static/chat.html` + `chat.js` + `chat.css` |
| 加 CI 自动跑测试 | 仓库根加 `.github/workflows/test.yml`（用 `./mvnw test`） |
| 改对话历史落盘位置 | `agentscope.session.dir` |
| Agent 接受新角色（如多 agent） | 改 `MsgRole` + 各 Converter 的 role 映射 |

## 3.9 阅读建议

按下面的顺序读源码，理解最顺：

1. `Msg.java` + `ContentBlock.java` 及 4 个实现 → 理解数据模型
2. `Tool.java` + `ToolParam.java` + `Toolkit.java` → 理解工具
3. `ChatModel.java` + `ChatChunk.java` → 理解模型抽象
4. `ReActAgent.java` → 主循环（看完前面再看这个就轻松）
5. `Hook.java` + 4 个 `*Event.java` → 拦截器
6. `ChatService.java` → 业务入口
7. `ChatController.java` → HTTP 层

接下来章节就按这个顺序展开。

## 3.10 自检

- [ ] 我能不打开 IDE 就说出 `@Tool` 注解定义在哪个包
- [ ] 我能解释为什么 `core/` 不依赖 Spring
- [ ] 我能说出"想加一个 Anthropic 之外的 provider 该改 2 个文件"分别是什么
