package com.lynn.myagentscopejava.core.agent;

import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatChunk;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatUsage;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.model.ToolCallDelta;
import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import com.lynn.myagentscopejava.core.tool.ToolSuspendException;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL（Human-In-The-Loop）相关测试：工具挂起、agent 状态查询、用户提供结果恢复执行。
 */
class HitlTest {

    /** 按预设脚本逐轮返回 chunk 序列。 */
    static class ScriptedModel implements ChatModel {
        final List<List<ChatChunk>> turns;
        int turn = 0;
        final List<List<Msg>> seenMessages = new ArrayList<>();

        ScriptedModel(List<List<ChatChunk>> turns) { this.turns = turns; }

        @Override public String getModelName() { return "scripted"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            seenMessages.add(List.copyOf(messages));
            return Flux.fromIterable(turns.get(turn++));
        }
    }

    /** 总是抛 ToolSuspendException 的工具，模拟"需要人工执行"的场景。 */
    public static class ApprovalRequired {
        @Tool(description = "执行扣款，必须人工审批")
        public String deduct(@ToolParam(description = "金额") int amount) {
            throw new ToolSuspendException("金额 " + amount + " 元需要人工审批");
        }
    }

    @Test
    void toolSuspensionEntersAwaitingState() {
        // turn 0：模型请求调用 deduct(500)
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "请扣款 500 元"));

        // 仅调用了一次模型，因为工具挂起后立即返回，不再 reasoning
        assertEquals(1, model.turn);
        assertTrue(agent.isAwaitingHumanInput());
        // pending 工具调用可被列出
        List<ToolUseBlock> pending = agent.getPendingToolUses();
        assertEquals(1, pending.size());
        assertEquals("deduct", pending.getFirst().name());
        assertEquals(500, pending.getFirst().input().get("amount"));

        // memory 中最后一条是 TOOL 消息，含 pending 结果
        Msg last = agent.getMemory().getMessages().getLast();
        assertEquals(MsgRole.TOOL, last.getRole());
        ToolResultBlock r = last.getBlocks(ToolResultBlock.class).getFirst();
        assertTrue(r.pending());
        assertFalse(r.isError());
        assertTrue(r.output().contains("人工审批"));
    }

    @Test
    void resumeWithHumanProvidedResult() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        // turn 1：恢复后的 reasoning，模型给出最终答案
        List<ChatChunk> turn2 = List.of(
                ChatChunk.text("已为您扣款 500 元，审批通过。"),
                ChatChunk.finish("stop", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1, turn2));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "请扣款 500 元"));
        assertTrue(agent.isAwaitingHumanInput());

        // 用户外部审批通过后，把真实结果以 TOOL 消息回传
        Msg humanResult = Msg.builder()
                .name("approver").role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_1", "审批通过，扣款成功"))
                .build();
        Msg finalReply = agent.call(humanResult);

        assertEquals("已为您扣款 500 元，审批通过。", finalReply.getText());
        assertFalse(agent.isAwaitingHumanInput());
        // memory 中已经没有 pending 项
        boolean anyPending = agent.getMemory().getMessages().stream()
                .flatMap(m -> m.getBlocks(ToolResultBlock.class).stream())
                .anyMatch(ToolResultBlock::pending);
        assertFalse(anyPending);
    }

    @Test
    void resumeRejectsWrongMessageRole() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "请扣款 500 元"));

        // 挂起状态下传一条普通 USER 消息，应被拒绝
        assertThrows(IllegalStateException.class,
                () -> agent.call(Msg.user("user", "再说点别的")));
    }

    @Test
    void resumeAcceptsPartialResults_keepsPendingForRest() {
        // 模型一次发起两个 tool call：deduct(100) + deduct(200)
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_a", "deduct", "{\"amount\":100}"),
                        new ToolCallDelta(1, "call_b", "deduct", "{\"amount\":200}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "扣两笔款"));
        assertEquals(2, agent.getPendingToolUses().size());

        // 只提供其中一个 → 允许，剩下的 pending 保留
        Msg partial = Msg.builder().name("approver").role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_a", "ok")).build();
        agent.call(partial);

        // 模型并未被再次调用（只跑过 turn 0）
        assertEquals(1, model.turn);
        // 仍处于挂起态
        assertTrue(agent.isAwaitingHumanInput());
        // pending 列表只剩 call_b
        List<ToolUseBlock> pending = agent.getPendingToolUses();
        assertEquals(1, pending.size());
        assertEquals("call_b", pending.getFirst().id());
    }

    @Test
    void resumePartialThenComplete_resumesReasoning() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_a", "deduct", "{\"amount\":100}"),
                        new ToolCallDelta(1, "call_b", "deduct", "{\"amount\":200}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        List<ChatChunk> turn2 = List.of(
                ChatChunk.text("两笔都已处理。"),
                ChatChunk.finish("stop", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1, turn2));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "扣两笔款"));

        // 第一次只填 a
        agent.call(Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_a", "ok-a")).build());
        assertTrue(agent.isAwaitingHumanInput());

        // 第二次填 b → 满齐 → 进 reasoning
        Msg reply = agent.call(Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_b", "ok-b")).build());

        assertEquals("两笔都已处理。", reply.getText());
        assertFalse(agent.isAwaitingHumanInput());
        assertEquals(2, model.turn);
    }

    @Test
    void resumeRejectsDuplicateIds() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_a", "deduct", "{\"amount\":100}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "扣款"));

        Msg dup = Msg.builder().role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.success("call_a", "ok"),
                ToolResultBlock.success("call_a", "ok again")
        )).build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> agent.call(dup));
        assertTrue(ex.getMessage().contains("重复"));
        assertTrue(ex.getMessage().contains("call_a"));
    }

    @Test
    void resumeRejectsInvalidIds() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_a", "deduct", "{\"amount\":100}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "扣款"));

        Msg invalid = Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_unknown", "ok")).build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> agent.call(invalid));
        assertTrue(ex.getMessage().contains("call_unknown"));
        assertTrue(ex.getMessage().contains("不在 pending 集合"));
    }

    @Test
    void resumeRejectsPartialWithTextContent() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_a", "deduct", "{\"amount\":100}"),
                        new ToolCallDelta(1, "call_b", "deduct", "{\"amount\":200}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).toolkit(kit).build();

        agent.call(Msg.user("user", "扣两笔款"));

        // 部分提供 + 同时混入 TextBlock → 拒绝
        Msg partialWithText = Msg.builder().role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.success("call_a", "ok"),
                new com.lynn.myagentscopejava.core.message.TextBlock("我先批准 a")
        )).build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> agent.call(partialWithText));
        assertTrue(ex.getMessage().contains("不允许混入"));
    }
}
