package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
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
import com.lynn.myagentscopejava.core.cluster.impl.LocalNotificationBus;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ToolConfirmationHook}：危险工具被拦截 → 不实际执行 → 进入 HITL 挂起。
 */
class ToolConfirmationHookTest {

    /** 按预设脚本逐轮返回 chunk 序列；与 ScriptedModel 同构。 */
    static class ScriptedModel implements ChatModel {
        final List<List<ChatChunk>> turns;
        int turn = 0;
        ScriptedModel(List<List<ChatChunk>> turns) { this.turns = turns; }
        @Override public String getModelName() { return "scripted"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            return Flux.fromIterable(turns.get(turn++));
        }
    }

    /** 计数 deduct 真正执行的次数；用于验证危险工具被拦截时不应被调用。 */
    public static class CountingPayment {
        public final AtomicInteger callCount = new AtomicInteger();

        @Tool(description = "扣款工具")
        public String deduct(@ToolParam(description = "金额") int amount) {
            callCount.incrementAndGet();
            return "ok " + amount;
        }
    }

    @Test
    void dangerousToolIsSuspendedWithoutExecution() {
        // turn 0：模型请求调用 deduct(500)
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        CountingPayment payment = new CountingPayment();
        Toolkit kit = new Toolkit().registerObject(payment);
        ToolConfirmationHook hook = new ToolConfirmationHook(Set.of("deduct"));
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(kit).hook(hook).build();

        agent.call(Msg.user("user", "扣 500"));

        // 工具被 hook 短路，根本没执行
        assertEquals(0, payment.callCount.get());
        // agent 应当处于挂起状态
        assertTrue(agent.isAwaitingHumanInput());
        // pending 列表能取到该次调用
        List<ToolUseBlock> pending = agent.getPendingToolUses();
        assertEquals(1, pending.size());
        assertEquals("deduct", pending.getFirst().name());
        // 末尾 TOOL 消息含 pending 标记的 ToolResultBlock，且 output 为审批文案
        Msg last = agent.getMemory().getMessages().getLast();
        assertEquals(MsgRole.TOOL, last.getRole());
        ToolResultBlock r = last.getBlocks(ToolResultBlock.class).getFirst();
        assertTrue(r.pending());
        assertFalse(r.isError());
        assertTrue(r.output().contains("人工审批"));
    }

    @Test
    void nonDangerousToolPassesThrough() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        List<ChatChunk> turn2 = List.of(
                ChatChunk.text("done"),
                ChatChunk.finish("stop", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1, turn2));
        CountingPayment payment = new CountingPayment();
        Toolkit kit = new Toolkit().registerObject(payment);
        // 把别的工具名加进去，但不包含 deduct → deduct 应正常执行
        ToolConfirmationHook hook = new ToolConfirmationHook(Set.of("rm_rf"));
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(kit).hook(hook).build();

        Msg reply = agent.call(Msg.user("user", "扣 500"));

        assertEquals(1, payment.callCount.get());
        assertFalse(agent.isAwaitingHumanInput());
        assertEquals("done", reply.getText());
    }

    @Test
    void dynamicallyAddedDangerousToolTakesEffect() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(
                        new ToolCallDelta(0, "call_1", "deduct", "{\"amount\":500}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
        ScriptedModel model = new ScriptedModel(List.of(turn1));
        CountingPayment payment = new CountingPayment();
        Toolkit kit = new Toolkit().registerObject(payment);
        ToolConfirmationHook hook = new ToolConfirmationHook();
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(kit).hook(hook).build();

        // 运行时动态加入 deduct
        hook.addDangerousTool("deduct");
        assertTrue(hook.isDangerous("deduct"));

        agent.call(Msg.user("user", "扣 500"));
        assertEquals(0, payment.callCount.get());
        assertTrue(agent.isAwaitingHumanInput());
    }

    @Test
    void crossNodeBroadcastSyncsDangerousTools() {
        // 模拟两节点：共享同一 LocalNotificationBus（单进程内的 LocalBus 等价于 Redis bus 的语义）
        LocalNotificationBus bus = new LocalNotificationBus();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        ToolConfirmationHook nodeA = new ToolConfirmationHook(Set.of(), bus, mapper);
        ToolConfirmationHook nodeB = new ToolConfirmationHook(Set.of(), bus, mapper);

        assertTrue(nodeA.getDangerousTools().isEmpty());
        assertTrue(nodeB.getDangerousTools().isEmpty());

        // Node A 改清单 + 广播
        nodeA.setDangerousToolsAndBroadcast(Set.of("deduct", "delete_file"));

        // Node A 立即生效
        assertTrue(nodeA.isDangerous("deduct"));
        assertTrue(nodeA.isDangerous("delete_file"));

        // Node B 通过订阅接收同步
        assertTrue(nodeB.isDangerous("deduct"));
        assertTrue(nodeB.isDangerous("delete_file"));

        // Node B 改 → Node A 同步
        nodeB.setDangerousToolsAndBroadcast(Set.of("transfer_money"));
        assertFalse(nodeA.isDangerous("deduct"));
        assertTrue(nodeA.isDangerous("transfer_money"));

        nodeA.close();
        nodeB.close();
    }

    @Test
    void closeReleasesSubscription() {
        LocalNotificationBus bus = new LocalNotificationBus();
        ToolConfirmationHook hook = new ToolConfirmationHook(Set.of(), bus, null);
        assertEquals(1, bus.subscriberCount(ToolConfirmationHook.CONFIG_CHANNEL));
        hook.close();
        assertEquals(0, bus.subscriberCount(ToolConfirmationHook.CONFIG_CHANNEL));
    }

    @Test
    void singleNodeNoBusFallback() {
        // 老构造（无 bus）应当依然工作，setDangerousToolsAndBroadcast 退化为本地修改
        ToolConfirmationHook hook = new ToolConfirmationHook(Set.of("a"));
        hook.setDangerousToolsAndBroadcast(Set.of("b", "c"));
        assertFalse(hook.isDangerous("a"));
        assertTrue(hook.isDangerous("b"));
        assertTrue(hook.isDangerous("c"));
    }
}
