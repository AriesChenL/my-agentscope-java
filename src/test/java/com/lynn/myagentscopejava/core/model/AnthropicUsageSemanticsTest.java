package com.lynn.myagentscopejava.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对 Anthropic usage 字段语义的回归测试。
 *
 * <p>背景：Anthropic 的 {@code input_tokens} 与 OpenAI 的 {@code prompt_tokens} <b>不同</b>：
 * <ul>
 *   <li>OpenAI: {@code prompt_tokens} = 总 prompt（含 cache 命中部分）</li>
 *   <li>Anthropic: {@code input_tokens} 仅含"非 cache、非 cache_creation"的纯新 token；
 *       cache_read / cache_creation 是平行的另两个字段</li>
 * </ul>
 * 早先 bug：直接把 input_tokens 当总 prompt 用，导致 cache hit rate 算出 3780% 这种荒谬值。
 *
 * <p>修复后：{@link AnthropicChatModel} 在构造最终 ChatUsage 时把
 * input + cache_read + cache_creation 加起来作为 inputTokens，对齐 OpenAI 语义。
 */
class AnthropicUsageSemanticsTest {

    @Test
    void cacheHitRateIsCorrectWhenMostTokensComeFromCache() {
        // 模拟用户截图里的真实数据
        int freshInput = 46;
        int cacheCreation = 321;
        int cacheRead = 1739;
        int output = 321;

        // 模拟 AnthropicChatModel 的最终聚合（见 AnthropicChatModel.parseEvent message_stop 分支）
        ChatUsage usage = ChatUsage.builder()
                .inputTokens(freshInput + cacheRead + cacheCreation)  // 2106
                .outputTokens(output)
                .cachedInputTokens(cacheRead)                          // 1739
                .cacheCreationTokens(cacheCreation)                    // 321
                .build();

        assertEquals(2106, usage.getInputTokens(), "总输入应为三段相加");
        assertEquals(1739, usage.getCachedInputTokens());
        assertEquals(321, usage.getCacheCreationTokens());

        double rate = usage.getCacheHitRate();
        // 1739 / 2106 ≈ 0.8257
        assertTrue(rate > 0.80 && rate < 0.85,
                "cache 命中率应在 80%~85%，实际：" + rate);
    }

    @Test
    void cacheHitRateIsZeroWhenNothingCached() {
        ChatUsage usage = ChatUsage.builder()
                .inputTokens(100)
                .outputTokens(50)
                .cachedInputTokens(0)
                .build();
        assertEquals(0.0, usage.getCacheHitRate());
    }

    @Test
    void cacheHitRateIsOneWhenEverythingCached() {
        // 极端：所有 prompt 都是 cache 命中（cache_read = total，没有 fresh、没有 creation）
        ChatUsage usage = ChatUsage.builder()
                .inputTokens(1000)
                .outputTokens(50)
                .cachedInputTokens(1000)
                .build();
        assertEquals(1.0, usage.getCacheHitRate());
    }

    @Test
    void zeroInputDoesNotDivideByZero() {
        ChatUsage usage = ChatUsage.builder()
                .inputTokens(0)
                .outputTokens(0)
                .cachedInputTokens(0)
                .build();
        assertEquals(0.0, usage.getCacheHitRate());
    }
}
