package com.lynn.myagentscopejava.core.cluster.impl;

import com.lynn.myagentscopejava.core.cluster.DistributedLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redisson 实现的 {@link DistributedLock}，跨节点共享。
 *
 * <p>每个 key 一把 Redisson {@link RLock}，借助 Redisson 的 watchdog 自动续约
 * （默认 30s 过期 + 每 10s 续约一次），避免持锁线程崩溃后锁死。
 *
 * <p><b>语义保证</b>
 * <ul>
 *   <li>同一 key 跨节点互斥</li>
 *   <li>同一节点同线程支持重入（与 {@link LocalDistributedLock} 行为一致）</li>
 *   <li>持锁节点崩溃 → watchdog 停止续约 → 锁在 30s 内被 Redis 自动释放</li>
 * </ul>
 *
 * <p>所有锁的 key 在 Redis 里加 namespace 前缀 {@code "agentscope:lock:"}，避免与其它系统冲突。
 */
public class RedisDistributedLock implements DistributedLock {

    private static final String KEY_PREFIX = "agentscope:lock:";

    private final RedissonClient redisson;

    public RedisDistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public LockHandle acquire(String key) {
        RLock rLock = redisson.getLock(KEY_PREFIX + key);
        rLock.lock();   // watchdog 自动续约（leaseTime=-1 by default）
        return new RedissonLockHandle(rLock);
    }

    @Override
    public LockHandle tryAcquire(String key, Duration timeout) {
        RLock rLock = redisson.getLock(KEY_PREFIX + key);
        try {
            // waitTime = timeout, leaseTime = -1（使用 watchdog 自动续约）
            boolean ok = rLock.tryLock(timeout.toMillis(), -1, TimeUnit.MILLISECONDS);
            return ok ? new RedissonLockHandle(rLock) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Redisson 锁的 AutoCloseable 包装，幂等 unlock。 */
    private static final class RedissonLockHandle implements LockHandle {
        private final RLock rLock;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        RedissonLockHandle(RLock rLock) { this.rLock = rLock; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // 仅当前线程持锁才 unlock；防御性检查避免 IllegalMonitorStateException
                if (rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                }
            }
        }
    }
}
