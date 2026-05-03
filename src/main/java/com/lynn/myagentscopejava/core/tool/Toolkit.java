package com.lynn.myagentscopejava.core.tool;

import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表，提供工具的注册、列出与调用入口。
 *
 * <p>通过 {@link #registerObject(Object)} 注册：扫描传入对象上所有标注了 {@link Tool}
 * 的 public 方法并登记。{@link #invoke(ToolUseBlock)} 执行模型发起的工具调用，
 * 始终返回 {@link ToolResultBlock}（成功或错误）—— 调用永不向上抛异常。
 */
public class Toolkit {

    private static final Logger log = LoggerFactory.getLogger(Toolkit.class);

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    /**
     * 扫描 {@code instance} 上的所有 {@link Tool} 注解方法并注册。
     *
     * @param instance 工具持有对象
     * @return 当前 Toolkit（链式调用）
     * @throws IllegalStateException 注册的工具名重名
     */
    public Toolkit registerObject(Object instance) {
        for (Method m : instance.getClass().getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Tool.class)) continue;
            ToolSchema schema = ToolSchemaGenerator.generate(m);
            if (tools.containsKey(schema.name())) {
                throw new IllegalStateException("工具名重复：" + schema.name());
            }
            m.setAccessible(true);
            tools.put(schema.name(), new RegisteredTool(schema, instance, m));
            log.debug("已注册工具：{}", schema.name());
        }
        return this;
    }

    /**
     * @return 所有工具的 schema 列表（按注册顺序）
     */
    public List<ToolSchema> getSchemas() {
        return tools.values().stream().map(RegisteredTool::schema).toList();
    }

    /**
     * @return 所有 RegisteredTool（按注册顺序）
     */
    public Collection<RegisteredTool> getTools() {
        return tools.values();
    }

    /**
     * @return 当前是否未注册任何工具
     */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 调用模型请求的工具。任何异常都会被转为错误状态的 {@link ToolResultBlock}，永不抛出。
     *
     * @param use 模型发起的工具调用请求
     * @return 工具执行结果（成功或错误）
     */
    public ToolResultBlock invoke(ToolUseBlock use) {
        RegisteredTool tool = tools.get(use.name());
        if (tool == null) {
            return ToolResultBlock.error(use.id(), use.name(), "Unknown tool: " + use.name());
        }
        try {
            Object[] args = bindArgs(tool.method(), use.input());
            Object result = tool.method().invoke(tool.instance(), args);
            return ToolResultBlock.success(use.id(), use.name(), stringify(result));
        } catch (InvocationTargetException ite) {
            // 工具方法本身抛了异常
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            // HITL：工具明确请求挂起 → 转为 pending 结果而非错误
            if (cause instanceof ToolSuspendException tse) {
                String reason = tse.getReason() != null ? tse.getReason() : tse.getMessage();
                log.info("工具 '{}' 请求挂起：{}", use.name(), reason);
                return ToolResultBlock.pending(use.id(), use.name(), reason);
            }
            log.warn("工具 '{}' 抛出异常：{}", use.name(), cause.toString());
            return ToolResultBlock.error(use.id(), use.name(),
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception e) {
            // 反射调用本身失败（参数绑定 / 访问权限等）
            log.warn("工具 '{}' 调用失败：{}", use.name(), e.toString());
            return ToolResultBlock.error(use.id(), use.name(), e.getMessage());
        }
    }

    /** 按形参名将 input map 中的值绑定到方法参数数组上，并完成类型转换。 */
    private Object[] bindArgs(Method method, Map<String, Object> input) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            ToolParam pa = p.getAnnotation(ToolParam.class);
            String key = (pa != null && !pa.name().isEmpty()) ? pa.name() : p.getName();
            args[i] = ToolSchemaGenerator.coerce(input.get(key), p.getType());
        }
        return args;
    }

    /** 把工具返回值转为字符串供回传给模型。 */
    private static String stringify(Object result) {
        if (result == null) return "";
        if (result instanceof String s) return s;
        if (result instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) parts.add(String.valueOf(o));
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(result);
    }
}
