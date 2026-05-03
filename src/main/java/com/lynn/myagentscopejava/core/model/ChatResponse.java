package com.lynn.myagentscopejava.core.model;

import com.lynn.myagentscopejava.core.message.Msg;

/**
 * 一次聊天补全的最终结果。
 *
 * @param message      模型返回的完整 assistant 消息（含所有内容块）
 * @param usage        本次调用的 token 用量；{@code null} 时会被规范化为空 {@link ChatUsage}
 * @param finishReason 模型终止原因（如 {@code stop} / {@code tool_calls} / {@code length}）
 */
public record ChatResponse(Msg message, ChatUsage usage, String finishReason) {
    public ChatResponse {
        if (usage == null) usage = ChatUsage.builder().build();
    }

    /**
     * @return assistant 消息中的拼接文本（无 TextBlock 时返回空字符串）
     */
    public String text() {
        return message != null ? message.getText() : "";
    }
}
