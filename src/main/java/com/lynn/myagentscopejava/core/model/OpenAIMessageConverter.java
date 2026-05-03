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
 * 把框架内的 {@link Msg}（含若干 {@link ContentBlock}）转成 OpenAI chat-completions
 * 接口要求的消息线格式。
 *
 * <p>映射规则：
 * <ul>
 *   <li>SYSTEM / USER 消息（含 TextBlock）→ 单条 OpenAI 消息，role=system|user，content=拼接文本</li>
 *   <li>ASSISTANT 消息（含 TextBlock 与/或 ToolUseBlock）→ 单条 assistant 消息：
 *       文本写入 {@code content}，ToolUseBlock 写入 {@code tool_calls}</li>
 *   <li>TOOL 或 USER 消息含 ToolResultBlock → 每个结果一条 {@code role=tool} 消息，
 *       带 {@code tool_call_id}</li>
 *   <li>ThinkingBlock 会被丢弃（OpenAI API 不接受，模型自行重新推导）</li>
 * </ul>
 */
public class OpenAIMessageConverter {

    private final ObjectMapper mapper;

    public OpenAIMessageConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 转换一组消息。
     *
     * @param messages 框架内消息
     * @return OpenAI 格式的消息列表
     */
    public List<ObjectNode> convert(List<Msg> messages) {
        List<ObjectNode> out = new ArrayList<>();
        for (Msg m : messages) {
            // 工具结果消息：每个结果块单独发一条 OpenAI 消息
            List<ToolResultBlock> results = m.getBlocks(ToolResultBlock.class);
            if (!results.isEmpty()) {
                for (ToolResultBlock r : results) {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("role", "tool");
                    node.put("tool_call_id", r.id());
                    node.put("content", r.isError() ? "[ERROR] " + r.output() : r.output());
                    out.add(node);
                }
                continue;
            }

            ObjectNode node = mapper.createObjectNode();
            node.put("role", roleOf(m.getRole()));

            // 拼接所有 TextBlock 作为 content
            String text = concatText(m.getContent());
            node.put("content", text);

            // 思考链（DeepSeek R1 / deepseek-v4-flash 等"thinking mode"模型必须把 reasoning_content 回传，
            // 否则下一轮请求会被服务端 400 拒绝）
            List<ThinkingBlock> thinking = m.getBlocks(ThinkingBlock.class);
            if (!thinking.isEmpty()) {
                StringBuilder rc = new StringBuilder();
                for (ThinkingBlock t : thinking) rc.append(t.thinking());
                node.put("reasoning_content", rc.toString());
            }

            // assistant 的工具调用
            List<ToolUseBlock> toolUses = m.getBlocks(ToolUseBlock.class);
            if (!toolUses.isEmpty()) {
                ArrayNode arr = node.putArray("tool_calls");
                for (ToolUseBlock tu : toolUses) {
                    ObjectNode call = arr.addObject();
                    call.put("id", tu.id());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", tu.name());
                    try {
                        fn.put("arguments", mapper.writeValueAsString(tu.input()));
                    } catch (Exception e) {
                        fn.put("arguments", "{}");
                    }
                }
            }

            out.add(node);
        }
        return out;
    }

    private static String concatText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock t) sb.append(t.text());
            // ThinkingBlock 单独写入 reasoning_content 字段，不混入 content
        }
        return sb.toString();
    }

    private static String roleOf(MsgRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }
}
