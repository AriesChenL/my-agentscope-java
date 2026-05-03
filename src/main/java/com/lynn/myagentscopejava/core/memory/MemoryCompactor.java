package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;

import java.util.List;

/**
 * 内存压缩策略。
 *
 * <p>当 {@link CompactingMemory} 中的消息数量超过策略设定的阈值时，
 * 会调用 {@link #compact(List)} 把旧消息折叠成更少的消息，避免上下文窗口爆掉。
 *
 * <p>常见实现：
 * <ul>
 *   <li>{@link SummarizingCompactor} —— 用 LLM 把旧消息摘要成一条 SYSTEM 消息</li>
 *   <li>未来可加：滑窗丢弃、按 token 预算裁剪等</li>
 * </ul>
 */
public interface MemoryCompactor {

    /**
     * 判断当前消息列表是否需要压缩。
     *
     * @param messages 当前 memory 中的全部消息
     * @return 需要压缩返回 true
     */
    boolean shouldCompact(List<Msg> messages);

    /**
     * 把消息列表压缩为更短的等价版本（应保留语义连续性 + 工具调用对齐）。
     *
     * @param messages 待压缩的消息
     * @return 压缩后的新消息列表（不修改入参）
     */
    List<Msg> compact(List<Msg> messages);
}
