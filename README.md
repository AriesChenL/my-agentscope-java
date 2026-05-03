# my-agentscope-java

阿里 [AgentScope](https://github.com/agentscope-ai/agentscope-java) 的 Java/Spring Boot 移植与重写：一个支持多 LLM provider、流式 SSE、工具调用、HITL（Human-in-the-Loop）和上下文自动压缩的 ReAct Agent 框架。

> 不是 fork，是参考其设计在 Spring Boot 3 + JDK 21 上重新实现，目标是开箱即用、便于嵌入到现有 Spring 项目里。

## 特性

- **ReAct Agent**：标准的 reasoning + acting 循环，可挂任意工具
- **多 Provider 同时启用**：OpenAI 协议（DeepSeek / Moonshot / OpenRouter / vLLM）、Anthropic Claude、Google Gemini —— 每个会话可独立选择
- **流式 SSE**：`SseEmitter` 推送 token / tool_call / tool_result / done 事件，前端原生 EventSource 即可消费
- **Human-in-the-Loop（3 种模式）**：
  1. `ask_user` 内置工具 —— LLM 主动向用户提问，前端按 `ui_type`（text / single_select / multi_select / form / number...）渲染输入控件
  2. `ToolConfirmationHook` —— 危险工具调用前人工审批（批准 / 拒绝），危险工具清单可运行时通过 REST 接口动态修改
  3. 业务工具抛 `ToolSuspendException` —— 工具自身可挂起会话等待人工输入
- **Hook 系统**：PreReasoning / PostReasoning / PreActing / PostActing 四个挂载点，可短路工具调用
- **会话持久化**：文件系统落盘 + per-key 锁，多用户并发安全
- **上下文自动压缩**：超出 token 预算时用 LLM 摘要旧历史，避免上下文爆炸
- **网络可靠性**：JDK HttpClient + HTTP/2 + 瞬时网络/SSL 错误自动重试（指数退避）

## 技术栈

- Spring Boot 3.5.14 / JDK 21 / Maven
- Spring MVC（同步控制器） + Spring WebFlux 的 `WebClient`（仅做 LLM 出站调用）
- 不引 Netty，`WebClient` 用 `JdkClientHttpConnector` 做 transport
- Thymeleaf + 原生 JS（`/static/chat.html` + `chat.js`）做 demo 前端

## 快速开始

### 1. 准备 API Key

复制本地配置模板：

```bash
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

填入至少一个 provider 的 key（或者用环境变量，见 `application.properties` 中的 `*.api-key-env`）：

```properties
agentscope.providers.openai.api-key=sk-...
```

境内访问 Anthropic / Gemini 需开启代理：

```properties
agentscope.http.proxy.enabled=true
agentscope.http.proxy.host=127.0.0.1
agentscope.http.proxy.port=7890
```

### 2. 启动

```bash
./mvnw spring-boot:run
```

打开 http://localhost:8080/chat.html 即可对话。

### 3. 启用其它 Provider

在 `application.properties` 把 `enabled=true` 打开即可：

```properties
agentscope.providers.anthropic.enabled=true
agentscope.providers.gemini.enabled=true
```

新建对话时前端可三选一。

## HITL 配置

```properties
# 内置 ask_user 工具，默认开启
agentscope.hitl.ask-user-tool-enabled=true

# 危险工具白名单（也可运行时通过 PUT /api/hitl/dangerous-tools 修改）
agentscope.hitl.dangerous-tools=delete_file,transfer_money
```

REST 接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/hitl/tools` | 当前注册的所有工具 |
| GET | `/api/hitl/dangerous-tools` | 当前危险工具清单 |
| PUT | `/api/hitl/dangerous-tools` | 替换危险工具清单 |
| POST | `/api/chat/{user}/{conv}/approve-tool` | 批准 / 拒绝挂起的工具调用 |

## 上下文压缩

```properties
agentscope.memory.compaction-enabled=true
agentscope.memory.compaction-max-tokens=8000
agentscope.memory.compaction-keep-recent=10
```

超过阈值时自动用同一 LLM 把旧历史摘要为一条 SYSTEM 消息，保留最近 N 轮原文。

## 项目结构

```
src/main/java/com/lynn/myagentscopejava/
  core/
    agent/         ReActAgent — reasoning + acting 主循环
    model/         ChatModel 抽象 + OpenAI/Anthropic/Gemini 实现 + Router
    memory/        Memory + CompactingMemory + SummarizingCompactor
    tool/          Toolkit + @Tool 注解 + JSON Schema 反射生成
    hook/          Hook 抽象 + ToolConfirmationHook
    session/       FileSystemSession 持久化
    conversation/  多对话目录服务
    service/       ChatService — 多用户并发入口
  tools/           内置工具：UserInteractionTool（ask_user）、WebSearchTool（Tavily）
  web/             ChatController — SSE 流式接口 + HITL REST
  config/          AgentAutoConfiguration、AgentProperties
src/main/resources/
  static/          chat.html + chat.js + chat.css 前端 demo
  application.properties
```

## 测试

```bash
./mvnw test
```

包含 118 个单元测试，覆盖 ReAct 循环、HITL 三种模式、Memory 压缩、Schema 生成等核心路径。

## 致谢

设计参考阿里 [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)。

## License

未指定。仅供学习参考。
