package com.lynn.myagentscopejava.core.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynn.myagentscopejava.core.cluster.NotificationBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "危险工具人工审批" 钩子：在工具真正被执行前拦截，让 agent 进入挂起状态等待人工决定。
 *
 * <p>触发流程：
 * <ol>
 *   <li>LLM 调用某个工具（例如 {@code delete_file} / {@code transfer_money}）</li>
 *   <li>本 hook 在 {@link PreActingEvent} 阶段检查工具名是否命中
 *       {@link #getDangerousTools() dangerousTools} 集合</li>
 *   <li>命中则调 {@link PreActingEvent#suspend(String)} 短路实际调用 ——
 *       框架会产生一个 pending {@link
 *       com.lynn.myagentscopejava.core.message.ToolResultBlock}，agent 立即返回</li>
 *   <li>前端识别 pending 工具，渲染"批准 / 拒绝"按钮供用户决定</li>
 *   <li>用户批准 → 后端真正执行该工具；用户拒绝 → 把"已拒绝"作为工具结果回填</li>
 * </ol>
 *
 * <p><b>跨节点热更新</b>：{@link #setDangerousToolsAndBroadcast(Set)} 在修改本地清单的同时
 * 通过 {@link NotificationBus} 把新清单广播到 {@link #CONFIG_CHANNEL}，其它节点的同名 hook
 * 订阅此频道并同步更新本地清单 —— 一处改全集群生效。
 *
 * <p>线程安全：{@link #setDangerousTools(Set)} 等修改方法可在运行时调用，与 onEvent
 * 并发安全（用 {@code synchronized} 保护 {@link #dangerousTools}）。
 *
 * <p>参考：阿里 agentscope-java 仓库
 * {@code agentscope-examples/hitl-chat/hook/ToolConfirmationHook}。
 */
public class ToolConfirmationHook implements Hook, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationHook.class);

    /** 跨节点同步危险工具清单的 NotificationBus 频道名。payload 是 JSON 数组：{@code ["a","b"]}。 */
    public static final String CONFIG_CHANNEL = "config:hitl-dangerous-tools";

    private final Set<String> dangerousTools = new HashSet<>();
    private final NotificationBus notificationBus;
    private final ObjectMapper mapper;
    private final NotificationBus.Subscription subscription;

    /** 单机用便利构造（不订阅广播）。 */
    public ToolConfirmationHook() {
        this(null, null, null);
    }

    /** 兼容老调用：仅初始清单，不参与跨节点同步。 */
    public ToolConfirmationHook(Set<String> dangerousTools) {
        this(dangerousTools, null, null);
    }

    /**
     * 完整构造：初始清单 + 接入跨节点 bus。
     *
     * @param initial         初始清单（启动时配置 {@code agentscope.hitl.dangerous-tools}）
     * @param notificationBus 用于广播；{@code null} 则不广播也不订阅
     * @param mapper          用于 (反)序列化 JSON 清单；{@code null} 自动创建
     */
    public ToolConfirmationHook(Set<String> initial, NotificationBus notificationBus, ObjectMapper mapper) {
        if (initial != null) this.dangerousTools.addAll(initial);
        this.notificationBus = notificationBus;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        // 接入广播：订阅别的节点的更新
        this.subscription = notificationBus != null
                ? notificationBus.subscribe(CONFIG_CHANNEL, this::onRemoteUpdate)
                : null;
    }

    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreActingEvent pre)) return;
        String toolName = pre.getToolUse().name();
        if (isDangerous(toolName)) {
            pre.suspend("工具 '" + toolName + "' 已被标记为需要人工审批");
        }
    }

    /** 增加一个待审批的工具名。仅本地，不广播。 */
    public synchronized void addDangerousTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) dangerousTools.add(toolName);
    }

    /** 移除一个待审批的工具名。仅本地，不广播。 */
    public synchronized void removeDangerousTool(String toolName) {
        dangerousTools.remove(toolName);
    }

    /** 整体替换待审批工具集合。仅本地，不广播。 */
    public synchronized void setDangerousTools(Set<String> toolNames) {
        dangerousTools.clear();
        if (toolNames != null) dangerousTools.addAll(toolNames);
    }

    /**
     * 替换清单 <b>并广播到所有节点</b>。如果 {@link #notificationBus} 为空（单机模式），
     * 行为退化为 {@link #setDangerousTools(Set)}。
     *
     * <p>本节点的更新先于广播 —— 调用方拿到的 GET API 响应能立即反映新值。
     * 自己也会订阅到这条广播，但 {@link #setDangerousTools} 是幂等替换，重复无副作用。
     */
    public void setDangerousToolsAndBroadcast(Set<String> toolNames) {
        setDangerousTools(toolNames);
        if (notificationBus == null) return;
        try {
            List<String> list = toolNames == null ? List.of() : new ArrayList<>(toolNames);
            String json = mapper.writeValueAsString(list);
            notificationBus.publish(CONFIG_CHANNEL, json);
        } catch (Exception e) {
            // 广播失败不阻塞本节点写：下次广播或重启后其它节点会同步
            log.warn("broadcast dangerous-tools 失败: {}", e.toString());
        }
    }

    /** @return 当前所有待审批工具名（不可变快照） */
    public synchronized Set<String> getDangerousTools() {
        return Collections.unmodifiableSet(new HashSet<>(dangerousTools));
    }

    /** @return 该工具名当前是否需要人工审批 */
    public synchronized boolean isDangerous(String toolName) {
        return toolName != null && dangerousTools.contains(toolName);
    }

    /** 释放跨节点订阅（由 Spring 容器在 destroy 时调用）。 */
    @Override
    public void close() {
        if (subscription != null) subscription.close();
    }

    /** 收到其它节点广播的更新；本节点也会收到自己 publish 的，幂等无影响。 */
    private void onRemoteUpdate(String payload) {
        try {
            List<String> list = mapper.readValue(payload, new TypeReference<>() {});
            setDangerousTools(new HashSet<>(list));
            log.info("远程同步危险工具清单：{}", list);
        } catch (Exception e) {
            log.warn("解析远程 payload 失败 {}: {}", payload, e.toString());
        }
    }
}
