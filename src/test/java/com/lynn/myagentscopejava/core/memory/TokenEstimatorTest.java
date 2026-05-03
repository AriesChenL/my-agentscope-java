package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.TextBlock;
import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void approximateScalesWithTextLength() {
        TokenEstimator est = TokenEstimator.approximate();
        Msg short_ = Msg.user("u", "hi");
        Msg long_ = Msg.user("u", "a".repeat(280));
        // 280 字符按 2.8 char/token 约为 100 token
        int t1 = est.estimate(short_);
        int t2 = est.estimate(long_);
        assertTrue(t2 > t1 * 10, "长文本 token 数应远大于短文本，实际：" + t1 + " vs " + t2);
        assertTrue(t2 >= 95 && t2 <= 110, "280 字符约应估算为 100 token，实际：" + t2);
    }

    @Test
    void estimateSumsAcrossMessages() {
        TokenEstimator est = TokenEstimator.approximate();
        List<Msg> msgs = List.of(
                Msg.user("u", "a".repeat(100)),
                Msg.assistant("bot", "a".repeat(100))
        );
        int total = est.estimate(msgs);
        // 两条 100 字符消息合计 200 字符 → ~71 token + 固定开销
        assertTrue(total >= 70 && total <= 80, "合计 token 应约 71-80，实际：" + total);
    }

    @Test
    void estimateIncludesToolUseAndResultBlocks() {
        TokenEstimator est = TokenEstimator.approximate();
        Msg textOnly = Msg.user("u", "x");
        Msg withToolUse = Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call_1", "longToolNameHere",
                        Map.of("longParamKey", "longValueWithMoreCharacters"))).build();
        Msg withToolResult = Msg.builder().name("bot").role(MsgRole.TOOL)
                .content(ToolResultBlock.success("call_1", "a".repeat(50))).build();
        // 工具调用与工具结果都应该贡献 token，不应被忽略
        assertTrue(est.estimate(withToolUse) > est.estimate(textOnly));
        assertTrue(est.estimate(withToolResult) > est.estimate(textOnly));
    }

    @Test
    void thinkingBlockCountsTowardTokens() {
        TokenEstimator est = TokenEstimator.approximate();
        Msg withThinking = Msg.builder().name("bot").role(MsgRole.ASSISTANT)
                .content(new com.lynn.myagentscopejava.core.message.ThinkingBlock("a".repeat(100)),
                         new TextBlock("hi")).build();
        Msg textOnly = Msg.user("u", "hi");
        assertTrue(est.estimate(withThinking) > est.estimate(textOnly) * 5);
    }

    @Test
    void customCharsPerTokenChangesResult() {
        Msg m = Msg.user("u", "a".repeat(100));
        int loose = TokenEstimator.approximate(5.0).estimate(m);
        int tight = TokenEstimator.approximate(2.0).estimate(m);
        assertTrue(tight > loose, "更紧的密度应给出更多 token");
    }

    @Test
    void rejectsNonPositiveCharsPerToken() {
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.approximate(0));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.approximate(-1));
    }
}
