package com.lynn.myagentscopejava.core.tool;

import java.lang.reflect.Method;

/**
 * 已注册到 {@link Toolkit} 的工具：包含发送给 LLM 的 schema，以及背后实际调用的 Java 方法。
 *
 * @param schema   工具的 JSON Schema 描述
 * @param instance 持有该工具方法的对象实例
 * @param method   被反射调用的方法
 */
public record RegisteredTool(ToolSchema schema, Object instance, Method method) {

    /**
     * @return 工具名称（即 {@link #schema()}.{@link ToolSchema#name() name()}）
     */
    public String name() {
        return schema.name();
    }
}
