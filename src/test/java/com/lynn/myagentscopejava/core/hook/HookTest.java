package com.lynn.myagentscopejava.core.hook;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookTest {

    static class ScriptedModel implements ChatModel {
        final List<List<ChatChunk>> turns;
        int turn = 0;
        final List<List<Msg>> seenMessages = new ArrayList<>();

        ScriptedModel(List<List<ChatChunk>> turns) { this.turns = turns; }

        @Override public String getModelName() { return "scripted"; }
        @Override public Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                                GenerateOptions options, CancellationToken token,
                                                String sessionId) {
            seenMessages.add(List.copyOf(messages));
            return Flux.fromIterable(turns.get(turn++));
        }
    }

    public static class Calc {
        @Tool(description = "add")
        public int add(@ToolParam(description = "x") int a, @ToolParam(description = "y") int b) {
            return a + b;
        }
    }

    @Test
    void preReasoningCanInjectMessage() {
        ScriptedModel model = new ScriptedModel(List.of(List.of(
                ChatChunk.text("done"),
                ChatChunk.finish("stop", ChatUsage.builder().build())
        )));
        Hook injector = event -> {
            if (event instanceof PreReasoningEvent e) {
                List<Msg> mod = new ArrayList<>(e.getMessages());
                mod.add(0, Msg.system("INJECTED-CONTEXT"));
                e.setMessages(mod);
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).hook(injector).build();

        agent.call(Msg.user("user", "hi"));

        Msg first = model.seenMessages.getFirst().getFirst();
        assertEquals(MsgRole.SYSTEM, first.getRole());
        assertEquals("INJECTED-CONTEXT", first.getText());
    }

    @Test
    void postReasoningCanRequestStop() {
        ScriptedModel model = new ScriptedModel(List.of(
                List.of(ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "c1", "add", "{\"a\":1,\"b\":1}"))),
                        ChatChunk.finish("tool_calls", ChatUsage.builder().build()))));
        Hook stopper = event -> {
            if (event instanceof PostReasoningEvent e) e.requestStop();
        };
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(new Toolkit().registerObject(new Calc()))
                .hook(stopper).build();

        agent.call(Msg.user("user", "go"));
        // Loop stopped after first reasoning — model called only once, no tool execution
        assertEquals(1, model.turn);
    }

    @Test
    void preActingCanRewriteToolArgs() {
        ScriptedModel model = new ScriptedModel(List.of(
                List.of(ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "c1", "add", "{\"a\":1,\"b\":1}"))),
                        ChatChunk.finish("tool_calls", ChatUsage.builder().build())),
                List.of(ChatChunk.text("answer is 100"),
                        ChatChunk.finish("stop", ChatUsage.builder().build()))));

        Hook rewriter = event -> {
            if (event instanceof PreActingEvent e) {
                ToolUseBlock orig = e.getToolUse();
                e.setToolUse(new ToolUseBlock(orig.id(), orig.name(), Map.of("a", 50, "b", 50)));
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(new Toolkit().registerObject(new Calc()))
                .hook(rewriter).build();

        agent.call(Msg.user("user", "go"));
        ToolResultBlock r = agent.getMemory().getMessages().stream()
                .flatMap(m -> m.getBlocks(ToolResultBlock.class).stream())
                .findFirst().orElseThrow();
        assertEquals("100", r.output());
    }

    @Test
    void postActingCanRedactResult() {
        ScriptedModel model = new ScriptedModel(List.of(
                List.of(ChatChunk.toolCalls(List.of(new ToolCallDelta(0, "c1", "add", "{\"a\":1,\"b\":1}"))),
                        ChatChunk.finish("tool_calls", ChatUsage.builder().build())),
                List.of(ChatChunk.text("ok"), ChatChunk.finish("stop", ChatUsage.builder().build()))));

        Hook redactor = event -> {
            if (event instanceof PostActingEvent e) {
                e.setResult(ToolResultBlock.success(e.getResult().id(), "[REDACTED]"));
            }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).toolkit(new Toolkit().registerObject(new Calc()))
                .hook(redactor).build();

        agent.call(Msg.user("user", "go"));
        ToolResultBlock r = agent.getMemory().getMessages().stream()
                .flatMap(m -> m.getBlocks(ToolResultBlock.class).stream())
                .findFirst().orElseThrow();
        assertEquals("[REDACTED]", r.output());
    }

    @Test
    void hooksFireInPriorityOrder() {
        ScriptedModel model = new ScriptedModel(List.of(List.of(
                ChatChunk.text("ok"), ChatChunk.finish("stop", ChatUsage.builder().build()))));
        AtomicInteger counter = new AtomicInteger(0);
        StringBuilder order = new StringBuilder();

        Hook later = new Hook() {
            @Override public int priority() { return 10; }
            @Override public void onEvent(HookEvent e) { order.append(counter.incrementAndGet()).append(":B "); }
        };
        Hook earlier = new Hook() {
            @Override public int priority() { return 1; }
            @Override public void onEvent(HookEvent e) { order.append(counter.incrementAndGet()).append(":A "); }
        };
        ReActAgent agent = ReActAgent.builder()
                .name("Bot").model(model).hook(later).hook(earlier).build();

        agent.call(Msg.user("user", "hi"));

        // pre + post for one iteration = 4 firings (each hook fires twice)
        // earlier (priority 1) must always come before later (priority 10)
        assertTrue(order.toString().startsWith("1:A 2:B 3:A 4:B"), "got: " + order);
    }

    @Test
    void hookExceptionDoesNotBreakLoop() {
        ScriptedModel model = new ScriptedModel(List.of(List.of(
                new ChatChunk("done", null, null,
                        ChatUsage.builder().inputTokens(1).outputTokens(1).build(), "stop"))));
        Hook bad = event -> { throw new RuntimeException("boom"); };
        ReActAgent agent = ReActAgent.builder().name("Bot").model(model).hook(bad).build();

        Msg reply = agent.call(Msg.user("user", "hi"));
        assertEquals("done", reply.getText()); // loop continued despite hook failure
    }
}
