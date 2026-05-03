package com.lynn.myagentscopejava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynn.myagentscopejava.config.AgentProperties;
import com.lynn.myagentscopejava.core.conversation.Conversation;
import com.lynn.myagentscopejava.core.conversation.ConversationDirectory;
import com.lynn.myagentscopejava.core.message.Msg;
import com.lynn.myagentscopejava.core.message.MsgRole;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import com.lynn.myagentscopejava.core.service.ChatService;
import com.lynn.myagentscopejava.core.service.ReactEvent;
import com.lynn.myagentscopejava.core.session.SessionKey;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 聊天页面 + 后端 REST/SSE 接口。
 *
 * <p>路由：
 * <ul>
 *   <li>{@code GET /} —— 渲染聊天页（{@code ?user=alice&conv=c-xxx}）</li>
 *   <li>{@code POST /api/chat/{userId}/{convId}} —— 同步 chat（支持工具与 HITL）</li>
 *   <li>{@code GET /api/chat/{userId}/{convId}/react-stream?text=...} —— SSE 流式 chat（含工具）</li>
 *   <li>{@code GET /api/chat/{userId}/{convId}/history} —— 拉取该会话历史</li>
 *   <li>{@code DELETE /api/chat/{userId}/{convId}} —— 重置该会话消息</li>
 *   <li>{@code GET /api/users/{userId}/conversations} —— 列出该用户的所有会话</li>
 *   <li>{@code POST /api/users/{userId}/conversations} —— 创建新会话</li>
 *   <li>{@code DELETE /api/users/{userId}/conversations/{convId}} —— 删除会话（含消息）</li>
 *   <li>{@code PUT /api/users/{userId}/conversations/{convId}} —— 改名</li>
 * </ul>
 *
 * <p>内部：(userId, convId) 通过 {@code __} 拼成 SessionKey 给 {@link ChatService} 用。
 */
@Controller
public class ChatController {

    /** 进程启动时刻，作为静态资源 URL 的 cache-buster；进程重启就强制刷新 chat.js / chat.css */
    private static final String ASSET_VERSION = Long.toString(System.currentTimeMillis());

    private final ChatService chatService;
    private final ConversationDirectory conversations;
    private final AgentProperties props;

