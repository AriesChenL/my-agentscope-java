package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 把项目内部的 {@link Msg} 列表转成 Google Generative Language API 协议的 {@code contents} 数组。
 *
 * <p>核心差异（与 OpenAI / Anthropic 对比）：
 * <ul>
 *   <li>角色叫 {@code user} / {@code model}（不是 assistant）/ {@code function}（工具结果）</li>
 *   <li>{@link MsgRole#SYSTEM} 走 top-level {@code systemInstruction} 字段，本类用
 *       {@link #extractSystem(List)} 提取</li>
 *   <li>每条消息有 {@code parts:[...]}，每个 part 是 {@code {text}} / {@code {functionCall}}
 *       / {@code {functionResponse}}</li>
 *   <li>工具调用：{@code {functionCall: {name, args}}}（无 id 概念，靠 name 匹配）</li>
 *   <li>工具结果：{@code {functionResponse: {name, response: {...}}}}</li>
 *   <li>没有"thinking"对应字段，{@link com.lynn.myagentscopejava.core.message.ThinkingBlock} 直接丢弃</li>
 * </ul>
 */
class GeminiMessageConverter {

    private final ObjectMapper mapper;

    GeminiMessageConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 提取系统指令（合并所有 SYSTEM 消息文本）。
     *
     * @param messages 完整 prompt
     * @return 拼好的 system 文本；无 SYSTEM 消息时返回 {@code null}
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

    /** 把 messages 转成 Gemini 协议下的 contents 数组。SYSTEM 消息会被过滤掉。 */
    List<ObjectNode> convert(List<Msg> messages) {
        List<ObjectNode> out = new ArrayList<>();
        for (Msg m : messages) {
            ObjectNode c = switch (m.getRole()) {
                case SYSTEM -> null;
                case USER -> buildUserMsg(m);
                case ASSISTANT -> buildAssistantMsg(m);
                case TOOL -> buildToolResultMsg(m);
            };
            if (c != null) out.add(c);
        }
        return out;
    }

    private ObjectNode buildUserMsg(Msg m) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode parts = msg.putArray("parts");
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.text() != null && !tb.text().isEmpty()) {
                parts.addObject().put("text", tb.text());
            }
        }
        if (parts.isEmpty()) parts.addObject().put("text", "");
        return msg;
    }

    private ObjectNode buildAssistantMsg(Msg m) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "model");
        ArrayNode parts = msg.putArray("parts");
        // text 块
        for (ContentBlock b : m.getContent()) {
            if (b instanceof TextBlock tb && tb.text() != null && !tb.text().isEmpty()) {
                parts.addObject().put("text", tb.text());
            }
        }
        // tool_use 块 → functionCall（无 id，名字必须与后续 functionResponse 对得上）
        for (ContentBlock b : m.getContent()) {
            if (b instanceof ToolUseBlock tu) {
                ObjectNode part = parts.addObject();
                ObjectNode fc = part.putObject("functionCall");
                fc.put("name", tu.name());
                fc.set("args", mapper.valueToTree(tu.input()));
                // Gemini thinking 模型必需：把上一轮服务端给的 thoughtSignature 原样回写
                if (tu.providerSignature() != null && !tu.providerSignature().isEmpty()) {
                    part.put("thoughtSignature", tu.providerSignature());
                }
            }
        }
        return parts.isEmpty() ? null : msg;
    }

    private ObjectNode buildToolResultMsg(Msg m) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "function");
        ArrayNode parts = msg.putArray("parts");
        for (ContentBlock b : m.getContent()) {
            if (b instanceof ToolResultBlock tr) {
                ObjectNode part = parts.addObject();
                ObjectNode fr = part.putObject("functionResponse");
                // Gemini 用 name 匹配 functionCall。优先使用 ToolResultBlock.name；
                // 旧 API（无 name）回退到 id，但这种情况下 cachedContents 校验会失败。
                String fnName = tr.name() != null && !tr.name().isBlank() ? tr.name() : tr.id();
                fr.put("name", fnName);
                ObjectNode resp = fr.putObject("response");
                String content = tr.output() != null ? tr.output() : "";
                if (tr.isError()) resp.put("error", content);
                else resp.put("result", content);
            }
        }
        if (parts.isEmpty()) parts.addObject().put("text", "");
        return msg;
    }
}
