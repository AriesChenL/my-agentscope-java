package com.lynn.myagentscopejava.core.model;

import com.lynn.myagentscopejava.core.session.SessionKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 多 provider 模式下的 {@link ChatModel} 路由器。
 *
 * <p>每个会话可以使用不同的 provider；路由器负责按会话键解析使用哪个 ChatModel：
 * <ol>
 *   <li>调用 {@code keyToProvider} 函数从 SessionKey 解析对应 provider id（一般查
 *       {@link com.lynn.myagentscopejava.core.conversation.ConversationDirectory}）</li>
 *   <li>从 {@code models} 映射拿到对应 ChatModel；找不到则回退到 {@code defaultProviderId}</li>
 * </ol>
 *
 * <p>线程安全：{@link #models} 在构造后不可变，{@link #keyToProvider} 由调用方保证线程安全。
 */
public class ChatModelRouter {

    private final Map<String, ChatModel> models;
    private final String defaultProviderId;
    private final Function<SessionKey, String> keyToProvider;

    /**
     * @param models             provider id → ChatModel 实例（至少要有一个）
     * @param defaultProviderId  缺省 provider id；必须存在于 {@code models} 中
     * @param keyToProvider      会话键 → provider id 的解析函数，返回 {@code null} 表示走默认
     */
    public ChatModelRouter(Map<String, ChatModel> models,
                           String defaultProviderId,
                           Function<SessionKey, String> keyToProvider) {
        Objects.requireNonNull(models, "models");
        if (models.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少要启用一个 provider；请检查 application.properties 中的 agentscope.providers.*.enabled");
        }
        Objects.requireNonNull(defaultProviderId, "defaultProviderId");
        if (!models.containsKey(defaultProviderId)) {
            throw new IllegalArgumentException("defaultProviderId=" + defaultProviderId
                    + " 不在已启用的 provider 列表中: " + models.keySet());
        }
        // 不暴露内部可变性
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        this.defaultProviderId = defaultProviderId;
        this.keyToProvider = keyToProvider != null ? keyToProvider : k -> null;
    }

    /**
     * 按会话键解析 ChatModel。会话没有显式 provider 设定时回退到默认。
     */
    public ChatModel resolve(SessionKey key) {
        String pid = keyToProvider.apply(key);
        if (pid == null || !models.containsKey(pid)) {
            pid = defaultProviderId;
        }
        return models.get(pid);
    }

    /**
     * 直接按 provider id 取 ChatModel；未知 id 返回默认 provider。
     */
    public ChatModel byProvider(String providerId) {
        if (providerId == null || !models.containsKey(providerId)) {
            return models.get(defaultProviderId);
        }
        return models.get(providerId);
    }

    /**
     * @return 所有已启用的 provider id 列表（按 properties 中固定顺序）
     */
    public Map<String, ChatModel> all() {
        return models;
    }

    public String defaultProviderId() {
        return defaultProviderId;
    }
}
