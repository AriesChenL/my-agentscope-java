package com.lynn.myagentscopejava.core.message;

import com.lynn.myagentscopejava.core.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息历史一致性修复工具，专治"孤儿 tool_calls"。
 *
 * <p><b>问题背景</b>：OpenAI 协议硬性要求带 {@code tool_calls} 的 assistant 消息后面必须紧跟
 * 与每个 {@code tool_call_id} 一一对应的 {@code role:"tool"} 消息，否则下一次发送整段历史时
 * 服务端会返回 400：
 * <pre>An assistant message with 'tool_calls' must be followed by tool messages
 * responding to each 'tool_call_id'</pre>
 *
 * <p>以下场景会留下孤儿：用户中途中断流式响应、工具执行抛异常、进程崩溃等。落盘后再加载，
 * 整个会话就废了。
 *
 * <p>本工具参考 agentscope-java 的 {@code PendingToolRecoveryHook} 设计，扫描 memory，
 * 对每个没有 {@link ToolResultBlock} 响应的 {@link ToolUseBlock} 自动补一条 TOOL 消息，
 * 内容为合成的错误结果，让会话能继续推进。
 */
public final class MessageHealing {

    private static final Logger log = LoggerFactory.getLogger(MessageHealing.class);

    private MessageHealing() {}

    /**
     * 扫描 memory，为最近一条 assistant 消息中没有对应 ToolResultBlock 的 ToolUseBlock 补合成结果。
     *
     * <p>仅处理"最近一条 assistant 消息"——这是 OpenAI 协议下唯一会引发 400 的位置；
     * 更早的孤儿已经被前端中断后续消息覆盖，无须处理。
     *
     * @param memory     待修复的 memory
     * @param agentName  合成 TOOL 消息使用的 name 字段
     * @return 补了多少条合成结果（0 表示无需修复）
     */
    public static int healOrphanToolCalls(Memory memory, String agentName) {
        if (memory == null) return 0;
        List<Msg> messages = memory.getMessages();
        if (messages.isEmpty()) return 0;

        // 找最后一条 assistant 消息
        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) return 0;

        Msg lastAssistant = messages.get(lastAssistantIdx);
        List<ToolUseBlock> toolUses = lastAssistant.getBlocks(ToolUseBlock.class);
        if (toolUses.isEmpty()) return 0;

        // 收集"在最后一条 assistant 之后"已经存在的 tool result id
        Set<String> respondedIds = new HashSet<>();
        for (int i = lastAssistantIdx + 1; i < messages.size(); i++) {
            for (ToolResultBlock r : messages.get(i).getBlocks(ToolResultBlock.class)) {
                respondedIds.add(r.id());
            }
        }

        // 找出孤儿
        List<ToolUseBlock> orphans = new ArrayList<>();
        for (ToolUseBlock use : toolUses) {
            if (!respondedIds.contains(use.id())) orphans.add(use);
        }
        if (orphans.isEmpty()) return 0;

        // 合成一条 TOOL 消息，把所有孤儿的结果都装进去
        List<ContentBlock> resultBlocks = new ArrayList<>();
        for (ToolUseBlock use : orphans) {
            resultBlocks.add(ToolResultBlock.error(
                    use.id(), use.name(),
                    "[已中断] 上一轮工具调用未完成，可能是用户中断或网络异常。工具：" + use.name()));
        }
        Msg synthetic = Msg.builder()
                .name(agentName != null ? agentName : "Assistant")
                .role(MsgRole.TOOL)
                .content(resultBlocks)
                .build();
        memory.addMessage(synthetic);

        log.warn("修复孤儿 tool_calls，补了 {} 条合成结果，ids={}",
                orphans.size(),
                orphans.stream().map(ToolUseBlock::id).toList());
        return orphans.size();
    }
}
