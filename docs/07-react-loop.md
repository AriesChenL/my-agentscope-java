# 第 7 章 · ReActAgent 主循环

> 目标：逐段拆 `ReActAgent.java` 的核心循环，理解一次 `agent.call(userInput)` 在内部经历了什么，包括 HITL 的"挂起 → 恢复"机制。

## 7.1 ReAct 模式回顾

```
loop:
    1. Reasoning: 把 system + 历史 + 工具结果 喂给模型，拿一个回复
    2. 如果回复里没有 ToolUseBlock → 完成，返回回复
    3. Acting: 对每个 ToolUseBlock，调对应工具，收集结果
    4. 把结果作为 TOOL 消息加入 memory
    5. 回到 1
退出条件：
- 模型不再请求工具（最常见）
- 达到 maxIters（默认 10）
- 任意 hook 调用 requestStop()
- 出现 pending 工具（HITL）→ 立即返回，等外部输入
```

## 7.2 类结构

`core/agent/ReActAgent.java`，全文 400 行。字段：

```java
private final String name;
private final String sysPrompt;
private final ChatModel model;
private final Memory memory;
private final Toolkit toolkit;
private final GenerateOptions generateOptions;
private final int maxIters;
private final List<Hook> hooks;
private final AtomicReference<CancellationToken> currentToken = new AtomicReference<>();
```

构造用 Builder。`hooks` 在构造时按 `priority()` 升序排序，之后不可变。

## 7.3 入口：`call(Msg userInput)`

```java
public Msg call(Msg userInput) {
    CancellationToken token = new CancellationToken();
    currentToken.set(token);
    try {
        if (isAwaitingHumanInput()) {
            resumeWithHumanInput(userInput);
        } else if (userInput != null) {
            memory.addMessage(userInput);
        }
        return reactLoop(token);
    } finally {
        currentToken.compareAndSet(token, null);
    }
}
```

三种调用场景：

1. **正常调用**：传一条 USER 消息，进入 reactLoop
2. **HITL 恢复**：当前在挂起态，传一条带 ToolResultBlock 的 TOOL 消息，先 resume 再 reactLoop
3. **空调用**：`userInput=null` 且不在挂起态 —— 框架不报错，直接进 loop（典型用于"重试上一轮"）

`AtomicReference<CancellationToken>` 让外部 `interrupt()` 能找到当前正在跑的 token：

```java
public void interrupt(InterruptSource source) {
    CancellationToken token = currentToken.get();
    if (token != null) token.cancel(source);
}
```

`finally` 里用 `compareAndSet` 而不是直接 `set(null)` —— 防止"两个并发 call 串到一起时 A 结束错误地把 B 的 token 抹掉"。本项目不鼓励单实例并发 call，但保险起见。

## 7.4 `reactLoop(CancellationToken)` —— 主体

伪代码：

```java
for (int iter = 0; iter < maxIters; iter++) {
    token.throwIfCancelled();

    // ---- Reasoning ----
    PreReasoningEvent pre = new PreReasoningEvent(this, prepareMessages(), iter);
    fire(pre);

    ChatResponse resp = model.chat(pre.getMessages(), toolkit.getSchemas(), options, token);
    Msg assistantMsg = renameTo(resp.message(), name);

    PostReasoningEvent post = new PostReasoningEvent(this, assistantMsg, resp.usage(), iter);
    fire(post);
    assistantMsg = post.getMessage();
    memory.addMessage(assistantMsg);
    if (post.isStopRequested()) return assistantMsg;

    List<ToolUseBlock> toolUses = assistantMsg.getBlocks(ToolUseBlock.class);
    if (toolUses.isEmpty()) return assistantMsg;

    // ---- Acting ----
    List<ContentBlock> resultBlocks = new ArrayList<>();
    for (ToolUseBlock use : toolUses) {
        token.throwIfCancelled();

        PreActingEvent preAct = new PreActingEvent(this, use);
        fire(preAct);
        ToolUseBlock effective = preAct.getToolUse();

        ToolResultBlock result;
        if (preAct.isSuspended()) {
            result = ToolResultBlock.pending(effective.id(), effective.name(), preAct.getSuspendReason());
        } else {
            result = toolkit.invoke(effective);
        }

        PostActingEvent postAct = new PostActingEvent(this, effective, result);
        fire(postAct);
        resultBlocks.add(postAct.getResult());
        if (postAct.isStopRequested()) stopAfterActing = true;
    }
    Msg toolMsg = Msg.builder().role(TOOL).content(resultBlocks).build();
    memory.addMessage(toolMsg);

    if (stopAfterActing) return assistantMsg;
    if (anyPending(resultBlocks)) return assistantMsg;  // ← HITL 出口
}
return fallback("[max iterations reached]");
```

