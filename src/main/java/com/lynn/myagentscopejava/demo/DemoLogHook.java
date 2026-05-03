package com.lynn.myagentscopejava.demo;

import com.lynn.myagentscopejava.core.hook.Hook;
import com.lynn.myagentscopejava.core.hook.HookEvent;
import com.lynn.myagentscopejava.core.hook.PostActingEvent;
import com.lynn.myagentscopejava.core.hook.PostReasoningEvent;
import com.lynn.myagentscopejava.core.hook.PreActingEvent;
import com.lynn.myagentscopejava.core.hook.PreReasoningEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo 用的日志 Hook：把 ReAct 循环中四个事件（PreReasoning / PostReasoning / PreActing / PostActing）
 * 的关键信息打印到控制台。Spring 会自动把它注入到 ReActAgent。
 */
@Component
@Profile("demo")
public class DemoLogHook implements Hook {

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public void onEvent(HookEvent event) {
        if (event instanceof PreReasoningEvent e) {
            System.out.println("  [hook] PreReasoning  iter=" + e.getIteration()
                    + " messages=" + e.getMessages().size());
        } else if (event instanceof PostReasoningEvent e) {
            String preview = e.getMessage().getText();
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            System.out.println("  [hook] PostReasoning iter=" + e.getIteration()
                    + " text=\"" + preview + "\""
                    + " usage=" + e.getUsage());
        } else if (event instanceof PreActingEvent e) {
            System.out.println("  [hook] PreActing     tool=" + e.getToolUse().name()
                    + " args=" + e.getToolUse().input());
        } else if (event instanceof PostActingEvent e) {
            System.out.println("  [hook] PostActing    tool=" + e.getToolUse().name()
                    + " result=" + e.getResult().output());
        }
    }
}
