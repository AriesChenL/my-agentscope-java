package com.lynn.myagentscopejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绑定 {@code application.properties} 中以 {@code agentscope.*} 开头的配置项。
 */
@ConfigurationProperties(prefix = "agentscope")
public class AgentProperties {

    /** Agent 名称。 */
    private String name = "Assistant";

    /** 系统提示词。 */
    private String sysPrompt = "You are a helpful AI assistant.";

    private final Providers providers = new Providers();
    private final Generate generate = new Generate();
    private final SessionConfig session = new SessionConfig();
    private final MemoryConfig memory = new MemoryConfig();
    private final ToolsConfig tools = new ToolsConfig();
    private final Http http = new Http();
    private final Hitl hitl = new Hitl();
    private final Cluster cluster = new Cluster();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSysPrompt() { return sysPrompt; }
    public void setSysPrompt(String sysPrompt) { this.sysPrompt = sysPrompt; }

    public Providers getProviders() { return providers; }
    public Generate getGenerate() { return generate; }
    public SessionConfig getSession() { return session; }
    public MemoryConfig getMemory() { return memory; }
    public ToolsConfig getTools() { return tools; }
    public Http getHttp() { return http; }
    public Hitl getHitl() { return hitl; }
    public Cluster getCluster() { return cluster; }

    /**
     * 多 provider 配置：openai / anthropic / gemini 可同时启用。
     *
     * <p>每个 provider 一个独立的 {@link Provider} 配置块；用户可在新建对话时选择使用哪个，
     * 没选则使用 {@link #defaultId} 指定的默认 provider。
     */
    public static class Providers {

        /** 用户没指定时使用的默认 provider id。 */
        private String defaultId = "openai";

        private final Provider openai = new Provider();
        private final Provider anthropic = new Provider();
        private final Provider gemini = new Provider();

        public String getDefaultId() { return defaultId; }
        public void setDefaultId(String defaultId) { this.defaultId = defaultId; }
        public Provider getOpenai() { return openai; }
        public Provider getAnthropic() { return anthropic; }
        public Provider getGemini() { return gemini; }

        /**
         * 把 3 个固定槽位归一为 (id → Provider) 映射，仅返回 enabled 的项。
         * 顺序按 openai / anthropic / gemini 固定，便于前端展示。
         */
        public Map<String, Provider> enabledMap() {
            Map<String, Provider> m = new LinkedHashMap<>();
            if (openai.isEnabled()) m.put("openai", openai);
            if (anthropic.isEnabled()) m.put("anthropic", anthropic);
            if (gemini.isEnabled()) m.put("gemini", gemini);
            return m;
        }
    }

    /**
     * 单个 provider 的配置块。
     *
     * <p>{@link #displayName} 仅给前端展示用；其余字段含义与原 Model 完全一致。
     */
    public static class Provider {

        /** 是否启用该 provider。未启用时不会构造 ChatModel bean。 */
        private boolean enabled = false;

        /** 前端选择对话框上显示的友好名（例：DeepSeek / Claude / Gemini）。 */
        private String displayName;

        /** 模型名称（API 实际请求的 model 字段）。 */
        private String name;

        /** API key；优先级最高。留空时退回到 {@link #apiKeyEnv} 指定的环境变量。 */
        private String apiKey;

        /** API base URL；留空时使用 provider 的官方默认值。 */
        private String baseUrl;

        /** {@link #apiKey} 留空时回退读取的环境变量名。 */
        private String apiKeyEnv;

        /** 是否打印 HTTP 请求 body 与响应 SSE chunk 到日志（INFO 级别）。 */
        private boolean logHttp = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public boolean isLogHttp() { return logHttp; }
        public void setLogHttp(boolean logHttp) { this.logHttp = logHttp; }
    }

    /**
     * HTTP 客户端配置（共享 WebClient bean 用）。
     */
    public static class Http {
        private final Proxy proxy = new Proxy();
        public Proxy getProxy() { return proxy; }

        /**
         * 出站 HTTP 代理；境内访问 Anthropic / Gemini 必备。
         * 启用方式：{@code agentscope.http.proxy.enabled=true} + host/port。
         */
        public static class Proxy {
            /** 是否启用代理。默认关闭——开了之后所有 ChatModel HTTP 都走代理。 */
            private boolean enabled = false;
            /** 代理主机；典型本地 Clash / V2Ray 是 127.0.0.1。 */
            private String host = "127.0.0.1";
            /** 代理端口；典型本地 Clash 是 7890。 */
            private int port = 7890;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getHost() { return host; }
            public void setHost(String host) { this.host = host; }
            public int getPort() { return port; }
            public void setPort(int port) { this.port = port; }
        }
    }

    /**
     * 分布式部署配置。
     *
     * <p>{@code mode=single}（默认）：所有状态走进程内 / 文件系统，无需外部依赖
     * <br>{@code mode=distributed}：会话、对话目录走 PostgreSQL；分布式锁 / pub-sub 走 Redis
     */
    public static class Cluster {
        /** {@code single}（默认） 或 {@code distributed}。 */
        private String mode = "single";
        /** 节点唯一标识；为空时自动 hostname + 启动时间戳。用于跨节点广播去重（不响应自己发的信号）。 */
        private String nodeId;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    }

