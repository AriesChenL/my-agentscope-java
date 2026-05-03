package com.lynn.myagentscopejava.core.cluster;

import com.lynn.myagentscopejava.core.cluster.impl.RedisDistributedLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisDistributedLock} 集成测试。用 Testcontainers 起 Redis 7。
 */
@Testcontainers
class RedisDistributedLockIT {

    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.6").asCompatibleSubstituteFor("redis"))
            .withExposedPorts(6379);

    static RedissonClient redisson;
    static RedisDistributedLock lock;

    @BeforeAll
    static void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(cfg);
        lock = new RedisDistributedLock(redisson);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) redisson.shutdown();
    }

    @Test
    void acquireAndReleaseViaTryWithResources() {
        try (DistributedLock.LockHandle h = lock.acquire("k1")) {
            assertNotNull(h);
        }
        try (DistributedLock.LockHandle h2 = lock.acquire("k1")) {
            assertNotNull(h2);
        }
    }

    @Test
    void closeIsIdempotent() {
        DistributedLock.LockHandle h = lock.acquire("k-idem");
        h.close();
        h.close();   // 不抛
        h.close();
    }

    @Test
    void differentKeysParallel() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch a = new CountDownLatch(1);
        CountDownLatch b = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { return; }
            try (DistributedLock.LockHandle h = lock.acquire("k-a")) {
                a.countDown();
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { return; }
            try (DistributedLock.LockHandle h = lock.acquire("k-b")) {
                b.countDown();
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        });
        t1.start(); t2.start();
        start.countDown();
        // 两不同 key 几乎同时拿到锁
        assertEquals(true, a.await(2, TimeUnit.SECONDS));
        assertEquals(true, b.await(2, TimeUnit.SECONDS));
        t1.join(); t2.join();
    }

    @Test
    void sameKeyAcrossThreadsSerializes() throws InterruptedException {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[3];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try (DistributedLock.LockHandle h = lock.acquire("serial-key")) {
                    int n = inside.incrementAndGet();
                    maxInside.updateAndGet(m -> Math.max(m, n));
                    Thread.sleep(50);
                    inside.decrementAndGet();
                } catch (InterruptedException ignored) {}
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) t.join();
        // 跨线程同 key 任意时刻只能 1 个在临界区
        assertEquals(1, maxInside.get());
    }

    @Test
    void tryAcquireSucceedsWhenFree() {
        DistributedLock.LockHandle h = lock.tryAcquire("free-key", Duration.ofMillis(100));
        assertNotNull(h);
        h.close();
    }

    @Test
    void tryAcquireTimesOutWhenHeldByOtherThread() throws InterruptedException {
        DistributedLock.LockHandle held = lock.acquire("busy-key");
        try {
            AtomicReference<DistributedLock.LockHandle> ref = new AtomicReference<>();
            Thread t = new Thread(() -> ref.set(lock.tryAcquire("busy-key", Duration.ofMillis(200))));
            t.start();
            t.join();
            assertNull(ref.get());
        } finally {
            held.close();
        }
    }

    @Test
    void reentrantBySameThread() {
        // 同节点同线程支持重入
        try (var outer = lock.acquire("reentry")) {
            try (var inner = lock.acquire("reentry")) {
                assertNotNull(inner);
            }
        }
    }

    @Test
    void simulatesTwoNodesContending() throws InterruptedException {
        // 模拟两个不同节点（两个独立 RedisDistributedLock 实例，共享同一 Redis）
        RedisDistributedLock nodeA = new RedisDistributedLock(redisson);
        RedisDistributedLock nodeB = new RedisDistributedLock(redisson);

        AtomicReference<DistributedLock.LockHandle> bResult = new AtomicReference<>();

        // Node A 持锁
        DistributedLock.LockHandle aHandle = nodeA.acquire("multi-node");
        try {
            // Node B（另一线程模拟另一节点）同时尝试，应超时拿不到
            Thread tB = new Thread(() ->
                    bResult.set(nodeB.tryAcquire("multi-node", Duration.ofMillis(200))));
            tB.start();
            tB.join();
            assertNull(bResult.get(), "另一节点持锁时本节点不应能拿到");
        } finally {
            aHandle.close();
        }

        // A 释放后，B 应能拿到
        Thread tB2 = new Thread(() ->
                bResult.set(nodeB.tryAcquire("multi-node", Duration.ofMillis(500))));
        tB2.start();
        tB2.join();
        assertNotNull(bResult.get(), "另一节点释放后本节点应能拿到");
        bResult.get().close();
    }
}
