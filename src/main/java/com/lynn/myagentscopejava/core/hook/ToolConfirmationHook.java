package com.lynn.myagentscopejava.core.hook;

import java.util.Collections;
import java.util.HashSet;
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
 * <p>用法：
 * <pre>{@code
 * @Bean
 * Hook toolConfirmationHook() {
 *     return new ToolConfirmationHook(Set.of("delete_file", "transfer_money"));
 * }
 * }</pre>
 *
 * <p>线程安全：{@link #setDangerousTools(Set)} 等修改方法可在运行时调用，与 onEvent
 * 并发安全（用 {@code synchronized} 保护 {@link #dangerousTools}）。
 *
 * <p>参考：阿里 agentscope-java 仓库
 * {@code agentscope-examples/hitl-chat/hook/ToolConfirmationHook}。
 */
public class ToolConfirmationHook implements Hook {

    private final Set<String> dangerousTools = new HashSet<>();

    public ToolConfirmationHook() {}

    public ToolConfirmationHook(Set<String> dangerousTools) {
        if (dangerousTools != null) this.dangerousTools.addAll(dangerousTools);
    }

    @Override
    public void onEvent(HookEvent event) {
        if (!(event instanceof PreActingEvent pre)) return;
        String toolName = pre.getToolUse().name();
        if (isDangerous(toolName)) {
            pre.suspend("工具 '" + toolName + "' 已被标记为需要人工审批");
        }
    }

    /** 增加一个待审批的工具名。 */
    public synchronized void addDangerousTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) dangerousTools.add(toolName);
    }

    /** 移除一个待审批的工具名。 */
    public synchronized void removeDangerousTool(String toolName) {
        dangerousTools.remove(toolName);
    }

    /** 整体替换待审批工具集合。 */
    public synchronized void setDangerousTools(Set<String> toolNames) {
        dangerousTools.clear();
        if (toolNames != null) dangerousTools.addAll(toolNames);
    }

    /** @return 当前所有待审批工具名（不可变快照） */
    public synchronized Set<String> getDangerousTools() {
        return Collections.unmodifiableSet(new HashSet<>(dangerousTools));
    }

    /** @return 该工具名当前是否需要人工审批 */
    public synchronized boolean isDangerous(String toolName) {
        return toolName != null && dangerousTools.contains(toolName);
    }
}
