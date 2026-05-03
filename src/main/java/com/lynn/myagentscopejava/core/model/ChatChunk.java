package com.lynn.myagentscopejava.core.model;

import java.util.Collections;
import java.util.List;

/**
 * 流式聊天补全的单个 chunk。
 *
 * <p>典型的下发顺序：
 * <ol>
 *   <li>0..N 个 chunk，{@code textDelta} 非空（或 {@code thinkingDelta} 非空，针对 R1 类模型）</li>
 *   <li>0..N 个 chunk，{@code toolCallDeltas} 非空（模型正在拼装工具调用）</li>
 *   <li>1 个最终 chunk，{@code finishReason} 非空，通常同时携带 {@code usage}</li>
 * </ol>
 * 任意字段在与本 chunk 无关时可能为 {@code null} / 空。
 *
 * @param textDelta      本 chunk 的文本增量
 * @param thinkingDelta  本 chunk 的思考链增量（DeepSeek R1 等）
 * @param toolCallDeltas 本 chunk 的工具调用增量片段列表
 * @param usage          仅最终 chunk 携带的 token 用量
 * @param finishReason   仅最终 chunk 携带的结束原因
 */
public record ChatChunk(
        String textDelta,
        String thinkingDelta,
        List<ToolCallDelta> toolCallDeltas,
        ChatUsage usage,
        String finishReason
) {
    public ChatChunk {
        toolCallDeltas = toolCallDeltas == null
                ? List.of()
                : Collections.unmodifiableList(toolCallDeltas);
    }

    /**
     * 构造一个仅含文本增量的 chunk。
     *
     * @param delta 文本增量
     * @return chunk 实例
     */
    public static ChatChunk text(String delta) {
        return new ChatChunk(delta, null, null, null, null);
    }

    /**
     * 构造一个仅含思考链增量的 chunk。
     *
     * @param delta 思考链增量
     * @return chunk 实例
     */
    public static ChatChunk thinking(String delta) {
        return new ChatChunk(null, delta, null, null, null);
    }

    /**
     * 构造一个仅含工具调用增量的 chunk。
     *
     * @param deltas 工具调用增量片段列表
     * @return chunk 实例
     */
    public static ChatChunk toolCalls(List<ToolCallDelta> deltas) {
        return new ChatChunk(null, null, deltas, null, null);
    }

    /**
     * 构造一个最终 chunk（携带结束原因 + token 用量）。
     *
     * @param reason 结束原因
     * @param usage  token 用量
     * @return chunk 实例
     */
    public static ChatChunk finish(String reason, ChatUsage usage) {
        return new ChatChunk(null, null, null, usage, reason);
    }

    /**
     * @return 是否为流的最终 chunk
     */
    public boolean isFinal() {
        return finishReason != null;
    }
}
