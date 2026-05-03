package com.lynn.myagentscopejava.core.agent;

import com.lynn.myagentscopejava.core.hook.Hook;
import com.lynn.myagentscopejava.core.hook.HookEvent;
import com.lynn.myagentscopejava.core.hook.PostActingEvent;
import com.lynn.myagentscopejava.core.hook.PostReasoningEvent;
import com.lynn.myagentscopejava.core.hook.PreActingEvent;
import com.lynn.myagentscopejava.core.hook.PreCallEvent;
import com.lynn.myagentscopejava.core.hook.PreReasoningEvent;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.interruption.InterruptSource;
import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatResponse;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.session.SessionKey;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * ReAct（Reasoning + Acting）模式的 agent。
 *
 * <p>主循环：
 * <ol>
 *   <li><b>Reasoning</b> —— 触发 {@link PreReasoningEvent}，调用模型，触发 {@link PostReasoningEvent}</li>
 *   <li>若 assistant 消息中没有 {@link ToolUseBlock} → 完成，返回结果</li>
 *   <li><b>Acting</b> —— 对每个工具调用：触发 {@link PreActingEvent}，调用工具，
 *       触发 {@link PostActingEvent}；收集所有结果，组装一条 TOOL 消息加入 memory，回到第 1 步</li>
 * </ol>
 * 循环退出条件：模型不再请求工具；达到 {@code maxIters}；任意 hook 调用 {@link HookEvent#requestStop()}。
 *
 * <p>支持通过 {@link #interrupt()} 协作式取消正在执行的调用。
 */
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final String name;
    private final String sysPrompt;
    private final ChatModel model;
    private final Memory memory;
    private final Toolkit toolkit;
    private final GenerateOptions generateOptions;
    private final int maxIters;
    private final List<Hook> hooks;

    /** 当前正在进行的调用对应的取消令牌；调用结束后会被清空。 */
    private final AtomicReference<CancellationToken> currentToken = new AtomicReference<>();

    private ReActAgent(Builder b) {
        if (b.name == null || b.name.isBlank()) throw new IllegalArgumentException("name 必填");
        if (b.model == null) throw new IllegalArgumentException("model 必填");
        this.name = b.name;
        this.sysPrompt = b.sysPrompt;
        this.model = b.model;
        this.memory = b.memory != null ? b.memory : new InMemoryMemory();
        this.toolkit = b.toolkit != null ? b.toolkit : new Toolkit();
        this.generateOptions = b.generateOptions != null ? b.generateOptions : GenerateOptions.defaults();
        this.maxIters = b.maxIters > 0 ? b.maxIters : 10;
        this.hooks = b.hooks.stream()
                .sorted(Comparator.comparingInt(Hook::priority))
                .toList();
    }

    /**
     * 处理一条用户输入，运行 ReAct 循环直至完成。
     *
     * <p>HITL 恢复：若 agent 处于 {@link #isAwaitingHumanInput()} 状态，且
     * {@code userInput} 是一条 {@link MsgRole#TOOL} 消息且其
     * {@link ToolResultBlock} 的 id 集合恰好覆盖所有 pending id —— 则把 memory 中
     * pending 的 TOOL 消息替换为用户提供的真实结果，自动恢复 ReAct 循环。
     *
     * @param userInput 用户输入消息；可为 {@code null}
     * @return agent 最终的 assistant 消息
     * @throws IllegalStateException 当 agent 处于挂起状态但 userInput 不是合法的 TOOL 结果消息
     */
    public Msg call(Msg userInput) {
        CancellationToken token = new CancellationToken();
        currentToken.set(token);
        try {
            // 给 hook 一次"在 reasoning 之前修复 / 预处理 memory"的机会
            // 内置的 PendingToolRecoveryHook 会在这里把孤儿 tool_calls 补合成结果
            PreCallEvent pre = new PreCallEvent(this, memory,
                    userInput != null ? List.of(userInput) : List.of());
            fire(pre);

            if (isAwaitingHumanInput()) {
                resumeWithHumanInput(userInput);
            } else if (userInput != null) {
                memory.addMessage(userInput);
            }
            return reactLoop(token);
        } finally {
            // 仅当本次调用的 token 还在槽位上时才清空，避免覆盖并发调用
            currentToken.compareAndSet(token, null);
        }
    }

    /**
     * 判断 agent 是否处于"等待外部 / 人工提供工具结果"的挂起状态。
     *
     * @return memory 末尾消息为 TOOL 角色且至少含一个 pending {@link ToolResultBlock} 时返回 true
     */
    public boolean isAwaitingHumanInput() {
        List<Msg> msgs = memory.getMessages();
        if (msgs.isEmpty()) return false;
        Msg last = msgs.getLast();
        if (last.getRole() != MsgRole.TOOL) return false;
        return last.getBlocks(ToolResultBlock.class).stream().anyMatch(ToolResultBlock::pending);
    }

    /**
     * 列出当前所有等待外部执行的工具调用。
     *
     * @return pending 的 ToolUseBlock 列表；非挂起状态返回空
     */
    public List<ToolUseBlock> getPendingToolUses() {
        if (!isAwaitingHumanInput()) return List.of();
        List<Msg> msgs = memory.getMessages();
        Msg toolMsg = msgs.getLast();
        Set<String> pendingIds = toolMsg.getBlocks(ToolResultBlock.class).stream()
                .filter(ToolResultBlock::pending)
                .map(ToolResultBlock::id)
                .collect(Collectors.toSet());
        // 上一条 assistant 消息中匹配 id 的 ToolUseBlock
        for (int i = msgs.size() - 2; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.getRole() == MsgRole.ASSISTANT) {
                return m.getBlocks(ToolUseBlock.class).stream()
                        .filter(u -> pendingIds.contains(u.id()))
                        .toList();
            }
        }
        return List.of();
    }

    /**
     * 用人工提供的工具结果替换 memory 中的 pending TOOL 消息，使 agent 能从 reasoning 阶段恢复执行。
     */
    private void resumeWithHumanInput(Msg userInput) {
        if (userInput == null || userInput.getRole() != MsgRole.TOOL) {
            throw new IllegalStateException(
                    "agent 当前处于挂起状态，需要 TOOL 角色的消息提供工具结果，实际收到："
                            + (userInput == null ? "null" : userInput.getRole()));
        }
        List<ToolResultBlock> provided = userInput.getBlocks(ToolResultBlock.class);
        if (provided.isEmpty()) {
            throw new IllegalStateException("TOOL 消息中未包含任何 ToolResultBlock");
        }

        List<Msg> msgs = new ArrayList<>(memory.getMessages());
        Msg pendingToolMsg = msgs.getLast();
        List<ContentBlock> oldBlocks = pendingToolMsg.getContent();

        Set<String> pendingIds = oldBlocks.stream()
                .filter(b -> b instanceof ToolResultBlock r && r.pending())
                .map(b -> ((ToolResultBlock) b).id())
                .collect(Collectors.toSet());
        Set<String> providedIds = provided.stream()
                .map(ToolResultBlock::id)
                .collect(Collectors.toSet());
        if (!providedIds.containsAll(pendingIds)) {
            Set<String> missing = new HashSet<>(pendingIds);
            missing.removeAll(providedIds);
            throw new IllegalStateException("缺少 pending 工具的结果，未提供的 id：" + missing);
        }

        // 用 provided 中匹配 id 的真实结果替换 pending 项；非 pending 项保持原样
        Map<String, ToolResultBlock> replacement = provided.stream()
                .collect(Collectors.toMap(ToolResultBlock::id, b -> b, (a, b) -> a));
        List<ContentBlock> newBlocks = new ArrayList<>(oldBlocks.size());
        for (ContentBlock b : oldBlocks) {
            if (b instanceof ToolResultBlock r && r.pending() && replacement.containsKey(r.id())) {
                newBlocks.add(replacement.get(r.id()));
            } else {
                newBlocks.add(b);
            }
        }
        Msg replaced = Msg.builder()
                .id(pendingToolMsg.getId())
                .name(pendingToolMsg.getName())
                .role(MsgRole.TOOL)
                .content(newBlocks)
                .build();

        // memory 没有提供"替换最后一条"的接口，统一通过 clear + 全量回填实现
        memory.clear();
        msgs.set(msgs.size() - 1, replaced);
        msgs.forEach(memory::addMessage);
        log.info("[{}] HITL 恢复：已替换 {} 个 pending 工具结果", name, pendingIds.size());
    }

    /** 真正的 ReAct 循环主体。 */
    private Msg reactLoop(CancellationToken token) {
        Msg lastAssistantMsg = null;
        for (int iter = 0; iter < maxIters; iter++) {
            token.throwIfCancelled();

            // ---- Reasoning ----
            PreReasoningEvent pre = new PreReasoningEvent(this, prepareMessages(), iter);
            fire(pre);

            ChatResponse resp = model.chat(pre.getMessages(), toolkit.getSchemas(), generateOptions, token);
            if (resp.usage() != null) {
                log.info("[{}] iter {} {}", name, iter, resp.usage());
            }
            Msg assistantMsg = renameTo(resp.message(), name);

            PostReasoningEvent post = new PostReasoningEvent(this, assistantMsg, resp.usage(), iter);
            fire(post);
            assistantMsg = post.getMessage();
            memory.addMessage(assistantMsg);
            lastAssistantMsg = assistantMsg;
            if (post.isStopRequested()) {
                log.info("[{}] hook 请求停止 reasoning iter {}", name, iter);
                return assistantMsg;
            }

            List<ToolUseBlock> toolUses = assistantMsg.getBlocks(ToolUseBlock.class);
            if (toolUses.isEmpty()) return assistantMsg;

            // ---- Acting ----
            List<ContentBlock> resultBlocks = new ArrayList<>();
            boolean stopAfterActing = false;
            for (ToolUseBlock use : toolUses) {
                token.throwIfCancelled();

                PreActingEvent preAct = new PreActingEvent(this, use);
                fire(preAct);
                ToolUseBlock effectiveUse = preAct.getToolUse();

                ToolResultBlock result;
                if (preAct.isSuspended()) {
                    // hook 短路了实际工具调用（典型用例：ToolConfirmationHook 拦截危险工具）
                    String reason = preAct.getSuspendReason() != null
                            ? preAct.getSuspendReason()
                            : "等待人工审批";
                    log.info("[{}] hook 挂起工具 '{}'：{}", name, effectiveUse.name(), reason);
                    result = ToolResultBlock.pending(effectiveUse.id(), effectiveUse.name(), reason);
                } else {
                    result = toolkit.invoke(effectiveUse);
                }

                PostActingEvent postAct = new PostActingEvent(this, effectiveUse, result);
                fire(postAct);
                resultBlocks.add(postAct.getResult());
                if (postAct.isStopRequested()) stopAfterActing = true;
            }
            Msg toolMsg = Msg.builder()
                    .name(name).role(MsgRole.TOOL)
                    .content(resultBlocks).build();
            memory.addMessage(toolMsg);

            if (stopAfterActing) {
                log.info("[{}] hook 请求停止 acting iter {}", name, iter);
                return assistantMsg;
            }
            // HITL：本轮有任何工具进入 pending → 立即返回，等外部提供结果后再恢复
            boolean anyPending = resultBlocks.stream()
                    .anyMatch(b -> b instanceof ToolResultBlock r && r.pending());
            if (anyPending) {
                log.info("[{}] iter {} 发现 pending 工具，挂起等待外部提供结果", name, iter);
                return assistantMsg;
            }
        }
        // 达到最大迭代数，写入 fallback 消息
        log.warn("[{}] 达到 maxIters={}，返回 fallback 消息", name, maxIters);
        Msg fallback = Msg.builder()
                .name(name).role(MsgRole.ASSISTANT)
                .content(new TextBlock("[max iterations " + maxIters + " reached]"))
                .build();
        memory.addMessage(fallback);
        return lastAssistantMsg != null ? lastAssistantMsg : fallback;
    }

    /** 按优先级顺序触发所有 hook，单个 hook 异常不中断循环。 */
    private void fire(HookEvent event) {
        for (Hook h : hooks) {
            try {
                h.onEvent(event);
            } catch (Exception e) {
                log.warn("Hook {} 抛异常：{}", h.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /**
     * 中断当前正在进行的 {@link #call(Msg)}（默认来源 {@link InterruptSource#USER}）。
     *
     * <p>会取消正在飞的 HTTP 请求，正在执行的 call 将抛
     * {@link com.lynn.myagentscopejava.core.interruption.AgentInterruptedException}。
     * 当前没有调用在跑时为 no-op。
     */
    public void interrupt() {
        interrupt(InterruptSource.USER);
    }

    /**
     * 指定来源的中断。
     *
     * @param source 中断来源；{@code null} 视为 USER
     */
    public void interrupt(InterruptSource source) {
        CancellationToken token = currentToken.get();
        if (token != null) token.cancel(source);
    }

    /**
     * @return 当前是否有调用在执行
     */
    public boolean isRunning() {
        return currentToken.get() != null;
    }

    /**
     * 把 agent 的有状态组件（当前仅 memory）持久化到 session。
     *
     * @param session 持久化后端
     * @param key     会话键
     */
    public void saveTo(Session session, SessionKey key) {
        memory.saveTo(session, key);
    }

    /**
     * 从 session 恢复 agent 状态。
     *
     * @param session 持久化后端
     * @param key     会话键
     * @return 是否真的恢复了数据；session 中无数据时返回 false
     */
    public boolean loadFrom(Session session, SessionKey key) {
        if (!session.exists(key)) return false;
        memory.loadFrom(session, key);
        return true;
    }

    /** 拼装发给模型的消息列表：sysPrompt（如有）+ memory 中的所有历史。 */
    private List<Msg> prepareMessages() {
        List<Msg> list = new ArrayList<>();
        if (sysPrompt != null && !sysPrompt.isBlank()) list.add(Msg.system(sysPrompt));
        list.addAll(memory.getMessages());
        return list;
    }

    /** 给模型回复重新设置 name，保留 id / role / content。 */
    private static Msg renameTo(Msg src, String name) {
        return Msg.builder()
                .id(src.getId()).name(name).role(src.getRole()).content(src.getContent()).build();
    }

    public String getName() { return name; }
    public Memory getMemory() { return memory; }
    public ChatModel getModel() { return model; }
    public Toolkit getToolkit() { return toolkit; }
    public List<Hook> getHooks() { return hooks; }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * ReActAgent 的链式构造器。
     */
    public static class Builder {
        private String name;
        private String sysPrompt;
        private ChatModel model;
        private Memory memory;
        private Toolkit toolkit;
        private GenerateOptions generateOptions;
        private int maxIters = 10;
        private final List<Hook> hooks = new ArrayList<>();

        public Builder name(String name) { this.name = name; return this; }
        public Builder sysPrompt(String sysPrompt) { this.sysPrompt = sysPrompt; return this; }
        public Builder model(ChatModel model) { this.model = model; return this; }
        public Builder memory(Memory memory) { this.memory = memory; return this; }
        public Builder toolkit(Toolkit toolkit) { this.toolkit = toolkit; return this; }
        public Builder generateOptions(GenerateOptions opts) { this.generateOptions = opts; return this; }
        public Builder maxIters(int maxIters) { this.maxIters = maxIters; return this; }
        public Builder hook(Hook hook) { this.hooks.add(hook); return this; }
        public Builder hooks(List<Hook> hs) { this.hooks.addAll(hs); return this; }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
