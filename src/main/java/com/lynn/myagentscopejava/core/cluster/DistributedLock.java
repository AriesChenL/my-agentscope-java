package com.lynn.myagentscopejava.core.cluster;

import java.time.Duration;

/**
 * 跨节点协调用的分布式锁抽象。
 *
 * <p>语义：同一 {@code key} 在集群范围内独占；不同 {@code key} 并行。
 * 单机部署时由 {@link com.lynn.myagentscopejava.core.cluster.impl.LocalDistributedLock}
 * 退化为进程内 {@link java.util.concurrent.locks.ReentrantLock}；多节点部署时由
 * Redis (Redisson) 等实现支撑。
 *
 * <p>使用 try-with-resources 方式：
 * <pre>{@code
 * try (LockHandle handle = distributedLock.acquire("session:alice__c-001")) {
 *     // 临界区
 * }
 * }</pre>
 *
 * <p><b>注意</b>：{@link #acquire(String)} 是阻塞调用，永不超时。如果你需要超时控制
 *（典型用例：HTTP 请求快速失败而不是积压在锁队列上），用 {@link #tryAcquire(String, Duration)}。
 */
public interface DistributedLock {

    /**
     * 获取指定 key 的锁，阻塞直到拿到。返回的句柄须在 try-with-resources 块中使用以保证释放。
     *
     * @param key 锁 key（业务上一般用 SessionKey.value()）
     * @return 锁句柄，关闭时释放锁
     */
    LockHandle acquire(String key);

    /**
     * 尝试获取锁，超时未拿到返回 {@code null}。
     *
     * @param key     锁 key
     * @param timeout 超时时间
     * @return 拿到则返回句柄；超时返回 {@code null}
     */
    LockHandle tryAcquire(String key, Duration timeout);

    /**
     * 锁句柄。{@link #close()} 释放锁；幂等，重复 close 不报错。
     */
    interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }
}
