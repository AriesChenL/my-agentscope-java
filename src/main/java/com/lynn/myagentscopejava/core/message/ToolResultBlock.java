package com.lynn.myagentscopejava.core.message;

/**
 * 工具调用的执行结果，回传给模型用于下一轮推理。
 *
 * <p>{@code id} 必须与发起调用的 {@link ToolUseBlock#id()} 一致。
 *
 * <p>{@code name} 是工具名，必须与发起调用的 {@link ToolUseBlock#name()} 一致。
 * Gemini 协议依赖 {@code functionResponse.name} 与 {@code functionCall.name} 匹配，
 * 否则 cachedContents 等严格校验接口会 400。OpenAI / Anthropic 用 id 匹配，name 仅作为
 * 元数据可选携带。允许为 {@code null}（旧调用方未提供时回退到 id）。
 *
 * <p>三种状态：
 * <ul>
 *   <li>正常成功：{@link #success(String, String, String)}</li>
 *   <li>执行错误：{@link #error(String, String, String)}</li>
 *   <li>挂起待外部执行（HITL）：{@link #pending(String, String, String)} —— 工具自身抛
 *       {@link com.lynn.myagentscopejava.core.tool.ToolSuspendException} 时由框架自动产生</li>
 * </ul>
 *
 * @param id      关联的 ToolUseBlock id
 * @param name    工具名（与 ToolUseBlock.name 对应）；可为 {@code null}
 * @param output  工具输出 / 错误描述 / 挂起原因；{@code null} 会被规范化为空字符串
 * @param isError 是否为错误结果
 * @param pending 是否处于"挂起等待外部执行"状态
 */
public record ToolResultBlock(String id, String name, String output, boolean isError, boolean pending)
        implements ContentBlock {

    public ToolResultBlock {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolResultBlock id 必填");
        }
        if (output == null) output = "";
    }

    /** 兼容旧 4 参构造（无 name）。 */
    public ToolResultBlock(String id, String output, boolean isError, boolean pending) {
        this(id, null, output, isError, pending);
    }

    public static ToolResultBlock success(String id, String name, String output) {
        return new ToolResultBlock(id, name, output, false, false);
    }

    public static ToolResultBlock error(String id, String name, String message) {
        return new ToolResultBlock(id, name, message, true, false);
    }

    public static ToolResultBlock pending(String id, String name, String reason) {
        return new ToolResultBlock(id, name, reason, false, true);
    }

    /** 兼容旧 API（无 name）。 */
    public static ToolResultBlock success(String id, String output) {
        return success(id, null, output);
    }

    /** 兼容旧 API（无 name）。 */
    public static ToolResultBlock error(String id, String message) {
        return error(id, null, message);
    }

    /** 兼容旧 API（无 name）。 */
    public static ToolResultBlock pending(String id, String reason) {
        return pending(id, null, reason);
    }
}
