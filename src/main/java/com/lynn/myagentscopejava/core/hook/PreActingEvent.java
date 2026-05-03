package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

/**
 * 在每次工具调用前触发，每个待调用的工具一次。
 *
 * <p>Hook 可整体替换 {@code toolUse}（例如清理参数、改写工具名）—— agent 实际调用的是
 * 修改后的 ToolUseBlock。
 *
 * <p>Hook 也可调用 {@link #suspend(String)} 短路本次工具调用：实际工具不会被执行，
 * 框架会直接产生一个 pending {@link com.lynn.myagentscopejava.core.message.ToolResultBlock}，
 * 让 agent 进入"等待人工输入"状态。这是
 * {@link com.lynn.myagentscopejava.core.hook.ToolConfirmationHook} 实现"危险工具人工审批"
 * 的关键钩子。
 */
public class PreActingEvent extends HookEvent {

    private ToolUseBlock toolUse;
    private boolean suspended = false;
    private String suspendReason;

    public PreActingEvent(ReActAgent agent, ToolUseBlock toolUse) {
        super(agent);
        this.toolUse = toolUse;
    }

    public ToolUseBlock getToolUse() { return toolUse; }

    public void setToolUse(ToolUseBlock toolUse) { this.toolUse = toolUse; }

    /**
     * 短路本次工具调用：实际工具不会被执行，框架直接产生 pending ToolResult 等待人工提供。
     *
     * @param reason 挂起原因（会作为 pending 结果的 output 透传给前端，便于展示）
     */
    public void suspend(String reason) {
        this.suspended = true;
        this.suspendReason = reason;
    }

    /**
     * @return 本次工具调用是否被某个 hook 标记为挂起
     */
    public boolean isSuspended() { return suspended; }

    /**
     * @return 挂起原因；未挂起时返回 {@code null}
     */
    public String getSuspendReason() { return suspendReason; }
}
