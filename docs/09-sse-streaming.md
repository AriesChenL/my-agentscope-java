# 第 9 章 · 流式 SSE 到前端

> 目标：理解 `SseEmitter` 怎么把后端 `Flux<ReactEvent>` 推到浏览器，前端 `EventSource` 又怎么按事件类型渲染对应卡片。

## 9.1 为什么用 SSE 而不是 WebSocket

| 维度 | SSE | WebSocket |
|------|-----|-----------|
| 方向 | 服务端 → 客户端 单向 | 双向 |
| 协议 | HTTP（一个长连接 + chunked encoding） | 升级握手 + 二进制帧 |
| 浏览器 API | `EventSource`（原生） | `WebSocket`（原生） |
| 自动重连 | 是 | 否（要手写） |
| 代理穿透 | 好 | 偶有问题 |
| 后端复杂度 | 低 | 高 |

LLM 流式回复是天然单向场景：服务器持续 push token，客户端不需要中途回话。SSE 完美匹配，且不需要额外协议升级。

## 9.2 后端 SSE 端点

`ChatController`：

```java
@GetMapping(value = "/api/chat/{userId}/{convId}/react-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter reactStream(@PathVariable String userId,
                              @PathVariable String convId,
                              @RequestParam String text) {
    SessionKey key = SessionKey.of(userId + "__" + convId);
    SseEmitter emitter = new SseEmitter(0L);  // 0 = 不超时
    Msg input = Msg.user(userId, text);

    Disposable sub = chatService.chatReactStream(key, input)
            .subscribe(
                event -> emitter.send(toSseEvent(event)),
                err -> emitter.completeWithError(err),
                () -> emitter.complete()
            );
    emitter.onCompletion(sub::dispose);
    emitter.onTimeout(sub::dispose);
    return emitter;
}
```

5 个关键点：

1. `SseEmitter(0L)` —— 不超时（默认 30 秒，对 LLM 太短）
2. `produces = TEXT_EVENT_STREAM_VALUE` —— 让 Spring 自动设 `Content-Type: text/event-stream`
3. `chatService.chatReactStream(...)` 返回 `Flux<ReactEvent>`，订阅后转 SSE 推
4. `Disposable` 在 emitter 完成/超时时取消订阅，释放后端资源
5. 错误用 `completeWithError`，前端 `EventSource.onerror` 会触发

## 9.3 `ReactEvent` → SSE event

`ReactEvent` 是 sealed interface（详见第 7 章）：

```java
sealed interface ReactEvent {
    record IterationStart(int iteration) implements ReactEvent {}
    record Thinking(String delta)        implements ReactEvent {}
    record Text(String delta)            implements ReactEvent {}
    record ToolCall(ToolUseBlock toolUse) implements ReactEvent {}
    record ToolResult(ToolResultBlock result) implements ReactEvent {}
    record Done(ChatUsage usage)         implements ReactEvent {}
}
```

控制器把它翻译成有名字的 SSE event：

```java
private SseEmitter.SseEventBuilder toSseEvent(ReactEvent ev) {
    SseEmitter.SseEventBuilder e = SseEmitter.event();
    return switch (ev) {
        case ReactEvent.IterationStart i -> e.name("iteration").data(Map.of("index", i.iteration()));
        case ReactEvent.Thinking t       -> e.name("thinking").data(Map.of("delta", t.delta()));
        case ReactEvent.Text t           -> e.name("text").data(Map.of("delta", t.delta()));
        case ReactEvent.ToolCall c       -> e.name("tool_call").data(Map.of(
                "id", c.toolUse().id(), "name", c.toolUse().name(), "input", c.toolUse().input()));
        case ReactEvent.ToolResult r     -> e.name("tool_result").data(Map.of(
                "id", r.result().id(), "name", r.result().name(),
                "output", r.result().output(),
                "isError", r.result().isError(),
                "pending", r.result().pending()));
        case ReactEvent.Done d           -> e.name("done").data(Map.of(
                "usage", d.usage()));
    };
}
```

SSE 协议线 wire 格式：

