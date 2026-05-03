package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link Memory} 的进程内实现，使用 {@link ArrayList} 存储消息。
 *
 * <p>无并发保护，假定每个 agent 实例独占一份 InMemoryMemory，且 agent 自身串行执行。
 */
public class InMemoryMemory implements Memory {

    private final List<Msg> messages = new ArrayList<>();

    @Override
    public void addMessage(Msg msg) {
        if (msg != null) {
            messages.add(msg);
        }
    }

    @Override
    public List<Msg> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }
}
