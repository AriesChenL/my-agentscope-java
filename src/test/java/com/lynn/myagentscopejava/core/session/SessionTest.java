package com.lynn.myagentscopejava.core.session;

import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ThinkingBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    @Test
    void inMemorySessionRoundTripsString() {
        Session s = new InMemorySession();
        SessionKey k = SessionKey.of("user-42");
        s.save(k, "greeting", "hello");
        assertEquals("hello", s.get(k, "greeting", String.class).orElseThrow());
        assertTrue(s.exists(k));
        s.delete(k);
        assertFalse(s.exists(k));
    }

    @Test
    void sessionKeyValidatesCharset() {
        assertThrows(IllegalArgumentException.class, () -> SessionKey.of("bad/path"));
        assertThrows(IllegalArgumentException.class, () -> SessionKey.of(""));
        SessionKey ok = SessionKey.of("user_42-abc");
        assertEquals("user_42-abc", ok.value());
    }

    @Test
    void memorySaveLoadPreservesAllBlockTypes() {
        Memory m1 = new InMemoryMemory();
        m1.addMessage(Msg.system("be terse"));
        m1.addMessage(Msg.user("user", "hi"));
        m1.addMessage(Msg.builder()
                .name("bot").role(MsgRole.ASSISTANT)
                .content(
                        new ThinkingBlock("hmm"),
                        new TextBlock("calling tool"),
                        new ToolUseBlock("c1", "add", Map.of("a", 1, "b", 2))
                ).build());
        m1.addMessage(Msg.builder()
                .name("bot").role(MsgRole.TOOL)
                .content(ToolResultBlock.success("c1", "3"))
                .build());

        Session session = new InMemorySession();
        SessionKey key = SessionKey.of("convo-1");
        m1.saveTo(session, key);

        Memory m2 = new InMemoryMemory();
        m2.loadFrom(session, key);

        assertEquals(4, m2.getMessages().size());
        Msg asst = m2.getMessages().get(2);
        assertEquals(MsgRole.ASSISTANT, asst.getRole());
        assertEquals(3, asst.getContent().size());
        assertInstanceOf(ThinkingBlock.class, asst.getContent().getFirst());
        assertInstanceOf(TextBlock.class, asst.getContent().get(1));
        ToolUseBlock tu = (ToolUseBlock) asst.getContent().get(2);
        assertEquals("add", tu.name());
        assertEquals(1, tu.input().get("a"));

        Msg tool = m2.getMessages().get(3);
        assertEquals(MsgRole.TOOL, tool.getRole());
        assertEquals("3", ((ToolResultBlock) tool.getContent().getFirst()).output());
    }

    @Test
    void fileSystemSessionSurvivesNewInstance(@TempDir Path tmp) {
        SessionKey key = SessionKey.of("user-1");
        Memory m1 = new InMemoryMemory();
        m1.addMessage(Msg.user("user", "remember me"));
        m1.addMessage(Msg.assistant("bot", "ok"));

        Session s1 = new FileSystemSession(tmp);
        m1.saveTo(s1, key);

        // Simulate restart: brand-new session pointing to the same dir
        Session s2 = new FileSystemSession(tmp);
        Memory m2 = new InMemoryMemory();
        m2.loadFrom(s2, key);

        assertEquals(2, m2.getMessages().size());
        assertEquals("remember me", m2.getMessages().getFirst().getText());
        assertEquals("ok", m2.getMessages().getLast().getText());
    }

    @Test
    void loadFromMissingKeyIsNoOp() {
        Memory m = new InMemoryMemory();
        m.addMessage(Msg.user("user", "existing"));
        m.loadFrom(new InMemorySession(), SessionKey.of("nope"));
        assertEquals(1, m.getMessages().size()); // unchanged
    }

    @Test
    void loadOverwritesExistingMessages() {
        Memory m = new InMemoryMemory();
        m.addMessage(Msg.user("user", "stale"));

        Memory source = new InMemoryMemory();
        source.addMessage(Msg.user("user", "fresh-1"));
        source.addMessage(Msg.user("user", "fresh-2"));
        Session s = new InMemorySession();
        SessionKey k = SessionKey.of("test");
        source.saveTo(s, k);

        m.loadFrom(s, k);
        assertEquals(2, m.getMessages().size());
        assertEquals("fresh-1", m.getMessages().getFirst().getText());
    }
}
