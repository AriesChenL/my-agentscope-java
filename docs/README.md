# my-agentscope-java · 学习文档

这是一份从零开始理解本项目的渐进式学习资料。**每章独立**，30 分钟内可读完，配自检题。读完全部 12 章应该能独立修改任何一处代码。

## 阅读路径

> 推荐按顺序读。如果你已经熟悉某部分，可按"想改 X 看这章"自由跳读。

### Part 1 · 入门（你跑起来 + 看懂"是什么"）

| # | 章节 | 一句话 |
|---|------|------|
| 01 | [项目是什么 / 整体架构](./01-what-is-this-project.md) | LLM Agent / ReAct 是什么，整体架构图 |
| 02 | [环境准备 & Hello World](./02-hello-world.md) | JDK 21、API key、跑起来、第一次对话 |
| 03 | [代码地图](./03-code-map.md) | 包结构、"想改 X 看哪里"速查表 |

### Part 2 · 核心抽象（看懂"怎么做的"）

| # | 章节 | 一句话 |
|---|------|------|
| 04 | [Message & Block 模型](./04-message-and-block.md) | `Msg` + 4 种 `ContentBlock`，sealed interface |
| 05 | [ChatModel 抽象与流式响应](./05-chatmodel-and-streaming.md) | `Flux<ChatChunk>`、为什么 `WebClient + JdkClientHttpConnector` |
| 06 | [Tool 系统](./06-tool-system.md) | `@Tool` 注解、反射生成 Schema、`Toolkit` 注册与调用 |
| 07 | [ReActAgent 主循环](./07-react-loop.md) | reasoning + acting 一轮一轮怎么转，HITL 挂起恢复 |

### Part 3 · 进阶能力

| # | 章节 | 一句话 |
|---|------|------|
| 08 | [多 Provider 路由](./08-multi-provider-routing.md) | OpenAI / Anthropic / Gemini 协议差异，按会话切 |
| 09 | [流式 SSE 到前端](./09-sse-streaming.md) | `SseEmitter` + `EventSource`，事件类型表 |
| 10 | [Hook 系统](./10-hook-system.md) | 4 个挂载点，写一个自定义 hook |
| 11 | [HITL 三种模式](./11-hitl-three-modes.md) | `ask_user` / `ToolConfirmationHook` / `ToolSuspendException` |
| 12 | [Memory 压缩 + 会话持久化](./12-memory-and-session.md) | `CompactingMemory`、`FileSystemSession`、并发锁 |

### Part 4 · 生产部署

| # | 章节 | 一句话 |
|---|------|------|
| 13 | [分布式部署](./13-distributed-deployment.md) | 单机→多节点改造、PG/Redis 抽象层、Nginx sticky、docker-compose 一键起 |

## "想改 X 看哪一章" 速查

| 想做什么 | 主要看 |
|----------|--------|
| 跑起来 | 02 |
| 加一个工具 | 06、03 §3.8 |
| 加一个新 LLM 提供方 | 08、05 |
| 自定义"危险工具"清单 | 11 §11.4 |
| 加一个拦截器 / 改写消息 | 10 |
| 实现自动总结历史 | 12 §12.3-§12.7 |
| 改前端 UI / 卡片渲染 | 09 |
| 让 LLM 主动问用户 | 11 §11.3 |
| 在工具内部按条件挂起 | 11 §11.5、06 §6.6 |
| 调试 SSL / 网络问题 | 02 §2.7、05 §5.6-§5.7 |
| 重启不丢对话 | 12 §12.10 |
| 多用户并发 | 12 §12.11 |
| 中断正在跑的请求 | 09 §9.9、07 §7.3 |
| 测试自己的工具 / hook | 看 `src/test/` 现有用例 |

## 推荐学习节奏

- **第 1 天**：Part 1（3 章）—— 把环境跑起来，建立全局认知
- **第 2-3 天**：Part 2（4 章）—— 理解核心抽象，能看懂 80% 代码
- **第 4-5 天**：Part 3（5 章）—— 理解高级能力，能独立改一处复杂逻辑
- **第 6 天**：跑测试 + 自己加一个工具 / hook 验证学习效果

## 配套自检

每章末尾的"自检"清单建议**口头回答**而不是默念 —— 真正能讲出来的才是真懂。

跟读源码时随时打开 IDE，对照行号跳转查看实现。

## 反馈

文档有错漏 / 想加章节 / 哪里讲不清，提 issue 或直接 PR。

---

> 项目地址：https://github.com/AriesChenL/my-agentscope-java
> 设计参考：阿里 [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)
