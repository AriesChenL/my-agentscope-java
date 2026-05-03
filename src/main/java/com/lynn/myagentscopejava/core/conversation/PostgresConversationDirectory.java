package com.lynn.myagentscopejava.core.conversation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL 实现的 {@link ConversationDirectory}。
 *
 * <p>表结构由 {@code db/migration/V1__init_distributed_schema.sql} 创建：
 * <pre>
 * conversations(id, user_id, conv_id, title, provider, created_at, updated_at)
 *   UNIQUE (user_id, conv_id)
 * </pre>
 *
 * <p>所有写操作走单条 SQL，DB 自身保证 (user_id, conv_id) 唯一约束的并发安全。
 */
public class PostgresConversationDirectory implements ConversationDirectory {

    private final JdbcTemplate jdbc;

    public PostgresConversationDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Conversation> ROW_MAPPER = (rs, idx) ->
            new Conversation(
                    rs.getString("conv_id"),
                    rs.getString("title"),
                    rs.getString("provider"),
                    rs.getTimestamp("created_at").getTime(),
                    rs.getTimestamp("updated_at").getTime()
            );

    @Override
    public List<Conversation> list(String userId) {
        return jdbc.query(
                "SELECT conv_id, title, provider, created_at, updated_at "
                        + "FROM conversations WHERE user_id = ? "
                        + "ORDER BY updated_at DESC",
                ROW_MAPPER, userId);
    }

    @Override
    public Conversation create(String userId, String title, String provider) {
        String convId = "c-" + UUID.randomUUID().toString().substring(0, 8);
        String safeTitle = (title == null || title.isBlank()) ? "新对话" : title;
        try {
            jdbc.update(
                    "INSERT INTO conversations (user_id, conv_id, title, provider) VALUES (?, ?, ?, ?)",
                    userId, convId, safeTitle, provider);
        } catch (DuplicateKeyException e) {
            // UUID 撞车概率极低，但兜底重试一次
            return create(userId, title, provider);
        }
        return get(userId, convId).orElseThrow(() ->
                new IllegalStateException("create 后立即 get 不到：" + userId + "/" + convId));
    }

    @Override
    public boolean delete(String userId, String convId) {
        int n = jdbc.update(
                "DELETE FROM conversations WHERE user_id = ? AND conv_id = ?",
                userId, convId);
        return n > 0;
    }

    @Override
    public Optional<Conversation> get(String userId, String convId) {
        try {
            Conversation c = jdbc.queryForObject(
                    "SELECT conv_id, title, provider, created_at, updated_at "
                            + "FROM conversations WHERE user_id = ? AND conv_id = ?",
                    ROW_MAPPER, userId, convId);
            return Optional.ofNullable(c);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Conversation> rename(String userId, String convId, String title) {
        if (title == null || title.isBlank()) return get(userId, convId);
        int n = jdbc.update(
                "UPDATE conversations SET title = ?, updated_at = NOW() "
                        + "WHERE user_id = ? AND conv_id = ?",
                title, userId, convId);
        return n > 0 ? get(userId, convId) : Optional.empty();
    }

    @Override
    public void touch(String userId, String convId) {
        jdbc.update(
                "UPDATE conversations SET updated_at = NOW() WHERE user_id = ? AND conv_id = ?",
                userId, convId);
    }
}
