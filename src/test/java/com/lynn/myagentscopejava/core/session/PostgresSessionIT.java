package com.lynn.myagentscopejava.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresSession} 集成测试，用 Testcontainers 起一个真实 PG 16。
 *
 * <p>需要本机有 Docker（容器镜像 ~80MB）。无 Docker 环境下整个测试类会被
 * Testcontainers 自动跳过（DockerCheckRule）。
 */
@Testcontainers
class PostgresSessionIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentscope")
            .withUsername("agent")
            .withPassword("secret");

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static PostgresSession session;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        dataSource = ds;
        // 跑 Flyway 建表
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        session = new PostgresSession(jdbc, new ObjectMapper());
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE TABLE session_memory");
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        Optional<Msg[]> got = session.get(SessionKey.of("nope"), "memory", Msg[].class);
        assertTrue(got.isEmpty());
    }

    @Test
    void existsFalseWhenNoData() {
        assertFalse(session.exists(SessionKey.of("nope")));
    }

    @Test
    void saveAndGetRoundtripPlainObject() {
        SessionKey key = SessionKey.of("alice__c-001");
        Map<String, Object> data = Map.of("foo", "bar", "n", 42);
        session.save(key, "memory", data);

        Optional<Map> got = session.get(key, "memory", Map.class);
        assertTrue(got.isPresent());
        assertEquals("bar", got.get().get("foo"));
        assertEquals(42, got.get().get("n"));
        assertTrue(session.exists(key));
    }

    @Test
    void saveOverwritesPreviousValue() {
        SessionKey key = SessionKey.of("alice__c-002");
        session.save(key, "memory", Map.of("v", 1));
        session.save(key, "memory", Map.of("v", 2));   // UPSERT 覆盖
        Map got = session.get(key, "memory", Map.class).orElseThrow();
        assertEquals(2, got.get("v"));
    }

    @Test
    void msgArrayWithPolymorphicBlocks() {
        // 验证 Jackson 多态序列化（@JsonTypeInfo on ContentBlock）穿透 JSONB 往返
        SessionKey key = SessionKey.of("alice__c-003");
        Msg[] arr = new Msg[]{
                Msg.user("alice", "扣款 50000"),
                Msg.builder().name("bot").role(MsgRole.ASSISTANT).content(List.of(
                        new TextBlock("准备扣款"),
                        new ToolUseBlock("c1", "deduct", Map.of("amount", 50000))
                )).build(),
                Msg.builder().name("bot").role(MsgRole.TOOL).content(
                        ToolResultBlock.pending("c1", "deduct", "金额超阈值，请审批")
                ).build()
        };
        session.save(key, "memory", arr);

        Msg[] back = session.get(key, "memory", Msg[].class).orElseThrow();
        assertEquals(3, back.length);
        // 第二条：assistant 含 TextBlock + ToolUseBlock
        assertEquals(MsgRole.ASSISTANT, back[1].getRole());
        assertEquals(1, back[1].getBlocks(TextBlock.class).size());
        assertEquals(1, back[1].getBlocks(ToolUseBlock.class).size());
        ToolUseBlock use = back[1].getBlocks(ToolUseBlock.class).getFirst();
        assertEquals("deduct", use.name());
        assertEquals(50000, use.input().get("amount"));
        // 第三条：TOOL 含 pending ToolResultBlock
        ToolResultBlock res = back[2].getBlocks(ToolResultBlock.class).getFirst();
        assertEquals("c1", res.id());
        assertTrue(res.pending());
        assertFalse(res.isError());
    }

    @Test
    void deleteRemovesAllSlots() {
        SessionKey key = SessionKey.of("alice__c-004");
        session.save(key, "memory", Map.of("v", 1));
        session.save(key, "long_term", Map.of("v", 2));
        assertTrue(session.exists(key));
        session.delete(key);
        assertFalse(session.exists(key));
        assertTrue(session.get(key, "memory", Map.class).isEmpty());
        assertTrue(session.get(key, "long_term", Map.class).isEmpty());
    }

    @Test
    void differentKeysAreIsolated() {
        session.save(SessionKey.of("alice__a"), "memory", Map.of("who", "alice"));
        session.save(SessionKey.of("bob__b"), "memory", Map.of("who", "bob"));
        Map a = session.get(SessionKey.of("alice__a"), "memory", Map.class).orElseThrow();
        Map b = session.get(SessionKey.of("bob__b"), "memory", Map.class).orElseThrow();
        assertEquals("alice", a.get("who"));
        assertEquals("bob", b.get("who"));
    }

    @Test
    void saveIncrementsVersion() {
        SessionKey key = SessionKey.of("alice__c-005");
        session.save(key, "memory", Map.of("v", 1));
        Long v1 = jdbc.queryForObject(
                "SELECT version FROM session_memory WHERE session_key = ?",
                Long.class, key.value());
        session.save(key, "memory", Map.of("v", 2));
        Long v2 = jdbc.queryForObject(
                "SELECT version FROM session_memory WHERE session_key = ?",
                Long.class, key.value());
        assertEquals(0L, v1);
        assertEquals(1L, v2);
    }
}
