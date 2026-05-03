package com.lynn.myagentscopejava.core.cluster.impl;

import com.lynn.myagentscopejava.core.cluster.NotificationBus;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redisson 实现的 {@link NotificationBus}，跨节点 pub/sub。
 *
 * <p>底层是 Redis PUB/SUB —— 至多一次投递，订阅者宕机错过的消息丢失。
 * 这跟 {@link LocalNotificationBus} 的语义一致。
 *
 * <p><b>"自己发的不响应"</b>：单纯 Redis pub/sub 会把消息广播到**所有**订阅者（包括发布者本节点）。
 * 业务层（如 ChatService 的 interrupt 逻辑）需要自己判断"信号源是不是本节点"——
 * 我们用 payload 里编码 nodeId 来过滤。本类不做强制，保持 transport 层简洁。
 *
 * <p>所有频道在 Redis 里加 namespace 前缀 {@code "agentscope:bus:"}，避免与其它系统冲突。
 *
 * <p>回调线程：Redisson 的 listener 在内部 netty event-loop 上回调，handler 内**禁止阻塞**。
 * 需要重业务逻辑时应自己 dispatch 到业务线程池。
 */
public class RedisNotificationBus implements NotificationBus {

    private static final Logger log = LoggerFactory.getLogger(RedisNotificationBus.class);
    private static final String CHANNEL_PREFIX = "agentscope:bus:";

    private final RedissonClient redisson;

    public RedisNotificationBus(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void publish(String channel, String payload) {
        try {
            RTopic topic = redisson.getTopic(CHANNEL_PREFIX + channel);
            topic.publish(payload);
        } catch (Exception e) {
            // pub/sub 是 fire-and-forget；发不出去不应阻塞业务（典型场景：Redis 闪断）
            log.warn("publish 失败 channel={}: {}", channel, e.toString());
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> handler) {
        RTopic topic = redisson.getTopic(CHANNEL_PREFIX + channel);
        int listenerId = topic.addListener(String.class, (ch, msg) -> {
            try {
                handler.accept(msg);
            } catch (Exception e) {
                log.warn("订阅者 handler 抛异常 channel={} msg={}: {}", channel, msg, e.toString());
            }
        });
        return new RedissonSubscription(topic, listenerId);
    }

    private static final class RedissonSubscription implements Subscription {
        private final RTopic topic;
        private final int listenerId;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        RedissonSubscription(RTopic topic, int listenerId) {
            this.topic = topic;
            this.listenerId = listenerId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    topic.removeListener(listenerId);
                } catch (Exception e) {
                    log.warn("removeListener 失败: {}", e.toString());
                }
            }
        }

        private static final Logger log = LoggerFactory.getLogger(RedisNotificationBus.class);
    }
}
