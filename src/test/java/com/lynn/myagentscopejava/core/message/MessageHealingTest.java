package com.lynn.myagentscopejava.core.message;

import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHealingTest {

    @Test
    void noopWhenLastAssistantHasNoToolCalls() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.user("u", "hi"));
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(new TextBlock("hello"))).build());
        int healed = MessageHealing.healOrphanToolCalls(mem, "bot");
        assertEquals(0, healed);
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
        int healed = MessageHealing.healOrphanToolCalls(mem, "bot");
        assertEquals(0, healed);
    }

    @Test
    void patchesAllOrphanToolCalls() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.user("u", "do it"));
        ToolUseBlock t1 = new ToolUseBlock("call_1", "fetch", Map.of("url", "x"));
        ToolUseBlock t2 = new ToolUseBlock("call_2", "search", Map.of("q", "y"));
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(t1, t2)).build());

        int healed = MessageHealing.healOrphanToolCalls(mem, "bot");
        assertEquals(2, healed);
        // 应该新增一条 TOOL 消息，包含 2 个 error ToolResultBlock
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
        // 只有 call_2 收到了真实结果
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.success("call_2", "done"))).build());

        int healed = MessageHealing.healOrphanToolCalls(mem, "bot");
        assertEquals(2, healed);
        Msg added = mem.getMessages().getLast();
        List<ToolResultBlock> results = added.getBlocks(ToolResultBlock.class);
        assertEquals(2, results.size());
        // 应该是 call_1 和 call_3
        assertEquals("call_1", results.get(0).id());
        assertEquals("call_3", results.get(1).id());
    }

    @Test
    void handlesEmptyMemoryGracefully() {
        Memory mem = new InMemoryMemory();
        assertEquals(0, MessageHealing.healOrphanToolCalls(mem, "bot"));
        assertEquals(0, MessageHealing.healOrphanToolCalls(null, "bot"));
    }

    @Test
    void syntheticResultMentionsToolName() {
        Memory mem = new InMemoryMemory();
        mem.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(List.of(new ToolUseBlock("c1", "web_fetch", Map.of()))).build());
        MessageHealing.healOrphanToolCalls(mem, "bot");
        ToolResultBlock r = mem.getMessages().getLast().getBlocks(ToolResultBlock.class).getFirst();
        assertNotNull(r);
        assertTrue(r.output().contains("web_fetch"), "应在错误信息里提到工具名: " + r.output());
        assertTrue(r.output().contains("已中断"));
    }
}
