package com.lynn.myagentscopejava.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUsageTest {

    @Test
    void cacheHitRateIsZeroWhenPromptEmpty() {
        ChatUsage u = ChatUsage.builder().build();
        assertEquals(0.0, u.getCacheHitRate());
    }

    @Test
    void cacheHitRateComputed() {
        ChatUsage u = ChatUsage.builder()
                .inputTokens(1000)
                .outputTokens(50)
                .cachedInputTokens(750)
                .build();
        assertEquals(0.75, u.getCacheHitRate());
        assertEquals(1050, u.getTotalTokens());
    }

    @Test
    void toStringIncludesCacheStats() {
        ChatUsage u = ChatUsage.builder()
                .inputTokens(100).outputTokens(20).cachedInputTokens(80).build();
        String s = u.toString();
        assertTrue(s.contains("cached=80"));
        assertTrue(s.contains("80.0%"));
    }
}