    /**
     * Human-in-the-Loop 相关配置。
     *
     * <p>当前支持：
     * <ul>
     *   <li>{@link #dangerousTools} —— 列入此清单的工具被调用时会被
     *       {@link com.lynn.myagentscopejava.core.hook.ToolConfirmationHook}
     *       拦截，转为 pending 等待人工"批准 / 拒绝"</li>
     *   <li>{@link #askUserToolEnabled} —— 是否注册内置的 {@code ask_user} 工具</li>
     * </ul>
     */
    public static class Hitl {
        /** 需要人工审批的工具名列表；为空则 {@link com.lynn.myagentscopejava.core.hook.ToolConfirmationHook} 不生效。 */
        private List<String> dangerousTools = new ArrayList<>();
        /** 是否启用 ask_user 工具（让 LLM 主动问用户）。默认 true。 */
        private boolean askUserToolEnabled = true;

        public List<String> getDangerousTools() { return dangerousTools; }
        public void setDangerousTools(List<String> v) { this.dangerousTools = v; }
        public boolean isAskUserToolEnabled() { return askUserToolEnabled; }
        public void setAskUserToolEnabled(boolean v) { this.askUserToolEnabled = v; }
    }

    /**
     * 第三方工具的配置（每个工具一个嵌套块）。
     */
    public static class ToolsConfig {
        private final Tavily tavily = new Tavily();
        public Tavily getTavily() { return tavily; }

        /** Tavily 网页搜索（用于 web_search / web_fetch 工具）。 */
        public static class Tavily {
            /** 留空则不注册搜索工具。去 https://tavily.com 申请免费 key。 */
            private String apiKey;
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        }
    }

    /**
     * 自动压缩历史的开关与策略参数。
     */
    public static class MemoryConfig {
        /** 是否启用自动压缩。{@code false} 时 ChatService 用普通 InMemoryMemory。 */
        private boolean compactionEnabled = false;
        /** 估算 token 数超过该值即触发压缩（首选指标）。{@code 0} 禁用按 token 触发。 */
        private int compactionMaxTokens = 8000;
        /** 消息数超过该值即触发压缩（兜底，防一条消息超巨情况下忘了压缩）。{@code 0} 禁用。 */
        private int compactionMaxMessages = 0;
        /** 压缩后至少保留的最近消息数量（实际可能更少，取决于安全分割点）。 */
        private int compactionKeepRecent = 10;
        /** 字符近似估算的字符密度（字符 / token）；未来可换为真实 tokenizer 实现时忽略。 */
        private double compactionCharsPerToken = 2.8;
        /** 摘要指令；为空使用内置默认指令。 */
        private String compactionInstruction;

        public boolean isCompactionEnabled() { return compactionEnabled; }
        public void setCompactionEnabled(boolean v) { this.compactionEnabled = v; }
        public int getCompactionMaxTokens() { return compactionMaxTokens; }
        public void setCompactionMaxTokens(int v) { this.compactionMaxTokens = v; }
        public int getCompactionMaxMessages() { return compactionMaxMessages; }
        public void setCompactionMaxMessages(int v) { this.compactionMaxMessages = v; }
        public int getCompactionKeepRecent() { return compactionKeepRecent; }
        public void setCompactionKeepRecent(int v) { this.compactionKeepRecent = v; }
        public double getCompactionCharsPerToken() { return compactionCharsPerToken; }
        public void setCompactionCharsPerToken(double v) { this.compactionCharsPerToken = v; }
        public String getCompactionInstruction() { return compactionInstruction; }
        public void setCompactionInstruction(String v) { this.compactionInstruction = v; }
    }

    /**
     * 多用户会话持久化配置。{@link ChatService} 用此配置定位 Session 存储位置。
     */
    public static class SessionConfig {
        /** 会话持久化目录（FileSystemSession 的 baseDir）。 */
        private String dir = "./.agent-state";
        /** 单个 ReAct 循环的最大迭代次数。 */
        private int maxIters = 10;

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public int getMaxIters() { return maxIters; }
        public void setMaxIters(int maxIters) { this.maxIters = maxIters; }
    }

    /**
     * LLM 生成参数；{@code null} 表示沿用 provider 默认值。
     */
    public static class Generate {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double v) { this.temperature = v; }
        public Double getTopP() { return topP; }
        public void setTopP(Double v) { this.topP = v; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer v) { this.maxTokens = v; }
        public Double getFrequencyPenalty() { return frequencyPenalty; }
        public void setFrequencyPenalty(Double v) { this.frequencyPenalty = v; }
        public Double getPresencePenalty() { return presencePenalty; }
        public void setPresencePenalty(Double v) { this.presencePenalty = v; }
    }
}
