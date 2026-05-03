package com.lynn.myagentscopejava.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * PostgreSQL 实现的 {@link Session}，用 JSONB 列存储任意结构化对象。
 *
 * <p>表结构由 Flyway 脚本 {@code db/migration/V1__init_distributed_schema.sql} 创建：
 * <pre>
 * session_memory(id, session_key, slot, payload JSONB, version, updated_at)
 *   UNIQUE (session_key, slot)
 * </pre>
 *
 * <p><b>语义</b>
 * <ul>
 *   <li>{@link #save} 用 PostgreSQL 的 {@code INSERT ... ON CONFLICT (session_key, slot) DO UPDATE}
 *       实现 UPSERT，单条 SQL 完成"有则更新无则插入"，避免先 SELECT 再分支</li>
 *   <li>{@link #get} 把 JSONB 直接读为 String，由 Jackson 还原对象</li>
 *   <li>{@link #delete} 删除该 session_key 下所有 slot 行</li>
 *   <li>{@link #exists} 看是否有任意 slot 行</li>
 * </ul>
 *
 * <p>{@link com.lynn.myagentscopejava.core.message.ContentBlock} 等多态字段已用 {@code @JsonTypeInfo}
 * 标注，本实现用框架默认 {@link ObjectMapper} 即可正确往返。
 */
public class PostgresSession implements Session {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresSession(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(SessionKey key, String slot, Object value) {
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败：" + key + "/" + slot, e);
        }
        // ON CONFLICT 触发 UPDATE：payload 整段覆盖、version 自增、updated_at 刷新
        // 用 ?::jsonb SQL cast 让 PG 把字符串参数自动转 JSONB，避免引 PGobject（runtime scope 不可见）
        jdbc.update(
                "INSERT INTO session_memory (session_key, slot, payload) "
                        + "VALUES (?, ?, ?::jsonb) "
                        + "ON CONFLICT (session_key, slot) DO UPDATE "
                        + "SET payload = EXCLUDED.payload, "
                        + "    version = session_memory.version + 1, "
                        + "    updated_at = NOW()",
                key.value(), slot, json);
    }

    @Override
    public <T> Optional<T> get(SessionKey key, String slot, Class<T> type) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT payload::text FROM session_memory WHERE session_key = ? AND slot = ?",
                    String.class,
                    key.value(), slot);
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readValue(json, type));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("加载失败：" + key + "/" + slot, e);
        }
    }

    @Override
    public void delete(SessionKey key) {
        jdbc.update("DELETE FROM session_memory WHERE session_key = ?", key.value());
    }

    @Override
    public boolean exists(SessionKey key) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_memory WHERE session_key = ?",
                Integer.class,
                key.value());
        return cnt != null && cnt > 0;
    }
}
