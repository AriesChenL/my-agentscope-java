package com.lynn.myagentscopejava.core.interruption;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协作式取消信号，用于在长耗时调用（模型 HTTP、工具执行等）中传递中断意图。
 *
 * <p>使用模式：
 * <ul>
 *   <li>生产者（如 {@link com.lynn.myagentscopejava.core.agent.ReActAgent#interrupt()}）调用 {@link #cancel()}</li>
 *   <li>消费者（如 {@link com.lynn.myagentscopejava.core.model.ChatModel}）在安全点轮询 {@link #isCancelled()} /
 *       {@link #throwIfCancelled()}，或通过 {@link #onCancel(Runnable)} 注册回调以中止异步操作</li>
 * </ul>
 *
 * <p>取消时携带 {@link InterruptSource}，方便上层根据来源做不同的善后（用户中断保留 partial、
 * 系统中断可能丢弃 partial 等）。{@link #cancel()} 不指定来源默认为 {@link InterruptSource#USER}。
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<InterruptSource> source = new AtomicReference<>();
    private final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    /**
     * @return 是否已被取消
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * @return 取消的来源；尚未取消时返回 {@code null}
     */
    public InterruptSource getSource() {
        return source.get();
    }

    /**
     * 若已取消则抛 {@link AgentInterruptedException}（带 source），否则什么都不做。
     *
     * @throws AgentInterruptedException 当已被取消
     */
    public void throwIfCancelled() throws AgentInterruptedException {
        if (cancelled.get()) {
            throw new AgentInterruptedException("Operation cancelled", source.get());
        }
    }

    /**
     * 标记取消（来源默认 {@link InterruptSource#USER}）并触发已注册的回调。重复调用是幂等的。
     */
    public void cancel() {
        cancel(InterruptSource.USER);
    }

    /**
     * 指定来源标记取消并触发已注册的回调。重复调用幂等 —— 后续 {@code cancel(...)} 不会改变来源。
     *
     * @param src 取消来源；{@code null} 视为 USER
     */
    public void cancel(InterruptSource src) {
        if (cancelled.compareAndSet(false, true)) {
            source.set(src != null ? src : InterruptSource.USER);
            Runnable cb = onCancel.getAndSet(null);
            if (cb != null) {
                try { cb.run(); } catch (Exception ignored) { /* best effort */ }
            }
        }
    }

    /**
     * 注册取消回调。若已经取消，回调立即执行；同一时刻只支持一个回调，后注册的会覆盖前一个。
     *
     * @param callback 取消时执行的回调
     */
    public void onCancel(Runnable callback) {
        if (cancelled.get()) {
            callback.run();
            return;
        }
        onCancel.set(callback);
        if (cancelled.get()) {
            Runnable cb = onCancel.getAndSet(null);
            if (cb != null) cb.run();
        }
    }

    /**
     * @return 一个全新的、未取消的 token
     */
    public static CancellationToken none() {
        return new CancellationToken();
    }
}
