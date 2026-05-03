package com.lynn.myagentscopejava.core.model;

/**
 * 一次聊天补全的 token 用量统计，含 prompt cache 命中数据。
 *
 * <p>Cache 命中字段在 provider 返回相应数据时被填充：
 * <ul>
 *   <li>DeepSeek: {@code usage.prompt_cache_hit_tokens}</li>
 *   <li>OpenAI:   {@code usage.prompt_tokens_details.cached_tokens}</li>
 *   <li>Anthropic:{@code usage.cache_creation_input_tokens} / {@code cache_read_input_tokens}</li>
 * </ul>
 */
public class ChatUsage {

    private final int inputTokens;
    private final int outputTokens;
    private final int cachedInputTokens;
    private final int cacheCreationTokens;

    private ChatUsage(Builder b) {
        this.inputTokens = b.inputTokens;
        this.outputTokens = b.outputTokens;
        this.cachedInputTokens = b.cachedInputTokens;
        this.cacheCreationTokens = b.cacheCreationTokens;
    }

    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getTotalTokens() { return inputTokens + outputTokens; }

    /**
     * @return 来自 cache 的 prompt token 数（cache HIT 部分）
     */
    public int getCachedInputTokens() {
        return cachedInputTokens;
    }

    /**
     * @return 本次写入 cache 的 prompt token 数（仅 Anthropic 提供，其它 provider 为 0）
     */
    public int getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    /**
     * @return prompt 部分的 cache 命中率（[0, 1]），prompt 为空时返回 0
     */
    public double getCacheHitRate() {
        return inputTokens == 0 ? 0.0 : (double) cachedInputTokens / inputTokens;
    }

    @Override
    public String toString() {
        return "ChatUsage{in=%d, out=%d, cached=%d (%.1f%%), cacheCreation=%d}".formatted(
                inputTokens, outputTokens, cachedInputTokens, getCacheHitRate() * 100, cacheCreationTokens);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * ChatUsage 的链式构造器。
     */
    public static class Builder {
        private int inputTokens;
        private int outputTokens;
        private int cachedInputTokens;
        private int cacheCreationTokens;

        public Builder inputTokens(int v) { this.inputTokens = v; return this; }
        public Builder outputTokens(int v) { this.outputTokens = v; return this; }
        public Builder cachedInputTokens(int v) { this.cachedInputTokens = v; return this; }
        public Builder cacheCreationTokens(int v) { this.cacheCreationTokens = v; return this; }

        public ChatUsage build() {
            return new ChatUsage(this);
        }
    }
}
