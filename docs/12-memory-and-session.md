# 第 12 章 · Memory 压缩 + 会话持久化

> 目标：理解 `Memory` 抽象、`CompactingMemory` 装饰器是怎么压缩历史的、`FileSystemSession` 落盘格式、`SessionLockManager` 怎么做并发隔离，以及 `ChatService` 怎么把这一切串起来。

## 12.1 `Memory` 接口

```java
public interface Memory {
    void addMessage(Msg msg);
    List<Msg> getMessages();
    void clear();
    default void saveTo(Session session, SessionKey key) {
        session.save(key, "memory", getMessages().toArray(new Msg[0]));
    }
    default void loadFrom(Session session, SessionKey key) {
        session.get(key, "memory", Msg[].class).ifPresent(arr -> {
            clear();
            for (Msg m : arr) addMessage(m);
        });
    }
}
```

**职责**：按时间顺序存消息 + 与 `Session` 互转。

**两种实现**：
- `InMemoryMemory` —— 进程内 `ArrayList`
- `CompactingMemory` —— 装饰器，超阈值自动压缩

## 12.2 为什么需要压缩

LLM 上下文窗口有限：

| 模型 | 上下文长度 |
|------|----------|
| DeepSeek V3 | 128K |
| Claude Sonnet 4.6 | 200K |
| Gemini 3 Flash | 1M |

看起来很大，但：

- 长对话很容易上 50K（特别是工具调用结果占大头）
- 上下文越长，每次请求成本越高（按 token 计费）
- 实际有用信息很少，模型注意力在长文本上会衰减

**压缩**：把"老消息"用 LLM 摘要成一两句话，保留"近消息"原文。

## 12.3 `CompactingMemory` 装饰器

```java
public class CompactingMemory implements Memory {
    private final Memory delegate;
    private final MemoryCompactor compactor;
    private int compactionCount = 0;

    @Override
    public void addMessage(Msg msg) {
        delegate.addMessage(msg);
        compactIfNeeded();
    }

    public void compactIfNeeded() {
        List<Msg> current = delegate.getMessages();
        if (!compactor.shouldCompact(current)) return;
        int before = current.size();
        List<Msg> compacted = compactor.compact(current);
        if (compacted == current) return;  // compactor 决定不压
        delegate.clear();
        compacted.forEach(delegate::addMessage);
        compactionCount++;
        log.info("memory 压缩生效：{} → {} 条消息", before, compacted.size());
    }
}
```

设计选择：**在 `addMessage` 后即时压缩**，不在 `getMessages` 时按需压缩。原因：

- 成本（一次 LLM 调用）显式落在某次 add，行为可预期
- `getMessages` 不引入隐藏副作用
- 多个并发读者不会触发重复压缩

## 12.4 `MemoryCompactor` 接口

```java
public interface MemoryCompactor {
    boolean shouldCompact(List<Msg> messages);
    List<Msg> compact(List<Msg> messages);
}
```

`compact` 返回**新列表**或**原列表**：

- 返回原列表（`==` 同一引用）= 没什么可压
- 返回新列表 = 已压缩，调用方要替换底层

注意是**引用相等**判断，不是 size 判断 —— 摘要 USER + ASSISTANT 占位对子可能让压缩后消息数与压缩前相等甚至更多，但 token 显著减少。

## 12.5 `SummarizingCompactor` —— 内置实现

```java
SummarizingCompactor.builder()
    .summarizer(chatModel)              // 用哪个 ChatModel 做摘要
    .maxTokens(8000)                    // 超过即压缩
    .maxMessages(0)                     // 兜底（0 = 禁用）
    .keepRecent(10)                     // 至少保留最近 10 条
    .estimator(TokenEstimator.approximate(2.8))  // 字符 → token 估算
    .summaryInstruction("请把下面的对话摘要为关键事实清单")
    .build();
```

`shouldCompact` 逻辑：

1. 用 `TokenEstimator` 算所有消息的总 token 估算值
2. 如果 `>= maxTokens` 触发
3. 或者 `maxMessages > 0` 且 `messages.size() >= maxMessages` 触发

`compact` 逻辑：

1. 留下最后 `keepRecent` 条原文不动
2. 前面的所有消息拼成 prompt，调 `summarizer.chat()` 做摘要
3. 返回 `[Msg.system(摘要), ...keepRecent 条]`

为什么用 USER + ASSISTANT 对子作为摘要载体？因为有些 provider 不允许多条连续 SYSTEM 消息，对话开头的 system 可能已经存在，再插一条会出错。详见 `SummarizingCompactor` 源码。

## 12.6 `TokenEstimator`

```java
public interface TokenEstimator {
    int estimate(String text);
    int estimate(Msg msg);
    int estimate(List<Msg> msgs);

    static TokenEstimator approximate(double charsPerToken) {
        return text -> (int) Math.ceil(text.length() / charsPerToken);
    }
}
```