5 个关键节点（也是 4 个 Hook 的挂载位置）：

| 阶段 | 触发的 Hook 事件 | 你能做什么 |
|------|----------------|-----------|
| 调模型前 | `PreReasoningEvent` | 改写发给模型的 messages（如裁剪、注入提示） |
| 调模型后 | `PostReasoningEvent` | 改写 assistant 消息 / 调 `requestStop()` |
| 每个工具调用前 | `PreActingEvent` | 改写 ToolUse / 调 `suspend()` 短路 |
| 每个工具调用后 | `PostActingEvent` | 改写结果 / 调 `requestStop()` |

第 10 章详细讲 Hook。

## 7.5 `prepareMessages()` —— 拼 prompt

```java
private List<Msg> prepareMessages() {
    List<Msg> list = new ArrayList<>();
    if (sysPrompt != null && !sysPrompt.isBlank()) list.add(Msg.system(sysPrompt));
    list.addAll(memory.getMessages());
    return list;
}
```

简单粗暴：sysPrompt + memory 全量。memory 的压缩在第 12 章讲（用 `CompactingMemory` 装饰器，对 ReActAgent 透明）。

## 7.6 HITL 挂起：`isAwaitingHumanInput()`

```java
public boolean isAwaitingHumanInput() {
    List<Msg> msgs = memory.getMessages();
    if (msgs.isEmpty()) return false;
    Msg last = msgs.getLast();
    if (last.getRole() != MsgRole.TOOL) return false;
    return last.getBlocks(ToolResultBlock.class).stream().anyMatch(ToolResultBlock::pending);
}
```

判断标准：**memory 末尾消息是 TOOL 角色，且至少含一个 pending 的 ToolResultBlock**。

reactLoop 退出时如果出现 pending，留下来的 memory 状态正好满足这个条件。下次 `call(...)` 进来时框架就知道"上次没完成，等用户输入"。

## 7.7 HITL 恢复：`resumeWithHumanInput(Msg)`

```java
private void resumeWithHumanInput(Msg userInput) {
    if (userInput == null || userInput.getRole() != MsgRole.TOOL)
        throw new IllegalStateException("需要 TOOL 角色的消息提供工具结果");

    List<ToolResultBlock> provided = userInput.getBlocks(ToolResultBlock.class);
    if (provided.isEmpty())
        throw new IllegalStateException("TOOL 消息中未包含任何 ToolResultBlock");

    List<Msg> msgs = new ArrayList<>(memory.getMessages());
    Msg pendingToolMsg = msgs.getLast();
    List<ContentBlock> oldBlocks = pendingToolMsg.getContent();

    Set<String> pendingIds = oldBlocks.stream()
            .filter(b -> b instanceof ToolResultBlock r && r.pending())
            .map(b -> ((ToolResultBlock) b).id())
            .collect(Collectors.toSet());
    Set<String> providedIds = provided.stream().map(ToolResultBlock::id).collect(Collectors.toSet());
    if (!providedIds.containsAll(pendingIds))
        throw new IllegalStateException("缺少 pending 工具的结果，未提供的 id：" + missing);

    // 用 provided 替换 oldBlocks 里 pending 的项
    Map<String, ToolResultBlock> replacement = provided.stream()
            .collect(Collectors.toMap(ToolResultBlock::id, b -> b));
    List<ContentBlock> newBlocks = new ArrayList<>();
    for (ContentBlock b : oldBlocks) {
        if (b instanceof ToolResultBlock r && r.pending() && replacement.containsKey(r.id()))
            newBlocks.add(replacement.get(r.id()));
        else
            newBlocks.add(b);
    }
    Msg replaced = Msg.builder().id(pendingToolMsg.getId()).role(TOOL).content(newBlocks).build();

    memory.clear();
    msgs.set(msgs.size() - 1, replaced);
    msgs.forEach(memory::addMessage);
}
```

