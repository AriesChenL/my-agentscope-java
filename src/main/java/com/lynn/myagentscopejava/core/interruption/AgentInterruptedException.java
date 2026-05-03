package com.lynn.myagentscopejava.core.interruption;

/**
 * 当 agent 操作被 {@link CancellationToken#cancel()}（通常由
 * {@code ReActAgent.interrupt()} 触发）取消时抛出的异常。
 *
 * <p>携带 {@link InterruptSource} 让上层代码能根据来源做不同处理：
 * 例如用户中断保留 partial reasoning，系统中断可选择丢弃。
 */
public class AgentInterruptedException extends RuntimeException {

    private final InterruptSource source;

    public AgentInterruptedException(String message) {
        this(message, InterruptSource.USER, null);
    }

    public AgentInterruptedException(String message, InterruptSource source) {
        this(message, source, null);
    }

    public AgentInterruptedException(String message, Throwable cause) {
        this(message, InterruptSource.USER, cause);
    }

    public AgentInterruptedException(String message, InterruptSource source, Throwable cause) {
        super(message, cause);
        this.source = source != null ? source : InterruptSource.USER;
    }

    /**
     * @return 中断来源；构造时未指定则为 {@link InterruptSource#USER}
     */
    public InterruptSource getSource() {
        return source;
    }
}