```
event: text
data: {"delta": "你"}

event: text
data: {"delta": "好"}

event: tool_call
data: {"id":"c1","name":"add","input":{"a":1,"b":2}}

event: tool_result
data: {"id":"c1","name":"add","output":"3","isError":false,"pending":false}

event: done
data: {"usage":{"promptTokens":120,"completionTokens":45}}
```

每个 event 用空行分隔。

## 9.4 前端订阅

`static/chat.js` 主流程：

```javascript
function sendMessage(text) {
    const url = `/api/chat/${user}/${conv}/react-stream?text=${encodeURIComponent(text)}`;
    const es = new EventSource(url);

    es.addEventListener('iteration', e => {
        const {index} = JSON.parse(e.data);
        // 准备一个新的 assistant 消息容器
    });

    es.addEventListener('text', e => {
        const {delta} = JSON.parse(e.data);
        appendTextDelta(delta);  // 追加到当前 assistant 消息
    });

    es.addEventListener('thinking', e => {
        const {delta} = JSON.parse(e.data);
        appendThinkingDelta(delta);  // 追加到思考链卡片
    });

    es.addEventListener('tool_call', e => {
        const data = JSON.parse(e.data);
        appendToolCall(data);  // 渲染金色卡片
        if (data.name === ASK_USER) {
            pendingTools.set(data.id, data);  // HITL：先标记，等 done 时渲染输入控件
        }
    });

    es.addEventListener('tool_result', e => {
        const data = JSON.parse(e.data);
        appendToolResult(data);  // 渲染绿色卡片
        if (data.pending) {
            pendingTools.set(data.id, data);  // HITL：标记需要人工
        }
    });

    es.addEventListener('done', e => {
        es.close();
        renderHitlPrompt();  // 扫 pendingTools，渲染输入卡片
    });

    es.onerror = () => { es.close(); /* 显示错误 */ };
}
```

## 9.5 前端 DOM 结构

每条助手消息长这样：

```html
<div class="msg assistant">
  <div class="msg-header">Assistant</div>
  <div class="msg-thinking" hidden>
    <details><summary>思考过程</summary><pre>...</pre></details>
  </div>
  <div class="msg-text"></div>             ← textDelta 累加
  <div class="msg-tool-calls"></div>       ← 每个 tool_call 一个卡片
  <div class="msg-tool-results"></div>     ← 每个 tool_result 一个卡片
  <div class="msg-hitl"></div>             ← HITL 输入卡片（done 时插入）
</div>
```

`appendTextDelta` 就是 `el.msg-text.textContent += delta`，浏览器自动重绘出"打字机效果"。

## 9.6 HITL 卡片渲染

HITL 模式 1（`ask_user`）的渲染逻辑：

```javascript
function renderAskUserInto(card, toolUse) {
    const params = toolUse.input || {};
    const uiType = params.ui_type || 'text';
    const question = params.question || '请输入';

    card.innerHTML = `
        <div class="hitl-question">${escape(question)}</div>
        <div class="hitl-input"></div>
        <div class="hitl-actions">
            <button class="hitl-btn hitl-submit">提交</button>
        </div>
    `;

    const input = card.querySelector('.hitl-input');
    switch (uiType) {
        case 'text':
            input.innerHTML = `<input type="text" />`;
            break;
        case 'select':
            input.innerHTML = (params.options || [])
                .map(o => `<label class="hitl-opt"><input type="radio" name="x" value="${o}"/>${o}</label>`)
                .join('');
            break;
        case 'multi_select': /* checkbox */ break;
        case 'confirm': /* 是/否 按钮 */ break;
        case 'form': /* 多字段表单 */ break;
        case 'date': /* date picker */ break;
        case 'number': /* number input */ break;
    }

    card.querySelector('.hitl-submit').addEventListener('click', () => submitToolReply(toolUse.id, gatherValue(card)));
}
```

提交时 POST 一条带 ToolResultBlock 的消息：

