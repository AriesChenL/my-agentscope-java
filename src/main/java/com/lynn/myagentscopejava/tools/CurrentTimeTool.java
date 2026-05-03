package com.lynn.myagentscopejava.tools;

import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 获取当前时间的工具。模型本身无法感知"现在"，被问到时间相关问题时通常会主动调用本工具，
 * 是验证 Toolkit 链路是否打通的最佳示例。
 */
@Component
public class CurrentTimeTool implements ToolProvider {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (E)");

    @Tool(description = "获取当前的真实日期与时间，模型无法自己知道当前时间，需要查询时必须调用本工具")
    public String currentTime() {
        return ZonedDateTime.now(ZoneId.systemDefault()).format(FMT);
    }
}
