# 第 11 章 · HITL 三种模式

> 目标：彻底搞懂"Human-in-the-Loop"的三种触发方式、它们的语义差异、前后端协作的完整时序。

## 11.1 什么是 HITL

让"人"成为 agent 循环的一部分：信息缺失时停下来问、危险操作前要审批、需要外部系统执行的操作交给人代办。

本项目对齐阿里 agentscope-java 的 3 种实现：

| 模式 | 触发方 | 典型场景 |
|------|-------|---------|
| 1. `ask_user` 工具 | LLM 主动 | 信息不全：目的地、预算、二次确认 |
| 2. `ToolConfirmationHook` | 外部配置 | 危险工具拦截：删文件、转账 |
| 3. `ToolSuspendException` | 业务工具自身 | 工具内部判断需要外部执行：金额超阈值 |

三种最终都走**同一套机制**：产生 pending `ToolResultBlock` → agent 进入挂起态 → 等用户回填 → 续推。

## 11.2 共同的核心机制

无论哪种模式，最终都是：

```
1. 出现 pending ToolResultBlock
   ↓
2. memory 末尾消息变成 [TOOL, content=[..., pending block, ...]]
   ↓
3. ReActAgent.reactLoop 检测到 pending → return（不再调模型）
   ↓
4. agent.isAwaitingHumanInput() == true
   ↓
5. 前端拉到 pending 状态，渲染对应 UI
   ↓
6. 用户操作 → 后端把 pending 替换为真实 ToolResultBlock
   ↓
7. resumeWithHumanInput → 回到 reactLoop 继续
```

第 7 章已经讲过 `resumeWithHumanInput` 的实现。本章重点看前 5 步在三种模式下分别怎么发生。

## 11.3 模式 1：`ask_user` 工具

### 工具定义

`tools/UserInteractionTool.java`：

```java
public class UserInteractionTool implements ToolProvider {
    public static final String TOOL_NAME = "ask_user";

    @Tool(name = TOOL_NAME, description = "Ask the user for clarification...")
    public String askUser(
        @ToolParam(name = "question", description = "向用户提的问题") String question,
        @ToolParam(name = "ui_type", required = false) String uiType,
        @ToolParam(name = "options", required = false) List<String> options,
        @ToolParam(name = "fields", required = false) List<Map<String, Object>> fields,
        @ToolParam(name = "default_value", required = false) Object defaultValue,
        @ToolParam(name = "allow_other", required = false) Boolean allowOther
    ) {
        String reason = (question != null && !question.isBlank()) ? question : "等待用户输入";
        throw new ToolSuspendException(reason);
    }
}
```

**永远抛 `ToolSuspendException`** —— 这个工具的"实现"就是挂起。

### 完整时序

```
用户输入: "帮我订机票"
  ↓
[第 1 轮 reasoning]
  ↓
模型决定: ask_user(question="目的地?", ui_type="text")
  ↓
[第 1 轮 acting]
  ↓
toolkit.invoke(askUser) → askUser() 抛 ToolSuspendException
  ↓
Toolkit.invoke() catch → 返回 ToolResultBlock.pending("c1", "ask_user", "目的地?")
  ↓
memory 末尾写入 TOOL 消息（含 pending block）
  ↓
reactLoop 检测 pending → return
  ↓
SSE 推 tool_call(name=ask_user, input={question:"目的地?", ui_type:"text"}) + tool_result(pending=true)
  ↓
前端 done 事件 → renderHitlPrompt → 渲染输入框
  ↓
用户输入"上海" → 点提交
  ↓
前端 POST /api/chat/{user}/{conv} body={role:"TOOL", toolCallId:"c1", text:"上海"}
  ↓
ChatService.chat 把它包成 Msg(TOOL, [ToolResultBlock.success("c1","ask_user","上海")])
  ↓
ReActAgent.call(toolMsg) → resumeWithHumanInput → reactLoop 续推
  ↓
[第 2 轮 reasoning]
  ↓
模型: ask_user(question="出发地?")
  ↓
... 继续问预算、日期等 ...
  ↓
模型最终给出建议
```

### 设计要点

- LLM 自己**决定**何时调 `ask_user`，由 system prompt 中的描述引导
- 工具入参里 `ui_type` 决定前端用什么控件：`text` / `select` / `multi_select` / `confirm` / `form` / `date` / `number`
- 模型可以多次连续调 `ask_user`（每问一次挂起一次）—— 这就是为什么前面那个 bug 一定要修：用户回答完不能再 `skip(1)`
- 工具入参原样保留在 `ToolUseBlock.input`，前端从 SSE 拿到这些参数渲染 UI