```javascript
async function submitToolReply(toolCallId, value) {
    markHitlCardSubmitting(card, '提交中…');  // 禁用按钮
    await fetch(`/api/chat/${user}/${conv}`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            role: 'TOOL',
            toolCallId: toolCallId,
            text: JSON.stringify(value),
        })
    });
    // 然后重新 EventSource 拉一次新流（agent 续推）
    sendMessageWithoutNewUserMsg();
}
```

## 9.6.1 HITL 模式 2 的渲染

`ToolConfirmationHook` 拦截危险工具时，前端拿到的是 `tool_result` 事件 + `pending=true`。渲染"批准 / 拒绝"两个按钮：

```javascript
function renderApproveRejectInto(card, toolUseId, name, input) {
    card.innerHTML = `
        <div class="hitl-question">是否批准执行 ${name}(${JSON.stringify(input)}) ?</div>
        <div class="hitl-actions">
            <button class="hitl-btn hitl-approve">批准</button>
            <button class="hitl-btn hitl-reject">拒绝</button>
        </div>
    `;
    card.querySelector('.hitl-approve').addEventListener('click', () => approveTool(toolUseId));
    card.querySelector('.hitl-reject').addEventListener('click', () => rejectTool(toolUseId));
}

async function approveTool(toolCallId) {
    await fetch(`/api/chat/${user}/${conv}/approve-tool`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({toolCallId})
    });
    sendMessageWithoutNewUserMsg();
}
```

后端 `ChatController.approveTool` 调 `ChatService.approvePendingTool(key, toolCallId)`，框架真正执行工具 + 续推（详见第 11 章）。

## 9.7 idempotency：避免重复渲染

一个曾经的 bug：`done` 事件触发渲染 HITL 卡，同时 `tool_call` 事件也触发，结果同一个 ask_user 渲染了两遍。

修复：用 `data-hitl-id` DOM 属性做幂等：

```javascript
function renderHitlPrompt(toolUse) {
    const existing = document.querySelector(`[data-hitl-id="${toolUse.id}"]`);
    if (existing) return;  // 已经渲染过，跳过
    const card = document.createElement('div');
    card.className = 'hitl-card';
    card.dataset.hitlId = toolUse.id;
    // ...
}
```

## 9.8 静态资源缓存破坏

`ChatController.ASSET_VERSION = System.currentTimeMillis()`，进程启动时间作为版本号。

`chat.html` 引用：

```html
<script src="/static/chat.js?v=[[${assetVersion}]]"></script>
<link rel="stylesheet" href="/static/chat.css?v=[[${assetVersion}]]"/>
```

每次后端重启都换 URL，浏览器强制重新下载。开发时方便，生产可以替换成 git commit hash。

## 9.9 中断 / 取消

前端"停止"按钮：

```javascript
async function stopGeneration() {
    es.close();  // 客户端断开
    await fetch(`/api/chat/${user}/${conv}/interrupt`, {method: 'POST'});  // 通知后端
}
```

后端：

```java
@PostMapping("/api/chat/{userId}/{convId}/interrupt")
public Map<String, Object> interrupt(@PathVariable String userId, @PathVariable String convId) {
    SessionKey key = SessionKey.of(userId + "__" + convId);
    boolean ok = chatService.interrupt(key);
    return Map.of("interrupted", ok);
}
```

`ChatService.interrupt(key)` 调 `CancellationToken.cancel()` → HTTP 请求中断 → `runReactLoop` 抛 `AgentInterruptedException` → `chatReactStream` 用 `complete()`（不是 `error()`）收尾，避免误报错误。

被中断的 ASSISTANT 消息会带"[已被用户中断]"标记落盘，下次加载历史能看到（详见 `ChatService.runReactLoop` 中的 `interruptMarker`）。

## 9.10 自检

- [ ] 我能解释为什么用 SSE 而不是 WebSocket
- [ ] 我能列出 6 种 ReactEvent 对应的 SSE event 名
- [ ] 我能描述前端"打字机效果"是怎么实现的（每个 text event 追加到 DOM）
- [ ] 我能解释 `data-hitl-id` 的幂等作用
- [ ] 我知道用户点"停止"后端做了什么（取消 token + complete sink）
