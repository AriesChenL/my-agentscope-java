package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

import java.util.List;
import java.util.Map;

/**
 * 估算消息的 token 数量，用于驱动按 token 预算触发的压缩策略。
 *
 * <p>实现可以从轻量近似（字符数 / N）一直到接入真实的 tokenizer
 *（如 jtokkit / tiktoken-jvm），按需要替换。
 */
public interface TokenEstimator {

    /**
     * 估算单条消息的 token 数。
     *
     * @param msg 待估算的消息
     * @return 估算 token 数（应 ≥ 0）
     */
    int estimate(Msg msg);

    /**
     * 估算一组消息的总 token 数。默认实现为求和。
     *
     * @param messages 消息列表
     * @return 累计 token 数
     */
    default int estimate(List<Msg> messages) {
        int sum = 0;
        for (Msg m : messages) sum += estimate(m);
        return sum;
    }

    /**
     * 默认近似估算器。基于字符数 / {@code charsPerToken}，
     * 中英文混合场景 {@code charsPerToken=2.8} 约能覆盖大多数情况（保守略偏高，
     * 用于触发压缩反而是好事）。
     *
     * @return 默认近似估算器
     */
    static TokenEstimator approximate() {
        return new ApproximateTokenEstimator(2.8);
    }

    /**
     * 自定义字符密度的近似估算器。
     *
     * @param charsPerToken 平均每个 token 对应的字符数
     * @return 自定义比例的估算器
     */
    static TokenEstimator approximate(double charsPerToken) {
        return new ApproximateTokenEstimator(charsPerToken);
    }

    /**
     * 基于字符数 / 平均比例的近似实现。把消息中所有可序列化文本（含 ContentBlock 的内部字段）
     * 拼起来再除以 {@code charsPerToken}。
     */
    final class ApproximateTokenEstimator implements TokenEstimator {

        private final double charsPerToken;

        ApproximateTokenEstimator(double charsPerToken) {
            if (charsPerToken <= 0) {
                throw new IllegalArgumentException("charsPerToken 必须 > 0");
            }
            this.charsPerToken = charsPerToken;
        }

        @Override
        public int estimate(Msg msg) {
            if (msg == null || msg.getContent() == null) return 0;
            // 角色 + name 也会进 prompt，给 4 个 token 的固定开销
            int baseChars = 4;
            for (ContentBlock b : msg.getContent()) {
                baseChars += charCountOf(b);
            }
            return (int) Math.ceil(baseChars / charsPerToken);
        }

        private int charCountOf(ContentBlock b) {
            if (b instanceof TextBlock t) return t.text().length();
            if (b instanceof ThinkingBlock t) return t.thinking().length();
            if (b instanceof ToolResultBlock t) return t.output().length();
            if (b instanceof ToolUseBlock t) {
                int n = t.name().length() + 4;
                for (Map.Entry<String, Object> e : t.input().entrySet()) {
                    n += e.getKey().length();
                    n += e.getValue() == null ? 4 : e.getValue().toString().length();
                    n += 4; // 引号、冒号、逗号
                }
                return n;
            }
            return 0;
        }
    }
}
