# 第 1 章 · 项目是什么 / 整体架构

> 本章目标：30 分钟之内，让你能用一句话回答"这个项目是干啥的"，能画出整体架构图，并知道每一块解决什么问题。**不写一行代码**。

## 1.1 一句话定义

> **my-agentscope-java** 是阿里 [AgentScope](https://github.com/agentscope-ai/agentscope-java) 在 Spring Boot 上的 Java 实现：一个能调用工具、能流式回复、能等待用户审批的 LLM Agent 框架。

把这句话拆开：

| 关键词 | 含义 |
|--------|------|
| **Agent** | 一段能"自己思考 + 自己行动"的程序，不是单次问答 |
| **LLM** | 大语言模型（DeepSeek / Claude / Gemini …），是 agent 的"大脑" |
| **调用工具** | LLM 自己决定调哪个 Java 方法（计算、搜索、扣款…），框架负责派发与回填结果 |
| **流式回复** | 模型还没说完，前端就开始一个字一个字显示 |
| **等待用户审批** | Human-in-the-Loop（HITL）—— 危险操作或信息缺失时停下来，等人决定 |

## 1.2 为什么需要"Agent"，不是直接调 OpenAI API？

直接调 LLM API 像"问一句答一句"：

```
用户：北京今天天气怎么样？
模型：抱歉，我无法访问实时数据。
```

Agent 模式像"问一句、想一想、动手查、再回答"：

```
用户：北京今天天气怎么样？
[模型思考]：我需要调用 weather 工具
[工具执行]：weather("北京") → "晴 22℃"
[模型回答]：北京今天晴，22℃
```

这就是 **ReAct（Reasoning + Acting）**：让模型在"思考"和"调工具"之间循环，直到得到答案。本项目的核心 `ReActAgent` 就是这个循环的实现。

## 1.3 整体架构

```
┌────────────────────────────────────────────────────────────────────┐
│                       前端（chat.html + chat.js）                   │
│                EventSource 订阅 SSE，渲染消息卡片                    │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ HTTP / SSE
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                     ChatController (web 层)                         │
│   /api/chat/{user}/{conv}                  → 流式对话               │
│   /api/chat/{user}/{conv}/approve-tool     → HITL 批准              │
│   /api/hitl/dangerous-tools                → 危险工具清单           │
└─────────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                ChatService (多用户安全的入口)                        │
│  • per-key ReentrantLock：同会话串行，跨会话并行                    │
│  • 每次请求新建 ReActAgent（共享 model/toolkit/hooks）              │
│  • Session 落盘 + 加载                                              │
└─────────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                       ReActAgent (核心循环)                          │
│                                                                     │
│   ┌─ Reasoning ──┐    ┌─ Acting ──┐                                │
│   │ 调 ChatModel │ ─→ │ 调 Toolkit│ ─→ 回到 Reasoning（拿结果）   │
│   └──────────────┘    └───────────┘                                │
│        ▲                                                            │
│        │                                                            │
│        ├──── Hook 系统：PreReasoning / PostReasoning /              │
│        │                  PreActing / PostActing                    │
│        │                                                            │
│        └──── HITL：suspend / 等用户输入 / resume                    │
└────┬───────────────────┬──────────────────┬───────────────────────┘
     │                   │                  │
     ▼                   ▼                  ▼
┌──────────┐    ┌─────────────────┐    ┌──────────────────┐
│  Memory  │    │   ChatModel     │    │     Toolkit      │
│ 消息历史  │    │ OpenAI/Anthrop. │    │  @Tool 方法注册  │
│ (压缩)   │    │ /Gemini Router │    │  反射调用 + Schema│
└────┬─────┘    └────────┬────────┘    └──────────────────┘
     │                   │
     ▼                   ▼
┌──────────┐    ┌─────────────────┐
│ Session  │    │   WebClient     │
│ 落盘     │    │ JDK HttpClient  │
│ (json)   │    │ HTTP/2 + retry  │
└──────────┘    └─────────────────┘
```

## 1.4 模块职责（一句话版）

| 包 | 一句话职责 |
|----|-----------|
| `core.agent` | `ReActAgent`：reasoning + acting 主循环 |
| `core.model` | LLM 抽象 + 三家 provider 实现 + 路由器 |
| `core.tool` | `@Tool` 注解、反射生成 JSON Schema、Toolkit 调用器 |
| `core.message` | `Msg` + 4 种 `ContentBlock`（文本/思考/工具调用/工具结果） |
| `core.memory` | 历史存储 + 自动压缩装饰器 |
| `core.hook` | 4 个挂载点的事件 + 内置 `ToolConfirmationHook` |
| `core.session` | `Session` 抽象 + 文件系统实现 |
| `core.conversation` | "一个用户多个对话"的目录服务 |
| `core.service` | `ChatService`：多用户安全入口，对外的"业务 API" |
| `core.interruption` | 协作式取消令牌（`CancellationToken`） |
| `tools` | 几个内置工具（`ask_user` / `calculator` / `web_search` / `current_time`） |
| `web` | `ChatController`：HTTP / SSE 入口 |
| `config` | Spring 自动装配 + `@ConfigurationProperties` |
| `demo` | 命令行 demo runner（不依赖 web） |

## 1.5 关键概念速查

学完这一章你应该能解释下面这些词：

- **ReAct**：模型轮流"思考（reasoning）"和"行动（acting，即调工具）"，直到不再调工具为止
- **Tool**：一个被 `@Tool` 标注的 Java 方法，框架把它的签名转成 JSON Schema 喂给 LLM
- **Tool Use Block / Tool Result Block**：模型说"我要调工具" / 框架说"工具的执行结果"
- **HITL（Human-in-the-Loop）**：把人塞进 agent 循环里 —— 信息不全问用户，危险操作要审批
- **Hook**：挂在 agent 循环 4 个固定节点的回调，可以改写消息、短路工具、提前终止循环
- **Session**：一个用户的某个对话的持久化身份，文件系统下就是 `<key>/memory.json`
- **Provider**：LLM 服务商；本项目支持 OpenAI 协议（含 DeepSeek/Moonshot 等兼容服务）、Anthropic、Gemini

## 1.6 这个项目和原版 AgentScope 的关系

- **不是 fork**，是从零用 Java 重写
- 设计参考阿里 `agentscope-ai/agentscope-java`，**核心抽象（Agent / Model / Tool / Hook / Memory）保持一致**，便于互相对照理解
- 三种 HITL 模式（`ask_user` / `ToolConfirmationHook` / `ToolSuspendException`）也对齐原版
- 增加了 Spring Boot 整套生态：`@Configuration`、`@ConfigurationProperties`、`SseEmitter`、自动装配 …

## 1.7 下一章

第 2 章会让你把项目跑起来 + 完成第一次对话。

---

**自检题**（不需要写代码，能口头回答即可）：

1. ReAct 模式下，agent 的循环是什么 → 什么 → 什么？
2. 一个 `@Tool` 注解的方法，是怎么"喂"给 LLM 的？
3. HITL 的三种触发方式是哪三种？（提示：一个工具、一个 hook、一个异常）
4. `ChatService` 为什么不直接用单例 `ReActAgent`？
