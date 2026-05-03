package com.lynn.myagentscopejava.core.service;

import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.model.ChatUsage;

/**
 * {@link ChatService#chatReactStream} 推流的结构化事件。
 *
 * <p>事件按 ReAct 循环时间线发出：
 * <ol>
 *   <li>{@link IterationStart} —— 进入新的 reasoning 迭代</li>
 *   <li>0..N 个 {@link Thinking} —— 思考链增量（thinking-mode 模型才有）</li>
 *   <li>0..N 个 {@link Text} —— 助手文本增量</li>
 *   <li>0..N 个 {@link ToolCall} → {@link ToolResult} —— 工具调用与结果</li>
 *   <li>1 个 {@link Done} —— 流终止，含最终 usage</li>
 * </ol>
 */
public sealed interface ReactEvent
        permits ReactEvent.IterationStart, ReactEvent.Thinking, ReactEvent.Text,
                ReactEvent.ToolCall, ReactEvent.ToolResult, ReactEvent.Done {

    /** 一轮 reasoning 开始。 */
    record IterationStart(int iteration) implements ReactEvent {}

    /** 思考链文本增量。 */
    record Thinking(String delta) implements ReactEvent {}

    /** 助手文本增量。 */
    record Text(String delta) implements ReactEvent {}

    /** 模型决定调用工具。 */
    record ToolCall(ToolUseBlock toolUse) implements ReactEvent {}

    /** 工具执行结果。 */
    record ToolResult(ToolResultBlock result) implements ReactEvent {}

    /** 流结束，含本次累计 usage（可能为 null）。 */
    record Done(ChatUsage usage) implements ReactEvent {}
}
