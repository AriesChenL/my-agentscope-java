package com.lynn.myagentscopejava.core.message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsgTest {

    @Test
    void textHelperWrapsInTextBlock() {
        Msg m = Msg.user("user", "hello");
        assertEquals(1, m.getContent().size());
        assertTrue(m.getContent().getFirst() instanceof TextBlock);
        assertEquals("hello", m.getText());
    }

    @Test
    void multipleBlocksInSingleMessage() {
        Msg m = Msg.builder()
                .name("bot").role(MsgRole.ASSISTANT)
                .content(
                        new ThinkingBlock("let me think..."),
                        new TextBlock("here's my answer:"),
                        new ToolUseBlock("call_1", "get_weather", Map.of("city", "Beijing"))
                ).build();

        assertEquals(3, m.getContent().size());
        assertEquals("here's my answer:", m.getText());
        assertEquals(1, m.getBlocks(ToolUseBlock.class).size());
        assertTrue(m.hasBlock(ThinkingBlock.class));
        assertFalse(m.hasBlock(ToolResultBlock.class));
    }

    @Test
    void toolUseBlockRequiresIdAndName() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolUseBlock(null, "x", Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolUseBlock("id", "", Map.of()));
    }

    @Test
    void toolResultBlockFactories() {
        ToolResultBlock ok = ToolResultBlock.success("call_1", "42");
        ToolResultBlock err = ToolResultBlock.error("call_2", "boom");
        assertFalse(ok.isError());
        assertTrue(err.isError());
        assertEquals("42", ok.output());
    }
}
