package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 把项目内部的 {@link Msg} 列表转成 Anthropic Messages API 协议的 JSON message 数组。
 *
 * <p>核心差异（与 OpenAI 对比）：
 * <ul>
 *   <li>{@link MsgRole#SYSTEM} <b>不进 messages 数组</b>，而是单独提取出来作为请求的 top-level
 *       {@code system} 字段；本类的 {@link #extractSystem(List)} 负责返回它</li>
 *   <li>{@link MsgRole#TOOL}（工具结果）也不是独立 role，而是放进 user 消息的 content 数组里，
 *       表示为 {@code {"type":"tool_result","tool_use_id":...,"content":...}}</li>
 *   <li>assistant 消息的 content 是块数组：text / thinking / tool_use；不存在 reasoning_content 字段</li>
 *   <li>每条消息的 content 必须是数组形式，单个 text 也要包成 {@code [{"type":"text","text":"..."}]}</li>
 * </ul>
 *
 * <p><b>Prompt cache</b>：Anthropic 的缓存通过 {@code cache_control:{"type":"ephemeral"}} 标记
 * 在某个 content block 上启用。本类不在 message 内部加缓存标记，而是由
 * {@link AnthropicChatModel} 在 system 字段和 tools 数组上加 —— 这两处通常稳定且大，缓存价值最高。
 */
class AnthropicMessageConverter {

    private final ObjectMapper mapper;

    AnthropicMessageConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 提取系统提示词（合并所有 SYSTEM 角色的消息文本，按出现顺序用空行连接）。
     *
     * @param messages 完整 prompt
     * @return 拼好的系统提示文本；无 SYSTEM 消息时返回 {@code null}
     */
    String extractSystem(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            if (m.getRole() != MsgRole.SYSTEM) continue;
            String t = m.getText();
            if (t == null || t.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 把 messages 转成 Anthropic 协议下的 user/assistant 消息数组。
     * SYSTEM 消息会被过滤掉（由 {@link #extractSystem} 单独处理）。
     */
    List<ObjectNode> convert(List<Msg> messages) {
        List<ObjectNode> out = new ArrayList<>();
        for (Msg m : messages) {
            switch (m.getRole()) {
                case SYSTEM -> { /* 走 system 字段，跳过 */ }
                case USER -> out.add(buildUserMsg(m));
                case ASSISTANT -> {
                    ObjectNode n = buildAssistantMsg(m);
                    if (n != null) out.add(n);
                }
                case TOOL -> out.add(buildToolResultMsg(m));
            }
        }
        return out;
    }

    private ObjectNode buildUserMsg(Msg m) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.text() != null && !tb.text().isEmpty()) {
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", tb.text());
            }
        }
        // Anthropic 要求 user 消息至少有一个 content block
        if (content.isEmpty()) {
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", "");
        }
        return msg;
    }

    private ObjectNode buildAssistantMsg(Msg m) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode content = msg.putArray("content");

        // Anthropic 的 content 块顺序约定：thinking → text → tool_use
        for (ContentBlock b : m.getContent()) {
            if (b instanceof ThinkingBlock tb && tb.thinking() != null && !tb.thinking().isEmpty()) {
                ObjectNode block = content.addObject();
                block.put("type", "thinking");
                block.put("thinking", tb.thinking());
            }
        }
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.text() != null && !tb.text().isEmpty()) {
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", tb.text());
            }
        }
        for (ContentBlock b : m.getContent()) {
            if (b instanceof ToolUseBlock tu) {
                ObjectNode block = content.addObject();
                block.put("type", "tool_use");
                block.put("id", tu.id());
                block.put("name", tu.name());
                block.set("input", mapper.valueToTree(tu.input()));
            }
        }
        // 完全没内容的 assistant 消息不发——会被 API 拒
        return content.isEmpty() ? null : msg;
    }

    private ObjectNode buildToolResultMsg(Msg m) {
        // TOOL 消息在 Anthropic 协议下伪装成 user 消息，内部装 tool_result 块
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        for (ContentBlock b : m.getContent()) {
            if (b instanceof ToolResultBlock tr) {
                ObjectNode block = content.addObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", tr.id());
                block.put("content", tr.output() != null ? tr.output() : "");
                if (tr.isError()) block.put("is_error", true);
            }
        }
        if (content.isEmpty()) {
            // 防御性兜底，避免空 content 触发 API 400
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", "");
        }
        return msg;
    }
}
