package com.lynn.myagentscopejava.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的进程内 {@link Session}。
 *
 * <p>适用于单元测试或不需要崩溃恢复的单进程应用。
 *
 * <p>仍走 JSON 序列化（与 {@link FileSystemSession} 持久化形态保持一致），
 * 便于及早暴露序列化相关 bug。
 */
public class InMemorySession implements Session {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<SessionKey, Map<String, String>> store = new ConcurrentHashMap<>();

    @Override
    public void save(SessionKey key, String slot, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            store.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(slot, json);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败：" + key + "/" + slot, e);
        }
    }

    @Override
    public <T> Optional<T> get(SessionKey key, String slot, Class<T> type) {
        Map<String, String> slots = store.get(key);
        if (slots == null) return Optional.empty();
        String json = slots.get(slot);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败：" + key + "/" + slot + " as " + type, e);
        }
    }

    @Override
    public void delete(SessionKey key) {
        store.remove(key);
    }

    @Override
    public boolean exists(SessionKey key) {
        Map<String, String> slots = store.get(key);
        return slots != null && !slots.isEmpty();
    }
}
