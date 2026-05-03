# 第 10 章 · Hook 系统

> 目标：理解 4 个挂载点能做什么、Hook 怎么改写事件、`requestStop()` 和 `suspend()` 的区别、并能写一个自定义 hook。

## 10.1 设计动机

ReAct 循环里有几个**通用**的扩展点：

- 调模型前，想注入额外提示（"用户是 VIP，回复要更详细"）
- 调模型后，想检测敏感词，发现就改写或停止
- 调工具前，想拦截危险操作要审批
- 调工具后，想给结果加日志 / 监控

如果直接改 `ReActAgent`，每加一个需求都要改核心代码。**Hook = 切面**：把扩展点暴露出来，业务实现只关心自己关心的事件。

## 10.2 接口

`core/hook/Hook.java`：

```java
@FunctionalInterface
public interface Hook {
    void onEvent(HookEvent event);
    default int priority() { return 0; }
}
```

只有一个方法，参数是 `HookEvent` 基类。具体类型靠 `instanceof` 分发：

```java
Hook logger = event -> {
    if (event instanceof PostReasoningEvent e) {
        System.out.println("model said: " + e.getMessage().getText());
    }
};
```

## 10.3 5 个事件类型

| 事件 | 何时触发 | 能做什么 |
|------|---------|---------|
| `PreCallEvent` | `agent.call()` 入口（先于 HITL resume / 追加 user 消息） | 在 reasoning 之前预处理 memory（典型：补救孤儿 tool_calls） |
| `PreReasoningEvent` | 调 `model.chat()` 前 | 改写要发给模型的 messages |
| `PostReasoningEvent` | 模型回完后 | 改写 assistant 消息 / `requestStop()` |
| `PreActingEvent` | 每个工具调用前 | 改写 ToolUse / `suspend(reason)` 短路 |
| `PostActingEvent` | 每个工具调用后 | 改写 ToolResult / `requestStop()` |

每个事件都继承 `HookEvent`，含：

```java
public abstract class HookEvent {
    private final ReActAgent agent;       // 触发的 agent
    private boolean stopRequested;
    public void requestStop() { ... }
    public boolean isStopRequested() { ... }
}
```

具体子类暴露各自的可改字段。

## 10.4 `PreReasoningEvent`

```java
public class PreReasoningEvent extends HookEvent {
    private List<Msg> messages;
    private final int iteration;
    public List<Msg> getMessages() { return messages; }
    public void setMessages(List<Msg> v) { this.messages = v; }
}
```

可以**整体替换**要发给模型的消息列表。典型场景：

```java
Hook injectVipHint = event -> {
    if (event instanceof PreReasoningEvent e && isVipUser()) {
        List<Msg> msgs = new ArrayList<>(e.getMessages());
        msgs.add(0, Msg.system("注：用户是 VIP，回复要更详细。"));
        e.setMessages(msgs);
    }
};
```

注意：改的是发给模型的消息，**不影响 memory**。下一轮触发时还是从 memory 重新拼，注入逻辑会再跑一次。

## 10.5 `PostReasoningEvent`

```java
public class PostReasoningEvent extends HookEvent {
    private Msg message;
    private final ChatUsage usage;
    private final int iteration;
    public Msg getMessage() { return message; }
    public void setMessage(Msg v) { this.message = v; }
}
```

模型回完后的钩子。可改写 assistant 消息（**会写入 memory**），或调 `requestStop()` 让循环立即终止。

```java
Hook contentFilter = event -> {
    if (event instanceof PostReasoningEvent e) {
        String text = e.getMessage().getText();
        if (text.contains("敏感词")) {
            e.setMessage(Msg.assistant("Bot", "[已过滤]"));
            e.requestStop();
        }
    }
};
```

## 10.6 `PreActingEvent`

