package com.lynn.myagentscopejava.core.interruption;

/**
 * 中断的来源分类。
 *
 * <p>通过区分来源，agent 在收尾时可以做不同的处理：例如
 * <ul>
 *   <li>USER 中断：保留部分流式输出 + 加"已被用户中断"标记，方便用户看到截断点</li>
 *   <li>SYSTEM 中断：可能想直接丢弃 partial reasoning（避免半截答案污染下次上下文）</li>
 *   <li>TOOL 中断：通常说明工具触发了某种业务停止条件，需保留并附说明</li>
 * </ul>
 */
public enum InterruptSource {

    /** 用户主动中断（点击 UI 停止按钮、调用 agent.interrupt() 等）。 */
    USER,

    /** 工具内部决定中断（达到限额、检测到非法输入、需要外部审批等）。 */
    TOOL,

    /** 系统级中断（超时、资源限制、graceful shutdown 等）。 */
    SYSTEM
}
