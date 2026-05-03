package com.lynn.myagentscopejava.core.conversation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresConversationDirectoryIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentscope")
            .withUsername("agent")
            .withPassword("secret");

    static JdbcTemplate jdbc;
    static PostgresConversationDirectory dir;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        dir = new PostgresConversationDirectory(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE TABLE conversations");
    }

    @Test
    void createReturnsFreshConversation() {
        Conversation c = dir.create("alice", "我的对话", "openai");
        assertNotNull(c.id());
        assertTrue(c.id().startsWith("c-"));
        assertEquals("我的对话", c.title());
        assertEquals("openai", c.provider());
        assertTrue(c.createdAt() > 0);
    }

    @Test
    void createWithBlankTitleUsesDefault() {
        Conversation c = dir.create("alice", "", null);
        assertEquals("新对话", c.title());
    }

    @Test
    void listReturnsAllUserConversations() {
        Conversation c1 = dir.create("alice", "first", "openai");
        Conversation c2 = dir.create("alice", "second", "anthropic");
        dir.create("bob", "bob's", null);  // 不应出现在 alice 的列表

        List<Conversation> list = dir.list("alice");
        assertEquals(2, list.size());
        // 按 updated_at DESC，刚创建的 c2 在前
        assertEquals(c2.id(), list.get(0).id());
        assertEquals(c1.id(), list.get(1).id());
    }

    @Test
    void getReturnsExistingConversation() {
        Conversation c = dir.create("alice", "x", null);
        Optional<Conversation> got = dir.get("alice", c.id());
        assertTrue(got.isPresent());
        assertEquals(c.id(), got.get().id());
    }

    @Test
    void getReturnsEmptyForMissing() {
        assertTrue(dir.get("alice", "c-nope").isEmpty());
    }

    @Test
    void renameUpdatesTitleAndTimestamp() throws InterruptedException {
        Conversation c = dir.create("alice", "old", null);
        long createdAt = c.updatedAt();
        Thread.sleep(10);  // 让 updated_at 能看到差异
        Optional<Conversation> renamed = dir.rename("alice", c.id(), "new");
        assertTrue(renamed.isPresent());
        assertEquals("new", renamed.get().title());
        assertTrue(renamed.get().updatedAt() >= createdAt);
    }

    @Test
    void renameReturnsEmptyForMissing() {
        assertTrue(dir.rename("alice", "c-nope", "x").isEmpty());
    }

    @Test
    void deleteReturnsTrueWhenExists() {
        Conversation c = dir.create("alice", "x", null);
        assertTrue(dir.delete("alice", c.id()));
        assertFalse(dir.delete("alice", c.id()));  // 二次删除返回 false
        assertTrue(dir.get("alice", c.id()).isEmpty());
    }

    @Test
    void touchUpdatesTimestamp() throws InterruptedException {
        Conversation c1 = dir.create("alice", "first", null);
        Thread.sleep(5);
        Conversation c2 = dir.create("alice", "second", null);
        // 此时 list 顺序：c2 (新) → c1 (旧)
        assertEquals(c2.id(), dir.list("alice").get(0).id());

        Thread.sleep(5);
        dir.touch("alice", c1.id());
        // touch 后 c1 应该顶到最前
        assertEquals(c1.id(), dir.list("alice").get(0).id());
    }
}
