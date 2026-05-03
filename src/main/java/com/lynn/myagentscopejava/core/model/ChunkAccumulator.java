package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把流式下发的 {@link ChatChunk} 序列累积成最终的 {@link ChatResponse} 及
 * 累积后的 assistant {@link com.lynn.myagentscopejava.core.message.Msg} 内容块列表。
 *
 * <p>非线程安全；预期由单一订阅者顺序消费。
 */
public class ChunkAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    /** index -> 该位置的工具调用累积缓冲区（id / name / args） */
    private final Map<Integer, ToolCallBuffer> toolCalls = new TreeMap<>();
    private ChatUsage usage;
    private String finishReason;

    /**
     * 累积一个 chunk。
     *
     * @param chunk 流中的下一个 chunk；{@code null} 会被忽略
     */
    public void accept(ChatChunk chunk) {
        if (chunk == null) return;
        if (chunk.textDelta() != null) text.append(chunk.textDelta());
        if (chunk.thinkingDelta() != null) thinking.append(chunk.thinkingDelta());
        for (ToolCallDelta d : chunk.toolCallDeltas()) {
            ToolCallBuffer buf = toolCalls.computeIfAbsent(d.index(), i -> new ToolCallBuffer());
            if (d.id() != null && !d.id().isEmpty()) buf.id = d.id();
            if (d.nameDelta() != null && !d.nameDelta().isEmpty()) buf.name = d.nameDelta();
            buf.args.append(d.argsDelta());
            if (d.providerSignature() != null && !d.providerSignature().isEmpty()) {
                // 后到的覆盖：Gemini 的 thoughtSignature 在最后一个 chunk 才完整
                buf.providerSignature = d.providerSignature();
            }
        }
        if (chunk.usage() != null) this.usage = chunk.usage();
        if (chunk.finishReason() != null) this.finishReason = chunk.finishReason();
    }

    /**
     * 输出最终的内容块列表，顺序固定为 ThinkingBlock → TextBlock → ToolUseBlock(s)。
     *
     * @return 内容块列表
     */
    public List<ContentBlock> buildBlocks() {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!thinking.isEmpty()) blocks.add(new ThinkingBlock(thinking.toString()));
        if (!text.isEmpty()) blocks.add(new TextBlock(text.toString()));
        for (ToolCallBuffer buf : toolCalls.values()) {
            if (buf.id == null || buf.name == null) continue;
            Map<String, Object> args = parseArgs(buf.args.toString());
            blocks.add(new ToolUseBlock(buf.id, buf.name, args, buf.providerSignature));
        }
        return blocks;
    }

    /**
     * @return 累积后的 token 用量（可能为 {@code null}，调用方需自行处理）
     */
    public ChatUsage usage() {
        return usage;
    }

    /**
     * @return 流的结束原因
     */
    public String finishReason() {
        return finishReason;
    }

    /** 解析累积的 JSON 参数字符串；解析失败时返回带 {@code _raw} 与 {@code _parseError} 的兜底 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            // 工具调用参数是流式拼接出来的，模型偶尔会发出非法 JSON —— 留下原文与错误信息便于排查
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("_raw", json);
            m.put("_parseError", e.getMessage());
            return m;
        }
    }

    /** 单个工具调用的累积缓冲区。 */
    private static class ToolCallBuffer {
        String id;
        String name;
        String providerSignature;  // Gemini thoughtSignature 等
        final StringBuilder args = new StringBuilder();
    }
}
