package com.lynn.myagentscopejava.tools;

import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import com.lynn.myagentscopejava.core.tool.ToolSuspendException;

import java.util.List;
import java.util.Map;

/**
 * Human-in-the-Loop 内置工具：让 LLM 在信息不全时主动"问用户"。
 *
 * <p>触发流程：
 * <ol>
 *   <li>LLM 自己判断需要用户提供更多信息（例如目的地、预算、二次确认），调用本工具</li>
 *   <li>方法<b>永远</b>抛 {@link ToolSuspendException}，框架把它转成 pending
 *       {@link com.lynn.myagentscopejava.core.message.ToolResultBlock}</li>
 *   <li>调用入参（question / ui_type / options / fields …）原样保留在
 *       {@link com.lynn.myagentscopejava.core.message.ToolUseBlock#input()} 里</li>
 *   <li>前端从 pendingTools 中读取这些参数，按 {@code ui_type} 渲染对应输入控件</li>
 *   <li>用户提交答案后，前端走标准的 HITL 回填接口（{@code POST /api/chat/.../} body
 *       {@code {role:"TOOL", toolCallId, text}}），agent 续跑</li>
 * </ol>
 *
 * <p>支持的 {@code ui_type}：
 * {@code text / select / multi_select / confirm / form / date / number}（对应前端不同 UI）。
 *
 * <p>参考：阿里 agentscope-java 仓库
 * {@code agentscope-examples/advanced/hitl/UserInteractionTool}。
 *
 * <p>本工具不直接 {@code @Component}，由
 * {@link com.lynn.myagentscopejava.config.AgentAutoConfiguration} 按
 * {@code agentscope.hitl.ask-user-tool-enabled} 配置决定是否注册为 bean。
 */
public class UserInteractionTool implements ToolProvider {

    /** 工具名常量；前端按此名识别"这是 ask_user，需要走 UI 渲染" */
    public static final String TOOL_NAME = "ask_user";

    @Tool(
            name = TOOL_NAME,
            description =
                    "Ask the user for clarification or additional information when the request "
                            + "is ambiguous or missing required details. Choose the appropriate "
                            + "ui_type: 'text' for free-form input, 'select' for choosing one "
                            + "from a list (provide options), 'multi_select' for choosing "
                            + "multiple from a list, 'confirm' for yes/no questions, 'form' for "
                            + "collecting multiple fields at once (provide fields), 'date' for "
                            + "date selection, 'number' for numeric input.")
    public String askUser(
            @ToolParam(name = "question", description = "向用户提的问题")
            String question,
            @ToolParam(name = "ui_type",
                    description = "UI 控件类型：text / select / multi_select / confirm / form / "
                            + "date / number；默认 text",
                    required = false)
            String uiType,
            @ToolParam(name = "options",
                    description = "select / multi_select 的候选项列表，例如 [\"北京\", \"上海\"]",
                    required = false)
            List<String> options,
            @ToolParam(name = "fields",
                    description = "form 类型的字段定义列表，每项含 name/label/type/placeholder/"
                            + "required/options/min/max/step",
                    required = false)
            List<Map<String, Object>> fields,
            @ToolParam(name = "default_value",
                    description = "输入控件的默认值",
                    required = false)
            Object defaultValue,
            @ToolParam(name = "allow_other",
                    description = "select / multi_select 是否允许用户输入预设外的'其它'值",
                    required = false)
            Boolean allowOther) {
        String reason = (question != null && !question.isBlank())
                ? question
                : "等待用户输入";
        throw new ToolSuspendException(reason);
    }
}
