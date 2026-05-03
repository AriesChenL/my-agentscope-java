package com.lynn.myagentscopejava.core.service;

import com.lynn.myagentscopejava.core.interruption.AgentInterruptedException;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.model.ChatChunk;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatUsage;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.model.ToolCallDelta;
import com.lynn.myagentscopejava.core.session.FileSystemSession;
import com.lynn.myagentscopejava.core.session.InMemorySession;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.session.SessionKey;
import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import com.lynn.myagentscopejava.core.tool.ToolSuspendException;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多用户隔离 + 并发安全测试。
 */
class ChatServiceTest {

    /** 回声模型：把最后一条用户消息直接 echo 出来，便于断言"哪条历史"。 */
    static class EchoModel implements ChatModel {
        @Override public String getModelName() { return "echo"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            String history = messages.stream()
                    .filter(m -> m.getRole() == MsgRole.USER)
                    .map(Msg::getText)
                    .reduce((a, b) -> a + "|" + b).orElse("");
            return Flux.just(
                    ChatChunk.text(history),
                    ChatChunk.finish("stop", ChatUsage.builder().build()));
        }
    }

    /** 在 chat() 期间阻塞直到外部 release，用于测试 interrupt 场景。 */
    static class BlockingModel implements ChatModel {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public String getModelName() { return "blocking"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            return Flux.create(sink -> {
                entered.countDown();
                if (token != null) token.onCancel(release::countDown);
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (token != null && token.isCancelled()) sink.error(new AgentInterruptedException("cancelled"));
                else { sink.next(ChatChunk.text("ok"));
                    sink.next(ChatChunk.finish("stop", ChatUsage.builder().build()));
                    sink.complete(); }
            });
        }
    }

    public static class ApprovalRequired {
        @Tool(description = "需审批的扣款")
        public String deduct(@ToolParam(description = "金额") int amount) {
            throw new ToolSuspendException("需要人工审批：" + amount);
        }
    }

    private ChatService newService(ChatModel model, Session session) {
        return ChatService.builder()
                .modelRouter(singletonRouter(model))
                .session(session)
                .agentName("Bot")
                .sysPrompt("be terse")
                .build();
    }

    /** 测试用：把单个 ChatModel 包成 router（所有会话都用它）。 */
    private static com.lynn.myagentscopejava.core.model.ChatModelRouter singletonRouter(ChatModel m) {
        return new com.lynn.myagentscopejava.core.model.ChatModelRouter(
                java.util.Map.of("test", m), "test", k -> null);
    }

    @Test
    void differentUsersHaveIsolatedHistory() {
        ChatService svc = newService(new EchoModel(), new InMemorySession());

        SessionKey alice = SessionKey.of("alice");
        SessionKey bob = SessionKey.of("bob");

        svc.chat(alice, Msg.user("alice", "msg-a1"));
        svc.chat(bob, Msg.user("bob", "msg-b1"));
        Msg aliceReply = svc.chat(alice, Msg.user("alice", "msg-a2"));
        Msg bobReply = svc.chat(bob, Msg.user("bob", "msg-b2"));

        // EchoModel 把 user 历史拼成 "msg1|msg2"
        // alice 的回复只能含 alice 的消息，不能见到 bob 的
        assertEquals("msg-a1|msg-a2", aliceReply.getText());
        assertEquals("msg-b1|msg-b2", bobReply.getText());
        assertFalse(aliceReply.getText().contains("msg-b"));
        assertFalse(bobReply.getText().contains("msg-a"));
    }

    @Test
    void historySurvivesAcrossChatServiceInstances(@TempDir Path tmp) {
        Session session = new FileSystemSession(tmp);

        // 第一个 service 实例处理首轮
        ChatService svc1 = newService(new EchoModel(), session);
        svc1.chat(SessionKey.of("u1"), Msg.user("u1", "first"));

        // 模拟重启：全新 service 实例 + 同样的 session 目录
        ChatService svc2 = newService(new EchoModel(), session);
        Msg reply = svc2.chat(SessionKey.of("u1"), Msg.user("u1", "second"));

        assertEquals("first|second", reply.getText());
    }

