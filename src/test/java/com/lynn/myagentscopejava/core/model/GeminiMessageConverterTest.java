package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiMessageConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiMessageConverter conv = new GeminiMessageConverter(mapper);

    @Test
    void extractSystemReturnsNullWhenNone() {
        assertNull(conv.extractSystem(List.of(Msg.user("u", "hi"))));
    }

    @Test
    void systemFilteredFromContents() {
        List<ObjectNode> out = conv.convert(List.of(
                Msg.system("ignored"),
                Msg.user("u", "hi")
        ));
        assertEquals(1, out.size());
        assertEquals("user", out.getFirst().path("role").asText());
    }

    @Test
    void userTextBecomesPart() {
        ObjectNode out = conv.convert(List.of(Msg.user("u", "hello"))).getFirst();
        assertEquals("user", out.path("role").asText());
        assertEquals("hello", out.path("parts").get(0).path("text").asText());
    }

    @Test
    void assistantRoleIsModelNotAssistant() {
        Msg m = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new TextBlock("hello")
        )).build();
        ObjectNode out = conv.convert(List.of(m)).getFirst();
        assertEquals("model", out.path("role").asText());
    }

    @Test
    void assistantToolUseBecomesFunctionCall() {
        Msg m = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new ToolUseBlock("call_1", "search", Map.of("q", "java"))
        )).build();
        ObjectNode out = conv.convert(List.of(m)).getFirst();
        assertEquals("model", out.path("role").asText());
        ObjectNode fc = (ObjectNode) out.path("parts").get(0).path("functionCall");
        assertEquals("search", fc.path("name").asText());
        assertEquals("java", fc.path("args").path("q").asText());
    }

    @Test
    void toolResultBecomesFunctionResponse() {
        Msg t = Msg.builder().name("a").role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.success("call_1", "found 5 results")
        )).build();
        ObjectNode out = conv.convert(List.of(t)).getFirst();
        assertEquals("function", out.path("role").asText());
        ObjectNode fr = (ObjectNode) out.path("parts").get(0).path("functionResponse");
        assertEquals("call_1", fr.path("name").asText());
        assertEquals("found 5 results", fr.path("response").path("result").asText());
    }

    @Test
    void toolErrorGoesIntoErrorField() {
        Msg t = Msg.builder().name("a").role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.error("call_1", "boom")
        )).build();
        ObjectNode out = conv.convert(List.of(t)).getFirst();
        ObjectNode resp = (ObjectNode) out.path("parts").get(0).path("functionResponse").path("response");
        assertEquals("boom", resp.path("error").asText());
    }

    @Test
    void consecutiveUserMessagesPassThroughUnmerged() {
        // 钉死 SummarizingCompactor 的设计前提：摘要 USER + 原 USER 两条相邻
        // Gemini 协议允许连续相同 role；converter 不去合并
        List<Msg> msgs = List.of(
                Msg.user("summary", "<previous_conversation_summary>历史摘要</previous_conversation_summary>"),
                Msg.user("alice", "现在请回答：今天几号？")
        );
        List<ObjectNode> out = conv.convert(msgs);
        assertEquals(2, out.size(), "两条 USER 应原样保留");
        assertEquals("user", out.get(0).path("role").asText());
        assertEquals("user", out.get(1).path("role").asText());
        assertTrue(out.get(0).path("parts").get(0).path("text").asText().contains("摘要"));
        assertTrue(out.get(1).path("parts").get(0).path("text").asText().contains("今天几号"));
    }

    @Test
    void functionResponseNameUsesToolNameWhenProvided() {
        // 关键：Gemini cachedContents 严格校验 functionResponse.name 必须等于 functionCall.name
        // ToolResultBlock 携带 name 时，应该写工具名而不是 id
        Msg t = Msg.builder().name("a").role(MsgRole.TOOL).content(List.of(
                ToolResultBlock.success("call_xyz", "currentTime", "2026-05-02 19:54")
        )).build();
        ObjectNode out = conv.convert(List.of(t)).getFirst();
        ObjectNode fr = (ObjectNode) out.path("parts").get(0).path("functionResponse");
        assertEquals("currentTime", fr.path("name").asText(),
                "functionResponse.name 必须用工具名，不能用 id");
    }
}