字符近似估算。中英文混合 `2.8` 字符/token 比较合理（纯英文约 4，纯中文约 1.5）。

不用真实 tokenizer 是因为：

- BPE 分词器实现复杂、依赖大
- 估算误差 ±20%，对"是否触发压缩"够用
- 真要精确可以替换实现

## 12.7 配置压缩

```properties
agentscope.memory.compaction-enabled=true
agentscope.memory.compaction-max-tokens=8000
agentscope.memory.compaction-max-messages=0
agentscope.memory.compaction-keep-recent=10
agentscope.memory.compaction-chars-per-token=2.8
# agentscope.memory.compaction-instruction=自定义摘要指令
```

`AgentAutoConfiguration.chatService()`：

```java
SummarizingCompactor compactor = null;
if (mc.getCompactionMaxTokens() > 0 || mc.getCompactionMaxMessages() > 0) {
    compactor = SummarizingCompactor.builder()...build();
}

Supplier<Memory> memoryFactory = (mc.isCompactionEnabled() && compactor != null)
        ? () -> new CompactingMemory(new InMemoryMemory(), compactor)
        : InMemoryMemory::new;
```

注意 `compactor` 是否构造取决于"阈值参数有没有"，但 `compactionEnabled` 决定**自动**触发。手动接口 `compactNow` 只要 `compactor` 存在就能用 —— 用户可以禁用自动压缩，但保留"我点按钮就压缩"的能力。

## 12.8 `Session` 抽象

```java
public interface Session {
    void save(SessionKey key, String slot, Object value);
    <T> Optional<T> get(SessionKey key, String slot, Class<T> type);
    void delete(SessionKey key);
    boolean exists(SessionKey key);
}
```

按 `(key, slot)` 存对象。`slot` 是组件自己的命名空间 —— `Memory` 用 `"memory"` 槽位，未来其它组件可用其它名字（如 `"long_term_memory"`）。

## 12.9 `SessionKey`

```java
public record SessionKey(String value) {
    public SessionKey {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("SessionKey value 必填");
        if (!value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("SessionKey 只允许 [A-Za-z0-9_-]");
    }
}
```

**只允许文件系统安全的字符**，防止 `../etc/passwd` 这样的路径注入。所有用户输入到这一层**必须**先校验。

`ChatService` 用 `userId + "__" + convId` 拼出 SessionKey，所以 `userId` 和 `convId` 也必须只含安全字符 —— `ConversationDirectory.create` 会生成 `"c-" + UUID` 这种合规 id。

## 12.10 `FileSystemSession`

```java
public class FileSystemSession implements Session {
    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void save(SessionKey key, String slot, Object value) {
        Path dir = baseDir.resolve(key.value());
        Files.createDirectories(dir);
        Path file = dir.resolve(slot + ".json");
        mapper.writeValue(file.toFile(), value);
    }

    @Override
    public <T> Optional<T> get(SessionKey key, String slot, Class<T> type) {
        Path file = baseDir.resolve(key.value()).resolve(slot + ".json");
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), type));
    }
    ...
}
```

落盘格式：

```
.agent-state/
├── alice__conv-001/
│   └── memory.json    ← Msg[] 序列化
├── alice__conv-002/
│   └── memory.json
└── bob__conv-001/
    └── memory.json
```

每个 SessionKey 一个目录，每个 slot 一个 JSON 文件。简单直观，方便 `cat` 查看 / 备份。

`Msg` + `ContentBlock` 的多态序列化由 Jackson 的 `@JsonTypeInfo` 自动处理（详见第 4 章 §4.3.2）。

**线程安全**：FileSystemSession 不同 key 写不同文件天然安全；同一 key 并发写要靠上层锁（`SessionLockManager`）保证。

## 12.11 `SessionLockManager`

```java
public class SessionLockManager {
    private final ConcurrentMap<SessionKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(SessionKey key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
```

per-key `ReentrantLock`：

- 同一 key 的并发请求**串行**（防止丢消息 / 脏写）
- 不同 key 的请求**并行**（高吞吐）

`ChatService` 的所有公共方法都包在 `lock.lock() / lock.unlock()` 里。

**可重入**很重要：`approvePendingTool` 内部要调 `chatDetailedNoLock`，理论上重入安全；为避免重入计数膨胀，`chatDetailedNoLock` 跳过锁获取（调用方已持锁）。

## 12.12 `ChatService` 全景

把前面所有抽象组合起来：