    @Test
    void concurrentDifferentUsersDoNotInterfere() throws Exception {
        ChatService svc = newService(new EchoModel(), new InMemorySession());
        int users = 20;
        int turnsPerUser = 5;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch done = new CountDownLatch(users);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int u = 0; u < users; u++) {
            final int userId = u;
            pool.submit(() -> {
                try {
                    SessionKey key = SessionKey.of("user-" + userId);
                    Msg lastReply = null;
                    for (int t = 0; t < turnsPerUser; t++) {
                        lastReply = svc.chat(key, Msg.user("u" + userId, "u" + userId + "-t" + t));
                    }
                    // 最终回复应该恰好是该用户所有消息拼接，不能混入别人的
                    String expected = java.util.stream.IntStream.range(0, turnsPerUser)
                            .mapToObj(t -> "u" + userId + "-t" + t)
                            .reduce((a, b) -> a + "|" + b).orElseThrow();
                    if (!expected.equals(lastReply.getText())) {
                        failure.set(new AssertionError(
                                "用户 " + userId + " 历史污染，期望 " + expected
                                        + " 实际 " + lastReply.getText()));
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    void concurrentSameUserSerializes() throws Exception {
        // 同一 key 的两个并发请求必须串行执行（消息不能丢）
        ChatService svc = newService(new EchoModel(), new InMemorySession());
        SessionKey key = SessionKey.of("shared");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> { try { svc.chat(key, Msg.user("u", "A")); } finally { done.countDown(); } });
        pool.submit(() -> { try { svc.chat(key, Msg.user("u", "B")); } finally { done.countDown(); } });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        // 第三次 chat：历史里必须有 A 和 B 两条 user，两次写入都不能丢
        Msg third = svc.chat(key, Msg.user("u", "C"));
        assertTrue(third.getText().contains("A") && third.getText().contains("B")
                && third.getText().contains("C"),
                "并发请求丢失了消息：" + third.getText());
    }

    @Test
    void hitlIsPerSession() {
        Toolkit kit = new Toolkit().registerObject(new ApprovalRequired());
        // turn 1：deduct(100) → 触发挂起
        ChatModel model = new ChatModel() {
            int turn = 0;
            @Override public String getModelName() { return "scripted"; }
            @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                   GenerateOptions options, CancellationToken token,
                                                   String sessionId) {
                turn++;
                return Flux.just(
                        ChatChunk.toolCalls(List.of(
                                new ToolCallDelta(0, "call_" + turn, "deduct", "{\"amount\":100}"))),
                        ChatChunk.finish("tool_calls", ChatUsage.builder().build()));
            }
        };
        ChatService svc = ChatService.builder()
                .modelRouter(singletonRouter(model)).session(new InMemorySession())
                .toolkit(kit).agentName("Bot").build();

        SessionKey alice = SessionKey.of("alice");
        SessionKey bob = SessionKey.of("bob");

        svc.chat(alice, Msg.user("alice", "扣款"));
        // alice 进入挂起状态；bob 应该完全不受影响
        assertTrue(svc.isAwaitingHumanInput(alice));
        assertFalse(svc.isAwaitingHumanInput(bob));
        assertEquals(1, svc.getPendingToolUses(alice).size());
        assertEquals(0, svc.getPendingToolUses(bob).size());

        // bob 自己也触发一次挂起
        svc.chat(bob, Msg.user("bob", "扣款"));
        assertTrue(svc.isAwaitingHumanInput(bob));
        // alice 和 bob 的 pending tool use id 不能相同（独立会话独立流水）
        String aliceId = svc.getPendingToolUses(alice).getFirst().id();
        String bobId = svc.getPendingToolUses(bob).getFirst().id();
        assertNotEquals(aliceId, bobId);
    }

    @Test
    void interruptOnlyAffectsSpecifiedSession() throws Exception {
        BlockingModel blocking = new BlockingModel();
        ChatService svc = newService(blocking, new InMemorySession());
        SessionKey alice = SessionKey.of("alice");
        SessionKey bob = SessionKey.of("bob");

        AtomicReference<Throwable> aliceErr = new AtomicReference<>();
        Thread aliceCall = new Thread(() -> {
            try { svc.chat(alice, Msg.user("a", "hi")); }
            catch (Throwable t) { aliceErr.set(t); }
        });
        aliceCall.start();
        assertTrue(blocking.entered.await(2, TimeUnit.SECONDS));

        // 此时 alice 的 chat 卡在 model 里；尝试 interrupt bob 应返回 false（bob 没在跑）
        assertFalse(svc.interrupt(bob));
        // interrupt alice 应该成功
        assertTrue(svc.interrupt(alice));
        aliceCall.join(2000);
        assertInstanceOf(AgentInterruptedException.class, aliceErr.get());
    }

    @Test
    void deleteSessionWipesHistory() {
        ChatService svc = newService(new EchoModel(), new InMemorySession());
        SessionKey key = SessionKey.of("u");
        svc.chat(key, Msg.user("u", "first"));
        svc.deleteSession(key);
        Msg reply = svc.chat(key, Msg.user("u", "fresh"));
        assertEquals("fresh", reply.getText()); // 没有上一轮的 "first" 历史
    }
}
