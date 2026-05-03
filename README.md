# my-agentscope-java

阿里 [AgentScope](https://github.com/agentscope-ai/agentscope-java) 的 Java/Spring Boot 实现：一个支持多 LLM provider、流式 SSE、工具调用、HITL（Human-in-the-Loop）、上下文自动压缩的 ReAct Agent 框架，**单机能跑、分布式可扩展**。

> 不是 fork，是参考其设计在 Spring Boot 3 + JDK 21 上重新实现，目标是开箱即用、便于嵌入到现有 Spring 项目里。

## 特性

### 核心 Agent 能力
- **ReAct Agent**：标准的 reasoning + acting 循环，可挂任意工具
- **多 Provider 同时启用**：OpenAI 协议（DeepSeek / Moonshot / OpenRouter / vLLM）、Anthropic Claude、Google Gemini —— 每个会话可独立选择
- **流式 SSE**：`SseEmitter` 推送 token / tool_call / tool_result / done 事件，前端原生 EventSource 即可消费
- **工具系统**：`@Tool` 注解 + 反射自动生成 JSON Schema，支持嵌套类型
- **Hook 系统**：5 个挂载点（PreCall / PreReasoning / PostReasoning / PreActing / PostActing），可改写消息、短路工具调用、提前终止循环
- **Human-in-the-Loop（3 种模式）**：
  1. `ask_user` 内置工具 —— LLM 主动向用户提问，前端按 `ui_type`（text / single_select / multi_select / form / number...）渲染输入控件
  2. `ToolConfirmationHook` —— 危险工具调用前人工审批（批准 / 拒绝），危险工具清单可运行时通过 REST 接口动态修改 + **跨节点广播**
  3. 业务工具抛 `ToolSuspendException` —— 工具自身可挂起会话等待人工输入
- **HITL 分批提交**：`resumeWithHumanInput` 支持部分填充 pending 工具结果，未填的保留挂起态
- **孤儿 tool_calls 自动修复**：`PendingToolRecoveryHook` 在 `PreCallEvent` 自动补合成结果（中断 / 崩溃恢复用，对齐上游设计）
- **上下文自动压缩**：超出 token 预算时用 LLM 摘要旧历史，避免上下文爆炸

### 部署形态
- **单机模式**（默认）：零外部依赖，会话走文件系统，锁走进程内
- **分布式模式**：会话走 PostgreSQL JSONB，分布式锁 + pub/sub 走 Redis（Redisson）
- **抽象层**：`DistributedLock` / `NotificationBus` / `Session` / `ConversationDirectory` 4 个 SPI，按 `cluster.mode` 配置二选一装配
- **跨节点协调**：interrupt 通过 Redis pub/sub 传播；HITL 走 PG 共享天然分布式；危险工具清单跨节点热更新

### 网络与可靠性
- JDK HttpClient + HTTP/2 + 瞬时网络/SSL 错误自动重试（指数退避）
- WebClient 用 `JdkClientHttpConnector` 不引 Netty，与 Spring MVC Tomcat 不冲突

## 技术栈

- Spring Boot 3.5.14 / JDK 21 / Maven
- Spring MVC（同步控制器） + Spring WebFlux 的 `WebClient`（仅做 LLM 出站调用）
- 分布式可选：PostgreSQL 16 + Flyway + Redisson 3.50
- Thymeleaf + 原生 JS（`/static/chat.html` + `chat.js`）做 demo 前端

## 快速开始

### 单机模式（推荐先跑通）

#### 1. 准备 API Key

```bash
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

填入至少一个 provider 的 key（或用环境变量，见 `application.properties` 中的 `*.api-key-env`）：

```properties
agentscope.providers.openai.api-key=sk-...
```

境内访问 Anthropic / Gemini 需开启代理：

```properties
agentscope.http.proxy.enabled=true
agentscope.http.proxy.host=127.0.0.1
agentscope.http.proxy.port=7890
```

#### 2. 启动

```bash
./mvnw spring-boot:run
```

打开 http://localhost:8080/chat.html 即可对话。

#### 3. 启用其它 Provider

在 `application.properties` 把 `enabled=true` 打开即可：

```properties
agentscope.providers.anthropic.enabled=true
agentscope.providers.gemini.enabled=true
```

新建对话时前端可三选一。

### 分布式模式（一键 docker-compose）

3 节点 + PostgreSQL + Redis + Nginx，所有外部依赖容器化。

```bash
# Linux / Mac
cd deploy
export DEEPSEEK_API_KEY=sk-xxx
./start.sh
# → http://localhost
```

```powershell
# Windows
cd deploy
$env:DEEPSEEK_API_KEY = "sk-xxx"
.\start.ps1
```

启动脚本会自动 build 镜像 + 起 6 个容器 + 健康检查。详见 [`deploy/README.md`](./deploy/README.md)。

完整分布式架构、跨节点协调机制、容量估算、运维指南：[`docs/13-distributed-deployment.md`](./docs/13-distributed-deployment.md)。

## HITL 配置

```properties
# 内置 ask_user 工具，默认开启
agentscope.hitl.ask-user-tool-enabled=true

# 危险工具白名单（也可运行时通过 PUT /api/hitl/dangerous-tools 修改，分布式下跨节点广播）
agentscope.hitl.dangerous-tools=delete_file,transfer_money
```

REST 接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/hitl/tools` | 当前注册的所有工具 |
| GET | `/api/hitl/dangerous-tools` | 当前危险工具清单 |
| PUT | `/api/hitl/dangerous-tools` | 替换危险工具清单（分布式下广播到所有节点） |
| POST | `/api/chat/{user}/{conv}/approve-tool` | 批准挂起的工具调用 |

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
    hook/          Hook + 5 个 *Event + ToolConfirmationHook + PendingToolRecoveryHook
    session/       Session 抽象 + FileSystemSession + PostgresSession
    conversation/  ConversationDirectory + FileSystem/Postgres 双实现
    cluster/       DistributedLock + NotificationBus 抽象 + Local/Redis 双实现
    service/       ChatService — 多用户并发入口（含跨节点 interrupt）
  tools/           内置工具：UserInteractionTool（ask_user）、Calculator、CurrentTime、WebSearch
  web/             ChatController — SSE 流式接口 + HITL REST + Conversation CRUD
  config/          AgentAutoConfiguration、AgentProperties

src/main/resources/
  static/                  chat.html + chat.js + chat.css 前端 demo
  db/migration/            Flyway SQL 脚本（分布式模式启用）
  application.properties              单机默认（排除 PG/Redis autoconfig）
  application-distributed.properties  --spring.profiles.active=distributed 时启用

deploy/                    docker-compose 部署（含 Dockerfile / nginx.conf / 启动脚本）

docs/                      13 章学习文档（架构 / Agent / HITL / 分布式部署...）
```

## 文档

完整学习路径见 [`docs/README.md`](./docs/README.md)，**13 章渐进式指南**：

- **Part 1 入门** —— 项目是什么、Hello World、代码地图
- **Part 2 核心抽象** —— Message/Block 模型、ChatModel 流式、Tool 系统、ReActAgent 主循环
- **Part 3 进阶能力** —— 多 Provider 路由、SSE、Hook、HITL 三种模式、Memory + 持久化
- **Part 4 生产部署** —— 分布式架构、跨节点协调、Nginx sticky、容量估算

## 测试

```bash
./mvnw test          # 单元测试 142 个（约 5s）
./mvnw verify        # 加 32 个 Testcontainers IT（PG + Redis，需 Docker，约 35s）
```

覆盖 ReAct 循环、HITL 三种模式（含分批提交）、Memory 压缩、Schema 生成、Hook 短路、跨节点锁竞争、PG JSONB 序列化、Redis pub/sub 等核心路径。

## 与上游 agentscope-java 的差异

设计大方向一致，部分细节按 Java 习惯重写：

| 维度 | 上游 | 本项目 |
|------|------|--------|
| 孤儿修复 | `PendingToolRecoveryHook` (Hook) | 同名同设计 ✅ |
| 结构化输出 | `generate_response` 伪工具 + StructuredOutputHook | 暂未实现，设计已研究 |
| HITL resume 校验 | 重复ID/无效ID/部分+text 三类 | 已对齐 ✅ |
| 数据模型 | `ToolResultBlock` 无 pending 字段 | 保留 pending 字段（语义略丰富） |
| 部署 | 默认单机 | 单机 + 一键分布式 |

## License

未指定。仅供学习参考。

## 致谢

设计参考阿里 [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)。
