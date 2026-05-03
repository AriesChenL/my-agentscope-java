package com.lynn.myagentscopejava.core.cluster;

import com.lynn.myagentscopejava.core.cluster.impl.RedisNotificationBus;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisNotificationBus} 集成测试 —— 用 Testcontainers 的 Redis 7 实例。
 *
 * <p>验证真实 pub/sub 行为：跨"模拟节点"（两个独立 bus 实例共享同一 Redis）能收到广播，
 * 取消订阅后停止接收。
 */
@Testcontainers
class RedisNotificationBusIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.6").asCompatibleSubstituteFor("redis"))
            .withExposedPorts(6379);

    static RedissonClient redisson;

    @BeforeAll
    static void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(cfg);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) redisson.shutdown();
    }

    @Test
    void publishWithNoSubscriberIsNoop() {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        bus.publish("ch-noop", "hello");   // 不抛
    }

    @Test
    void selfPublishReachesOwnSubscriber() throws InterruptedException {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        try (var sub = bus.subscribe("self-ch", msg -> { received.add(msg); latch.countDown(); })) {
            bus.publish("self-ch", "hi");
            assertTrue(latch.await(2, TimeUnit.SECONDS), "应在 2s 内收到");
            assertEquals(List.of("hi"), received);
        }
    }

    @Test
    void crossNodeBroadcast() throws InterruptedException {
        // 模拟两个节点：两个独立 bus 实例，共享同一 Redis
        RedisNotificationBus nodeA = new RedisNotificationBus(redisson);
        RedisNotificationBus nodeB = new RedisNotificationBus(redisson);

        CountDownLatch bGotMsg = new CountDownLatch(1);
        List<String> bReceived = new CopyOnWriteArrayList<>();
        try (var subB = nodeB.subscribe("cross-ch", msg -> {
            bReceived.add(msg);
            bGotMsg.countDown();
        })) {
            // Node A 发送，Node B 应收到
            nodeA.publish("cross-ch", "from-A");
            assertTrue(bGotMsg.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("from-A"), bReceived);
        }
    }

    @Test
    void unsubscribeStopsReceiving() throws InterruptedException {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        List<String> received = new CopyOnWriteArrayList<>();
        var sub = bus.subscribe("unsub-ch", received::add);

        // 收第一条
        CountDownLatch first = new CountDownLatch(1);
        try (var counter = bus.subscribe("unsub-ch", msg -> first.countDown())) {
            bus.publish("unsub-ch", "before");
            assertTrue(first.await(2, TimeUnit.SECONDS));
        }

        // 取消订阅
        sub.close();
        Thread.sleep(100);  // 给 Redisson 一点时间处理 unsubscribe

        // 再发一条 — 不应收到
        bus.publish("unsub-ch", "after");
        Thread.sleep(300);
        assertEquals(List.of("before"), received);
    }

    @Test
    void multipleSubscribersAllReceive() throws InterruptedException {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        try (var s1 = bus.subscribe("multi-ch", msg -> latch1.countDown());
             var s2 = bus.subscribe("multi-ch", msg -> latch2.countDown())) {
            bus.publish("multi-ch", "x");
            assertTrue(latch1.await(2, TimeUnit.SECONDS));
            assertTrue(latch2.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentChannelsAreIsolated() throws InterruptedException {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        List<String> rA = new CopyOnWriteArrayList<>();
        List<String> rB = new CopyOnWriteArrayList<>();
        CountDownLatch aGot = new CountDownLatch(1);
        try (var sA = bus.subscribe("iso-A", msg -> { rA.add(msg); aGot.countDown(); });
             var sB = bus.subscribe("iso-B", rB::add)) {
            bus.publish("iso-A", "hello-a");
            assertTrue(aGot.await(2, TimeUnit.SECONDS));
            // 给 B 一些时间确认它没收到
            Thread.sleep(200);
            assertEquals(List.of("hello-a"), rA);
            assertEquals(0, rB.size());
        }
    }

    @Test
    void handlerExceptionDoesNotKillSubscription() throws InterruptedException {
        RedisNotificationBus bus = new RedisNotificationBus(redisson);
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        try (var sBoom = bus.subscribe("err-ch", msg -> { throw new RuntimeException("boom"); });
             var sOk = bus.subscribe("err-ch", msg -> { received.add(msg); latch.countDown(); })) {
            bus.publish("err-ch", "msg-1");
            bus.publish("err-ch", "msg-2");
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(2, received.size());
        }
    }
}
