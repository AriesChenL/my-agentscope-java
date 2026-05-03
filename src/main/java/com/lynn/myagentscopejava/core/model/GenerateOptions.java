package com.lynn.myagentscopejava.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 生成参数，传给
 * {@link ChatModel#stream(java.util.List, java.util.List, GenerateOptions,
 * com.lynn.myagentscopejava.core.interruption.CancellationToken)}。
 *
 * <p>所有字段都是可选的：{@code null} 表示沿用 provider 默认值。
 * provider 私有的参数（如 OpenAI 的 {@code logit_bias}）可通过
 * {@link #getExtraParams()} 透传。
 */
public class GenerateOptions {

    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final List<String> stopSequences;
    private final Map<String, Object> extraParams;

    private GenerateOptions(Builder b) {
        this.temperature = b.temperature;
        this.topP = b.topP;
        this.topK = b.topK;
        this.maxTokens = b.maxTokens;
        this.frequencyPenalty = b.frequencyPenalty;
        this.presencePenalty = b.presencePenalty;
        this.stopSequences = b.stopSequences == null ? null : List.copyOf(b.stopSequences);
        this.extraParams = b.extraParams == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.extraParams));
    }

    public Double getTemperature() { return temperature; }
    public Double getTopP() { return topP; }
    public Integer getTopK() { return topK; }
    public Integer getMaxTokens() { return maxTokens; }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public Double getPresencePenalty() { return presencePenalty; }
    public List<String> getStopSequences() { return stopSequences; }
    public Map<String, Object> getExtraParams() { return extraParams; }

    /**
     * @return 全部字段为空的默认实例
     */
    public static GenerateOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * GenerateOptions 的链式构造器。
     */
    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private List<String> stopSequences;
        private Map<String, Object> extraParams;

        public Builder temperature(Double v) { this.temperature = v; return this; }
        public Builder topP(Double v) { this.topP = v; return this; }
        public Builder topK(Integer v) { this.topK = v; return this; }
        public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
        public Builder frequencyPenalty(Double v) { this.frequencyPenalty = v; return this; }
        public Builder presencePenalty(Double v) { this.presencePenalty = v; return this; }
        public Builder stopSequences(List<String> v) { this.stopSequences = v; return this; }

        /**
         * 追加一个 provider 私有的额外参数，会原样写入请求体。
         *
         * @param key   参数名
         * @param value 参数值
         * @return 当前 Builder
         */
        public Builder extraParam(String key, Object value) {
            if (this.extraParams == null) this.extraParams = new LinkedHashMap<>();
            this.extraParams.put(key, value);
            return this;
        }

        public GenerateOptions build() {
            return new GenerateOptions(this);
        }
    }
}
