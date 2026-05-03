package com.lynn.myagentscopejava.core.tool;

/**
 * 工具内部抛出此异常，表示该工具调用需要由外部（人 / 另一个系统）执行 —— 即 HITL 场景。
 *
 * <p>框架收到该异常后会：
 * <ol>
 *   <li>把它转为一个 {@link com.lynn.myagentscopejava.core.message.ToolResultBlock}（pending 状态）</li>
 *   <li>把含有 pending 结果块的 TOOL 消息写入 memory，便于持久化与后续恢复</li>
 *   <li>{@code ReActAgent.call(...)} 立即返回，把控制权交给调用方</li>
 *   <li>调用方完成外部执行后，把真实结果以
 *       {@link com.lynn.myagentscopejava.core.message.MsgRole#TOOL} 消息形式
 *       传给 {@code ReActAgent.call(...)}，agent 自动恢复 ReAct 循环</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 * @Tool(description = "扣款，金额超过阈值时需要人工审批")
 * public String deduct(@ToolParam(description = "金额") int amount) {
 *     if (amount > 10000) {
 *         throw new ToolSuspendException("金额 " + amount + " 元超过阈值，请人工审批");
 *     }
 *     return "已扣款 " + amount + " 元";
 * }
 * }</pre>
 */
public class ToolSuspendException extends RuntimeException {

    private final String reason;

    public ToolSuspendException() {
        this(null);
    }

    /**
     * @param reason 挂起原因，会写入 pending {@code ToolResultBlock} 的 output 字段
     */
    public ToolSuspendException(String reason) {
        super(reason != null ? reason : "Tool execution suspended");
        this.reason = reason;
    }

    /**
     * @return 调用方设置的挂起原因，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }
}
