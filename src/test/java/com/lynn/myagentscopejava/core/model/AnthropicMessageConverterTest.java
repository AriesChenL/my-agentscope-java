package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicMessageConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessageConverter conv = new AnthropicMessageConverter(mapper);

    @Test
    void extractSystemMergesAllSystemMsgs() {
        List<Msg> msgs = List.of(
                Msg.system("You are helpful"),
                Msg.user("u", "hi"),
                Msg.system("Reply concisely")
        );
        assertEquals("You are helpful\n\nReply concisely", conv.extractSystem(msgs));
    }

    @Test
    void extractSystemReturnsNullWhenNone() {
        assertNull(conv.extractSystem(List.of(Msg.user("u", "hi"))));
    }

    @Test
    void systemMessagesAreFilteredFromConvert() {
        List<ObjectNode> out = conv.convert(List.of(
                Msg.system("ignored"),
                Msg.user("u", "hi")
        ));
        assertEquals(1, out.size());
        assertEquals("user", out.getFirst().path("role").asText());
    }

    @Test
    void userTextBecomesArrayOfTextBlocks() {
        List<ObjectNode> out = conv.convert(List.of(Msg.user("u", "hello")));
        ObjectNode m = out.getFirst();
        assertEquals("user", m.path("role").asText());
        assertEquals("text", m.path("content").get(0).path("type").asText());
        assertEquals("hello", m.path("content").get(0).path("text").asText());
    }

    @Test
    void assistantWithThinkingTextAndToolUseInOrder() {
        Msg m = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new ToolUseBlock("toolu_1", "search", Map.of("q", "x")),
                new TextBlock("here is the result"),
                new ThinkingBlock("let me think")
        )).build();
        ObjectNode out = conv.convert(List.of(m)).getFirst();
        assertEquals("assistant", out.path("role").asText());
        // 顺序应被规范化为：thinking → text → tool_use
        assertEquals("thinking", out.path("content").get(0).path("type").asText());
        assertEquals("let me think", out.path("content").get(0).path("thinking").asText());
        assertEquals("text", out.path("content").get(1).path("type").asText());
        assertEquals("tool_use", out.path("content").get(2).path("type").asText());
        assertEquals("toolu_1", out.path("content").get(2).path("id").asText());
        assertEquals("search", out.path("content").get(2).path("name").asText());
        assertEquals("x", out.path("content").get(2).path("input").path("q").asText());
    }

    @Test
    void toolMsgBecomesUserWithToolResultBlock() {
        Msg t = Msg.builder().name("a").role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.success("toolu_1", "the result")
        )).build();
        ObjectNode out = conv.convert(List.of(t)).getFirst();
        assertEquals("user", out.path("role").asText());
        assertEquals("tool_result", out.path("content").get(0).path("type").asText());
        assertEquals("toolu_1", out.path("content").get(0).path("tool_use_id").asText());
        assertEquals("the result", out.path("content").get(0).path("content").asText());
        // 成功结果不应有 is_error
        assertTrue(out.path("content").get(0).path("is_error").isMissingNode());
    }

    @Test
    void toolErrorMarksIsError() {
        Msg t = Msg.builder().name("a").role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.error("toolu_1", "boom")
        )).build();
        ObjectNode out = conv.convert(List.of(t)).getFirst();
        assertTrue(out.path("content").get(0).path("is_error").asBoolean());
    }

    @Test
    void emptyAssistantContentDropped() {
        Msg m = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new TextBlock(""),
                new ThinkingBlock("")
        )).build();
        assertEquals(0, conv.convert(List.of(m)).size());
    }

    @Test
    void consecutiveUserMessagesPassThroughUnmerged() {
        // 钉死 SummarizingCompactor 的设计前提：摘要 USER + 原 USER 两条相邻
        // converter 不去合并，由 Anthropic 服务端按文档自动合并成一个 turn
        // 文档原话："Consecutive user or assistant turns in your request will be combined into a single turn."
        List<Msg> msgs = List.of(
                Msg.user("summary", "<previous_conversation_summary>历史摘要</previous_conversation_summary>"),
                Msg.user("alice", "现在请回答：今天几号？")
        );
        List<ObjectNode> out = conv.convert(msgs);
        assertEquals(2, out.size(), "两条 USER 应原样保留，由服务端合并");
        assertEquals("user", out.get(0).path("role").asText());
        assertEquals("user", out.get(1).path("role").asText());
        assertTrue(out.get(0).path("content").get(0).path("text").asText().contains("摘要"));
        assertTrue(out.get(1).path("content").get(0).path("text").asText().contains("今天几号"));
    }
}
