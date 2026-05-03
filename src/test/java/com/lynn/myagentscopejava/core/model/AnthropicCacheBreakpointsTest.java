package com.lynn.myagentscopejava.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 Anthropic 三个固定缓存断点的位置：
 * <ol>
 *   <li>system 块尾</li>
 *   <li>tools 数组最后一个工具尾</li>
 *   <li>messages 最后一条消息的最后一个 content block 尾</li>
 * </ol>
 */
class AnthropicCacheBreakpointsTest {

    private final AnthropicChatModel model = AnthropicChatModel.builder()
            .modelName("claude-test").apiKey("fake")
            .enablePromptCache(true)
            .build();

    private static final ToolSchema TOOL_A = new ToolSchema(
            "search", "search the web", Map.of("type", "object"));
    private static final ToolSchema TOOL_B = new ToolSchema(
            "calc", "calculator", Map.of("type", "object"));

    @Test
    void allThreeBreakpointsPresentWhenSystemToolsAndMessagesAllExist() throws Exception {
        List<Msg> messages = List.of(
                Msg.system("You are helpful"),
                Msg.user("u", "hi there")
        );
        ObjectNode body = model.buildBody(messages, List.of(TOOL_A, TOOL_B), null);

        // 断点 #1：system 块尾
        JsonNode sys = body.path("system");
        assertTrue(sys.isArray() && !sys.isEmpty(), "system 应为数组形式");
        assertCacheControl(sys.get(sys.size() - 1), "断点 #1 应在 system 块上");

        // 断点 #2：tools 数组最后一个工具
        JsonNode tools = body.path("tools");
        assertEquals(2, tools.size());
        assertNoCacheControl(tools.get(0), "倒数第二个 tool 不应该有 cache_control");
        assertCacheControl(tools.get(1), "断点 #2 应在最后一个 tool 上");

        // 断点 #3：messages 最后一条消息的最后一个 content block
        JsonNode msgs = body.path("messages");
        assertTrue(msgs.isArray() && !msgs.isEmpty());
        JsonNode lastMsg = msgs.get(msgs.size() - 1);
        JsonNode content = lastMsg.path("content");
        assertTrue(content.isArray() && !content.isEmpty());
        assertCacheControl(content.get(content.size() - 1), "断点 #3 应在最后一条消息最后一个 block");
    }

    @Test
    void historicalMessageBlocksHaveNoCacheControl() throws Exception {
        // 多轮对话场景：只有最后一条消息会被标 cache_control
        List<Msg> messages = List.of(
                Msg.system("sys"),
                Msg.user("u", "first"),
                Msg.assistant("bot", "answer"),
                Msg.user("u", "follow-up")
        );
        ObjectNode body = model.buildBody(messages, List.of(), null);
        JsonNode msgs = body.path("messages");
        // user/assistant/user 三条
        assertEquals(3, msgs.size());
        for (int i = 0; i < msgs.size() - 1; i++) {
            JsonNode content = msgs.get(i).path("content");
            for (int j = 0; j < content.size(); j++) {
                assertNoCacheControl(content.get(j),
                        "历史消息 #" + i + " 的 block #" + j + " 不应有 cache_control");
            }
        }
        // 仅最后一条最后一个 block 有
        JsonNode lastContent = msgs.get(msgs.size() - 1).path("content");
        assertCacheControl(lastContent.get(lastContent.size() - 1), "");
    }

    @Test
    void noToolsStillHasSystemAndMessagesBreakpoints() throws Exception {
        List<Msg> messages = List.of(
                Msg.system("sys"),
                Msg.user("u", "hi")
        );
        ObjectNode body = model.buildBody(messages, List.of(), null);
        // 没 tools 时 body 里不应该有 tools 字段
        assertTrue(body.path("tools").isMissingNode() || body.path("tools").isEmpty());
        // 但 system 和 messages 的断点还在
        assertCacheControl(body.path("system").get(0), "");
        JsonNode lastContent = body.path("messages").get(0).path("content");
        assertCacheControl(lastContent.get(lastContent.size() - 1), "");
    }

    @Test
    void disablingPromptCacheRemovesAllBreakpoints() throws Exception {
        AnthropicChatModel noCacheModel = AnthropicChatModel.builder()
                .modelName("claude-test").apiKey("fake")
                .enablePromptCache(false)
                .build();
        ObjectNode body = noCacheModel.buildBody(
                List.of(Msg.system("sys"), Msg.user("u", "hi")),
                List.of(TOOL_A), null);
        assertNoCacheControl(body.path("system").get(0), "system 断点应被禁用");
        assertNoCacheControl(body.path("tools").get(0), "tools 断点应被禁用");
        JsonNode lastContent = body.path("messages").get(0).path("content");
        assertNoCacheControl(lastContent.get(lastContent.size() - 1), "messages 断点应被禁用");
    }

    private static void assertCacheControl(JsonNode block, String msg) {
        JsonNode cc = block.path("cache_control");
        assertFalse(cc.isMissingNode(), msg + " (找不到 cache_control)");
        assertEquals("ephemeral", cc.path("type").asText(), msg + " (type 应为 ephemeral)");
    }

    private static void assertNoCacheControl(JsonNode block, String msg) {
        assertTrue(block.path("cache_control").isMissingNode(),
                msg + " (不该出现 cache_control)");
    }
}
