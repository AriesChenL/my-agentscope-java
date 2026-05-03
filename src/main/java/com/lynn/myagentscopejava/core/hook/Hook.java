package com.lynn.myagentscopejava.core.hook;

/**
 * 同步拦截器，会在 agent ReAct 循环的若干预定义节点被调用。
 *
 * <p>通过 {@code instanceof} 在事件类型上分发：
 * <pre>{@code
 * Hook logger = event -> {
 *     if (event instanceof PostReasoningEvent e) {
 *         System.out.println("model said: " + e.getMessage().getText());
 *     }
 * };
 * }</pre>
 *
 * <p>Hook 可直接修改事件上的字段（例如替换消息、改写工具参数），
 * 也可调用 {@link HookEvent#requestStop()} 让 agent 提前结束循环（HITL）。
 *
 * <p>多个 Hook 按 {@link #priority()} 升序触发，相同优先级的相对顺序未定义。
 */
@FunctionalInterface
public interface Hook {

    /**
     * 处理一次事件。
     *
     * @param event 事件对象
     */
    void onEvent(HookEvent event);

    /**
     * 优先级，数值越小越早执行。
     *
     * @return 默认 0
     */
    default int priority() {
        return 0;
    }
}
