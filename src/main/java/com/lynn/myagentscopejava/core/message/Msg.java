package com.lynn.myagentscopejava.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 对话中的一条消息。
 *
 * <p>内容由若干 {@link ContentBlock} 组成，因此一条 ASSISTANT 消息可以同时携带
 * {@link ThinkingBlock}、{@link TextBlock} 以及若干 {@link ToolUseBlock}，
 * 反映模型一次输出中"先思考、再回复、最后调用工具"的结构。
 *
 * <p>对象不可变；通过 {@link Builder} 构造。
 */
public class Msg {

    private final String id;
    private final String name;
    private final MsgRole role;
    private final List<ContentBlock> content;

    private Msg(Builder b) {
        this(b.id, b.name, b.role, b.content);
    }

    /**
     * 供 Jackson 反序列化使用的全字段构造器。
     *
     * @param id      消息 id；{@code null} 时自动生成 UUID
     * @param name    消息发送方名称
     * @param role    消息角色
     * @param content 内容块列表；{@code null} 视为空列表
     */
    @JsonCreator
    public Msg(@JsonProperty("id") String id,
               @JsonProperty("name") String name,
               @JsonProperty("role") MsgRole role,
               @JsonProperty("content") List<ContentBlock> content) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.role = role;
        this.content = content == null
                ? List.of()
                : List.copyOf(content);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public MsgRole getRole() { return role; }
    public List<ContentBlock> getContent() { return content; }

    /**
     * 拼接消息中所有 {@link TextBlock} 的文本（无 TextBlock 时返回空字符串）。
     *
     * @return 拼接后的纯文本
     */
    @JsonIgnore
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : content) {
            if (b instanceof TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }

    /**
     * 提取所有指定类型的内容块。
     *
     * @param type 目标内容块类型
     * @param <T>  内容块类型参数
     * @return 匹配的内容块列表（可能为空）
     */
    public <T extends ContentBlock> List<T> getBlocks(Class<T> type) {
        return content.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * 判断消息中是否存在指定类型的内容块。
     *
     * @param type 目标内容块类型
     * @return 存在返回 true
     */
    public boolean hasBlock(Class<? extends ContentBlock> type) {
        return content.stream().anyMatch(type::isInstance);
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 构造一条用户消息（仅含一个 TextBlock）。
     *
     * @param name 用户名
     * @param text 文本内容
     * @return 用户消息
     */
    public static Msg user(String name, String text) {
        return builder().name(name).role(MsgRole.USER).text(text).build();
    }

    /**
     * 构造一条 assistant 消息（仅含一个 TextBlock）。
     *
     * @param name agent 名称
     * @param text 文本内容
     * @return assistant 消息
     */
    public static Msg assistant(String name, String text) {
        return builder().name(name).role(MsgRole.ASSISTANT).text(text).build();
    }

    /**
     * 构造一条 system 消息（仅含一个 TextBlock）。
     *
     * @param text 系统提示词
     * @return system 消息
     */
    public static Msg system(String text) {
        return builder().name("system").role(MsgRole.SYSTEM).text(text).build();
    }

    /**
     * Msg 的链式构造器。
     */
    public static class Builder {
        private String id;
        private String name;
        private MsgRole role;
        private List<ContentBlock> content;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder role(MsgRole role) { this.role = role; return this; }

        /**
         * 便捷方法：将 content 设置为一个 {@link TextBlock}。
         *
         * @param text 文本内容
         * @return 当前 Builder
         */
        public Builder text(String text) {
            this.content = List.of(new TextBlock(text == null ? "" : text));
            return this;
        }

        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        public Builder content(ContentBlock... blocks) {
            this.content = List.of(blocks);
            return this;
        }

        /**
         * 在已有 content 末尾追加一个内容块。
         *
         * @param block 待追加的内容块
         * @return 当前 Builder
         */
        public Builder addBlock(ContentBlock block) {
            if (this.content == null) this.content = new ArrayList<>();
            else if (!(this.content instanceof ArrayList)) this.content = new ArrayList<>(this.content);
            this.content.add(block);
            return this;
        }

        public Msg build() { return new Msg(this); }
    }
}
