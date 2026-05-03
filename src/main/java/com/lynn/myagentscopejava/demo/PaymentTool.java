package com.lynn.myagentscopejava.demo;

import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import com.lynn.myagentscopejava.core.tool.ToolSuspendException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo 用的支付工具，演示 HITL：
 * 金额 ≤ 1000 直接执行；超过阈值则抛 {@link ToolSuspendException}，触发挂起等待人工审批。
 */
@Component
@Profile("demo")
public class PaymentTool implements ToolProvider {

    private static final int APPROVAL_THRESHOLD = 1000;

    @Tool(description = "执行扣款。金额超过 1000 元时需要人工审批。")
    public String deduct(@ToolParam(description = "扣款金额，单位：元") int amount) {
        if (amount > APPROVAL_THRESHOLD) {
            throw new ToolSuspendException(
                    "金额 " + amount + " 元超过自动放行阈值 " + APPROVAL_THRESHOLD + " 元，需要人工审批");
        }
        return "扣款成功，金额：" + amount + " 元";
    }
}
