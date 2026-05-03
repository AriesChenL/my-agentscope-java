package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.model.ChatUsage;

/**
 * 在每次模型回复后触发。Hook 可整体替换 {@code message}（例如脱敏、改写输出），
 * 也可调用 {@link #requestStop()} 让 agent 立即返回不再继续循环。
 */
public class PostReasoningEvent extends HookEvent {

    private Msg message;
    private final ChatUsage usage;
    private final int iteration;

    public PostReasoningEvent(ReActAgent agent, Msg message, ChatUsage usage, int iteration) {
        super(agent);
        this.message = message;
        this.usage = usage;
        this.iteration = iteration;
    }

    public Msg getMessage() { return message; }

    public void setMessage(Msg message) { this.message = message; }

    /**
     * @return 本轮调用的 token 用量（含 cache 命中数）
     */
    public ChatUsage getUsage() { return usage; }

    public int getIteration() { return iteration; }
}
