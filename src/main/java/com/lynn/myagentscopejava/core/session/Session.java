package com.lynn.myagentscopejava.core.session;

import java.util.Optional;

/**
 * Agent 状态的可插拔持久化层。
 *
 * <p>状态以"槽位"形式存放在 {@link SessionKey} 之下；各组件
 *（如 {@link com.lynn.myagentscopejava.core.memory.Memory}）拥有自己的槽位名，
 * 通过它独立读写。
 *
 * <p>实现需自行处理 JSON (反)序列化。{@link com.lynn.myagentscopejava.core.message.ContentBlock}
 * 等多态类型已用 {@code @JsonTypeInfo} 标注，标准 Jackson {@code ObjectMapper} 即可正确往返。
 */
public interface Session {

    /**
     * 将 {@code value} 持久化到 {@code (key, slot)}，覆盖旧值。
     *
     * @param key   会话键
     * @param slot  槽位名
     * @param value 待保存的对象
     */
    void save(SessionKey key, String slot, Object value);

    /**
     * 读取此前保存的值。
     *
     * @param key  会话键
     * @param slot 槽位名
     * @param type 反序列化目标类型
     * @param <T>  类型参数
     * @return 槽位存在时返回反序列化后的对象，否则返回空
     */
    <T> Optional<T> get(SessionKey key, String slot, Class<T> type);

    /**
     * 删除此 key 下的所有槽位。幂等操作。
     *
     * @param key 会话键
     */
    void delete(SessionKey key);

    /**
     * 判断此 key 下是否已有任何槽位数据。
     *
     * @param key 会话键
     * @return 存在任意槽位时返回 true
     */
    boolean exists(SessionKey key);
}
