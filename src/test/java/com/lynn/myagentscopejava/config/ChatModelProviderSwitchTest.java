package com.lynn.myagentscopejava.config;

import com.lynn.myagentscopejava.core.conversation.Conversation;
import com.lynn.myagentscopejava.core.conversation.ConversationDirectory;
import com.lynn.myagentscopejava.core.model.AnthropicChatModel;
import com.lynn.myagentscopejava.core.model.ChatModel;
import com.lynn.myagentscopejava.core.model.ChatModelRouter;
import com.lynn.myagentscopejava.core.model.GeminiChatModel;
import com.lynn.myagentscopejava.core.model.OpenAIChatModel;
import com.lynn.myagentscopejava.core.session.SessionKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 provider 装配测试（不打网络）：验证 3 个 provider 可以同时启用，
 * Router 按 SessionKey 正确路由，未启用 provider 时回退到默认。
 */
class ChatModelProviderSwitchTest {

    private final AgentAutoConfiguration cfg = new AgentAutoConfiguration();

    /** 构造一个三 provider 全部启用的 AgentProperties。 */
    private AgentProperties allEnabledProps() {
        AgentProperties p = new AgentProperties();
        p.getProviders().setDefaultId("openai");
        var openai = p.getProviders().getOpenai();
        openai.setEnabled(true);
        openai.setName("deepseek-chat");
        openai.setApiKey("fake");
        var anth = p.getProviders().getAnthropic();
        anth.setEnabled(true);
        anth.setName("claude-sonnet-4-5");
        anth.setApiKey("fake");
        var gem = p.getProviders().getGemini();
        gem.setEnabled(true);
        gem.setName("gemini-2.0-flash");
        gem.setApiKey("fake");
        return p;
    }

    private WebClient noopWebClient() {
        return WebClient.builder().clientConnector(new JdkClientHttpConnector()).build();
    }

    /** 假的 ConversationDirectory：把 convId 当 provider 返回，方便测路由。 */
    private ConversationDirectory fakeDirectory(Map<String, String> convToProvider) {
        return new ConversationDirectory() {
            @Override public List<Conversation> list(String userId) { return List.of(); }
            @Override public Conversation create(String userId, String title, String provider) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean delete(String userId, String convId) { return false; }
            @Override public Optional<Conversation> get(String userId, String convId) {
                String prov = convToProvider.get(convId);
                if (prov == null) return Optional.empty();
                return Optional.of(new Conversation(convId, "t", prov, 0L, 0L));
            }
            @Override public Optional<Conversation> rename(String userId, String convId, String t) {
                return Optional.empty();
            }
            @Override public void touch(String userId, String convId) {}
        };
    }

    @Test
    void allThreeProvidersBuiltSimultaneously() {
        Map<String, ChatModel> models = cfg.chatModels(allEnabledProps(), noopWebClient());
        assertEquals(3, models.size());
        assertInstanceOf(OpenAIChatModel.class, models.get("openai"));
        assertInstanceOf(AnthropicChatModel.class, models.get("anthropic"));
        assertInstanceOf(GeminiChatModel.class, models.get("gemini"));
    }

    @Test
    void emptyEnabledThrowsClearError() {
        AgentProperties p = new AgentProperties();
        // 全部 disabled
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                cfg.chatModels(p, noopWebClient()));
        assertTrue(ex.getMessage().contains("至少要启用一个 provider"));
    }

    @Test
    void routerResolvesByConversationProvider() {
        AgentProperties props = allEnabledProps();
        Map<String, ChatModel> models = cfg.chatModels(props, noopWebClient());
        ChatModelRouter router = cfg.chatModelRouter(models, props,
                fakeDirectory(Map.of(
                        "c1", "anthropic",
                        "c2", "gemini")));
        assertInstanceOf(AnthropicChatModel.class,
                router.resolve(SessionKey.of("alice__c1")));
        assertInstanceOf(GeminiChatModel.class,
                router.resolve(SessionKey.of("alice__c2")));
    }

    @Test
    void routerFallsBackToDefaultWhenConvUnknown() {
        AgentProperties props = allEnabledProps();
        Map<String, ChatModel> models = cfg.chatModels(props, noopWebClient());
        ChatModelRouter router = cfg.chatModelRouter(models, props, fakeDirectory(Map.of()));
        assertInstanceOf(OpenAIChatModel.class,
                router.resolve(SessionKey.of("alice__never-existed")));
    }

    @Test
    void routerFallsBackToFirstEnabledWhenDefaultNotEnabled() {
        AgentProperties props = allEnabledProps();
        // 把 default 设成一个未启用的 id
        props.getProviders().getOpenai().setEnabled(false);
        props.getProviders().setDefaultId("openai");
        Map<String, ChatModel> models = cfg.chatModels(props, noopWebClient());
        // 现在只有 anthropic 和 gemini 启用，openai 不在 models 里
        ChatModelRouter router = cfg.chatModelRouter(models, props, fakeDirectory(Map.of()));
        // 应该回退到第一个启用的（anthropic）
        assertEquals("anthropic", router.defaultProviderId());
    }

    @Test
    void byProviderReturnsRequestedModel() {
        AgentProperties props = allEnabledProps();
        Map<String, ChatModel> models = cfg.chatModels(props, noopWebClient());
        ChatModelRouter router = cfg.chatModelRouter(models, props, fakeDirectory(Map.of()));
        assertInstanceOf(GeminiChatModel.class, router.byProvider("gemini"));
        // 未知 id 回退到默认
        assertInstanceOf(OpenAIChatModel.class, router.byProvider("nonexistent"));
    }

    @Test
    void webClientBuildsWithProxyDisabled() {
        AgentProperties p = new AgentProperties();
        p.getHttp().getProxy().setEnabled(false);
        WebClient wc = cfg.agentscopeWebClient(p);
        assertEquals(true, wc != null);
    }

    @Test
    void webClientBuildsWithProxyEnabled() {
        AgentProperties p = new AgentProperties();
        p.getHttp().getProxy().setEnabled(true);
        p.getHttp().getProxy().setHost("127.0.0.1");
        p.getHttp().getProxy().setPort(7890);
        WebClient wc = cfg.agentscopeWebClient(p);
        assertEquals(true, wc != null);
    }

    @Test
    void rawJdkHttpClientWithProxyDoesNotBlowUp() {
        HttpClient hc = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)))
                .build();
        assertEquals(true, hc != null);
    }
}
