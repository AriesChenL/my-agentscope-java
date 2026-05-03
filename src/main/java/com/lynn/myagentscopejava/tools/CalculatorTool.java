package com.lynn.myagentscopejava.tools;

import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import org.springframework.stereotype.Component;

/**
 * 计算器工具。注册为 Spring bean 后会被 {@code AgentAutoConfiguration} 自动加入默认 Toolkit。
 */
@Component
public class CalculatorTool implements ToolProvider {

    @Tool(description = "对两个整数求和")
    public int add(@ToolParam(description = "加数 a") int a,
                   @ToolParam(description = "加数 b") int b) {
        return a + b;
    }

    @Tool(description = "对两个整数求乘积")
    public int multiply(@ToolParam(description = "因子 a") int a,
                        @ToolParam(description = "因子 b") int b) {
        return a * b;
    }
}
