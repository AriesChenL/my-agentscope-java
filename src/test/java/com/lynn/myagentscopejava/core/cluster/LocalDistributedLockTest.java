package com.lynn.myagentscopejava.core.cluster;

import com.lynn.myagentscopejava.core.cluster.impl.LocalDistributedLock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDistributedLockTest {

    @Test
    void acquireAndReleaseViaTryWithResources() {
        LocalDistributedLock lock = new LocalDistributedLock();
        try (DistributedLock.LockHandle h = lock.acquire("k1")) {
            assertNotNull(h);
        }
        // 同 key 再次 acquire 应能成功
        try (DistributedLock.LockHandle h2 = lock.acquire("k1")) {
            assertNotNull(h2);
        }
    }

    @Test
    void closeIsIdempotent() {
        LocalDistributedLock lock = new LocalDistributedLock();
        DistributedLock.LockHandle h = lock.acquire("k");
        h.close();
        h.close();    // 重复 close 不抛
        h.close();
    }

    @Test
    void differentKeysParallel() throws InterruptedException {
        LocalDistributedLock lock = new LocalDistributedLock();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch a = new CountDownLatch(1);
        CountDownLatch b = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();

        Thread t1 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { return; }
            try (DistributedLock.LockHandle h = lock.acquire("k-a")) {
                a.countDown();
                Thread.sleep(50);
                order.incrementAndGet();
            } catch (InterruptedException ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { return; }
            try (DistributedLock.LockHandle h = lock.acquire("k-b")) {
                b.countDown();
                Thread.sleep(50);
                order.incrementAndGet();
            } catch (InterruptedException ignored) {}
        });
        t1.start(); t2.start();
        start.countDown();
        // 两个不同 key 应该几乎同时拿到锁
        assertTrue(a.await(1, TimeUnit.SECONDS));
        assertTrue(b.await(1, TimeUnit.SECONDS));
        t1.join(); t2.join();
        assertEquals(2, order.get());
    }

    @Test
    void sameKeySerialized() throws InterruptedException {
        LocalDistributedLock lock = new LocalDistributedLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try (DistributedLock.LockHandle h = lock.acquire("same")) {
                    int n = inside.incrementAndGet();
                    maxInside.updateAndGet(m -> Math.max(m, n));
                    Thread.sleep(10);
                    inside.decrementAndGet();
                } catch (InterruptedException ignored) {}
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) t.join();
        // 同一 key 任意时刻最多 1 个线程在临界区
        assertEquals(1, maxInside.get());
    }

    @Test
    void tryAcquireSucceedsWhenFree() {
        LocalDistributedLock lock = new LocalDistributedLock();
        DistributedLock.LockHandle h = lock.tryAcquire("k", Duration.ofMillis(100));
        assertNotNull(h);
        h.close();
    }

    @Test
    void tryAcquireTimesOutWhenHeldByOtherThread() throws InterruptedException {
        LocalDistributedLock lock = new LocalDistributedLock();
        DistributedLock.LockHandle held = lock.acquire("busy");
        try {
            // 必须在另一线程 tryAcquire —— 同线程 ReentrantLock 会直接重入拿到锁
            java.util.concurrent.atomic.AtomicReference<DistributedLock.LockHandle> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread t = new Thread(() -> ref.set(lock.tryAcquire("busy", Duration.ofMillis(50))));
            t.start();
            t.join();
            assertNull(ref.get());
        } finally {
            held.close();
        }
    }

    @Test
    void sameKeyReturnsSameUnderlyingLock() {
        // 同 key 多次 acquire 须复用同一个 ReentrantLock 实例（否则互斥失效）
        LocalDistributedLock lock = new LocalDistributedLock();
        // size 体现锁实例缓存，连续两次同 key acquire 应只多一个条目
        try (var h = lock.acquire("xx")) { assertEquals(1, lock.size()); }
        try (var h = lock.acquire("xx")) { assertEquals(1, lock.size()); }
        try (var h = lock.acquire("yy")) { assertEquals(2, lock.size()); }
    }

    @Test
    void reentrantBySameThread() {
        // 同线程同 key 重入须不死锁
        LocalDistributedLock lock = new LocalDistributedLock();
        try (var outer = lock.acquire("k")) {
            try (var inner = lock.acquire("k")) {
                assertNotNull(inner);
            }
        }
    }
}
