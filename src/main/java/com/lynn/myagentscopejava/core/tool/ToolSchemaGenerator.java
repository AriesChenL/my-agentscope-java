package com.lynn.myagentscopejava.core.tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过反射为 {@link Tool} 注解的方法生成 JSON Schema 描述。
 *
 * <p>支持的参数类型：
 * <ul>
 *   <li>{@code String / int / long / double / float / boolean}（含装箱类型）</li>
 *   <li>{@code List<T>} / {@code Collection<T>}（T 为上述基本类型或 {@code Map<String,Object>}）</li>
 *   <li>{@code Map<String, Object>}（对应 JSON object）</li>
 *   <li>{@code Object}（不限定 schema 类型，由调用方解释）</li>
 * </ul>
 *
 * <p>参数名依赖 Java {@code -parameters} 编译开关（spring-boot-starter-parent 默认开启）；
 * 也可通过 {@link ToolParam#name()} 在每个参数上单独指定。
 */
public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {}

    /**
     * 为指定方法生成 ToolSchema。
     *
     * @param method 必须带 {@link Tool} 注解的方法
     * @return 描述该方法的 ToolSchema
     * @throws IllegalArgumentException 方法未标注 {@link Tool}，或参数类型不被支持
     */
    public static ToolSchema generate(Method method) {
        Tool ann = method.getAnnotation(Tool.class);
        if (ann == null) {
            throw new IllegalArgumentException("方法未标注 @Tool：" + method);
        }
        String name = ann.name().isEmpty() ? method.getName() : ann.name();

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter p : method.getParameters()) {
            ToolParam pa = p.getAnnotation(ToolParam.class);
            String paramName = (pa != null && !pa.name().isEmpty()) ? pa.name() : p.getName();
            String desc = pa != null ? pa.description() : "";
            boolean req = pa == null || pa.required();

            Map<String, Object> propSchema = buildSchemaFor(p);
            if (!desc.isEmpty()) propSchema.put("description", desc);
            properties.put(paramName, propSchema);
            if (req) required.add(paramName);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) parameters.put("required", required);

        return new ToolSchema(name, ann.description(), parameters);
    }

    private static String jsonTypeOf(Class<?> t) {
        if (t == String.class) return "string";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t == int.class || t == Integer.class
                || t == long.class || t == Long.class) return "integer";
        if (t == double.class || t == Double.class
                || t == float.class || t == Float.class) return "number";
        if (Collection.class.isAssignableFrom(t)) return "array";
        if (Map.class.isAssignableFrom(t)) return "object";
        if (t == Object.class) return null; // 任意类型，schema 不限定
        throw new IllegalArgumentException(
                "不支持的工具参数类型：" + t);
    }

    /** 为单个参数构造完整 schema 节点（含数组的 items 子 schema）。 */
    private static Map<String, Object> buildSchemaFor(Parameter p) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Class<?> raw = p.getType();
        String type = jsonTypeOf(raw);
        if (type != null) schema.put("type", type);
        if (Collection.class.isAssignableFrom(raw)) {
            // 取 List<X> 的 X 作为 items.type
            Type generic = p.getParameterizedType();
            Class<?> itemType = String.class;
            if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                Type t0 = pt.getActualTypeArguments()[0];
                if (t0 instanceof Class<?> c) {
                    itemType = c;
                } else if (t0 instanceof ParameterizedType pt2
                        && pt2.getRawType() instanceof Class<?> c2) {
                    itemType = c2;
                }
            }
            Map<String, Object> items = new LinkedHashMap<>();
            String itemTypeName = jsonTypeOf(itemType);
            if (itemTypeName != null) items.put("type", itemTypeName);
            schema.put("items", items);
        }
        return schema;
    }

    /**
     * 将 JSON 解析后的值（String / Number / Boolean）强制转换为目标 Java 参数类型。
     *
     * @param value      待转换的值，可为 {@code null}
     * @param targetType 目标 Java 类型
     * @return 转换后的对象（基本类型缺省值或装箱后的实际值）
     * @throws IllegalArgumentException 类型不兼容
     */
    public static Object coerce(Object value, Class<?> targetType) {
        if (value == null) return defaultValue(targetType);
        if (targetType.isInstance(value)) return value;
        if (targetType == Object.class) return value; // 任意类型，原样塞回

        if (targetType == String.class) return value.toString();
        if (value instanceof Number n) {
            if (targetType == int.class || targetType == Integer.class) return n.intValue();
            if (targetType == long.class || targetType == Long.class) return n.longValue();
            if (targetType == double.class || targetType == Double.class) return n.doubleValue();
            if (targetType == float.class || targetType == Float.class) return n.floatValue();
        }
        if (value instanceof String s) {
            if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(s);
            if (targetType == long.class || targetType == Long.class) return Long.parseLong(s);
            if (targetType == double.class || targetType == Double.class) return Double.parseDouble(s);
            if (targetType == float.class || targetType == Float.class) return Float.parseFloat(s);
            if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException(
                "无法将 " + value + "（" + value.getClass() + "）转换为 " + targetType);
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0f;
        return null;
    }
}
