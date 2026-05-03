package com.lynn.myagentscopejava.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息内容块的密封接口（sealed interface），描述一条 {@link Msg} 中的一段类型化内容。
 *
 * <p>实现类共 4 种：
 * <ul>
 *   <li>{@link TextBlock}       —— 普通文本</li>
 *   <li>{@link ThinkingBlock}   —— 模型的思考链 / 推理过程</li>
 *   <li>{@link ToolUseBlock}    —— 模型发起的工具调用请求</li>
 *   <li>{@link ToolResultBlock} —— 工具执行结果，回传给模型</li>
 * </ul>
 *
 * <p>使用 sealed 是为了让框架在编译期穷举所有内容块类型，避免后期新增类型遗漏处理。
 *
 * <p>{@link JsonTypeInfo} + {@link JsonSubTypes} 配置 Jackson 的多态序列化，
 * Session 持久化时会以 {@code @type} 字段区分具体类型。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
})
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock {
}