```java
public class PreActingEvent extends HookEvent {
    private ToolUseBlock toolUse;
    private boolean suspended;
    private String suspendReason;
    public ToolUseBlock getToolUse() { return toolUse; }
    public void setToolUse(ToolUseBlock v) { this.toolUse = v; }
    public void suspend(String reason) { ... }
}
```

工具调用前的钩子。两种用法：

### 改写工具调用

```java
Hook sanitizeArgs = event -> {
    if (event instanceof PreActingEvent e && e.getToolUse().name().equals("sql")) {
        ToolUseBlock orig = e.getToolUse();
        Map<String, Object> safe = new HashMap<>(orig.input());
        safe.put("query", sanitize((String) safe.get("query")));
        e.setToolUse(new ToolUseBlock(orig.id(), orig.name(), safe, orig.providerSignature()));
    }
};
```

### 短路工具调用 (`suspend`)

```java
Hook needApproval = event -> {
    if (event instanceof PreActingEvent e && e.getToolUse().name().equals("delete_file")) {
        e.suspend("删除操作需要确认");
    }
};
```

调 `suspend(reason)` 后：

- 真实工具**不被执行**
- 框架产生 pending `ToolResultBlock`，reason 写到 output 字段
- agent 进入 HITL 挂起态，立即返回

这就是 `ToolConfirmationHook` 的实现机制（详见第 11 章）。

### `suspend()` vs `requestStop()` 的区别

| | `suspend()` | `requestStop()` |
|---|---|---|
| 哪个事件支持 | 仅 `PreActingEvent` | 所有 `HookEvent` |
| 影响 | 单个工具不执行，产生 pending | 当前轮结束后整个循环退出 |
| memory 写入 | 写入 pending TOOL 消息 | 写入当前轮的 assistant + tool 消息（如果有） |
| 后续行为 | 等外部回填 → 续推 | 直接 return |

## 10.7 `PostActingEvent`

```java
public class PostActingEvent extends HookEvent {
    private final ToolUseBlock toolUse;
    private ToolResultBlock result;
    public ToolResultBlock getResult() { return result; }
    public void setResult(ToolResultBlock v) { this.result = v; }
}
```

工具调用后的钩子。可改写结果或 `requestStop()`。常见用法：日志、监控、结果再加工。

```java
Hook resultLogger = event -> {
    if (event instanceof PostActingEvent e) {
        log.info("tool {} returned: {}", e.getToolUse().name(), e.getResult().output());
    }
};
```

## 10.8 注册：Spring Bean

任何 `Hook` 类型的 Spring bean 都会被自动收集到 `ReActAgent` 和 `ChatService`：

```java
@Configuration
public class MyHooksConfig {
    @Bean
    public Hook myLogger() {
        return event -> { /* ... */ };
    }
}
```

`AgentAutoConfiguration`：

```java
@Bean
public ReActAgent reActAgent(..., @Autowired(required = false) List<Hook> hooks) {
    ReActAgent.Builder b = ReActAgent.builder()...;
    if (hooks != null) b.hooks(hooks);
    return b.build();
}
```

`required = false` 让"没有任何 hook"也能正常启动。

## 10.9 优先级

```java
public interface Hook {
    default int priority() { return 0; }
}
```

数值越小越早执行。`ReActAgent` 构造时按 `priority` 排序：

```java
this.hooks = b.hooks.stream()
        .sorted(Comparator.comparingInt(Hook::priority))
        .toList();
```

典型用法：

```java
@Bean
Hook auditFirst() {  // 第一个跑：审计
    return new Hook() {
        @Override public void onEvent(HookEvent e) { /* 写审计日志 */ }
        @Override public int priority() { return 0; }
    };
}

@Bean
Hook contentFilter() {  // 第二个跑：内容过滤
    return new Hook() {
        @Override public void onEvent(HookEvent e) { /* 检测敏感词 */ }
        @Override public int priority() { return 100; }
    };
}
```

审计先跑，确保即使内容过滤改写了消息，原始内容也已经被记录。

## 10.10 异常处理

