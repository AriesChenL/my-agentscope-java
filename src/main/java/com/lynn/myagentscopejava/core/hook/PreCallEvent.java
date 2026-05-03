package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.Msg;

import java.util.Collections;
import java.util.List;

/**
 * 在 agent 处理一次调用最开始触发，先于"resume HITL / 追加 user 消息 / 进入 reasoning"。
 *
 * <p>典型用途：在 reasoning 之前对 memory 做最后的修复或预处理。内置的
 * {@link PendingToolRecoveryHook} 就在此事件里把"孤儿 tool_calls"补成合成错误结果，
 * 避免下一轮请求被 LLM API 以 400 拒绝。
 *
 * <p>本事件提供 {@link #getMemory()} 直接访问 —— ReActAgent 路径下与
 * {@code agent.getMemory()} 等价；{@link com.lynn.myagentscopejava.core.service.ChatService}
 * 的流式路径没有 ReActAgent 实例，{@link #getAgent()} 为 {@code null}，hook 必须用
 * {@link #getMemory()} 访问。
 *
 * <p>对应上游 agentscope-java 的 {@code PreCallEvent} —— 形态略有差异（上游事件含
 * {@code inputMessages} 列表，我们这边 ReActAgent.call 单条 Msg、ChatService 单条 Msg，
 * 用统一的 {@code List<Msg>} 包装表达）。
 */
public class PreCallEvent extends HookEvent {

    private final Memory memory;
    private final List<Msg> inputMessages;

    /**
     * @param agent         触发的 agent；流式路径下可为 {@code null}
     * @param memory        将参与本次调用的 memory，必填
     * @param inputMessages 用户在本次调用中提供的输入消息列表（可空）；
     *                      HITL resume 时通常含 {@link com.lynn.myagentscopejava.core.message.ToolResultBlock}
     */
    public PreCallEvent(ReActAgent agent, Memory memory, List<Msg> inputMessages) {
        super(agent);
        if (memory == null) throw new IllegalArgumentException("memory 必填");
        this.memory = memory;
        this.inputMessages = inputMessages == null ? List.of() : Collections.unmodifiableList(inputMessages);
    }

    public Memory getMemory() { return memory; }

    public List<Msg> getInputMessages() { return inputMessages; }
}