    public ChatController(ChatService chatService, ConversationDirectory conversations,
                          AgentProperties props) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.props = props;
    }

    /**
     * 渲染聊天页。如果未指定 conv，自动选最新一个或创建一个，重定向到带 conv 的 URL。
     */
    @GetMapping("/")
    public String index(@RequestParam(value = "user", defaultValue = "guest") String user,
                        @RequestParam(value = "conv", required = false) String conv,
                        Model model) {
        if (conv == null || conv.isBlank()) {
            List<Conversation> list = conversations.list(user);
            Conversation target = list.isEmpty() ? conversations.create(user, null) : list.getFirst();
            return "redirect:/?user=" + user + "&conv=" + target.id();
        }
        // 确保该 conv 在 index 中存在；不存在的话也创建一条占位（容错）
        if (conversations.get(user, conv).isEmpty()) {
            conversations.create(user, null);
        }
        model.addAttribute("agentName", props.getName());
        // modelName 由前端动态从 /api/providers 拉取再按会话切换；这里给一个占位
        model.addAttribute("modelName", "");
        model.addAttribute("userId", user);
        model.addAttribute("convId", conv);
        model.addAttribute("assetVersion", ASSET_VERSION);
        return "chat";
    }

    /** Conversation 维度的管理 API。 */
    @RestController
    @RequestMapping("/api/users")
    public static class ConversationApi {
        private final ConversationDirectory directory;
        private final ChatService chatService;

        public ConversationApi(ConversationDirectory directory, ChatService chatService) {
            this.directory = directory;
            this.chatService = chatService;
        }

        @GetMapping("/{userId}/conversations")
        public List<Conversation> list(@PathVariable String userId) {
            return directory.list(userId);
        }

        @PostMapping("/{userId}/conversations")
        public Conversation create(@PathVariable String userId,
                                   @RequestBody(required = false) CreateDto body) {
            String title = body == null ? null : body.title();
            String provider = body == null ? null : body.provider();
            return directory.create(userId, title, provider);
        }

        @PutMapping("/{userId}/conversations/{convId}")
        public Conversation rename(@PathVariable String userId, @PathVariable String convId,
                                   @RequestBody CreateDto body) {
            return directory.rename(userId, convId, body.title()).orElseThrow(
                    () -> new IllegalArgumentException("会话不存在：" + convId));
        }

        @DeleteMapping("/{userId}/conversations/{convId}")
        public Map<String, Object> delete(@PathVariable String userId, @PathVariable String convId) {
            chatService.deleteSession(SessionKey.of(userId + "__" + convId));
            boolean ok = directory.delete(userId, convId);
            return Map.of("ok", ok);
        }

        /** 创建/改名 通用 DTO；rename 时只用 title，create 时两个字段都可选。 */
        public record CreateDto(String title, String provider) {}
    }

    /**
     * 列出当前后端启用的 provider —— 前端"新建对话"弹窗用此填充选项。
     */
    @RestController
    @RequestMapping("/api/providers")
    public static class ProvidersApi {
        private final AgentProperties props;
        private final com.lynn.myagentscopejava.core.model.ChatModelRouter router;

        public ProvidersApi(AgentProperties props,
                            com.lynn.myagentscopejava.core.model.ChatModelRouter router) {
            this.props = props;
            this.router = router;
        }

        @GetMapping
        public Map<String, Object> list() {
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (var e : props.getProviders().enabledMap().entrySet()) {
                String id = e.getKey();
                AgentProperties.Provider p = e.getValue();
                items.add(Map.of(
                        "id", id,
                        "displayName", p.getDisplayName() != null ? p.getDisplayName() : defaultDisplayName(id),
                        "modelName", p.getName() != null ? p.getName() : ""));
            }
            return Map.of(
                    "providers", items,
                    "defaultId", router.defaultProviderId());
        }

        private static String defaultDisplayName(String id) {
            return switch (id) {
                case "openai" -> "OpenAI 兼容";
                case "anthropic" -> "Claude";
                case "gemini" -> "Gemini";
                default -> id;
            };
        }
    }

    /**
     * HITL 设置 API：管理"危险工具"清单与查询全部工具名。
     * 前端"设置"页面用此接口动态调整哪些工具需要人工审批。
     */
    @RestController
    @RequestMapping("/api/hitl")
    public static class HitlSettingsApi {
        private final com.lynn.myagentscopejava.core.hook.ToolConfirmationHook hook;
        private final com.lynn.myagentscopejava.core.tool.Toolkit toolkit;

        public HitlSettingsApi(com.lynn.myagentscopejava.core.hook.ToolConfirmationHook hook,
                               com.lynn.myagentscopejava.core.tool.Toolkit toolkit) {
            this.hook = hook;
            this.toolkit = toolkit;
        }

        /** 列出全部已注册工具名（前端"设置"页面用来选哪些工具加入危险清单）。 */
        @GetMapping("/tools")
        public java.util.Set<String> listTools() {
            return toolkit.getTools().stream()
                    .map(t -> t.schema().name())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        @GetMapping("/dangerous-tools")
        public java.util.Set<String> getDangerousTools() {
            return hook.getDangerousTools();
        }

        @PutMapping("/dangerous-tools")
        public Map<String, Object> setDangerousTools(@RequestBody java.util.Set<String> toolNames) {
            hook.setDangerousTools(toolNames);
            return Map.of("ok", true, "dangerousTools", hook.getDangerousTools());
        }
    }

    /** Chat REST 子控制器。 */
    @RestController
    @RequestMapping("/api/chat")
    public static class Api {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Api.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final ChatService chatService;
        private final ConversationDirectory directory;

        public Api(ChatService chatService, ConversationDirectory directory) {
            this.chatService = chatService;
            this.directory = directory;
        }

        /**
         * 兜底：用户主动中断不算服务器错误。任何路径漏出来的 AgentInterruptedException
         * 都在这里被静默处理，避免污染日志。
         */
        @org.springframework.web.bind.annotation.ExceptionHandler(
                com.lynn.myagentscopejava.core.interruption.AgentInterruptedException.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> handleInterrupted(
                com.lynn.myagentscopejava.core.interruption.AgentInterruptedException ex) {
            log.info("中断穿透到 controller（来源 {}）：{}",
                    ex.getSource() != null ? ex.getSource().name() : "?", ex.getMessage());
            return org.springframework.http.ResponseEntity.ok(Map.of(
                    "interrupted", true,
                    "source", ex.getSource() != null ? ex.getSource().name() : "USER"));
        }

        /** 同步 chat：支持完整 ReAct 循环。 */
        @PostMapping("/{userId}/{convId}")
        public Map<String, Object> chat(@PathVariable String userId, @PathVariable String convId,
                                        @RequestBody ChatRequest req) {
            SessionKey key = SessionKey.of(userId + "__" + convId);
            Msg userMsg;
            if (req.role() == MsgRole.TOOL && req.toolCallId() != null) {
                // HITL 恢复：从挂起的 ToolUseBlock 找回工具名（Gemini cachedContents 严格校验需要）
                String toolName = chatService.getPendingToolUses(key).stream()
                        .filter(t -> req.toolCallId().equals(t.id()))
                        .map(com.lynn.myagentscopejava.core.message.ToolUseBlock::name)
                        .findFirst().orElse(null);
                userMsg = Msg.builder().role(MsgRole.TOOL).content(
                        com.lynn.myagentscopejava.core.message.ToolResultBlock.success(
                                req.toolCallId(), toolName, req.text())).build();
            } else {
                userMsg = Msg.user(userId, req.text());
            }
            ChatService.ChatTurn turn = chatService.chatDetailed(key, userMsg);
            maybeAutoTitleAndTouch(userId, convId, req.text());
            // skip(1) 仅适用于普通 chat（首条 added 是 USER 输入，前端已自渲染）；
            // HITL 回填路径下 resumeWithHumanInput 是替换末尾 TOOL 消息而非 append，
            // added 首条就是 LLM 续推产生的 ASSISTANT/TOOL，不能 skip 否则前端看不到模型回复。
            boolean isHitlResume = (req.role() == MsgRole.TOOL && req.toolCallId() != null);
            return Map.of(
                    "text", turn.reply().getText(),
                    "awaitingHumanInput", chatService.isAwaitingHumanInput(key),
                    "pendingTools", chatService.getPendingToolUses(key).stream()
                            .map(t -> Map.of("id", t.id(), "name", t.name(), "input", t.input()))
                            .toList(),
                    "addedMessages", turn.addedMessages().stream()
                            .skip(isHitlResume ? 0 : 1)
                            .map(Api::messageToMap)
                            .toList()
            );
        }

        /**
         * 完整流式 chat：走 ReAct 循环，按事件类型推流。
         * 使用 {@link SseEmitter} 而非返回 {@code Flux<String>} —— 后者在 Spring MVC + Tomcat 下
         * 不能保证每个 emission 立即 flush 到客户端（导致前端"打字机"看起来像一次性收到）。
         * SseEmitter.send() 会主动 flush，token 真的能边产生边出现。
         */
        @GetMapping(value = "/{userId}/{convId}/react-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter reactStream(@PathVariable String userId, @PathVariable String convId,
                                      @RequestParam String text) {
            SessionKey key = SessionKey.of(userId + "__" + convId);
            maybeAutoTitleAndTouch(userId, convId, text);

            // 5 分钟超时；够长是因为长对话 + 工具调用可能跑较久
            SseEmitter emitter = new SseEmitter(5L * 60 * 1000);
            Disposable disposable = chatService.chatReactStream(key, Msg.user(userId, text))
                    .subscribe(
                            ev -> {
                                try {
                                    emitter.send(SseEmitter.event().data(eventToJson(ev)));
                                } catch (Exception e) {
                                    emitter.completeWithError(e);
                                }
                            },
                            emitter::completeWithError,
                            emitter::complete
                    );
            // 客户端断开时取消上游
            emitter.onCompletion(disposable::dispose);
            emitter.onTimeout(disposable::dispose);
            emitter.onError(err -> disposable.dispose());
            return emitter;
        }

        /** 历史消息。 */
        @GetMapping("/{userId}/{convId}/history")
        public List<Map<String, Object>> history(@PathVariable String userId, @PathVariable String convId) {
            SessionKey key = SessionKey.of(userId + "__" + convId);
            return chatService.getMessages(key).stream()
                    .map(Api::messageToMap)
                    .toList();
        }

        /** 重置该会话的消息（保留会话本身在索引中）。 */
        @DeleteMapping("/{userId}/{convId}")
        public Map<String, Object> reset(@PathVariable String userId, @PathVariable String convId) {
            chatService.deleteSession(SessionKey.of(userId + "__" + convId));
            return Map.of("ok", true);
        }

        /**
         * "批准"挂起的工具调用：后端真正调用该工具，把真实结果回填给 agent 续推。
         *
         * <p>典型用例：{@code ToolConfirmationHook} 拦截了某个危险工具，前端显示
         * "批准/拒绝"两个按钮，用户点"批准"就调本接口。
         *
         * <p>区分于 {@code POST /{userId}/{convId}}：那个接口是"用户提供工具结果"
         * （拒绝、ask_user 答案、人工模拟工具输出），本接口是"让工具真的跑一次"。
         */
        @PostMapping("/{userId}/{convId}/approve-tool")
        public Map<String, Object> approveTool(@PathVariable String userId,
                                               @PathVariable String convId,
                                               @RequestBody ApproveToolRequest req) {
            SessionKey key = SessionKey.of(userId + "__" + convId);
            ChatService.ChatTurn turn = chatService.approvePendingTool(key, req.toolCallId());
            return Map.of(
                    "text", turn.reply().getText(),
                    "awaitingHumanInput", chatService.isAwaitingHumanInput(key),
                    "pendingTools", chatService.getPendingToolUses(key).stream()
                            .map(t -> Map.of("id", t.id(), "name", t.name(), "input", t.input()))
                            .toList(),
                    "addedMessages", turn.addedMessages().stream()
                            .map(Api::messageToMap)
                            .toList()
            );
        }

        /** {@link #approveTool} 的请求体。 */
        public record ApproveToolRequest(String toolCallId) {}

        /** 手动压缩该会话历史：用 LLM 摘要旧消息，节省后续对话的 token。 */
        @PostMapping("/{userId}/{convId}/compact")
        public Map<String, Object> compact(@PathVariable String userId, @PathVariable String convId) {
            try {
                ChatService.CompactionResult r = chatService.compactNow(
                        SessionKey.of(userId + "__" + convId));
                return Map.of("ok", true, "before", r.before(), "after", r.after());
            } catch (IllegalStateException ex) {
                return Map.of("ok", false, "error", ex.getMessage());
            }
        }

        /**
         * 中断该会话当前正在执行的 chat（流式或同步均生效）。
         * 已部分流式输出的内容会被持久化到 memory（带按来源区分的标记），下次对话上下文不丢。
         *
         * @param source 中断来源，可选 {@code USER / TOOL / SYSTEM}，默认 USER
         */
        @PostMapping("/{userId}/{convId}/interrupt")
        public Map<String, Object> interrupt(
                @PathVariable String userId,
                @PathVariable String convId,
                @org.springframework.web.bind.annotation.RequestParam(value = "source", defaultValue = "USER")
                com.lynn.myagentscopejava.core.interruption.InterruptSource source) {
            boolean ok = chatService.interrupt(SessionKey.of(userId + "__" + convId), source);
            return Map.of("interrupted", ok, "source", source.name());
        }

        // ---------- 内部工具 ----------

        /** 首次发消息时自动以这条消息为标题；其它时候只刷新 updatedAt 让会话排到顶部。 */
        private void maybeAutoTitleAndTouch(String userId, String convId, String userText) {
            Conversation existing = directory.get(userId, convId).orElse(null);
            if (existing == null) return;
            if ("新对话".equals(existing.title()) && userText != null && !userText.isBlank()) {
                String title = userText.length() > 30 ? userText.substring(0, 30) + "…" : userText;
                directory.rename(userId, convId, title);
            } else {
                directory.touch(userId, convId);
            }
        }

        private static Map<String, Object> messageToMap(Msg m) {
            return Map.of(
                    "role", m.getRole().name(),
                    "name", m.getName() == null ? "" : m.getName(),
                    "text", m.getText(),
                    "toolUses", m.getBlocks(ToolUseBlock.class).stream()
                            .map(t -> Map.of("id", t.id(), "name", t.name(), "input", t.input()))
                            .toList(),
                    "toolResults", m.getBlocks(com.lynn.myagentscopejava.core.message.ToolResultBlock.class).stream()
                            .map(t -> Map.of(
                                    "id", t.id(), "output", t.output(),
                                    "isError", t.isError(), "pending", t.pending()))
                            .toList()
            );
        }

        private static String eventToJson(ReactEvent ev) {
            try {
                return switch (ev) {
                    case ReactEvent.IterationStart e -> MAPPER.writeValueAsString(Map.of(
                            "type", "iter", "iteration", e.iteration()));
                    case ReactEvent.Thinking e -> MAPPER.writeValueAsString(Map.of(
                            "type", "thinking", "delta", e.delta()));
                    case ReactEvent.Text e -> MAPPER.writeValueAsString(Map.of(
                            "type", "text", "delta", e.delta()));
                    case ReactEvent.ToolCall e -> MAPPER.writeValueAsString(Map.of(
                            "type", "tool_call",
                            "id", e.toolUse().id(),
                            "name", e.toolUse().name(),
                            "input", e.toolUse().input()));
                    case ReactEvent.ToolResult e -> MAPPER.writeValueAsString(Map.of(
                            "type", "tool_result",
                            "id", e.result().id(),
                            "name", e.result().name() == null ? "" : e.result().name(),
                            "output", e.result().output(),
                            "isError", e.result().isError(),
                            "pending", e.result().pending()));
                    case ReactEvent.Done e -> MAPPER.writeValueAsString(Map.of(
                            "type", "done",
                            "usage", e.usage() == null ? Map.of() : Map.of(
                                    "in", e.usage().getInputTokens(),
                                    "out", e.usage().getOutputTokens(),
                                    "cached", e.usage().getCachedInputTokens(),
                                    "hitRate", e.usage().getCacheHitRate())));
                };
            } catch (Exception e) {
                return "{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            }
        }
    }

    /** chat 请求 DTO。 */
    public record ChatRequest(String text, MsgRole role, String toolCallId) {
        public ChatRequest {
            if (role == null) role = MsgRole.USER;
        }
    }
}
