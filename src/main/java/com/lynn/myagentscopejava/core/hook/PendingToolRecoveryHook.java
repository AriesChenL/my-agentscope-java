package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动补救"孤儿 tool_calls" 的钩子。
 *
 * <p><b>问题背景</b>：OpenAI 协议硬性要求带 {@code tool_calls} 的 assistant 消息后面必须紧跟
 * 与每个 {@code tool_call_id} 一一对应的 {@code role:"tool"} 消息，否则下一次发送整段历史时
 * 服务端会返回 400。以下场景会留下孤儿：用户中途中断流式响应、工具执行抛异常、进程崩溃等。
 * 落盘后再加载，整个会话就废了。
 *
 * <p>本 hook 在 {@link PreCallEvent} 时机扫描 memory，对最近一条 ASSISTANT 消息中没有对应
 * {@link ToolResultBlock} 的 {@link ToolUseBlock} 自动补一条合成的错误 TOOL 消息，让会话能继续推进。
 *
 * <p><b>跳过条件</b>：当本次调用的 {@code inputMessages} 已经含 {@link ToolResultBlock}（HITL resume
 * 场景），由 ReActAgent 自身的 {@code resumeWithHumanInput} 处理，hook 直接跳过避免重复补救。
 *
 * <p><b>注意</b>：HITL pending 状态下 memory 末尾 TOOL 消息已经含 pending {@link ToolResultBlock}，
 * 这些 id 算"已响应"，不会被识别为孤儿 —— pending 不会被本 hook 误伤。
 *
 * <p>对应上游 agentscope-java 的 {@code PendingToolRecoveryHook}。本项目以前用静态工具
 * {@code MessageHealing} 做同样的事，已迁移为 hook 形态。
 */
public class PendingToolRecoveryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PendingToolRecoveryHook.class);

    private final String agentName;

    public PendingToolRecoveryHook() {
        this("Assistant");
    }

    /**
     * @param agentName 合成 TOOL 消息使用的 name 字段
     */
    public PendingToolRecoveryHook(String agentName) {
        this.agentName = agentName != null ? agentName : "Assistant";
    }

    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreCallEvent pre)) return;
        Memory memory = pre.getMemory();
        if (memory == null) return;

        // 用户在 inputMessages 中自己提供了 ToolResult → 走 HITL resume 路径，跳过 auto-patch
        boolean userProvidedResults = pre.getInputMessages().stream()
                .anyMatch(m -> m.hasBlock(ToolResultBlock.class));
        if (userProvidedResults) return;

        healOrphanToolCalls(memory);
    }

    @Override
    public int priority() {
        // 数值越小越早执行（见 Hook.priority 文档）。本 hook 修复 memory，必须先于业务 hook 跑，
        // 让后续 hook 看到一致的 memory 状态。
        return -100;
    }

    /**
     * 扫描 memory，为最近一条 assistant 消息中没有对应 ToolResultBlock 的 ToolUseBlock 补合成结果。
     *
     * @return 补了多少条合成结果（0 表示无需修复）
     */
    int healOrphanToolCalls(Memory memory) {
        List<Msg> messages = memory.getMessages();
        if (messages.isEmpty()) return 0;

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

        Set<String> respondedIds = new HashSet<>();
        for (int i = lastAssistantIdx + 1; i < messages.size(); i++) {
            for (ToolResultBlock r : messages.get(i).getBlocks(ToolResultBlock.class)) {
                respondedIds.add(r.id());
            }
        }

        List<ToolUseBlock> orphans = new ArrayList<>();
        for (ToolUseBlock use : toolUses) {
            if (!respondedIds.contains(use.id())) orphans.add(use);
        }
        if (orphans.isEmpty()) return 0;

        List<ContentBlock> resultBlocks = new ArrayList<>();
        for (ToolUseBlock use : orphans) {
            resultBlocks.add(ToolResultBlock.error(
                    use.id(), use.name(),
                    "[已中断] 上一轮工具调用未完成，可能是用户中断或网络异常。工具：" + use.name()));
        }
        Msg synthetic = Msg.builder()
                .name(agentName)
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
