package com.lynn.myagentscopejava.core.message;

/**
 * 消息的角色类型，用于区分对话中不同身份的发送者。
 */
public enum MsgRole {

    /** 系统提示词。 */
    SYSTEM,

    /** 用户输入。 */
    USER,

    /** 模型生成的回复（含可能的 tool_calls）。 */
    ASSISTANT,

    /** 工具调用的输出，回传给模型作为下一轮推理的上下文，包含一个或多个 ToolResultBlock。 */
    TOOL
}
