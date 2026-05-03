package com.lynn.myagentscopejava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * my-agentscope-java 框架的启动入口。
 *
 * <p>这是一个 Spring Boot 应用，启动后会装配 agent 运行时上下文。
 * 具体的 agent 应用基于 {@code com.lynn.myagentscopejava.core} 中的核心抽象，
 * 在自己的工程里注册 bean（agent / tool / hook / runner 等）即可使用。
 */
@SpringBootApplication
public class AgentScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentScopeApplication.class, args);
    }
}
