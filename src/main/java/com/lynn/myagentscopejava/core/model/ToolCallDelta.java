package com.lynn.myagentscopejava.core.model;

/**
 * 流式响应中一次工具调用的增量片段。
 *
 * <p>OpenAI / DeepSeek 会把 tool call 拆成多段下发：
 * <ul>
 *   <li>同一个 {@code index} 的第一段携带 {@code id} + {@code nameDelta}（可能附带第一段 {@code argsDelta}）</li>
 *   <li>后续同 {@code index} 的片段持续追加 {@code argsDelta}（流式 JSON 字符串）</li>
 * </ul>
 * Accumulator 按 {@code index} 合并所有片段，最终拼成一个
 * {@link com.lynn.myagentscopejava.core.message.ToolUseBlock}。
 *
 * @param index             在 assistant 的 tool_calls 数组中的位置
 * @param id                工具调用 id（仅在该 index 的首段非空）
 * @param nameDelta         工具函数名（仅在该 index 的首段非空）
 * @param argsDelta         待追加到累积参数 buffer 的 JSON 字符串片段；{@code null} 视为空字符串
 * @param providerSignature provider 自定义的不透明签名（如 Gemini thinking 模型的 thoughtSignature）；
 *                          下一轮请求需要原样回写，否则会被服务端拒。仅 Gemini 使用，OpenAI/Anthropic 留 null
 */
public record ToolCallDelta(int index, String id, String nameDelta, String argsDelta,
                            String providerSignature) {
    public ToolCallDelta {
        if (argsDelta == null) argsDelta = "";
    }

    /** 兼容旧 4 参构造：providerSignature 默认 null。 */
    public ToolCallDelta(int index, String id, String nameDelta, String argsDelta) {
        this(index, id, nameDelta, argsDelta, null);
    }
}
