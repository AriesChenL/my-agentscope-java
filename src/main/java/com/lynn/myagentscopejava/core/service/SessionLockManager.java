package com.lynn.myagentscopejava.core.service;

import com.lynn.myagentscopejava.core.session.SessionKey;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 {@link SessionKey} 分配独立的 {@link ReentrantLock}，让"同一会话内串行、不同会话并行"成立。
 *
 * <p>典型用途：在 {@link ChatService#chat(SessionKey, com.lynn.myagentscopejava.core.message.Msg)}
 * 中包裹"加载 → 调用 → 保存"三步，保证同一用户两个并发请求不会丢消息或脏写。
 *
 * <p>锁实例按需创建并永久缓存于 {@link ConcurrentHashMap} 中。
 * 单进程预期会话数有限，简单缓存即可；如未来需要清理可改为 LRU。
 */
public class SessionLockManager {

    private final ConcurrentMap<SessionKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 获取与指定 key 关联的锁实例，不存在则原子创建。
     *
     * @param key 会话键
     * @return 该 key 对应的 ReentrantLock（同一 key 永远返回同一实例）
     */
    public ReentrantLock lockFor(SessionKey key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /**
     * 当前缓存的锁数量，主要用于监控 / 测试。
     *
     * @return 已分配的锁数量
     */
    public int size() {
        return locks.size();
    }
}
