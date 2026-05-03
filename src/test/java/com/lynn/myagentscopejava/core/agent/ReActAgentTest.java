package com.lynn.myagentscopejava.core.agent;

import com.lynn.myagentscopejava.core.interruption.AgentInterruptedException;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatChunk;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatUsage;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.model.ToolCallDelta;
import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {

    /** Streams a fixed text reply on each call. */
    static class FakeChatModel implements ChatModel {
        List<List<Msg>> calls = new ArrayList<>();
        String reply = "pong";

        @Override public String getModelName() { return "fake"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            calls.add(List.copyOf(messages));
            return Flux.just(
                    ChatChunk.text(reply),
                    ChatChunk.finish("stop", ChatUsage.builder().inputTokens(10).outputTokens(2).build())
            );
        }
    }

    /** Returns scripted responses turn-by-turn. */
    static class ScriptedChatModel implements ChatModel {
        final List<List<ChatChunk>> turns;
        int turn = 0;
        List<List<Msg>> calls = new ArrayList<>();
        ScriptedChatModel(List<List<ChatChunk>> turns) { this.turns = turns; }

        @Override public String getModelName() { return "scripted"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            calls.add(List.copyOf(messages));
            return Flux.fromIterable(turns.get(turn++));
        }
    }

    /** Hangs inside stream() until the cancellation token fires. */
    static class BlockingChatModel implements ChatModel {
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

    public static class CalcTools {
        @Tool(description = "Add two integers")
        public int add(@ToolParam(description = "first") int a,
                       @ToolParam(description = "second") int b) {
            return a + b;
        }
    }

    @Test
    void singleTurnWithoutTools() {
        FakeChatModel model = new FakeChatModel();
        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").sysPrompt("be terse").model(model).memory(memory).build();

        Msg reply = agent.call(Msg.user("user", "ping"));

        assertEquals("pong", reply.getText());
        assertEquals(MsgRole.ASSISTANT, reply.getRole());
        assertEquals(2, memory.getMessages().size());
    }

    @Test
    void reactLoopExecutesToolThenFinishes() {
        // Turn 1: model asks for add(2, 3). Turn 2: model gives final text answer.
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "call_1", "add", "{\"a\":2,\"b\":3}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().inputTokens(20).outputTokens(5).build())
        );
        List<ChatChunk> turn2 = List.of(
                ChatChunk.text("The result is 5"),
                ChatChunk.finish("stop", ChatUsage.builder().inputTokens(30).outputTokens(4).build())
        );
        ScriptedChatModel model = new ScriptedChatModel(List.of(turn1, turn2));

        Toolkit toolkit = new Toolkit().registerObject(new CalcTools());
        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(toolkit).memory(memory).build();

        Msg reply = agent.call(Msg.user("user", "what is 2 + 3?"));

        assertEquals("The result is 5", reply.getText());
        // memory: user + assistant(toolUse) + tool(result) + assistant(text) = 4
        assertEquals(4, memory.getMessages().size());
        Msg toolMsg = memory.getMessages().get(2);
        assertEquals(MsgRole.TOOL, toolMsg.getRole());
        ToolResultBlock result = toolMsg.getBlocks(ToolResultBlock.class).getFirst();
        assertEquals("call_1", result.id());
        assertEquals("5", result.output());
        assertFalse(result.isError());
        // model was called twice
        assertEquals(2, model.calls.size());
    }

    @Test
    void reactLoopHaltsAtMaxItersAndAddsFallback() {
        // Model keeps requesting add(0,0) forever
        List<ChatChunk> looping = List.of(
                ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "call_x", "add", "{\"a\":0,\"b\":0}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build())
        );
        ScriptedChatModel model = new ScriptedChatModel(List.of(looping, looping, looping));
        Toolkit toolkit = new Toolkit().registerObject(new CalcTools());
        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(toolkit).memory(memory).maxIters(2).build();

        agent.call(Msg.user("user", "loop"));

        assertEquals(2, model.calls.size());
        // user + (asst + tool) * 2 + fallback = 6
        assertEquals(6, memory.getMessages().size());
        Msg fallback = memory.getMessages().getLast();
        assertTrue(fallback.getText().contains("max iterations"));
    }

    @Test
    void interruptAbortsInFlightCall() throws Exception {
        BlockingChatModel model = new BlockingChatModel();
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).build();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { agent.call(Msg.user("user", "hi")); }
            catch (Throwable t) { thrown.set(t); }
        });
        caller.start();
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));
        assertTrue(agent.isRunning());
        agent.interrupt();
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertInstanceOf(AgentInterruptedException.class, thrown.get());
    }

    @Test
    void unknownToolReturnsErrorResult() {
        List<ChatChunk> turn1 = List.of(
                ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "call_z", "nope", "{}"))),
                ChatChunk.finish("tool_calls", ChatUsage.builder().build())
        );
        List<ChatChunk> turn2 = List.of(
                ChatChunk.text("sorry, that tool doesn't exist"),
                ChatChunk.finish("stop", ChatUsage.builder().build())
        );
        ScriptedChatModel model = new ScriptedChatModel(List.of(turn1, turn2));
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(new Toolkit()).build();

        agent.call(Msg.user("user", "do something"));
        // Find the tool result msg
        ToolResultBlock r = agent.getMemory().getMessages().stream()
                .flatMap(m -> m.getBlocks(ToolResultBlock.class).stream())
                .findFirst().orElseThrow();
        assertTrue(r.isError());
        assertTrue(r.output().contains("Unknown tool"));
    }
}
