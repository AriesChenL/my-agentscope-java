package com.lynn.myagentscopejava.tools;

import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link UserInteractionTool} 的 schema 生成 + 调用挂起行为。
 */
class UserInteractionToolTest {

    @Test
    void registersWithCorrectSchema() {
        Toolkit kit = new Toolkit().registerObject(new UserInteractionTool());
        ToolSchema schema = kit.getSchemas().stream()
                .filter(s -> UserInteractionTool.TOOL_NAME.equals(s.name()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> params = schema.parameters();
        assertEquals("object", params.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        // question 必填
        assertTrue(((List<?>) params.get("required")).contains("question"));
        // ui_type / options / fields / default_value / allow_other 都应在 properties
        assertTrue(props.containsKey("ui_type"));
        assertTrue(props.containsKey("options"));
        assertTrue(props.containsKey("fields"));
        assertTrue(props.containsKey("default_value"));
        assertTrue(props.containsKey("allow_other"));

        // options 是 array of string
        @SuppressWarnings("unchecked")
        Map<String, Object> optionsSchema = (Map<String, Object>) props.get("options");
        assertEquals("array", optionsSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) optionsSchema.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void invokeProducesPendingResultWithInputPreserved() {
        Toolkit kit = new Toolkit().registerObject(new UserInteractionTool());
        ToolUseBlock use = new ToolUseBlock("call_1", UserInteractionTool.TOOL_NAME, Map.of(
                "question", "你想去哪里？",
                "ui_type", "select",
                "options", List.of("北京", "上海", "深圳")));

        ToolResultBlock result = kit.invoke(use);

        // 工具自抛 ToolSuspendException → 框架转 pending
        assertTrue(result.pending());
        assertEquals("call_1", result.id());
        assertEquals(UserInteractionTool.TOOL_NAME, result.name());
        // pending 的 output 是问题文本
        assertEquals("你想去哪里？", result.output());
    }

    @Test
    void invokeWithFormFieldsAcceptsListOfMap() {
        Toolkit kit = new Toolkit().registerObject(new UserInteractionTool());
        ToolUseBlock use = new ToolUseBlock("call_2", UserInteractionTool.TOOL_NAME, Map.of(
                "question", "请填写信息",
                "ui_type", "form",
                "fields", List.of(
                        Map.of("name", "age", "type", "number", "label", "年龄"),
                        Map.of("name", "city", "type", "text", "label", "城市"))));

        ToolResultBlock result = kit.invoke(use);
        assertTrue(result.pending());
    }

    @Test
    void msgWithToolResultRoundTripsThroughMsgBuilder() {
        // 顺带验证 HITL 回填路径：构造一个含 ToolResult 的 TOOL Msg 不应报错
        Msg userReply = Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_1", UserInteractionTool.TOOL_NAME,
                        "User responded: 北京"))
                .build();
        assertNotNull(userReply);
        assertEquals(MsgRole.TOOL, userReply.getRole());
        assertEquals("User responded: 北京",
                userReply.getBlocks(ToolResultBlock.class).getFirst().output());
    }
}
