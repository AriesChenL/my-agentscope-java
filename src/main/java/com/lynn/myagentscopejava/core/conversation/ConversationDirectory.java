package com.lynn.myagentscopejava.core.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 用户名下多会话的目录服务：每个用户拥有一份会话索引，记录所属的全部会话元数据。
 *
 * <p>线程安全：实现需保证同一 userId 下的并发读写不出现数据竞争。
 */
public interface ConversationDirectory {

    /**
     * 列出某用户的所有会话，按 {@link Conversation#updatedAt()} 倒序（最新的在前）。
     *
     * @param userId 用户 id
     * @return 会话列表；若用户无任何会话返回空
     */
    List<Conversation> list(String userId);

    /**
     * 创建一个新会话。{@code title} / {@code provider} 可空。
     *
     * @param userId   用户 id
     * @param title    初始标题；为空则使用默认占位标题
     * @param provider 使用的 provider id（openai / anthropic / gemini）；为空则走默认 provider
     * @return 新创建的 Conversation
     */
    Conversation create(String userId, String title, String provider);

    /**
     * 兼容老调用：使用默认 provider 创建会话。
     */
    default Conversation create(String userId, String title) {
        return create(userId, title, null);
    }

    /**
     * 删除指定会话的元数据条目（不会同时删除 Session 中的消息内容，由调用方负责）。
     *
     * @param userId 用户 id
     * @param convId 会话 id
     * @return 是否真的删除了一条
     */
    boolean delete(String userId, String convId);

    /**
     * 获取单个会话元数据。
     *
     * @param userId 用户 id
     * @param convId 会话 id
     * @return 找到时返回 Conversation，否则空
     */
    Optional<Conversation> get(String userId, String convId);

    /**
     * 改名（顺带刷新 updatedAt）。
     *
     * @param userId 用户 id
     * @param convId 会话 id
     * @param title  新标题
     * @return 更新后的 Conversation；不存在时返回空
     */
    Optional<Conversation> rename(String userId, String convId, String title);

    /**
     * 仅刷新 updatedAt（标题不变）。在每次 chat 后调用，让最近活跃的会话排在前面。
     *
     * @param userId 用户 id
     * @param convId 会话 id
     */
    void touch(String userId, String convId);
}
