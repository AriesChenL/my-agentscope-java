package com.lynn.myagentscopejava.core.service;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.hook.Hook;
import com.lynn.myagentscopejava.core.interruption.AgentInterruptedException;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.interruption.InterruptSource;
import com.lynn.myagentscopejava.core.memory.CompactingMemory;
import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.MessageHealing;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatChunk;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatModelRouter;
import com.lynn.myagentscopejava.core.model.ChunkAccumulator;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.session.SessionKey;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 多用户安全的 chat 入口。
 *
 * <p>每一次 {@link #chat(SessionKey, Msg)} 调用都按下面的流程执行，从而做到会话级隔离：
 * <ol>
 *   <li>取得该会话的 per-key 锁（同一会话串行，不同会话并行）</li>
 *   <li>构造一个**全新**的 {@link Memory} 与 {@link ReActAgent}（共享无状态的 model / toolkit / hooks）</li>
 *   <li>从 {@link Session} 加载该会话的历史</li>
 *   <li>登记到 active map（让 {@link #interrupt(SessionKey)} 能找到正在跑的 agent）</li>
 *   <li>调用 {@link ReActAgent#call(Msg)}</li>
 *   <li>把更新后的 memory 保存回 Session</li>
 *   <li>释放锁、清理 active map</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code ReActAgent} 不再是单例，而是请求级临时对象，从根本上杜绝跨用户串记忆的可能</li>
 *   <li>{@code ChatModel} / {@code Toolkit} / {@code Hook} / {@code GenerateOptions} 都是无状态的，可以共享</li>
 *   <li>per-key 锁防止同一用户开多个 tab 并发请求时丢消息</li>
 *   <li>持久化层（{@link Session}）必须线程安全 —— FileSystemSession 各 key 写不同文件天然安全</li>
 * </ul>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatModelRouter modelRouter;
    private final Toolkit toolkit;
    private final GenerateOptions generateOptions;
    private final List<Hook> hooks;
    private final Session session;
    private final SessionLockManager lockManager;
    private final String agentName;
    private final String sysPrompt;
    private final int maxIters;
    private final Supplier<Memory> memoryFactory;
    /** 手动压缩用的 compactor；可为 null（应用未配置压缩时手动接口会返回错误）。 */
    private final com.lynn.myagentscopejava.core.memory.MemoryCompactor compactor;

    /** 当前正在执行的同步 agent 表，供 {@link #interrupt(SessionKey)} 查找。 */
    private final ConcurrentMap<SessionKey, ReActAgent> activeCalls = new ConcurrentHashMap<>();
    /** 当前正在执行的流式调用 token 表（chatReactStream 用）。 */
    private final ConcurrentMap<SessionKey, CancellationToken> activeStreams = new ConcurrentHashMap<>();

    private ChatService(Builder b) {
        if (b.modelRouter == null) throw new IllegalArgumentException("modelRouter 必填");
        if (b.session == null) throw new IllegalArgumentException("session 必填");
        this.modelRouter = b.modelRouter;
        this.toolkit = b.toolkit != null ? b.toolkit : new Toolkit();
        this.generateOptions = b.generateOptions != null ? b.generateOptions : GenerateOptions.defaults();
        this.hooks = b.hooks != null ? List.copyOf(b.hooks) : List.of();
        this.session = b.session;
        this.lockManager = b.lockManager != null ? b.lockManager : new SessionLockManager();
        this.agentName = b.agentName != null ? b.agentName : "Assistant";
        this.sysPrompt = b.sysPrompt;
        this.maxIters = b.maxIters > 0 ? b.maxIters : 10;
        this.memoryFactory = b.memoryFactory != null ? b.memoryFactory : InMemoryMemory::new;
        this.compactor = b.compactor;
    }

    /**
     * 处理一次会话级 chat 请求。
     *
     * @param key       会话键（多租户隔离的依据）
     * @param userInput 用户输入；HITL 恢复场景下可以是含 ToolResultBlock 的 TOOL 消息
     * @return agent 最终的 assistant 消息
     */
    public Msg chat(SessionKey key, Msg userInput) {
        return chatDetailed(key, userInput).reply();
    }

    /**
     * 同 {@link #chat(SessionKey, Msg)}，但额外返回这一轮新增到 memory 中的所有消息
     *（含中间的 ASSISTANT(tool_calls) / TOOL 结果），便于前端渲染完整 ReAct 步骤。
     *
     * @param key       会话键
     * @param userInput 用户输入
     * @return 包含最终回复 + 本轮新增消息列表的 {@link ChatTurn}
     */
    public ChatTurn chatDetailed(SessionKey key, Msg userInput) {
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            ReActAgent agent = newAgentForSession(key);
            int sizeBefore = agent.getMemory().getMessages().size();
            activeCalls.put(key, agent);
            try {
                Msg reply = agent.call(userInput);
                agent.saveTo(session, key);
                List<Msg> all = agent.getMemory().getMessages();
                List<Msg> added = sizeBefore < all.size()
                        ? List.copyOf(all.subList(sizeBefore, all.size()))
                        : List.of();
                return new ChatTurn(reply, added);
            } finally {
                activeCalls.remove(key, agent);
            }
        } finally {
            lock.unlock();
        }
    }

    /** chat 一轮的完整结果。 */
    public record ChatTurn(Msg reply, List<Msg> addedMessages) {}

    /**
     * 查询某会话当前是否处于 HITL 挂起状态（等待外部提供工具结果）。
     *
     * <p>会从 session 加载一次状态来判断；不影响内存中的 agent。
     *
     * @param key 会话键
     * @return 处于挂起状态时返回 true
     */
    public boolean isAwaitingHumanInput(SessionKey key) {
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            ReActAgent agent = newAgentForSession(key);
            return agent.isAwaitingHumanInput();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取某会话当前的全部消息（用于前端展示历史）。
     *
     * @param key 会话键
     * @return 消息列表（按时间顺序）；会话不存在返回空
     */
    public List<Msg> getMessages(SessionKey key) {
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            ReActAgent agent = newAgentForSession(key);
            return List.copyOf(agent.getMemory().getMessages());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 手动压缩某会话历史：调用 compactor 摘要旧消息，并把结果落盘。
     * <p>与 {@link CompactingMemory} 自动触发不同，此方法不检查 {@code shouldCompact} 阈值，
     * 由调用方（用户点按钮）显式触发。
     *
     * @param key 会话键
     * @return 压缩前后的消息数变化
     * @throws IllegalStateException 应用未配置 compactor 时抛出
     */
    public CompactionResult compactNow(SessionKey key) {
        if (compactor == null) {
            throw new IllegalStateException("应用未配置 compactor：请设置 agentscope.memory.compaction-* 相关属性");
        }
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            Memory memory = memoryFactory.get();
            if (session.exists(key)) memory.loadFrom(session, key);
            List<Msg> before = memory.getMessages();
            int beforeSize = before.size();
            List<Msg> after = compactor.compact(before);
            if (after == before) {
                log.info("compactNow: 历史 {} 条，无可压缩内容", beforeSize);
                return new CompactionResult(beforeSize, beforeSize);
            }
            memory.clear();
            after.forEach(memory::addMessage);
            memory.saveTo(session, key);
            log.info("compactNow: 历史 {} → {} 条（session={}）", beforeSize, after.size(), key.value());
            return new CompactionResult(beforeSize, after.size());
        } finally {
            lock.unlock();
        }
    }

    /** {@link #compactNow(SessionKey)} 的返回结果。 */
    public record CompactionResult(int before, int after) {}

    /**
     * "批准并执行"挂起的工具调用：后端真正执行该工具，把真实结果回填后让 agent 续推。
     *
     * <p>典型用例：{@link com.lynn.myagentscopejava.core.hook.ToolConfirmationHook}
     * 拦截了某个危险工具，前端展示"批准"按钮，用户点击后调本接口让工具真正执行。
     *
     * <p>语义：
     * <ul>
     *   <li>{@code toolCallId} 必须命中当前 pending 集合的某一项，否则抛 {@link IllegalStateException}</li>
     *   <li>未挂起 / 未找到对应 {@link ToolUseBlock} 都会抛 {@link IllegalStateException}</li>
     *   <li>工具真正执行后构造一条含 {@link ToolResultBlock#success} 的 TOOL 消息，
     *       走 {@link #chatDetailed(SessionKey, Msg)} 让 agent 续推</li>
     * </ul>
     *
     * @param key        会话键
     * @param toolCallId 待批准的工具调用 id
     * @return 续推后的 ChatTurn
     */
    public ChatTurn approvePendingTool(SessionKey key, String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 必填");
        }
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            ReActAgent agent = newAgentForSession(key);
            if (!agent.isAwaitingHumanInput()) {
                throw new IllegalStateException("会话当前不处于挂起状态");
            }
            ToolUseBlock target = agent.getPendingToolUses().stream()
                    .filter(t -> toolCallId.equals(t.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "toolCallId 不在当前 pending 集合中：" + toolCallId));
            ToolResultBlock realResult = toolkit.invoke(target);
            // 注意：批准并不意味着工具不能再次抛 ToolSuspendException —— 若工具自己仍要挂起，
            // 此处的 result.pending() 会再次为 true，agent.call 会再次进入挂起状态。
            Msg resumeMsg = Msg.builder()
                    .role(MsgRole.TOOL)
                    .content(realResult)
                    .build();
            return chatDetailedNoLock(key, resumeMsg, agent);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 同 {@link #chatDetailed}，但要求调用方已经持有 lock 并准备好 agent。
     * 不再获取 lock，避免 {@link ReentrantLock} 重入计数膨胀（虽然技术上允许重入）。
     */
    private ChatTurn chatDetailedNoLock(SessionKey key, Msg userInput, ReActAgent agent) {
        int sizeBefore = agent.getMemory().getMessages().size();
        activeCalls.put(key, agent);
        try {
            Msg reply = agent.call(userInput);
            agent.saveTo(session, key);
            List<Msg> all = agent.getMemory().getMessages();
            List<Msg> added = sizeBefore < all.size()
                    ? List.copyOf(all.subList(sizeBefore, all.size()))
                    : List.of();
            return new ChatTurn(reply, added);
        } finally {
            activeCalls.remove(key, agent);
        }
    }

    /**
     * 列出某会话当前所有等待外部执行的工具调用。
     *
     * @param key 会话键
     * @return pending 的 ToolUseBlock 列表；非挂起状态返回空
     */
    public List<ToolUseBlock> getPendingToolUses(SessionKey key) {
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            ReActAgent agent = newAgentForSession(key);
            return agent.getPendingToolUses();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 中断指定会话当前正在执行的 chat 请求。
     *
     * <p>仅当该会话的 chat 调用还在飞时生效；调用方会收到
     * {@link com.lynn.myagentscopejava.core.interruption.AgentInterruptedException}。
     *
     * @param key 会话键
     * @return 是否真的找到并打断了一个正在跑的请求
     */
    public boolean interrupt(SessionKey key) {
        return interrupt(key, InterruptSource.USER);
    }

    /**
     * 指定来源中断。
     *
     * @param key    会话键
     * @param source 中断来源；不同来源会影响中断标记文案与 partial 处理策略
     * @return 是否真的中断了一个正在跑的请求
     */
    public boolean interrupt(SessionKey key, InterruptSource source) {
        boolean interrupted = false;
        ReActAgent agent = activeCalls.get(key);
        if (agent != null) {
            agent.interrupt(source);
            interrupted = true;
        }
        CancellationToken token = activeStreams.get(key);
        if (token != null) {
            token.cancel(source);
            interrupted = true;
        }
        return interrupted;
    }

    /**
     * 完整的"流式 ReAct"接口：按 ReAct 循环驱动模型，并把每个阶段（思考链 / 文本增量 /
     * 工具调用 / 工具结果 / 结束）都作为 {@link ReactEvent} 推到下游。
     *
     * <p>这是给前端最自然的接口：用户既能看到 token 实时打字机效果，又能在中途看到
     * 工具调用的金色卡片和工具结果的绿色卡片。
     *
     * <p>实现重新走了一遍 ReAct 循环，没有复用 {@link ReActAgent#call(Msg)}，因为
     * 后者用的是阻塞的 {@code model.chat()}。这里直接订阅 {@code model.stream()}
     * 把 chunk 转译为事件下发。
     *
     * @param key       会话键
     * @param userInput 用户输入
     * @return 结构化 ReAct 事件流
     */
    public Flux<ReactEvent> chatReactStream(SessionKey key, Msg userInput) {
        ReentrantLock lock = lockManager.lockFor(key);
        CancellationToken token = new CancellationToken();
        return Flux.<ReactEvent>create(sink -> {
                    lock.lock();
                    activeStreams.put(key, token);
                    Memory memory = memoryFactory.get();
                    try {
                        if (session.exists(key)) memory.loadFrom(session, key);
                        // 修复历史中的孤儿 tool_calls（上一轮中断/崩溃留下的），否则模型 API 会返回 400
                        MessageHealing.healOrphanToolCalls(memory, agentName);
                        if (userInput != null) memory.addMessage(userInput);
                        runReactLoop(key, sink, memory, token);
                        memory.saveTo(session, key);
                        sink.complete();
                    } catch (AgentInterruptedException ie) {
                        // 中断不是错误，是用户主动行为：partial 内容已经在 runReactLoop 里落盘了，
                        // 这里只需把 memory save 一下、把流以 complete 结束（而非 error），
                        // 避免 AgentInterruptedException 顺着同步链路冒回到 controller 触发 ERROR 日志
                        try { memory.saveTo(session, key); } catch (Exception ignored) { /* best effort */ }
                        log.info("chat 被中断（来源 {}），按正常 complete 结束流", ie.getSource());
                        sink.complete();
                    } catch (Exception e) {
                        sink.error(e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> {
                    activeStreams.remove(key, token);
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                });
    }

    /** 同步执行 ReAct 循环，每一步都把事件推到 {@code sink}。仅在 chatReactStream 内调用。 */
    private void runReactLoop(SessionKey key, reactor.core.publisher.FluxSink<ReactEvent> sink,
                              Memory memory, CancellationToken token) {
        ChatModel chatModel = modelRouter.resolve(key);
        ChatUsageHolder lastUsage = new ChatUsageHolder();
        for (int iter = 0; iter < maxIters; iter++) {
            token.throwIfCancelled();
            sink.next(new ReactEvent.IterationStart(iter));

            List<Msg> messages = new ArrayList<>();
            if (sysPrompt != null && !sysPrompt.isBlank()) messages.add(Msg.system(sysPrompt));
            messages.addAll(memory.getMessages());

            ChunkAccumulator acc = new ChunkAccumulator();
            try {
                // 把 token 传给 chatModel.stream() —— cancel 时会自动断 HTTP；sessionId 让 Gemini 能维护 per-session cache
                chatModel.stream(messages, toolkit.getSchemas(), generateOptions, token, key.value())
                        .doOnNext(chunk -> {
                            if (chunk.thinkingDelta() != null && !chunk.thinkingDelta().isEmpty()) {
                                sink.next(new ReactEvent.Thinking(chunk.thinkingDelta()));
                            }
                            if (chunk.textDelta() != null && !chunk.textDelta().isEmpty()) {
                                sink.next(new ReactEvent.Text(chunk.textDelta()));
                            }
                            acc.accept(chunk);
                        })
                        .blockLast();
            } catch (RuntimeException ex) {
                // 被中断：把已部分流式输出的内容也持久化（带按来源区分的标记，方便追溯）
                if (token.isCancelled() || ex instanceof AgentInterruptedException) {
                    InterruptSource src = resolveSource(token, ex);
                    List<ContentBlock> blocks = new ArrayList<>(acc.buildBlocks());
                    if (!blocks.isEmpty()) {
                        blocks.add(new com.lynn.myagentscopejava.core.message.TextBlock(
                                "\n\n" + interruptMarker(src)));
                        memory.addMessage(Msg.builder()
                                .name(agentName).role(MsgRole.ASSISTANT).content(blocks).build());
                        // 这条 assistant 可能带半截 tool_calls（流式中断时常见）；立刻补合成结果，
                        // 避免下一轮加载历史时再次 400
                        MessageHealing.healOrphanToolCalls(memory, agentName);
                    }
                    throw ex instanceof AgentInterruptedException aie ? aie
                            : new AgentInterruptedException("chat 被中断", src, ex);
                }
                throw ex;
            }

            if (acc.usage() != null) lastUsage.value = acc.usage();

            // 把累积的 chunk 拼成完整 assistant 消息写回 memory
            List<ContentBlock> blocks = acc.buildBlocks();
            Msg assistantMsg = Msg.builder()
                    .name(agentName).role(MsgRole.ASSISTANT).content(blocks).build();
            memory.addMessage(assistantMsg);

            // 没有工具调用 → 收尾
            List<ToolUseBlock> toolUses = assistantMsg.getBlocks(ToolUseBlock.class);
            if (toolUses.isEmpty()) {
                sink.next(new ReactEvent.Done(lastUsage.value));
                return;
            }

            // 工具调用：每个工具发 ToolCall 事件，invoke 后发 ToolResult
            // 注意：这里也要 fire PreActingEvent，让 ToolConfirmationHook 等 HITL 钩子能在流式路径生效。
            List<ContentBlock> resultBlocks = new ArrayList<>();
            boolean anyPending = false;
            for (ToolUseBlock use : toolUses) {
                sink.next(new ReactEvent.ToolCall(use));
                com.lynn.myagentscopejava.core.hook.PreActingEvent preAct =
                        new com.lynn.myagentscopejava.core.hook.PreActingEvent(null, use);
                fireSafely(preAct);
                ToolUseBlock effective = preAct.getToolUse();
                ToolResultBlock result;
                if (preAct.isSuspended()) {
                    String reason = preAct.getSuspendReason() != null
                            ? preAct.getSuspendReason() : "等待人工审批";
                    result = ToolResultBlock.pending(effective.id(), effective.name(), reason);
                } else {
                    result = toolkit.invoke(effective);
                }
                sink.next(new ReactEvent.ToolResult(result));
                resultBlocks.add(result);
                if (result.pending()) anyPending = true;
            }
            Msg toolMsg = Msg.builder()
                    .name(agentName).role(MsgRole.TOOL).content(resultBlocks).build();
            memory.addMessage(toolMsg);
            // HITL：本轮有任何工具进入 pending → 立即收尾，等外部回填后再恢复
            if (anyPending) {
                sink.next(new ReactEvent.Done(lastUsage.value));
                return;
            }
        }
        // 达到 maxIters
        sink.next(new ReactEvent.Done(lastUsage.value));
    }

    /** 在流式路径下安全 fire 单个 hook 事件，单个 hook 异常不影响其它 hook。 */
    private void fireSafely(com.lynn.myagentscopejava.core.hook.HookEvent event) {
        for (Hook h : hooks) {
            try {
                h.onEvent(event);
            } catch (Exception e) {
                log.warn("Hook {} 抛异常：{}", h.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /** 简单可变持有器，避免 lambda 捕获变量限制。 */
    private static final class ChatUsageHolder {
        com.lynn.myagentscopejava.core.model.ChatUsage value;
    }

    /** 解析中断的真实来源：优先看异常自带的 source，再看 token 上的 source，兜底 USER。 */
    private static InterruptSource resolveSource(CancellationToken token, RuntimeException ex) {
        if (ex instanceof AgentInterruptedException aie && aie.getSource() != null) {
            return aie.getSource();
        }
        InterruptSource fromToken = token.getSource();
        return fromToken != null ? fromToken : InterruptSource.USER;
    }

    /** 不同来源对应不同的中断文案。 */
    private static String interruptMarker(InterruptSource src) {
        return switch (src) {
            case USER -> "[已被用户中断]";
            case TOOL -> "[已被工具中断]";
            case SYSTEM -> "[已被系统中断]";
        };
    }

    /**
     * 删除某会话的所有持久化状态，下次 {@link #chat} 会以全新会话开始。
     *
     * @param key 会话键
     */
    public void deleteSession(SessionKey key) {
        ReentrantLock lock = lockManager.lockFor(key);
        lock.lock();
        try {
            session.delete(key);
        } finally {
            lock.unlock();
        }
    }

    /** 构造一个加载好该会话历史的全新 ReActAgent；ChatModel 按会话路由。仅在锁内调用。 */
    private ReActAgent newAgentForSession(SessionKey key) {
        Memory memory = memoryFactory.get();
        ReActAgent.Builder b = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(modelRouter.resolve(key))
                .toolkit(toolkit)
                .memory(memory)
                .generateOptions(generateOptions)
                .maxIters(maxIters);
        if (!hooks.isEmpty()) b.hooks(hooks);
        ReActAgent agent = b.build();
        agent.loadFrom(session, key);
        // 修复历史中的孤儿 tool_calls（上一轮被中断/崩溃留下的）
        MessageHealing.healOrphanToolCalls(agent.getMemory(), agentName);
        return agent;
    }

    public Session getSession() { return session; }
    public SessionLockManager getLockManager() { return lockManager; }

    public static Builder builder() { return new Builder(); }

    /**
     * ChatService 的链式构造器。
     */
    public static class Builder {
        private ChatModelRouter modelRouter;
        private Toolkit toolkit;
        private GenerateOptions generateOptions;
        private List<Hook> hooks;
        private Session session;
        private SessionLockManager lockManager;
        private String agentName;
        private String sysPrompt;
        private int maxIters;
        private Supplier<Memory> memoryFactory;
        private com.lynn.myagentscopejava.core.memory.MemoryCompactor compactor;

        public Builder modelRouter(ChatModelRouter r) { this.modelRouter = r; return this; }
        public Builder toolkit(Toolkit t) { this.toolkit = t; return this; }
        public Builder generateOptions(GenerateOptions o) { this.generateOptions = o; return this; }
        public Builder hooks(List<Hook> h) { this.hooks = h; return this; }
        public Builder session(Session s) { this.session = s; return this; }
        public Builder lockManager(SessionLockManager lm) { this.lockManager = lm; return this; }
        public Builder agentName(String n) { this.agentName = n; return this; }
        public Builder sysPrompt(String p) { this.sysPrompt = p; return this; }
        public Builder maxIters(int n) { this.maxIters = n; return this; }

        /**
         * 设置每个会话使用的 Memory 工厂；默认 {@code InMemoryMemory::new}。
         * 启用自动压缩时传入 {@code () -> new CompactingMemory(new InMemoryMemory(), compactor)}。
         */
        public Builder memoryFactory(Supplier<Memory> f) { this.memoryFactory = f; return this; }

        /** 手动压缩用的 compactor；可不设，但设了之后才能调用 {@link ChatService#compactNow}。 */
        public Builder compactor(com.lynn.myagentscopejava.core.memory.MemoryCompactor c) {
            this.compactor = c; return this;
        }

        public ChatService build() { return new ChatService(this); }
    }
}
