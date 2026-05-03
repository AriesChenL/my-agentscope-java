package com.lynn.myagentscopejava.core.model;

import com.lynn.myagentscopejava.core.interruption.CancellationToken;
import com.lynn.myagentscopejava.core.message.ContentBlock;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.tool.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 聊天补全的抽象。
 *
 * <p>主方法是 {@link #stream}，返回 {@link Flux} of {@link ChatChunk}。
 * {@link #chat} 方法把流收集为单个 {@link ChatResponse} 提供给同步使用。
 */
public interface ChatModel {

    /**
     * @return 模型名称（用于日志与标识）
     */
    String getModelName();

    /**
     * 发起一次流式聊天补全。
     *
     * @param messages 完整 prompt（system + 历史 + 工具结果）
     * @param tools    可用工具的 schema 列表；空或 {@code null} 表示不带工具
     * @param options  生成参数；默认值用 {@link GenerateOptions#defaults()}
     * @param token    取消信号；可为 {@code null}。被取消时实现需中止 HTTP 调用并向 Flux 抛
     *                 {@link com.lynn.myagentscopejava.core.interruption.AgentInterruptedException}
     * @return chunk 流，最终一定会有一个 {@code finishReason} 非空的 chunk
     */
    default Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                                   GenerateOptions options, CancellationToken token) {
        return stream(messages, tools, options, token, null);
    }

    /**
     * 带会话标识的流式聊天补全。某些 provider（如 Gemini 显式缓存）需要按会话维护状态。
     *
     * @param sessionId 会话标识；{@code null} 表示无会话关联（无状态调用，如 summarizer）
     */
    Flux<ChatChunk> stream(List<Msg> messages, List<ToolSchema> tools,
                           GenerateOptions options, CancellationToken token,
                           String sessionId);

    /**
     * 收集流为单个 {@link ChatResponse}（阻塞直到流结束）。
     *
     * @param messages 完整 prompt
     * @param tools    可用工具
     * @param options  生成参数
     * @param token    取消信号
     * @return 聚合后的 ChatResponse
     */
    default ChatResponse chat(List<Msg> messages, List<ToolSchema> tools,
                              GenerateOptions options, CancellationToken token) {
        ChunkAccumulator acc = new ChunkAccumulator();
        stream(messages, tools, options, token).doOnNext(acc::accept).blockLast();
        List<ContentBlock> blocks = acc.buildBlocks();
        Msg msg = Msg.builder()
                .name(getModelName())
                .role(MsgRole.ASSISTANT)
                .content(blocks)
                .build();
        return new ChatResponse(msg, acc.usage(), acc.finishReason());
    }

    /** 不带工具、默认参数、无取消的便捷方法。 */
    default ChatResponse chat(List<Msg> messages) {
        return chat(messages, List.of(), GenerateOptions.defaults(), null);
    }

    /** 不带工具、自定义参数、无取消的便捷方法。 */
    default ChatResponse chat(List<Msg> messages, GenerateOptions options) {
        return chat(messages, List.of(), options, null);
    }

    /** 不带工具、自定义参数、自定义取消的便捷方法。 */
    default ChatResponse chat(List<Msg> messages, GenerateOptions options, CancellationToken token) {
        return chat(messages, List.of(), options, token);
    }
}
