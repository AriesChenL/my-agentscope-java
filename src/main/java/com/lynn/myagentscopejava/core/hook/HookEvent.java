package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;

/**
 * 所有 Hook 事件的抽象基类。
 *
 * <p>具体子类暴露各自可被 hook 修改的字段（例如
 * {@link PreReasoningEvent#setMessages} 让 hook 改写发送给模型的消息列表）。
 */
public abstract class HookEvent {

    private final ReActAgent agent;
    private boolean stopRequested = false;

    protected HookEvent(ReActAgent agent) {
        this.agent = agent;
    }

    /**
     * @return 触发本事件的 agent
     */
    public ReActAgent getAgent() {
        return agent;
    }

    /**
     * 请求 agent 在处理完本事件后立即终止 ReAct 循环（HITL 场景）。
     *
     * <p>对 {@link PostReasoningEvent} 与 {@link PostActingEvent} 生效。
     */
    public void requestStop() {
        this.stopRequested = true;
    }

    /**
     * @return 是否已被某个 hook 请求停止
     */
    public boolean isStopRequested() {
        return stopRequested;
    }
}
