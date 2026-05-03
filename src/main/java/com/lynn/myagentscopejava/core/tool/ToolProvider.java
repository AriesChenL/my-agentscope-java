package com.lynn.myagentscopejava.core.tool;

/**
 * 标记接口：声明一个 Spring bean 暴露了 {@link Tool} 注解的方法。
 *
 * <p>自动装配的 {@code Toolkit} bean 只扫描实现该接口的 bean，避免使用
 * {@code List<Object>} 注入时引入的循环依赖问题。
 */
public interface ToolProvider {
}
