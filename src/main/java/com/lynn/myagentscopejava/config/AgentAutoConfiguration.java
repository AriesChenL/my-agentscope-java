package com.lynn.myagentscopejava.config;

import com.lynn.myagentscopejava.core.agent.ReActAgent;
import com.lynn.myagentscopejava.core.hook.Hook;
import com.lynn.myagentscopejava.core.memory.CompactingMemory;
import com.lynn.myagentscopejava.core.memory.InMemoryMemory;
import com.lynn.myagentscopejava.core.memory.Memory;
import com.lynn.myagentscopejava.core.memory.SummarizingCompactor;
import com.lynn.myagentscopejava.core.memory.TokenEstimator;
import com.lynn.myagentscopejava.core.model.AnthropicChatModel;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatModelRouter;
import com.lynn.myagentscopejava.core.model.GeminiChatModel;
import com.lynn.myagentscopejava.core.model.GenerateOptions;
import com.lynn.myagentscopejava.core.model.OpenAIChatModel;
import com.lynn.myagentscopejava.core.service.ChatService;
import com.lynn.myagentscopejava.core.cluster.DistributedLock;
import com.lynn.myagentscopejava.core.cluster.NotificationBus;
import com.lynn.myagentscopejava.core.cluster.impl.LocalDistributedLock;
import com.lynn.myagentscopejava.core.cluster.impl.LocalNotificationBus;
import com.lynn.myagentscopejava.core.conversation.ConversationDirectory;
import com.lynn.myagentscopejava.core.conversation.FileSystemConversationDirectory;
import com.lynn.myagentscopejava.core.session.FileSystemSession;
import com.lynn.myagentscopejava.core.session.Session;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import com.lynn.myagentscopejava.core.tool.Toolkit;
import com.lynn.myagentscopejava.core.hook.PendingToolRecoveryHook;
import com.lynn.myagentscopejava.core.hook.ToolConfirmationHook;
import com.lynn.myagentscopejava.tools.UserInteractionTool;
import com.lynn.myagentscopejava.tools.WebSearchTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 框架的 Spring 自动装配类，把 ChatModel / Memory / Toolkit / ReActAgent 都注册成 bean，
 * 业务代码只需 {@code @Autowired ReActAgent agent} 即可使用。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {

    /**
     * 共享的 {@link WebClient} bean。
     *
     * <p>用 {@link JdkClientHttpConnector} 做 transport（基于 JDK 内置 HttpClient），
     * 不引 Netty。一个 WebClient 实例在内部维护连接池，所有 ChatModel 复用同一实例更高效。
     *
     * <p>定义为 {@code @ConditionalOnMissingBean}，让上层应用可以自定义（例如要换成 Reactor Netty
     * 或加全局 interceptor、超时等）。
     */
    @Bean
    @ConditionalOnMissingBean
    public WebClient agentscopeWebClient(AgentProperties props) {
        AgentProperties.Http.Proxy p = props.getHttp().getProxy();
        java.net.http.HttpClient.Builder hb = java.net.http.HttpClient.newBuilder()
                // HTTP/2（默认）：经 HTTP CONNECT 代理时部分 endpoint（如
                // generativelanguage.googleapis.com）可能 ALPN 握手失败抛
                // SSLHandshakeException: Remote host terminated the handshake。
                // 如遇此问题改回 .version(HTTP_1_1)。
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(15));
        if (p.isEnabled()) {
            // 全局出站代理；Anthropic/Gemini 在境内需要走本地 Clash/V2Ray
            hb.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(p.getHost(), p.getPort())));
        }
        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(hb.build()))
                // 连接级自动重试：境内访问海外 LLM 经代理时偶发 SSL/IO 中断
                // （SSLHandshakeException / AEADBadTagException / EOFException 等）。
                // 这里只针对"连接建立 + 请求发送"阶段重试；一旦开始接收 streaming body
                // 失败不会重试（避免重发请求烧 token）。
                .filter((req, next) -> next.exchange(req)
                        .retryWhen(reactor.util.retry.Retry
                                .backoff(2, java.time.Duration.ofMillis(500))
                                .maxBackoff(java.time.Duration.ofSeconds(3))
                                .filter(AgentAutoConfiguration::isTransientNetworkError)))
                .build();
    }

    /** 判断异常是否为可重试的瞬时网络/SSL 错误。 */
    private static boolean isTransientNetworkError(Throwable ex) {
        Throwable cur = ex;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur instanceof java.io.IOException) return true;
            if (cur instanceof javax.net.ssl.SSLException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 同时构造所有已启用的 ChatModel 实例（每个 enabled provider 一个）。
     *
     * <p>用户可在新建对话时选择使用哪个 provider；{@link ChatModelRouter} 负责按会话路由。
     *
     * <p>API key 解析优先级：properties 显式 {@code api-key} &gt; {@code api-key-env} 指定环境变量。
     */
    @Bean
    public java.util.Map<String, ChatModel> chatModels(AgentProperties props,
                                                       WebClient agentscopeWebClient) {
        java.util.Map<String, AgentProperties.Provider> enabled = props.getProviders().enabledMap();
        if (enabled.isEmpty()) {
            throw new IllegalStateException(
                    "至少要启用一个 provider；请在 application.properties 中至少把一个 "
                            + "agentscope.providers.{openai|anthropic|gemini}.enabled 改为 true");
        }
        java.util.Map<String, ChatModel> models = new java.util.LinkedHashMap<>();
        for (var e : enabled.entrySet()) {
            String id = e.getKey();
            AgentProperties.Provider p = e.getValue();
            models.put(id, buildChatModel(id, p, agentscopeWebClient));
        }
        return models;
    }

    private ChatModel buildChatModel(String providerId, AgentProperties.Provider p,
                                     WebClient webClient) {
        String apiKey = p.getApiKey();
        if ((apiKey == null || apiKey.isBlank()) && p.getApiKeyEnv() != null
                && !p.getApiKeyEnv().isBlank()) {
            apiKey = System.getenv(p.getApiKeyEnv());
        }
        return switch (providerId) {
            case "anthropic" -> AnthropicChatModel.builder()
                    .webClient(webClient).baseUrl(p.getBaseUrl()).apiKey(apiKey)
                    .modelName(p.getName()).logHttp(p.isLogHttp()).build();
            case "gemini" -> GeminiChatModel.builder()
                    .webClient(webClient).baseUrl(p.getBaseUrl()).apiKey(apiKey)
                    .modelName(p.getName()).logHttp(p.isLogHttp()).build();
            case "openai" -> OpenAIChatModel.builder()
                    .webClient(webClient).baseUrl(p.getBaseUrl()).apiKey(apiKey)
                    .modelName(p.getName()).logHttp(p.isLogHttp()).build();
            default -> throw new IllegalArgumentException("未知 provider id: " + providerId);
        };
    }

    /**
     * ChatModel 路由器：每个会话用哪个 provider 由 {@link ConversationDirectory} 里的 provider 字段决定。
     */
    @Bean
    public ChatModelRouter chatModelRouter(java.util.Map<String, ChatModel> chatModels,
                                           AgentProperties props,
                                           ConversationDirectory directory) {
        String defaultId = props.getProviders().getDefaultId();
        if (!chatModels.containsKey(defaultId)) {
            // 兜底：默认 id 没启用时退回到第一个启用的
            defaultId = chatModels.keySet().iterator().next();
        }
        return new ChatModelRouter(chatModels, defaultId, key -> {
            // SessionKey 形如 "userId__convId"
            String s = key.value();
            int sep = s.indexOf("__");
            if (sep < 0) return null;
            String userId = s.substring(0, sep);
            String convId = s.substring(sep + 2);
            return directory.get(userId, convId)
                    .map(c -> c.provider())
                    .orElse(null);
        });
    }

    /**
     * 把 properties 中的生成参数装配为 GenerateOptions bean。
     */
    @Bean
    public GenerateOptions generateOptions(AgentProperties props) {
        AgentProperties.Generate g = props.getGenerate();
        return GenerateOptions.builder()
                .temperature(g.getTemperature())
                .topP(g.getTopP())
                .maxTokens(g.getMaxTokens())
                .frequencyPenalty(g.getFrequencyPenalty())
                .presencePenalty(g.getPresencePenalty())
                .build();
    }

    /**
     * 默认的 Memory bean，使用进程内实现。
     */
    @Bean
    public Memory memory() {
        return new InMemoryMemory();
    }

    /**
     * 自动装配 Toolkit：把所有实现 {@link ToolProvider} 的 bean 注册进去。
     *
     * <p>使用 marker 接口而不是 {@code List<Object>} 是为了避免循环依赖：
     * 后者会让 Spring 把所有 bean（包括依赖 ReActAgent 的 runner）也算进来。
     */
    @Bean
    public Toolkit toolkit(@Autowired(required = false) List<ToolProvider> toolBeans) {
        Toolkit kit = new Toolkit();
        if (toolBeans != null) toolBeans.forEach(kit::registerObject);
        return kit;
    }

    /**
     * Tavily 网页搜索工具。
     *
     * <p>仅当 {@code agentscope.tools.tavily.api-key} 配置了非空值时才注册到 Spring 容器，
     * 进而被自动加入 Toolkit。没配 key 就什么都不发生，不影响其它工具。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "agentscope.tools.tavily", name = "api-key")
    public WebSearchTool webSearchTool(AgentProperties props,
                                       org.springframework.web.reactive.function.client.WebClient agentscopeWebClient) {
        return new WebSearchTool(agentscopeWebClient, props.getTools().getTavily().getApiKey());
    }

    /**
     * 内置的 {@code ask_user} HITL 工具：让 LLM 主动向用户提问。
     *
     * <p>默认启用；可通过 {@code agentscope.hitl.ask-user-tool-enabled=false} 关闭。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.hitl", name = "ask-user-tool-enabled",
            havingValue = "true", matchIfMissing = true)
    public UserInteractionTool userInteractionTool() {
        return new UserInteractionTool();
    }

    /**
     * "孤儿 tool_calls 自动修复" Hook。
     *
     * <p>注册为 Spring bean 后会被自动注入到 {@link ChatService} 与默认 {@link ReActAgent} 的
     * hooks 列表，在 {@link com.lynn.myagentscopejava.core.hook.PreCallEvent} 时机扫描 memory，
     * 给最近一条 ASSISTANT 中没有对应 ToolResultBlock 的 ToolUseBlock 补合成错误结果。
     *
     * <p>对应上游 agentscope-java 的 {@code PendingToolRecoveryHook}。
     */
    @Bean
    public PendingToolRecoveryHook pendingToolRecoveryHook(AgentProperties props) {
        return new PendingToolRecoveryHook(props.getName());
    }

    /**
     * "危险工具人工审批" Hook。把 {@code agentscope.hitl.dangerous-tools} 列出的工具名注入。
     *
     * <p>注：始终注册 bean，让运行时能动态增删危险工具（通过
     * {@link com.lynn.myagentscopejava.web.ChatController.HitlSettingsApi} 接口）。
     * 集合为空时 hook 是 no-op，无副作用。
     */
    @Bean
    public ToolConfirmationHook toolConfirmationHook(AgentProperties props) {
        List<String> initial = props.getHitl().getDangerousTools();
        return new ToolConfirmationHook(
                initial != null ? new java.util.HashSet<>(initial) : new java.util.HashSet<>());
    }

    /**
     * 默认的 ReActAgent bean，使用默认 provider。
     *
     * <p><b>注意：单例 + 共享 Memory，仅适合"单租户、串行"场景</b>（脚本、调试、单用户 CLI）。
     * 多用户并发请求 + 多 provider 切换请使用 {@link ChatService}。
     */
    @Bean
    public ReActAgent reActAgent(AgentProperties props, ChatModelRouter modelRouter,
                                 Memory memory, Toolkit toolkit, GenerateOptions generateOptions,
                                 @Autowired(required = false) List<Hook> hooks) {
        ReActAgent.Builder b = ReActAgent.builder()
                .name(props.getName())
                .sysPrompt(props.getSysPrompt())
                .model(modelRouter.byProvider(modelRouter.defaultProviderId()))
                .memory(memory)
                .toolkit(toolkit)
                .generateOptions(generateOptions);
        if (hooks != null) b.hooks(hooks);
        return b.build();
    }

    /**
     * 单机模式 Session bean：用 {@link FileSystemSession} 落盘到本地目录。
     *
     * <p>仅当 {@code agentscope.cluster.mode != distributed} 时启用（默认 single）。
     * 落盘目录由 {@code agentscope.session.dir} 配置（默认 {@code ./.agent-state}）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.cluster", name = "mode",
            havingValue = "single", matchIfMissing = true)
    public Session fileSystemSession(AgentProperties props) {
        return new FileSystemSession(Path.of(props.getSession().getDir()));
    }

    /**
     * 单机模式对话目录：与 {@link FileSystemSession} 同根目录。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.cluster", name = "mode",
            havingValue = "single", matchIfMissing = true)
    public ConversationDirectory fileSystemConversationDirectory(AgentProperties props) {
        return new FileSystemConversationDirectory(Path.of(props.getSession().getDir()));
    }

    /**
     * 分布式模式 Session bean：用 PostgreSQL JSONB 列存储。
     *
     * <p>启用条件：{@code agentscope.cluster.mode=distributed}。
     * 表结构由 Flyway 在启动时通过 {@code db/migration/V1__init_distributed_schema.sql} 创建，
     * 所以应用必须配 {@code spring.datasource.*}。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.cluster", name = "mode", havingValue = "distributed")
    public Session postgresSession(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                   com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new com.lynn.myagentscopejava.core.session.PostgresSession(jdbc, mapper);
    }

    /**
     * 分布式模式对话目录：PostgreSQL conversations 表。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.cluster", name = "mode", havingValue = "distributed")
    public ConversationDirectory postgresConversationDirectory(
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.lynn.myagentscopejava.core.conversation.PostgresConversationDirectory(jdbc);
    }

    /**
     * 分布式锁。单机部署用 {@link LocalDistributedLock}（进程内 ReentrantLock）；
     * 分布式部署可换 {@code RedisDistributedLock}（Redisson）。
     *
     * <p>{@code @ConditionalOnMissingBean} 让上层应用可以自定义实现覆盖默认。
     */
    @Bean
    @ConditionalOnMissingBean
    public DistributedLock distributedLock() {
        return new LocalDistributedLock();
    }

    /**
     * 跨节点广播总线。单机用 {@link LocalNotificationBus}（进程内回调）；
     * 分布式部署可换 {@code RedisNotificationBus}（pub/sub）。
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationBus notificationBus() {
        return new LocalNotificationBus();
    }

    /**
     * 多用户安全的 chat 入口。每次调用都在 Session 中按 SessionKey 隔离，
     * 同一 key 串行（避免脏写），不同 key 并行。
     *
     * <p>当 {@code agentscope.memory.compaction-enabled=true} 时，每个会话的 Memory 会被
     * {@link CompactingMemory} 包装，超过阈值自动用 LLM 摘要旧历史。
     */
    @Bean
    public ChatService chatService(AgentProperties props, ChatModelRouter modelRouter,
                                   Toolkit toolkit, GenerateOptions generateOptions,
                                   Session session, DistributedLock distributedLock,
                                   @Autowired(required = false) List<Hook> hooks) {
        AgentProperties.MemoryConfig mc = props.getMemory();
        // compactor 总是创建（只要 maxTokens / maxMessages 至少一个 > 0）。compactionEnabled 仅控制
        // 自动触发；手动按钮（ChatService.compactNow）只要 compactor 存在就能用。
        SummarizingCompactor compactor = null;
        if (mc.getCompactionMaxTokens() > 0 || mc.getCompactionMaxMessages() > 0) {
            TokenEstimator estimator = TokenEstimator.approximate(mc.getCompactionCharsPerToken());
            ChatModel summarizer = modelRouter.byProvider(modelRouter.defaultProviderId());
            compactor = SummarizingCompactor.builder()
                    .summarizer(summarizer)
                    .maxTokens(mc.getCompactionMaxTokens())
                    .maxMessages(mc.getCompactionMaxMessages())
                    .keepRecent(mc.getCompactionKeepRecent())
                    .estimator(estimator)
                    .summaryInstruction(mc.getCompactionInstruction())
                    .build();
        }

        final SummarizingCompactor finalCompactor = compactor;
        Supplier<Memory> memoryFactory = (mc.isCompactionEnabled() && finalCompactor != null)
                ? () -> new CompactingMemory(new InMemoryMemory(), finalCompactor)
                : InMemoryMemory::new;

        return ChatService.builder()
                .modelRouter(modelRouter)
                .toolkit(toolkit)
                .generateOptions(generateOptions)
                .session(session)
                .distributedLock(distributedLock)
                .agentName(props.getName())
                .sysPrompt(props.getSysPrompt())
                .maxIters(props.getSession().getMaxIters())
                .hooks(hooks != null ? hooks : List.of())
                .memoryFactory(memoryFactory)
                .compactor(compactor)
                .build();
    }
}
