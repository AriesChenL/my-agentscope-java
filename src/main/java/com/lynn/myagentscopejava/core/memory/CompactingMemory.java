package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 自动压缩历史的 {@link Memory} 装饰器。
 *
 * <p>每次 {@link #addMessage(Msg)} 后立即用 {@link MemoryCompactor#shouldCompact(List)} 检查，
 * 命中阈值就同步执行压缩、用结果替换底层存储。压缩动作对调用方透明。
 *
 * <p>之所以选择"在 add 时即时压缩"而不是"在 reasoning 前压缩"：
 * <ul>
 *   <li>压缩动作的成本（一次 LLM 调用）显式地落在某次 addMessage 上，行为可预期</li>
 *   <li>{@link #getMessages()} 不引入隐藏的副作用，调用方读到的永远是已压缩状态</li>
 *   <li>多个并发读者不会触发重复压缩</li>
 * </ul>
 */
public class CompactingMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(CompactingMemory.class);

    private final Memory delegate;
    private final MemoryCompactor compactor;
    private int compactionCount = 0;

    public CompactingMemory(Memory delegate, MemoryCompactor compactor) {
        if (delegate == null) throw new IllegalArgumentException("delegate 必填");
        if (compactor == null) throw new IllegalArgumentException("compactor 必填");
        this.delegate = delegate;
        this.compactor = compactor;
    }

    @Override
    public void addMessage(Msg msg) {
        delegate.addMessage(msg);
        compactIfNeeded();
    }

    @Override
    public List<Msg> getMessages() {
        return delegate.getMessages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /** 主动触发一次压缩判断（通常无需调用；addMessage 已自动调）。 */
    public void compactIfNeeded() {
        List<Msg> current = delegate.getMessages();
        if (!compactor.shouldCompact(current)) return;
        int before = current.size();
        List<Msg> compacted = compactor.compact(current);
        // SummarizingCompactor 在"无可压缩内容"路径返回同一个引用，用 == 判断（不能用 size 比较：
        // 摘要 USER + ASSISTANT 占位对子可能让压缩后消息数与压缩前相等甚至更多，但 token 显著减少）
        if (compacted == current) {
            log.debug("compactor 无可压缩内容（{} 条），跳过", before);
            return;
        }
        delegate.clear();
        compacted.forEach(delegate::addMessage);
        compactionCount++;
        log.info("memory 压缩生效：{} → {} 条消息（累计第 {} 次）", before, compacted.size(), compactionCount);
    }

    /** 已执行的压缩次数，用于监控。 */
    public int getCompactionCount() {
        return compactionCount;
    }

    public Memory getDelegate() {
        return delegate;
    }
}
