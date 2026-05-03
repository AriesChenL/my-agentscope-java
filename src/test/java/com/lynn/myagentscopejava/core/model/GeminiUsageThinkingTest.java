package com.lynn.myagentscopejava.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 Gemini thinking 模型（gemini-3-flash-preview / gemini-2.5-* 等）的 token 计算：
 * thoughtsTokenCount 必须并入 outputTokens，否则前端显示的输出 token 数会少计费 = 误导。
 */
class GeminiUsageThinkingTest {

    private final GeminiChatModel model = GeminiChatModel.builder()
            .modelName("gemini-3-flash-preview").apiKey("fake").build();

    /** 用户截图里的真实事件：text=空，finishReason=STOP，usageMetadata 含 thoughtsTokenCount=502 */
    private static final String FINAL_EVENT = """
            {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP","index":0}],
             "usageMetadata":{"promptTokenCount":1902,"candidatesTokenCount":676,
                              "totalTokenCount":3080,"thoughtsTokenCount":502},
             "modelVersion":"gemini-3-flash-preview","responseId":"x"}
            """.replaceAll("\\s+", " ");

    @Test
    void thoughtsTokensAreIncludedInOutputTokens() {
        List<ChatChunk> chunks = model.parseEvent(FINAL_EVENT, new AtomicInteger(0));
        // 应有 1 个 finish chunk
        ChatChunk finish = chunks.stream().filter(ChatChunk::isFinal).findFirst().orElse(null);
        assertNotNull(finish, "应该解析出 finish chunk");

        ChatUsage u = finish.usage();
        assertEquals(1902, u.getInputTokens(), "promptTokenCount → inputTokens");
        // 关键断言：676 (visible) + 502 (thoughts) = 1178
        assertEquals(1178, u.getOutputTokens(),
                "thoughtsTokenCount 必须并入 outputTokens（676 + 502 = 1178）");
        // total = 1902 + 1178 = 3080，与 Gemini 报的 totalTokenCount 一致
        assertEquals(3080, u.getTotalTokens(), "总和应对得上 Gemini 的 totalTokenCount");
    }

    @Test
    void nonThinkingModelStillWorks() {
        // 老 Gemini 2.0（无 thinking）的事件，没有 thoughtsTokenCount 字段
        String event = """
                {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP","index":0}],
                 "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":50,"totalTokenCount":150}}
                """.replaceAll("\\s+", " ");
        List<ChatChunk> chunks = model.parseEvent(event, new AtomicInteger(0));
        ChatChunk finish = chunks.stream().filter(ChatChunk::isFinal).findFirst().orElseThrow();
        assertEquals(100, finish.usage().getInputTokens());
        assertEquals(50, finish.usage().getOutputTokens(), "无 thoughts 时输出就是 candidates");
    }

    @Test
    void textChunksParsedNormally() {
        // 普通 text delta 事件不带 finish；usage 也不下发
        String event = """
                {"candidates":[{"content":{"parts":[{"text":"hello"}],"role":"model"},"index":0}],
                 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":1}}
                """.replaceAll("\\s+", " ");
        List<ChatChunk> chunks = model.parseEvent(event, new AtomicInteger(0));
        // 应该只有 text chunk，没有 finish
        assertTrue(chunks.stream().anyMatch(c -> "hello".equals(c.textDelta())));
        assertTrue(chunks.stream().noneMatch(ChatChunk::isFinal),
                "无 finishReason 不应该下发 finish chunk");
    }
}
