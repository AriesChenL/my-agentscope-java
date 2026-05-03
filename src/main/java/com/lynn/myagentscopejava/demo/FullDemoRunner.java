package com.lynn.myagentscopejava.demo;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.interruption.AgentInterruptedException;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.memory.CompactingMemory;
import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.SummarizingCompactor;
import com.lynn.myagentscopejava.core.memory.TokenEstimator;
import com.lynn.myagentscopejava.core.session.FileSystemSession;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.session.SessionKey;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 端到端 demo runner：把所有功能（流式、工具调用、Hook、cache 命中、session 持久化、中断）
 * 串起来跑一遍。激活方式：profile=demo + 配置好 DeepSeek API key。
 */
@Component
@Profile("demo")
public class FullDemoRunner implements CommandLineRunner {

    private final ReActAgent agent;
    private final ChatModel chatModel;
    private final ConfigurableApplicationContext ctx;

    public FullDemoRunner(ReActAgent agent, ChatModel chatModel, ConfigurableApplicationContext ctx) {
        this.agent = agent;
        this.chatModel = chatModel;
        this.ctx = ctx;
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            section(1, "STREAMING — 实时打印 DeepSeek 返回的 token");
            streamingDemo();

            section(2, "TOOL CALL + HOOKS — agent 调用计算器工具");
            toolCallDemo();

            section(3, "SESSION PERSISTENCE — 保存、重建 agent、加载、继续对话");
            sessionDemo();

            section(4, "PROMPT CACHE — 第二轮应命中 DeepSeek 自动 cache");
            cacheDemo();

            section(5, "INTERRUPT — 启动长生成后中途取消");
            interruptDemo();

            section(6, "HITL — 工具挂起等待人工审批后恢复");
            hitlDemo();

            section(7, "AUTO-COMPACTION — 上下文超 token 预算时自动摘要");
            compactionDemo();

            System.out.println("\n=== ALL DEMOS DONE ===\n");
        } finally {
            ctx.close();
        }
    }

    /** 1. 流式：直接订阅 chunk 并打印 textDelta，最终 chunk 携带 usage。 */
    private void streamingDemo() {
        System.out.print("> ");
        chatModel.stream(
                List.of(Msg.user("user", "用三句话介绍一下 ReAct agent。")),
                List.of(),
                GenerateOptions.builder().temperature(0.7).build(),
                null
        ).doOnNext(chunk -> {
            if (chunk.textDelta() != null) System.out.print(chunk.textDelta());
            if (chunk.isFinal() && chunk.usage() != null) {
                System.out.println("\n[final] " + chunk.usage());
            }
        }).blockLast();
    }

    /** 2. 工具调用 + Hook：模型自动决定调用 add / multiply 完成多步计算。 */
    private void toolCallDemo() {
        agent.getMemory().clear();
        Msg reply = agent.call(Msg.user("user",
                "帮我算一下 (12 + 34) * 5 是多少？请使用工具。"));
        System.out.println("> Final answer: " + reply.getText());
    }

    /** 3. Session 持久化：保存到磁盘 → 清空 memory → 加载 → 继续对话能记得名字。 */
    private void sessionDemo() {
        Session session = new FileSystemSession(Path.of("./.agent-demo-state"));
        SessionKey key = SessionKey.of("demo-conversation");
        session.delete(key); // 清空旧数据保证 demo 可重复

        agent.getMemory().clear();
        agent.call(Msg.user("user", "我叫 Lin，记住这个。"));
        agent.saveTo(session, key);
        System.out.println("> Saved " + agent.getMemory().getMessages().size() + " messages.");

        // 模拟重启：清空内存后从 session 恢复
        agent.getMemory().clear();
        agent.loadFrom(session, key);
        System.out.println("> Reloaded " + agent.getMemory().getMessages().size() + " messages.");

        Msg reply = agent.call(Msg.user("user", "我刚才告诉你我叫什么？"));
        System.out.println("> " + reply.getText());
    }

    /** 4. Prompt cache：用同样的长 prefix 跑两轮，观察第二轮 cache 命中率上升。 */
    private void cacheDemo() {
        agent.getMemory().clear();
        // 把 system context 拉长，达到 DeepSeek cache 触发阈值（>= 64 tokens）
        String longPrompt = "请基于以下背景回答问题。背景：".repeat(30) + "AgentScope 是一个 agent 框架。";
        System.out.println("Turn 1 (cold cache):");
        agent.call(Msg.user("user", longPrompt + " 它是什么？"));
        System.out.println("Turn 2 (should hit cache — same prefix):");
        agent.call(Msg.user("user", longPrompt + " 它支持什么功能？"));
        // cache 命中率从 [Bot] iter X ChatUsage{...} 日志里能看到
    }

    /**
     * 6. HITL：模型决定调 deduct 工具 → 工具因金额超限抛 ToolSuspendException → agent 挂起 →
     * 模拟人工审批回填结果 → agent 自动恢复并给出最终答复。
     */
    private void hitlDemo() {
        agent.getMemory().clear();

        // 第一次 call：模型会请求 deduct(50000)，超过阈值触发挂起
        Msg first = agent.call(Msg.user("user", "请帮我扣款 50000 元。"));
        System.out.println("> 第一次返回（assistant）：" + first.getText());
        System.out.println("> isAwaitingHumanInput = " + agent.isAwaitingHumanInput());

        if (!agent.isAwaitingHumanInput()) {
            System.out.println("> （模型未触发挂起工具，跳过此 demo）");
            return;
        }

        // 列出待审批的工具调用
        for (ToolUseBlock pending : agent.getPendingToolUses()) {
            System.out.println("> [待审批] tool=" + pending.name() + " args=" + pending.input());
        }

        // 模拟人工点击审批通过按钮，回填工具结果
        var pendingUse = agent.getPendingToolUses().getFirst();
        Msg approval = Msg.builder()
                .name("approver").role(MsgRole.TOOL)
                .content(ToolResultBlock.success(pendingUse.id(), pendingUse.name(),
                        "审批通过，扣款成功，流水号 TXN-20260501-001"))
                .build();
        System.out.println("> [人工审批通过] 回填结果，恢复 agent 执行...");

        Msg finalReply = agent.call(approval);
        System.out.println("> 最终答复：" + finalReply.getText());
        System.out.println("> isAwaitingHumanInput = " + agent.isAwaitingHumanInput());
    }

    /**
     * 7. 自动压缩：手工组装一个低 token 阈值的 agent，连续聊几轮后历史会被自动摘要。
     * 用低 token 阈值（300）+ 真实 DeepSeek 模型，方便在控制台看到压缩日志与命中率变化。
     */
    private void compactionDemo() {
        TokenEstimator estimator = TokenEstimator.approximate();
        SummarizingCompactor compactor = SummarizingCompactor.builder()
                .summarizer(chatModel)
                .maxTokens(300)        // 故意调小，让几轮就触发
                .keepRecent(2)
                .estimator(estimator)
                .build();
        CompactingMemory memory = new CompactingMemory(new InMemoryMemory(), compactor);
        ReActAgent compactingAgent = ReActAgent.builder()
                .name("CompactBot")
                .sysPrompt("你是一个简洁的助手。回答尽量短。")
                .model(chatModel)
                .toolkit(new Toolkit())
                .memory(memory)
                .generateOptions(GenerateOptions.defaults())
                .build();

        String[] prompts = {
                "请用一段话介绍 ReAct agent 的工作原理。",
                "再用一段话介绍 prompt cache 的好处。",
                "再介绍一下流式响应的优势。",
                "最后总结一下我们刚才聊的三个话题。"
        };
        for (int i = 0; i < prompts.length; i++) {
            System.out.println("\n[第 " + (i + 1) + " 轮] user: " + prompts[i]);
            int beforeMsgs = memory.getMessages().size();
            int beforeTokens = estimator.estimate(memory.getMessages());
            Msg reply = compactingAgent.call(Msg.user("user", prompts[i]));
            int afterMsgs = memory.getMessages().size();
            int afterTokens = estimator.estimate(memory.getMessages());

            String preview = reply.getText();
            if (preview.length() > 100) preview = preview.substring(0, 100) + "...";
            System.out.println("  bot: " + preview);
            System.out.println("  memory: " + beforeMsgs + " 条/" + beforeTokens + " token  →  "
                    + afterMsgs + " 条/" + afterTokens + " token"
                    + "  累计压缩 " + memory.getCompactionCount() + " 次");
        }
        System.out.println("\n> 最终 memory 第一条："
                + memory.getMessages().getFirst().getText().substring(0,
                Math.min(80, memory.getMessages().getFirst().getText().length())) + "...");
    }

    /** 5. Interrupt：另起线程跑长生成，主线程 800ms 后调用 interrupt() 立即中断。 */
    private void interruptDemo() throws InterruptedException {
        agent.getMemory().clear();
        Thread caller = new Thread(() -> {
            try {
                agent.call(Msg.user("user", "请详细解释一下 Transformer 架构，写 2000 字。"));
                System.out.println("> (call returned normally — interrupt arrived too late)");
            } catch (AgentInterruptedException e) {
                System.out.println("> Caught AgentInterruptedException — call was aborted.");
            }
        });
        caller.start();
        Thread.sleep(800);
        System.out.println("> Sending interrupt after 800ms...");
        agent.interrupt();
        caller.join(5000);
    }

    private static void section(int n, String title) {
        System.out.println("\n=== " + n + ". " + title + " ===");
    }
}
