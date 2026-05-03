package com.lynn.myagentscopejava.core.cluster.impl;

import com.lynn.myagentscopejava.core.cluster.DistributedLock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单机版 {@link DistributedLock}，每个 key 一把 {@link ReentrantLock}。
 *
 * <p>替代了原 {@code SessionLockManager}。功能完全等价，仅接口形态对齐分布式版本。
 * 锁实例按需创建并永久缓存于 {@link ConcurrentHashMap}；单进程预期会话数有限，简单缓存即可。
 */
public class LocalDistributedLock implements DistributedLock {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public LockHandle acquire(String key) {
        ReentrantLock lock = lockFor(key);
        lock.lock();
        return new ReentrantLockHandle(lock);
    }

    @Override
    public LockHandle tryAcquire(String key, Duration timeout) {
        ReentrantLock lock = lockFor(key);
        try {
            if (lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new ReentrantLockHandle(lock);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private ReentrantLock lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /** 缓存的锁数量，主要用于监控 / 测试。 */
    public int size() {
        return locks.size();
    }

    /** AutoCloseable 包装，幂等关闭。 */
    private static final class ReentrantLockHandle implements LockHandle {
        private final ReentrantLock lock;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ReentrantLockHandle(ReentrantLock lock) { this.lock = lock; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.unlock();
            }
        }
    }
}
