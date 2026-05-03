package com.lynn.myagentscopejava.core.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次会话的元数据。实际消息内容由 {@link com.lynn.myagentscopejava.core.session.Session}
 * 在另一个槽位存储。
 *
 * @param id        会话 id（必须匹配 {@code [A-Za-z0-9_-]+}）
 * @param title     会话标题（用户可改名；首次自动用第一条用户消息截取）
 * @param provider  使用的 provider id（openai / anthropic / gemini）；{@code null} 表示走默认
 * @param createdAt 创建时间戳（毫秒）
 * @param updatedAt 最后更新时间戳（毫秒）
 */
public record Conversation(String id, String title, String provider,
                           long createdAt, long updatedAt) {

    @JsonCreator
    public Conversation(@JsonProperty("id") String id,
                        @JsonProperty("title") String title,
                        @JsonProperty("provider") String provider,
                        @JsonProperty("createdAt") long createdAt,
                        @JsonProperty("updatedAt") long updatedAt) {
        this.id = id;
        this.title = title == null ? "新对话" : title;
        this.provider = provider;  // null = 走默认
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Conversation withTitle(String newTitle) {
        return new Conversation(id, newTitle, provider, createdAt, System.currentTimeMillis());
    }

    public Conversation touch() {
        return new Conversation(id, title, provider, createdAt, System.currentTimeMillis());
    }
}
