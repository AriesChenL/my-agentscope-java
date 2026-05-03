package com.lynn.myagentscopejava.core.message;

/**
 * 模型的思考链 / 推理过程（如 DeepSeek R1 的 {@code reasoning_content} 字段）。
 *
 * <p>与 {@link TextBlock} 分离存放，便于上层应用选择展示或隐藏思考过程。
 *
 * @param thinking 思考链文本；{@code null} 会被规范化为空字符串
 */
public record ThinkingBlock(String thinking) implements ContentBlock {
    public ThinkingBlock {
        if (thinking == null) thinking = "";
    }
}
