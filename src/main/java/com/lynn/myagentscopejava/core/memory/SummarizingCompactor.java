package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.GenerateOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 LLM 把旧消息摘要为一条 SYSTEM 消息的压缩策略。
 *
 * <p><b>触发条件</b>：满足以下任一即触发（OR 关系）：
 * <ul>
 *   <li>{@code maxTokens > 0} 且估算 token 数 &gt; {@code maxTokens}（首选指标）</li>
 *   <li>{@code maxMessages > 0} 且消息数 &gt; {@code maxMessages}（兜底，防异常情况）</li>
 * </ul>
 * 至少要设置其中一个。Token 估算由 {@link TokenEstimator} 提供，默认为字符近似。
 *
 * <p><b>压缩策略</b>：
 * <ol>
 *   <li>找一个"安全分割点"：从 {@code keepRecent} 倒数处向前找最近一条 USER 消息，
 *       从该位置开始保留 —— 避免破坏 ASSISTANT(tool_calls) → TOOL 的对齐</li>
 *   <li>把分割点之前的所有消息拼成一段历史脚本</li>
 *   <li>调用 LLM 生成摘要</li>
 *   <li>用一条 SYSTEM 消息"[历史摘要] ..."替换被压缩部分</li>
 * </ol>
 *
 * <p>原始 sysPrompt（由 ReActAgent 在 prepareMessages 注入）<b>不在 memory 内</b>，
 * 不会被本策略动到。
 *
 * <p>构造方式：推荐用 {@link #builder()}；也保留了消息数 trigger 的简化构造器作为快速用法。
 */
public class SummarizingCompactor implements MemoryCompactor {

    private static final String SUMMARY_PROMPT_PREFIX = "[历史摘要] ";

    private final ChatModel summarizer;
    private final int maxTokens;
    private final int maxMessages;
    private final int keepRecent;
    private final TokenEstimator estimator;
    private final String summaryInstruction;

    private SummarizingCompactor(Builder b) {
        if (b.summarizer == null) throw new IllegalArgumentException("summarizer 必填");
        if (b.maxTokens <= 0 && b.maxMessages <= 0) {
            throw new IllegalArgumentException("maxTokens 与 maxMessages 至少要设置一个 > 0");
        }
        if (b.keepRecent < 0) throw new IllegalArgumentException("keepRecent 不能为负数");
        if (b.maxMessages > 0 && b.maxMessages <= b.keepRecent) {
            throw new IllegalArgumentException("maxMessages 必须大于 keepRecent");
        }
        this.summarizer = b.summarizer;
        this.maxTokens = b.maxTokens;
        this.maxMessages = b.maxMessages;
        this.keepRecent = b.keepRecent;
        this.estimator = b.estimator != null ? b.estimator : TokenEstimator.approximate();
        this.summaryInstruction = (b.summaryInstruction == null || b.summaryInstruction.isBlank())
                ? "请把以下对话历史浓缩成一段尽量短的中文摘要，保留所有事实信息、用户偏好与已完成的工具调用结果，"
                + "不要遗漏关键细节，不要添加解释。直接输出摘要正文。"
                : b.summaryInstruction;
    }

    /**
     * 简化构造器：仅按消息数触发。等价于 {@code builder().maxMessages(triggerSize).keepRecent(keepRecent)}。
     *
     * @param summarizer  摘要模型
     * @param triggerSize 触发压缩的消息数阈值
     * @param keepRecent  压缩后至少保留的最近消息数
     */
    public SummarizingCompactor(ChatModel summarizer, int triggerSize, int keepRecent) {
        this(builder().summarizer(summarizer).maxMessages(triggerSize).keepRecent(keepRecent));
    }

    @Override
    public boolean shouldCompact(List<Msg> messages) {
        if (maxMessages > 0 && messages.size() > maxMessages) return true;
        return maxTokens > 0 && estimator.estimate(messages) > maxTokens;
    }

    @Override
    public List<Msg> compact(List<Msg> messages) {
        // 注：不再前置 shouldCompact 检查 —— 调用方（CompactingMemory.compactIfNeeded
        // 或手动触发的 ChatService.compactNow）负责决定是否调用。
        // splitAt 边界仍兜底"消息太少不值得压"的情况（比如只有 1 条 user）。
        int splitAt = findSafeSplit(messages);
        if (splitAt <= 0 || splitAt >= messages.size()) return messages;

        List<Msg> toCompact = messages.subList(0, splitAt);
        List<Msg> toKeep = messages.subList(splitAt, messages.size());

        String summary = callSummarizer(toCompact);

        // 用 USER 角色承载摘要，而不是 SYSTEM ——
        // 1) 保持原 sysPrompt（由 ReActAgent.prepareMessages 注入）的纯净
        // 2) Anthropic / OpenAI / Gemini 都允许连续 USER 消息（Anthropic 会自动合并成一个 turn），
        //    所以无需在摘要后插占位
        // 3) 用 <previous_conversation_summary> 标签包起来，提示模型这是历史摘要而非当前请求
        List<Msg> result = new ArrayList<>(toKeep.size() + 1);
        String wrapped = "<previous_conversation_summary>\n"
                + SUMMARY_PROMPT_PREFIX + summary + "\n</previous_conversation_summary>";
        result.add(Msg.user("summary", wrapped));
        result.addAll(toKeep);
        return result;
    }

    /**
     * 找一个安全分割点：保留至少 {@code keepRecent} 条消息，且第一条保留的消息必须是 USER 角色，
     * 避免 OpenAI 协议里 TOOL 消息成为孤儿。
     */
    private int findSafeSplit(List<Msg> all) {
        int candidate = Math.max(0, all.size() - keepRecent);
        for (int i = candidate; i < all.size(); i++) {
            if (all.get(i).getRole() == MsgRole.USER) return i;
        }
        return all.size();
    }

    private String callSummarizer(List<Msg> toCompact) {
        StringBuilder transcript = new StringBuilder();
        for (Msg m : toCompact) {
            transcript.append('[').append(m.getRole()).append("] ");
            String text = m.getText();
            transcript.append(text.isEmpty() ? "(无文本内容)" : text).append('\n');
        }
        Msg request = Msg.user("system",
                summaryInstruction + "\n\n----- 对话开始 -----\n" + transcript + "----- 对话结束 -----");
        return summarizer.chat(List.of(request), GenerateOptions.defaults()).text();
    }

    public int getMaxTokens() { return maxTokens; }
    public int getMaxMessages() { return maxMessages; }
    public int getKeepRecent() { return keepRecent; }
    public TokenEstimator getEstimator() { return estimator; }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * SummarizingCompactor 的链式构造器。
     */
    public static class Builder {
        private ChatModel summarizer;
        private int maxTokens;
        private int maxMessages;
        private int keepRecent;
        private TokenEstimator estimator;
        private String summaryInstruction;

        public Builder summarizer(ChatModel m) { this.summarizer = m; return this; }

        /** 估算 token 数超过该值即触发压缩；0 表示禁用按 token 触发。 */
        public Builder maxTokens(int n) { this.maxTokens = n; return this; }

        /** 消息数超过该值即触发压缩（兜底）；0 表示禁用按消息数触发。 */
        public Builder maxMessages(int n) { this.maxMessages = n; return this; }

        /** 压缩后至少保留的最近消息数（实际可能更少，受安全分割点影响）。 */
        public Builder keepRecent(int n) { this.keepRecent = n; return this; }

        /** Token 估算器，默认 {@link TokenEstimator#approximate()}。 */
        public Builder estimator(TokenEstimator e) { this.estimator = e; return this; }

        /** 自定义摘要指令；为空使用内置默认指令。 */
        public Builder summaryInstruction(String s) { this.summaryInstruction = s; return this; }

        public SummarizingCompactor build() {
            return new SummarizingCompactor(this);
        }
    }
}
