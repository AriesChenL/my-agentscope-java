package com.lynn.myagentscopejava.core.message;

/**
 * 纯文本内容块，承载用户输入或模型回复中的文字部分。
 *
 * @param text 文本内容；{@code null} 会被规范化为空字符串
 */
public record TextBlock(String text) implements ContentBlock {
    public TextBlock {
        if (text == null) text = "";
    }
}
