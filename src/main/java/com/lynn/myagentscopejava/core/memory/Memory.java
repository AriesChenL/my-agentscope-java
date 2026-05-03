package com.lynn.myagentscopejava.core.memory;

import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.session.SessionKey;

import java.util.List;

/**
 * Agent 的对话记忆抽象。
 *
 * <p>负责存储 agent 在多轮交互中产生的所有 {@link Msg}（用户输入、模型回复、工具结果），
 * 在每次推理时由 agent 取出作为模型上下文。
 */
public interface Memory {

    /**
     * 追加一条消息到记忆末尾。
     *
     * @param msg 待追加的消息；{@code null} 应被静默忽略
     */
    void addMessage(Msg msg);

    /**
     * 返回当前所有消息的只读视图。
     *
     * @return 按时间顺序排列的消息列表
     */
    List<Msg> getMessages();

    /** 清空所有消息。 */
    void clear();

    /**
     * 将当前记忆中的所有消息持久化到 session 的 {@code "memory"} 槽位。
     *
     * @param session 持久化后端
     * @param key     会话键
     */
    default void saveTo(Session session, SessionKey key) {
        session.save(key, "memory", getMessages().toArray(new Msg[0]));
    }

    /**
     * 从 session 的 {@code "memory"} 槽位恢复消息（先清空当前记忆再加载）。
     * 槽位不存在时为 no-op。
     *
     * @param session 持久化后端
     * @param key     会话键
     */
    default void loadFrom(Session session, SessionKey key) {
        session.get(key, "memory", Msg[].class).ifPresent(arr -> {
            clear();
            for (Msg m : arr) addMessage(m);
        });
    }
}