## 11.4 模式 2：`ToolConfirmationHook`

### 工作原理

危险工具清单**外部配置**，不需要改业务工具代码。

```java
public class ToolConfirmationHook implements Hook {
    private final Set<String> dangerousTools = new HashSet<>();

    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreActingEvent pre)) return;
        String toolName = pre.getToolUse().name();
        if (isDangerous(toolName)) {
            pre.suspend("工具 '" + toolName + "' 已被标记为需要人工审批");
        }
    }
}
```

`PreActingEvent.suspend(reason)` 让框架短路工具调用：

```java
// ReActAgent.reactLoop 中
if (preAct.isSuspended()) {
    result = ToolResultBlock.pending(effectiveUse.id(), effectiveUse.name(), reason);
} else {
    result = toolkit.invoke(effectiveUse);
}
```

### 关键差异（vs 模式 1）

- **工具不被执行**（vs 模式 1 的工具被执行了，但执行结果是抛异常）
- **批准后还能真正执行** —— 后端调 `chatService.approvePendingTool(key, toolCallId)` 时再执行
- **拒绝就回填"已拒绝"** 作为结果

### 完整时序

```
用户: "把这个文件删了"
  ↓
模型: delete_file(path="/etc/passwd")
  ↓
[acting] PreActingEvent fire
  ↓
ToolConfirmationHook 检测到 "delete_file" 在清单 → preAct.suspend("...")
  ↓
框架: result = ToolResultBlock.pending(...)  ← 工具完全没执行
  ↓
memory + SSE + 前端... 同模式 1
  ↓
前端渲染"批准 / 拒绝"两按钮
  ↓
[用户点"批准"]
  ↓
POST /api/chat/{user}/{conv}/approve-tool body={toolCallId:"c1"}
  ↓
ChatService.approvePendingTool:
    - 拿到该 SessionKey 的 agent
    - 校验 toolCallId 在 pending 集合里
    - 找到对应的 ToolUseBlock
    - toolkit.invoke(use)  ← 这里才真正执行
    - 包成 TOOL 消息 → resumeWithHumanInput → 续推
  ↓
[或用户点"拒绝"]
  ↓
POST /api/chat/{user}/{conv} body={role:"TOOL", toolCallId:"c1", text:"用户拒绝执行"}
  ↓
text 作为 ToolResultBlock.output → resumeWithHumanInput → 续推（模型看到拒绝原因）
```

### 运行时动态修改清单

`ToolConfirmationHook` 的 `dangerousTools` 用 `synchronized` 保护，可以运行时改：

```java
PUT /api/hitl/dangerous-tools
Body: ["delete_file", "transfer_money"]
```

`ChatController.HitlSettingsApi` 提供这个接口。配置 `agentscope.hitl.dangerous-tools=...` 是初始值，启动后可以通过 API 改。

## 11.5 模式 3：`ToolSuspendException`

### 工具内部判断

```java
@Tool(description = "扣款")
public String deduct(@ToolParam(description = "金额") int amount) {
    if (amount > 10000) {
        throw new ToolSuspendException("金额 " + amount + " 元超过阈值，请人工审批");
    }
    return "已扣款 " + amount + " 元";
}
```

工具自己根据**入参**判断要不要挂起 —— 模式 2 只能按工具名拦，没法看参数。

### 与模式 1 的关系

技术上**完全一样** —— `UserInteractionTool` 就是模式 3 的一个特例，区别只在"工具的语义"：

- 模式 1：工具的目的就是"问用户"
- 模式 3：工具是业务工具，只在某些条件下挂起

### 触发流程

```
模型: deduct(amount=50000)
  ↓
toolkit.invoke → deduct(50000) 抛 ToolSuspendException
  ↓
Toolkit.invoke catch → ToolResultBlock.pending("c1", "deduct", "金额 50000 元超过阈值...")
  ↓
... 同模式 1 ...
```

注意 `Toolkit.invoke` 的实现：

```java
catch (InvocationTargetException ite) {
    Throwable cause = ite.getCause();
    if (cause instanceof ToolSuspendException tse) {
        return ToolResultBlock.pending(use.id(), use.name(),
                tse.getReason() != null ? tse.getReason() : tse.getMessage());
    }
    return ToolResultBlock.error(...);
}
```

