package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

/**
 * 在每次工具调用返回后触发（成功或错误都会触发），每个工具一次。
 *
 * <p>Hook 可整体替换 {@code result}（例如截断超长输出、脱敏敏感字段），
 * 也可调用 {@link #requestStop()} 让 agent 在本工具完成后立即返回。
 */
public class PostActingEvent extends HookEvent {

    private final ToolUseBlock toolUse;
    private ToolResultBlock result;

    public PostActingEvent(ReActAgent agent, ToolUseBlock toolUse, ToolResultBlock result) {
        super(agent);
        this.toolUse = toolUse;
        this.result = result;
    }

    public ToolUseBlock getToolUse() { return toolUse; }

    public ToolResultBlock getResult() { return result; }

    public void setResult(ToolResultBlock result) { this.result = result; }
}