逻辑（对齐上游 agentscope-java `validateAndAddToolResults` 的校验严格度）：

1. 校验 userInput 是 TOOL 角色且含 ToolResultBlock
2. 校验提供的 ID 不重复
3. 收集 memory 末尾 TOOL 消息中所有 pending 的 ID 集合
4. 校验提供的 ID 都属于 pending 集合（不允许多余）
5. **支持分批提交**：providedIds 可以是 pendingIds 的子集
6. 部分提交时不允许混入 text 等非 ToolResultBlock 内容（避免污染上下文）
7. **替换** memory 末尾 TOOL 消息：providedIds 对应的 pending 项换成真实结果，未填的 pending 项保持原样
8. 因为 `Memory` 没有"替换最后一条"接口，统一用 clear + 全量回填

`call()` 在调完 `resumeWithHumanInput` 后会再次检查 `isAwaitingHumanInput()` —— 如果还有未填的 pending（分批提交场景），直接返回最后一条 ASSISTANT 消息，**不进 reactLoop**，等调用方下次再补。完整覆盖后才进入下一轮 reasoning。

## 7.8 acting 阶段的 HITL 短路

```java
if (preAct.isSuspended()) {
    String reason = preAct.getSuspendReason() != null ? preAct.getSuspendReason() : "等待人工审批";
    result = ToolResultBlock.pending(effectiveUse.id(), effectiveUse.name(), reason);
} else {
    result = toolkit.invoke(effectiveUse);
}
```

PreActingEvent 上的 `suspend(reason)` 让 hook 能短路工具调用。这是 `ToolConfirmationHook` 拦截危险工具的机制：hook 检测到工具名在黑名单 → `suspend("需要人工审批")` → 框架直接产生 pending 结果，工具实际不会被执行。

## 7.9 退出条件汇总

| 场景 | 退出方式 |
|------|---------|
| 模型不再请求工具 | reasoning 后 `toolUses.isEmpty()` → return |
| Hook 在 PostReasoning 调 `requestStop()` | reasoning 后立即 return |
| Hook 在 PostActing 调 `requestStop()` | acting 完一轮后 return |
| 任一工具产生 pending 结果（HITL） | acting 完一轮后 return |
| 达到 maxIters | 写一条 "[max iterations reached]" fallback 消息后 return |
| Token 取消 | `throwIfCancelled()` 抛 `AgentInterruptedException` |

## 7.10 单 ReActAgent vs ChatService

`ReActAgent` 本身**不是线程安全**的：单实例并发 call 会让 memory 串。所以：

- `AgentAutoConfiguration` 注册的 `ReActAgent` 单例 bean 仅适合"单租户、串行"场景（脚本、调试、单用户 CLI）
- 多用户并发请求请用 `ChatService`，它对每次请求新建 ReActAgent，详见第 12 章

## 7.11 `ChatService.runReactLoop` 的差异

`ChatService.chatReactStream()` 重新走了一遍 ReAct 循环（在 `runReactLoop()`），**没有复用** `ReActAgent.call()`。原因：

- `ReActAgent.call()` 用阻塞的 `model.chat()`，每轮 reasoning 要等模型完整回完才能继续
- `ChatService.runReactLoop()` 直接订阅 `model.stream()`，每个 chunk 转译成 SSE 事件下发，前端能看到打字机效果

逻辑一致，但流式版本多了"边累积边发事件"的步骤。第 9 章详细讲。

## 7.12 自检

- [ ] 我能口述 ReAct 循环的 4 步
- [ ] 我能列出循环的 6 种退出条件
- [ ] 我能解释 `isAwaitingHumanInput()` 的判断标准
- [ ] 我能描述 `resumeWithHumanInput()` 替换 memory 末尾消息的过程
- [ ] 我知道为什么 `ChatService` 不直接用 `ReActAgent` 单例
