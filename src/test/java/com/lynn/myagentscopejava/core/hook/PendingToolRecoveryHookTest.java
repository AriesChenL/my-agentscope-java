package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link PendingToolRecoveryHook}：在 {@link PreCallEvent} 时机扫 memory，
 * 给孤儿 tool_calls 补合成结果。
 */
class PendingToolRecoveryHookTest {

    private static void fire(PendingToolRecoveryHook hook, Memory memory, List<Msg> input) {
        hook.onEvent(new PreCallEvent(null, memory, input));
    }

    @Test
    void noopWhenLastAssistantHasNoToolCalls() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.user("u", "hi"));
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(new TextBlock("hello"))).build());
        fire(new PendingToolRecoveryHook("bot"), mem, List.of());
        assertEquals(2, mem.getMessages().size());
    }

    @Test
    void noopWhenAllToolCallsAlreadyResponded() {
        Memory mem = new InMemoryMemory();
        ToolUseBlock t1 = new ToolUseBlock("call_1", "search", Map.of());
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(t1)).build());
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.success("call_1", "ok"))).build());
        fire(new PendingToolRecoveryHook("bot"), mem, List.of());
        assertEquals(2, mem.getMessages().size()); // 没有新增
    }

    @Test
    void patchesAllOrphanToolCalls() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.user("u", "do it"));
        ToolUseBlock t1 = new ToolUseBlock("call_1", "fetch", Map.of("url", "x"));
        ToolUseBlock t2 = new ToolUseBlock("call_2", "search", Map.of("q", "y"));
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(t1, t2)).build());

        fire(new PendingToolRecoveryHook("bot"), mem, List.of());

        Msg added = mem.getMessages().getLast();
        assertEquals(MsgRole.TOOL, added.getRole());
        List<ToolResultBlock> results = added.getBlocks(ToolResultBlock.class);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ToolResultBlock::isError));
        assertEquals("call_1", results.get(0).id());
        assertEquals("call_2", results.get(1).id());
    }

    @Test
    void patchesOnlyMissingOnesWhenSomeResponded() {
        Memory mem = new InMemoryMemory();
        ToolUseBlock t1 = new ToolUseBlock("call_1", "a", Map.of());
        ToolUseBlock t2 = new ToolUseBlock("call_2", "b", Map.of());
        ToolUseBlock t3 = new ToolUseBlock("call_3", "c", Map.of());
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(t1, t2, t3)).build());
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.success("call_2", "done"))).build());

        fire(new PendingToolRecoveryHook("bot"), mem, List.of());

        Msg added = mem.getMessages().getLast();
        List<ToolResultBlock> results = added.getBlocks(ToolResultBlock.class);
        assertEquals(2, results.size());
        assertEquals("call_1", results.get(0).id());
        assertEquals("call_3", results.get(1).id());
    }

    @Test
    void handlesEmptyMemoryGracefully() {
        Memory mem = new InMemoryMemory();
        fire(new PendingToolRecoveryHook("bot"), mem, List.of());
        assertEquals(0, mem.getMessages().size());
    }

    @Test
    void syntheticResultMentionsToolName() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(new ToolUseBlock("c1", "web_fetch", Map.of()))).build());
        fire(new PendingToolRecoveryHook("bot"), mem, List.of());
        ToolResultBlock r = mem.getMessages().getLast().getBlocks(ToolResultBlock.class).getFirst();
        assertNotNull(r);
        assertTrue(r.output().contains("web_fetch"), "应在错误信息里提到工具名: " + r.output());
        assertTrue(r.output().contains("已中断"));
    }

    @Test
    void skipsWhenInputContainsToolResult() {
        // HITL resume 场景：用户主动提供了 ToolResult，hook 应跳过避免重复补救
        Memory mem = new InMemoryMemory();
        ToolUseBlock t1 = new ToolUseBlock("call_1", "ask_user", Map.of());
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(t1)).build());
        // memory 里有孤儿 tool_call call_1，但用户带着结果来了
        Msg userResume = Msg.builder().role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.success("call_1", "ask_user", "上海")))
                .build();

        fire(new PendingToolRecoveryHook("bot"), mem, List.of(userResume));

        // hook 没有补合成消息（memory 还是 1 条）
        assertEquals(1, mem.getMessages().size());
    }

    @Test
    void priorityIsLowEnoughToRunFirst() {
        // 数值越小越早执行，必须 < 0（业务 hook 默认 0），保证修复先于业务 hook 看到 memory
        assertTrue(new PendingToolRecoveryHook().priority() < 0);
    }
}