```java
public ChatTurn chatDetailed(SessionKey key, Msg userInput) {
    ReentrantLock lock = lockManager.lockFor(key);
    lock.lock();
    try {
        // 1. 新建 agent，加载历史
        ReActAgent agent = newAgentForSession(key);
        int sizeBefore = agent.getMemory().getMessages().size();

        // 2. 注册到 active map（让 interrupt 能找到）
        activeCalls.put(key, agent);
        try {
            // 3. 跑 ReAct
            Msg reply = agent.call(userInput);

            // 4. 落盘
            agent.saveTo(session, key);

            // 5. 返回新增的消息（含中间步骤）
            List<Msg> all = agent.getMemory().getMessages();
            List<Msg> added = sizeBefore < all.size()
                    ? List.copyOf(all.subList(sizeBefore, all.size()))
                    : List.of();
            return new ChatTurn(reply, added);
        } finally {
            activeCalls.remove(key, agent);
        }
    } finally {
        lock.unlock();
    }
}

private ReActAgent newAgentForSession(SessionKey key) {
    Memory memory = memoryFactory.get();   // ← 工厂决定要不要包 CompactingMemory
    ReActAgent.Builder b = ReActAgent.builder()
            .name(agentName)
            .sysPrompt(sysPrompt)
            .model(modelRouter.resolve(key))   // ← 按会话路由 provider
            .toolkit(toolkit)
            .memory(memory)
            .generateOptions(generateOptions)
            .maxIters(maxIters);
    if (!hooks.isEmpty()) b.hooks(hooks);
    ReActAgent agent = b.build();
    agent.loadFrom(session, key);          // ← 加载历史
    // 孤儿 tool_calls 修复由 PendingToolRecoveryHook 在 agent.call() 入口的 PreCallEvent 自动处理
    return agent;
}
```

每次请求**新建 agent**：

- ReActAgent 不是单例 → 杜绝跨用户串记忆
- ChatModel / Toolkit / Hook / GenerateOptions 是无状态的 → 共享
- Memory 由 `memoryFactory` 工厂创建 → 每次新的实例（带不带压缩看配置）

## 12.13 `chatReactStream` 的差异

流式版本结构类似但**不复用** `ReActAgent.call()`：

```java
public Flux<ReactEvent> chatReactStream(SessionKey key, Msg userInput) {
    ReentrantLock lock = lockManager.lockFor(key);
    CancellationToken token = new CancellationToken();
    return Flux.<ReactEvent>create(sink -> {
        lock.lock();
        activeStreams.put(key, token);
        Memory memory = memoryFactory.get();
        try {
            if (session.exists(key)) memory.loadFrom(session, key);
            // fire PreCallEvent 让 PendingToolRecoveryHook 修复孤儿 tool_calls
            fireSafely(new PreCallEvent(null, memory,
                    userInput != null ? List.of(userInput) : List.of()));
            if (userInput != null) memory.addMessage(userInput);
            runReactLoop(key, sink, memory, token);   // ← 自己实现的流式 loop
            memory.saveTo(session, key);
            sink.complete();
        } catch (AgentInterruptedException ie) {
            try { memory.saveTo(session, key); } catch (Exception ignored) {}
            sink.complete();   // 中断按 complete 不按 error
        } catch (Exception e) {
            sink.error(e);
        }
    })
    .subscribeOn(Schedulers.boundedElastic())
    .doFinally(sig -> {
        activeStreams.remove(key, token);
        if (lock.isHeldByCurrentThread()) lock.unlock();
    });
}
```

关键差异：

- `subscribeOn(Schedulers.boundedElastic())` —— 把阻塞 IO 移到弹性线程池，不占 Reactor 主线程
- `doFinally` 释放锁 —— 保证流被取消 / 完成 / 出错时锁都被释放
- 中断走 `complete()` 而不是 `error()` —— 避免 Spring 把它当业务错误打 ERROR 日志

## 12.14 手动压缩接口

```java
public CompactionResult compactNow(SessionKey key) {
    if (compactor == null) throw new IllegalStateException("应用未配置 compactor");
    ReentrantLock lock = lockManager.lockFor(key);
    lock.lock();
    try {
        Memory memory = memoryFactory.get();
        if (session.exists(key)) memory.loadFrom(session, key);
        List<Msg> before = memory.getMessages();
        List<Msg> after = compactor.compact(before);
        if (after == before) return new CompactionResult(before.size(), before.size());
        memory.clear();
        after.forEach(memory::addMessage);
        memory.saveTo(session, key);
        return new CompactionResult(before.size(), after.size());
    } finally {
        lock.unlock();
    }
}
```

绕过 `shouldCompact` 阈值，直接调 `compact` —— 用户点"立即压缩"按钮触发。

前端：`POST /api/chat/{user}/{conv}/compact`。

## 12.15 自检

- [ ] 我能解释 `CompactingMemory` 是装饰器模式
- [ ] 我能描述 `SummarizingCompactor` 的"前面摘要 + 末尾原文"策略
- [ ] 我能解释为什么 `SessionKey` 限制字符集
- [ ] 我能列出 FileSystemSession 落盘的目录结构
- [ ] 我能描述 `ChatService.chat` 一次调用的 7 步
- [ ] 我能说出"为什么 ReActAgent 不能是单例"