`ReActAgent.fire()`：

```java
private void fire(HookEvent event) {
    for (Hook h : hooks) {
        try {
            h.onEvent(event);
        } catch (Exception e) {
            log.warn("Hook {} 抛异常：{}", h.getClass().getSimpleName(), e.toString());
        }
    }
}
```

**单个 hook 异常不中断其它 hook**。这是有意的：hook 是辅助逻辑，不该把核心循环带崩。

代价：你写 hook 时要自己 try-catch / 校验，不能假设异常会冒泡。

## 10.10b 内置 Hook：`PendingToolRecoveryHook`

```java
public class PendingToolRecoveryHook implements Hook {
    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreCallEvent pre)) return;
        // 用户已自己提供 ToolResult → HITL resume 路径，跳过
        if (pre.getInputMessages().stream().anyMatch(m -> m.hasBlock(ToolResultBlock.class))) return;
        healOrphanToolCalls(pre.getMemory());
    }
    @Override public int priority() { return -100; }  // 必须先于业务 hook
}
```

修复"中断 / 崩溃"留下的孤儿 tool_calls（详见第 4 章 §4.8）。

`AgentAutoConfiguration` 默认注册为 bean，自动注入到所有 agent 的 hooks 列表。设计对应上游 agentscope-java 的同名 hook。

## 10.11 内置 Hook：`ToolConfirmationHook`

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

    public synchronized void addDangerousTool(String toolName) { ... }
    public synchronized void removeDangerousTool(String toolName) { ... }
    public synchronized boolean isDangerous(String toolName) { ... }
}
```

非常简洁的实现 —— `synchronized` 保护清单，让运行时通过 `PUT /api/hitl/dangerous-tools` 动态修改。详细见第 11 章。

## 10.12 实战：写一个"工具调用次数限流" Hook

任务：单次会话内同一工具不能调超过 5 次。

```java
@Component
public class ToolRateLimitHook implements Hook {
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreActingEvent pre)) return;
        String name = pre.getToolUse().name();
        int n = counts.merge(name, 1, Integer::sum);
        if (n > 5) {
            pre.suspend("工具 " + name + " 已调用 " + n + " 次，超过限制");
        }
    }

    public void reset() { counts.clear(); }
}
```

**问题**：这是单例 hook，`counts` 是全局的，多用户会串。修法：把计数挂到 `event.getAgent()` 上，或者改用 `@RequestScope`。本框架没有 per-session hook 机制，要做完美隔离需要在 `ChatService` 里"每次新建 agent 时新建 hook"。

## 10.13 ChatService 流式路径里的 Hook

`ChatService.runReactLoop` 在 acting 阶段也 fire `PreActingEvent`：

```java
PreActingEvent preAct = new PreActingEvent(null, use);
fireSafely(preAct);
ToolUseBlock effective = preAct.getToolUse();
ToolResultBlock result;
if (preAct.isSuspended()) {
    result = ToolResultBlock.pending(effective.id(), effective.name(),
            preAct.getSuspendReason() != null ? preAct.getSuspendReason() : "等待人工审批");
} else {
    result = toolkit.invoke(effective);
}
```

注意 `new PreActingEvent(null, use)` —— 流式路径没有 `ReActAgent` 实例，传 null。如果你的 hook 依赖 `event.getAgent()`，**在流式路径会拿到 null**，要做空判断。

`PreReasoningEvent` 和 `PostReasoningEvent` 在流式路径**不触发**（流式路径不走 `model.chat()`，绕过了那两个钩子）。这是个不对称，未来可能修。

## 10.14 自检

- [ ] 我能列出 4 个 hook 事件及各自能做什么
- [ ] 我能解释 `suspend()` 和 `requestStop()` 的差异
- [ ] 我知道单个 hook 抛异常不会影响其它 hook
- [ ] 我能写一个最简单的"日志 hook"
- [ ] 我知道流式路径下 `event.getAgent()` 可能为 null
