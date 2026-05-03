package com.lynn.myagentscopejava.core.cluster;

import java.util.function.Consumer;

/**
 * 跨节点广播抽象（pub/sub）。
 *
 * <p>用于在分布式部署下通知其它节点处理：典型用例
 * <ul>
 *   <li>{@code interrupt:{sessionKey}} —— 用户在 Node B 点中断，Node A 持有 SSE 收到信号取消令牌</li>
 *   <li>{@code resume:{sessionKey}}    —— HITL 批准在 Node B 完成，通知 Node A 续推</li>
 *   <li>{@code config:hitl}            —— 危险工具清单热更新，所有节点同步</li>
 * </ul>
 *
 * <p>单机部署时由 {@link com.lynn.myagentscopejava.core.cluster.impl.LocalNotificationBus}
 * 退化为进程内回调；多节点由 Redis pub/sub 实现支撑。
 *
 * <p><b>语义说明</b>
 * <ul>
 *   <li>"至多一次"投递 —— 不保证可靠送达，订阅者宕机期间错过的消息丢失</li>
 *   <li>本节点 publish 的消息**也会**被本节点的订阅者收到（fire-and-forget），订阅者需自己处理"自己发的不响应"逻辑</li>
 *   <li>同步还是异步由实现决定；调用方不应假设回调线程</li>
 * </ul>
 */
public interface NotificationBus {

    /**
     * 向指定频道发送一条消息。fire-and-forget，不等送达确认。
     *
     * @param channel 频道名（建议格式：{@code <type>:<key>}，例如 {@code interrupt:alice__c-001}）
     * @param payload 载荷字符串（实现一般不解析，由订阅方按业务约定 parse）
     */
    void publish(String channel, String payload);

    /**
     * 订阅指定频道。返回的 {@link Subscription} 在 {@link Subscription#close()} 时取消订阅。
     *
     * @param channel 频道名；支持精确匹配，不支持通配符（保持简单跨实现兼容）
     * @param handler 收到消息时的回调；可能在任意线程触发，handler 内须线程安全
     * @return 订阅句柄
     */
    Subscription subscribe(String channel, Consumer<String> handler);

    /**
     * 订阅句柄。{@link #close()} 释放订阅；幂等。
     */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
