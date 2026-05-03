package com.lynn.myagentscopejava.core.cluster.impl;

import com.lynn.myagentscopejava.core.cluster.NotificationBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单机版 {@link NotificationBus}，进程内同步回调。
 *
 * <p>publish 在调用线程上**同步**遍历订阅者并触发回调；订阅者抛异常被吃掉记日志，不影响其它订阅者。
 *
 * <p>分布式版本（Redis pub/sub）会异步触发回调（在 Redis 消息线程上），handler 实现需注意线程安全
 * —— 这一点本地版本也保留了同样的契约。
 */
public class LocalNotificationBus implements NotificationBus {

    private static final Logger log = LoggerFactory.getLogger(LocalNotificationBus.class);

    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, String payload) {
        CopyOnWriteArrayList<Consumer<String>> list = handlers.get(channel);
        if (list == null || list.isEmpty()) return;
        for (Consumer<String> h : list) {
            try {
                h.accept(payload);
            } catch (Exception e) {
                log.warn("订阅者 handler 抛异常 channel={} payload={}: {}", channel, payload, e.toString());
            }
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> handler) {
        CopyOnWriteArrayList<Consumer<String>> list = handlers.computeIfAbsent(
                channel, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return new LocalSubscription(channel, handler);
    }

    /** 暴露给测试 / 监控：当前频道订阅数。 */
    public int subscriberCount(String channel) {
        CopyOnWriteArrayList<Consumer<String>> list = handlers.get(channel);
        return list == null ? 0 : list.size();
    }

    private final class LocalSubscription implements Subscription {
        private final String channel;
        private final Consumer<String> handler;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        LocalSubscription(String channel, Consumer<String> handler) {
            this.channel = channel;
            this.handler = handler;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                CopyOnWriteArrayList<Consumer<String>> list = handlers.get(channel);
                if (list != null) list.remove(handler);
            }
        }
    }
}
