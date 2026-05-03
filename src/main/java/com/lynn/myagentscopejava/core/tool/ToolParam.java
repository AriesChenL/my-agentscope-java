package com.lynn.myagentscopejava.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述 {@link Tool} 注解方法的某个参数。
 *
 * <p>{@code description} 会出现在自动生成的 JSON Schema 中，供 LLM 理解参数用途；
 * {@code required} 控制该参数是否进入 schema 的 {@code required} 数组。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** schema 中的参数名；留空则使用反射读取的参数名（依赖 {@code -parameters} 编译开关）。 */
    String name() default "";

    /** 参数描述。 */
    String description() default "";

    /** 是否为必填参数。 */
    boolean required() default true;
}
