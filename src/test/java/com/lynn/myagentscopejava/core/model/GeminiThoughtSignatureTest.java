package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 Gemini thinking 模型（gemini-3-flash-preview / 2.5-*）的 thoughtSignature 透传：
 * 服务端给 functionCall 配的 thoughtSignature 必须原样回写，否则下一轮请求 400。
 */
class GeminiThoughtSignatureTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiChatModel model = GeminiChatModel.builder()
            .modelName("gemini-3-flash-preview").apiKey("fake")
            .build();
    private final GeminiMessageConverter converter = new GeminiMessageConverter(mapper);

    @Test
    void parseEventCapturesThoughtSignatureOnFunctionCall() {
        // 真实截图里的事件结构：functionCall 与 thoughtSignature 是 part 的平级字段
        String event = """
                {"candidates":[{"content":{"parts":[{
                    "functionCall":{"name":"add","args":{"a":12,"b":34}},
                    "thoughtSignature":"EroDC-FAKE-SIG"
                }],"role":"model"},"index":0}]}
                """.replaceAll("\\s+", " ");

        List<ChatChunk> chunks = model.parseEvent(event, new AtomicInteger(0));
        ChatChunk toolChunk = chunks.stream()
                .filter(c -> !c.toolCallDeltas().isEmpty())
                .findFirst().orElseThrow();
        ToolCallDelta delta = toolChunk.toolCallDeltas().getFirst();
        assertEquals("add", delta.nameDelta());
        assertEquals("EroDC-FAKE-SIG", delta.providerSignature(),
                "应从 part.thoughtSignature 抓出来放进 ToolCallDelta");
    }

    @Test
    void chunkAccumulatorPreservesSignatureToToolUseBlock() {
        ChunkAccumulator acc = new ChunkAccumulator();
        acc.accept(ChatChunk.toolCalls(List.of(
                new ToolCallDelta(0, "id1", "add", "{\"a\":1,\"b\":2}", "MY-SIG"))));
        acc.accept(ChatChunk.finish("stop", null));
        ToolUseBlock tu = (ToolUseBlock) acc.buildBlocks().stream()
                .filter(b -> b instanceof ToolUseBlock).findFirst().orElseThrow();
        assertEquals("MY-SIG", tu.providerSignature());
    }

    @Test
    void converterEchoesSignatureBackOnAssistantToolUse() {
        Msg assistant = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new ToolUseBlock("id1", "add", Map.of("a", 12, "b", 34), "ECHO-SIG")
        )).build();
        ObjectNode out = converter.convert(List.of(assistant)).getFirst();
        ObjectNode part = (ObjectNode) out.path("parts").get(0);
        // functionCall 应在
        assertNotNull(part.path("functionCall").path("name").asText(null));
        // 关键：thoughtSignature 必须回写
        assertEquals("ECHO-SIG", part.path("thoughtSignature").asText(null));
    }

    @Test
    void noSignatureMeansNoThoughtSignatureField() {
        // 老路径（OpenAI 来的工具，没 signature）：不应在 Gemini 请求里生成 thoughtSignature 字段
        Msg assistant = Msg.builder().name("a").role(MsgRole.ASSISTANT).content(List.of(
                new ToolUseBlock("id1", "add", Map.of("a", 1, "b", 2))
        )).build();
        ObjectNode out = converter.convert(List.of(assistant)).getFirst();
        ObjectNode part = (ObjectNode) out.path("parts").get(0);
        assertTrue(part.path("thoughtSignature").isMissingNode(),
                "无 signature 时不应输出 thoughtSignature 字段");
    }

    @Test
    void backwardCompatThreeArgConstructorStillWorks() {
        // 旧 3 参构造器应能继续用，signature 默认 null
        ToolUseBlock tu = new ToolUseBlock("id1", "search", Map.of("q", "x"));
        assertEquals(null, tu.providerSignature());
        ToolCallDelta d = new ToolCallDelta(0, "id1", "search", "{}");
        assertEquals(null, d.providerSignature());
    }
}
