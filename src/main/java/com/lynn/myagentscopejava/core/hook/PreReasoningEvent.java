package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.message.Msg;

import java.util.List;

/**
 * 在每次模型调用前触发。Hook 可整体替换 {@code messages} 列表
 *（例如注入额外上下文、裁剪历史、做 RAG 检索等）。
 */
public class PreReasoningEvent extends HookEvent {

    private List<Msg> messages;
    private final int iteration;

    public PreReasoningEvent(ReActAgent agent, List<Msg> messages, int iteration) {
        super(agent);
        this.messages = messages;
        this.iteration = iteration;
    }

    public List<Msg> getMessages() { return messages; }

    public void setMessages(List<Msg> messages) { this.messages = messages; }

    /**
     * @return ReAct 循环的迭代序号（从 0 开始）
     */
    public int getIteration() { return iteration; }
}