`ToolSuspendException` 是唯一被特殊处理的异常 —— 其它异常都进 `error` 分支。

## 11.6 三种模式对比

| 维度 | 模式 1 (`ask_user`) | 模式 2 (`ToolConfirmationHook`) | 模式 3 (`ToolSuspendException`) |
|------|----|----|----|
| 谁决定挂起 | LLM | 外部清单 | 工具自己 |
| 工具是否执行 | "执行"了（但实现就是抛异常） | **不执行** | "执行"到了抛异常那行 |
| 批准后行为 | 用户答案作为结果回填 | 真正执行该工具 | 用户答案作为结果回填 |
| 实现位置 | `tools/UserInteractionTool` | `core/hook/ToolConfirmationHook` | 业务工具的 `@Tool` 方法 |
| 配置位置 | `agentscope.hitl.ask-user-tool-enabled` | `agentscope.hitl.dangerous-tools` | 业务代码 |
| 适用场景 | 信息收集、二次确认 | 危险动作的 RBAC 式黑名单 | 工具自身的业务规则 |

## 11.7 后端 API 一览

| 接口 | 用途 |
|------|------|
| `POST /api/chat/{user}/{conv}` body={role:"TOOL",...} | **回填工具结果**（适用模式 1、模式 2 拒绝、模式 3） |
| `POST /api/chat/{user}/{conv}/approve-tool` | **批准并执行**（仅模式 2） |
| `GET /api/hitl/tools` | 列出所有已注册工具 |
| `GET /api/hitl/dangerous-tools` | 当前危险工具清单 |
| `PUT /api/hitl/dangerous-tools` | 替换危险工具清单 |

## 11.8 持久化

挂起态会**写入 session**：

```
.agent-state/alice__conv-001/memory.json
  └── 末尾消息: {role: TOOL, content: [{@type: tool_result, pending: true, ...}]}
```

这意味着**进程重启后 HITL 状态不丢**。下次用户打开页面 → 加载历史 → 检测到末尾 pending → 重新渲染输入卡片。

`PendingToolRecoveryHook` 在每次 `agent.call()` 入口的 `PreCallEvent` 时机扫一遍，给孤儿 tool_calls 补合成错误结果，确保 pending 态可恢复（详见第 4 章 §4.8）。HITL pending 不会被误伤 —— pending 的 ToolResultBlock 已经存在，不算孤儿。

## 11.9 已知陷阱与修复

回顾过往修过的几个 bug，理解 HITL 的边界条件：

### 陷阱 1：连续 `ask_user` 后会"停下来"

模型连续问 3 个问题，用户答完第 3 个后，agent 莫名其妙不再续推。

**原因**：HTTP API 默认 `skip(1)` `addedMessages`（跳过用户刚发的那条消息，避免和前端已显示的重复），但 HITL resume 不在开头追加 USER 消息（`resumeWithHumanInput` 是替换末尾 TOOL 消息），所以 `skip(1)` 会把第一条新消息错误吞掉。

**修法**：`skip(isHitlResume ? 0 : 1)`。

### 陷阱 2：提交按钮卡死

用户点提交后等几秒，按钮还可点击 → 用户连点 N 次。

**修法**：fetch 前 `markHitlCardSubmitting(card, '提交中…')` 禁用按钮 + 改文案。

### 陷阱 3：HITL 卡渲染两遍

`done` 事件触发 + `tool_call` 事件触发，同一个 ask_user 渲染两次。

**修法**：用 `data-hitl-id` DOM 属性做幂等检查。

### 陷阱 4：Gemini 把占位文案塞到 `ui_type` 字段

观察到 Gemini 偶发产生 `{"ui_type":"请选择城市","question":"..."}` 这种位置错乱。

**修法**：前端 `renderAskUserInto` 在未识别的 `ui_type` 时回退为 `text`。

## 11.10 自检

- [ ] 我能口述三种模式的触发方 / 工具是否执行 / 批准后行为
- [ ] 我能画出"模式 1 一次完整时序图"
- [ ] 我能解释 `ToolSuspendException` 是如何被 `Toolkit.invoke` 特殊处理的
- [ ] 我能解释为什么模式 2 的"批准"接口需要单独调用
- [ ] 我知道挂起态是持久化的（重启不丢）
