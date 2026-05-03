package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatChunk;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatUsage;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactingMemoryTest {

    /** 假摘要模型：每次返回固定字符串，方便测试断言。 */
    static class FakeSummarizer implements ChatModel {
        final AtomicInteger callCount = new AtomicInteger();
        @Override public String getModelName() { return "fake-summarizer"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            callCount.incrementAndGet();
            return Flux.just(
                    ChatChunk.text("用户和助手在前面聊了若干轮"),
                    ChatChunk.finish("stop", ChatUsage.builder().build()));
        }
    }

    @Test
    void belowThresholdDoesNotCompact() {
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = new SummarizingCompactor(summarizer, 5, 2);
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        for (int i = 0; i < 5; i++) {
            memory.addMessage(Msg.user("u", "msg-" + i));
        }
        assertEquals(5, memory.getMessages().size());
        assertEquals(0, memory.getCompactionCount());
        assertEquals(0, summarizer.callCount.get());
    }

    @Test
    void crossingThresholdTriggersCompaction() {
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = new SummarizingCompactor(summarizer, 4, 2);
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        // 加入 5 条 USER 消息：触发阈值 4 的压缩
        for (int i = 0; i < 5; i++) {
            memory.addMessage(Msg.user("u", "msg-" + i));
        }
        // 压缩后：1 条 USER(摘要) + 至少 2 条原 USER；连续 USER 在 Anthropic 上会被自动合并，OK
        List<Msg> after = memory.getMessages();
        assertTrue(after.size() < 5, "应当被压缩，实际：" + after.size());
        assertEquals(MsgRole.USER, after.getFirst().getRole());
        assertTrue(after.getFirst().getText().contains("[历史摘要]"));
        // 摘要 LLM 被调用过
        assertEquals(1, summarizer.callCount.get());
        assertEquals(1, memory.getCompactionCount());
    }

    @Test
    void compactionPreservesToolCallAlignment() {
        // 构造一个含 tool 调用对的历史，确保压缩后 ASSISTANT(toolUse) → TOOL 不会被切断
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = new SummarizingCompactor(summarizer, 6, 3);
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        memory.addMessage(Msg.user("u", "u1"));
        memory.addMessage(Msg.assistant("bot", "a1"));
        memory.addMessage(Msg.user("u", "u2"));
        memory.addMessage(Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("c1", "x", Map.of())).build());
        memory.addMessage(Msg.builder().name("bot").role(MsgRole.TOOL)
                .content(ToolResultBlock.success("c1", "ok")).build());
        memory.addMessage(Msg.assistant("bot", "a2"));
        memory.addMessage(Msg.user("u", "u3"));  // 这条触发压缩

        List<Msg> after = memory.getMessages();
        // 结构：USER(摘要) + 原 toKeep（toKeep 第一条必须是 USER 才能保 tool 对齐）
        assertEquals(MsgRole.USER, after.getFirst().getRole());
        assertEquals(MsgRole.USER, after.get(1).getRole(),
                "toKeep 第一条必须是 USER，否则破坏 ASSISTANT(toolUse) → TOOL 对齐");
    }

    @Test
    void multipleCompactionsAccumulate() {
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = new SummarizingCompactor(summarizer, 3, 1);
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        // 持续加消息，应触发多次压缩
        for (int i = 0; i < 12; i++) {
            memory.addMessage(Msg.user("u", "msg-" + i));
        }
        assertTrue(memory.getCompactionCount() >= 2,
                "12 条消息按 trigger=3 应触发多次压缩，实际：" + memory.getCompactionCount());
    }

    @Test
    void clearResetsState() {
        SummarizingCompactor c = new SummarizingCompactor(new FakeSummarizer(), 3, 1);
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);
        memory.addMessage(Msg.user("u", "x"));
        memory.clear();
        assertEquals(0, memory.getMessages().size());
    }

    @Test
    void tokenBasedTriggerFiresOnLargeMessages() {
        // 阈值 200 token，加几条短消息（每条 ~10 token）不触发；加一条 600 字符（~215 token）应触发
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = SummarizingCompactor.builder()
                .summarizer(summarizer)
                .maxTokens(200)
                .keepRecent(2)
                .build();
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        memory.addMessage(Msg.user("u", "短消息1"));
        memory.addMessage(Msg.assistant("bot", "短消息2"));
        assertEquals(0, memory.getCompactionCount(), "短消息不该触发");

        memory.addMessage(Msg.user("u", "x".repeat(600))); // 一条就超阈值
        assertEquals(1, memory.getCompactionCount(), "单条 600 字符应触发");
    }

    @Test
    void tokenAndMessageTriggersAreOr() {
        // 同时设置两个阈值，谁先到谁先触发
        FakeSummarizer summarizer = new FakeSummarizer();
        SummarizingCompactor c = SummarizingCompactor.builder()
                .summarizer(summarizer)
                .maxTokens(100_000) // token 阈值很大不会触发
                .maxMessages(3)     // 消息数阈值小，先触发
                .keepRecent(1)
                .build();
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), c);

        memory.addMessage(Msg.user("u", "a"));
        memory.addMessage(Msg.user("u", "b"));
        memory.addMessage(Msg.user("u", "c"));
        memory.addMessage(Msg.user("u", "d")); // 第 4 条触发
        assertEquals(1, memory.getCompactionCount());
    }

    @Test
    void builderRejectsZeroTriggers() {
        // 两个 trigger 都不设 → 抛异常
        assertThrows(IllegalArgumentException.class,
                () -> SummarizingCompactor.builder()
                        .summarizer(new FakeSummarizer())
                        .keepRecent(2).build());
    }

    @Test
    void compactingMemoryStillSupportsSaveLoad() {
        // CompactingMemory 通过 Memory 接口的默认 saveTo/loadFrom 应该工作
        SummarizingCompactor c = new SummarizingCompactor(new FakeSummarizer(), 100, 10);
        CompactingMemory m1 = new CompactingMemory(new InMemoryMemory(), c);
        m1.addMessage(Msg.user("u", "hello"));
        m1.addMessage(Msg.assistant("bot", "hi"));

        com.lynn.myagentscopejava.core.session.Session session =
                new com.lynn.myagentscopejava.core.session.InMemorySession();
        com.lynn.myagentscopejava.core.session.SessionKey key =
                com.lynn.myagentscopejava.core.session.SessionKey.of("test");
        m1.saveTo(session, key);

        CompactingMemory m2 = new CompactingMemory(new InMemoryMemory(), c);
        m2.loadFrom(session, key);

        assertEquals(2, m2.getMessages().size());
        assertEquals("hello", m2.getMessages().getFirst().getText());
        assertFalse(m2.getMessages().getFirst().getText().startsWith("[历史摘要]"));
    }
}
