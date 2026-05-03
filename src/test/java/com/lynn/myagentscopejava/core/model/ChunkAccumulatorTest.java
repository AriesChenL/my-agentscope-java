package com.lynn.myagentscopejava.core.model;

import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChunkAccumulatorTest {

    @Test
    void mergesTextDeltasInOrder() {
        ChunkAccumulator acc = new ChunkAccumulator();
        acc.accept(ChatChunk.text("Hel"));
        acc.accept(ChatChunk.text("lo, "));
        acc.accept(ChatChunk.text("world"));
        acc.accept(ChatChunk.finish("stop", ChatUsage.builder().inputTokens(5).outputTokens(3).build()));

        List<ContentBlock> blocks = acc.buildBlocks();
        assertEquals(1, blocks.size());
        assertEquals("Hello, world", ((TextBlock) blocks.getFirst()).text());
        assertNotNull(acc.usage());
        assertEquals(5, acc.usage().getInputTokens());
        assertEquals("stop", acc.finishReason());
    }

    @Test
    void buildsThinkingThenTextBlocks() {
        ChunkAccumulator acc = new ChunkAccumulator();
        acc.accept(ChatChunk.thinking("let me think... "));
        acc.accept(ChatChunk.thinking("done."));
        acc.accept(ChatChunk.text("answer is 42"));

        List<ContentBlock> blocks = acc.buildBlocks();
        assertEquals(2, blocks.size());
        assertInstanceOf(ThinkingBlock.class, blocks.getFirst());
        assertInstanceOf(TextBlock.class, blocks.get(1));
        assertEquals("let me think... done.", ((ThinkingBlock) blocks.getFirst()).thinking());
    }

    @Test
    void mergesStreamingToolCallByIndex() {
        ChunkAccumulator acc = new ChunkAccumulator();
        acc.accept(ChatChunk.toolCalls(List.of(
                new ToolCallDelta(0, "call_abc", "get_weather", "{\"city\":"))));
        acc.accept(ChatChunk.toolCalls(List.of(
                new ToolCallDelta(0, null, null, "\"Beijing\"}"))));
        acc.accept(ChatChunk.finish("tool_calls", ChatUsage.builder().build()));

        List<ContentBlock> blocks = acc.buildBlocks();
        assertEquals(1, blocks.size());
        ToolUseBlock tu = (ToolUseBlock) blocks.getFirst();
        assertEquals("call_abc", tu.id());
        assertEquals("get_weather", tu.name());
        assertEquals("Beijing", tu.input().get("city"));
    }
}
