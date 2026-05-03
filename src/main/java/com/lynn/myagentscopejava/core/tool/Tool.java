package com.lynn.myagentscopejava.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为可被 LLM 调用的工具。
 *
 * <p>方法可见性必须为 {@code public}。返回值会被转为字符串作为工具结果回传给模型；
 * 抛出的异常会被捕获并转为错误状态的
 * {@link com.lynn.myagentscopejava.core.message.ToolResultBlock}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** 暴露给 LLM 的工具名，留空则使用方法名。 */
    String name() default "";

    /** 工具功能描述，LLM 据此判断何时调用该工具。 */
    String description();
}
