# 第 13 章 · 分布式部署

> 目标：理解项目从单机到分布式的整体设计、4 个抽象层（Session / DistributedLock / NotificationBus / ConversationDirectory）、按 cluster.mode 切换的机制，以及一键部署到生产的步骤。

## 13.1 何时需要分布式

需求 → 拆分：

| 现象 | 信号 |
|------|------|
| 单机 50 并发"思考中"打满（见第 12 章 §12.x 容量估算） | 加节点 |
| 节点重启或崩溃影响所有用户 | 多副本 + 持久化 |
| 单机磁盘 / 内存放不下海量会话 | 走 PostgreSQL |
| 团队多人开发同一系统需要灰度 | 流量染色（需 SCG 或类似网关） |

如果暂时没有这些信号，**单机模式完全够用**，不要为分布式提前付复杂度税。

## 13.2 整体架构

```
┌────────────────────────────────────────────────────────────────────┐
│  浏览器                                                              │
│    ↓ HTTP / SSE                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Nginx (sticky by ?user= consistent hash)                   │    │
│  └────────┬─────────┬─────────┬───────────────────────────────┘    │
│           ↓         ↓         ↓                                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                              │
│  │ agent-1  │ │ agent-2  │ │ agent-3  │  Spring Boot 应用 x N        │
│  │ (无状态)  │ │ (无状态) │ │ (无状态) │  cluster.mode=distributed     │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘                              │
│       │            │            │                                     │
│       │  ┌─────────┴─────────┐  │                                     │
│       └─→│  PostgreSQL       │←─┘   conversations + session_memory   │
│          │  - 真理之源       │       (持久化 + JSONB)                │
│          └───────────────────┘                                        │
│       │  ┌───────────────────┐  │                                     │
│       └─→│  Redis            │←─┘   分布式锁 (Redisson RLock)         │
│          │  - 协调层 (短命)   │       pub/sub (interrupt 信号)         │
│          └───────────────────┘       config 热更新                    │
└────────────────────────────────────────────────────────────────────┘
```

3 个外部依赖：

- **PostgreSQL**：持久化所有会话数据（重启 / 节点崩溃不丢）
- **Redis**：协调层（锁 + pub/sub），数据丢了不影响业务恢复
- **Nginx**：sticky 路由 + SSE 透传（详见 §13.7）

## 13.3 抽象层（4 个 SPI）

`core/cluster/` 包定义了 2 个核心接口，分别有 Local / Redis 两种实现：

| 接口 | 单机实现 | 分布式实现 | 启用条件 |
|------|---------|-----------|---------|
| `DistributedLock` | `LocalDistributedLock` (ConcurrentMap + ReentrantLock) | `RedisDistributedLock` (Redisson RLock + watchdog) | `cluster.mode=distributed` |
| `NotificationBus` | `LocalNotificationBus` (进程内同步回调) | `RedisNotificationBus` (Redisson RTopic) | `cluster.mode=distributed` |
| `Session` | `FileSystemSession` (本地 JSON 文件) | `PostgresSession` (JdbcTemplate + JSONB) | `cluster.mode=distributed` |
| `ConversationDirectory` | `FileSystemConversationDirectory` | `PostgresConversationDirectory` | `cluster.mode=distributed` |

**自动装配**靠 `@ConditionalOnProperty(prefix = "agentscope.cluster", name = "mode", havingValue = "...")` 二选一注册 bean。Spring Boot 启动时只装其中一套，保证：

- 单机模式：完全不引 PG / Redis 类，启动失败也不会报"找不到数据库"
- 分布式模式：自动装数据源 / Flyway / Redisson

## 13.4 跨节点协调机制

### 13.4.1 锁

所有"同 SessionKey 的并发请求"都通过 `DistributedLock.acquire(key)` 串行。Redis 实现底层是 Redisson `RLock`：

- key namespace：`agentscope:lock:{sessionKey}`
- watchdog：默认 30s 过期 + 每 10s 续约 → 节点崩溃后锁在 30s 内被 Redis 自动释放
- 同节点同线程支持重入

### 13.4.2 中断 (interrupt) 跨节点传播

场景：用户在 Tab A（路由到 Node 1，持有 SSE 连接 + 跑 ReAct loop）+ Tab B（路由到 Node 2，点了"中断"按钮）。

```
Tab B → POST /api/chat/.../interrupt → Node 2
    ↓
ChatService.interrupt(key) on Node 2:
    interruptLocal()  → 本地 activeStreams 没这个 key → 返回 false
    notificationBus.publish("interrupt", key + "|" + USER)
    ↓
Redis pub/sub 广播
    ↓
Node 1, 2, 3 全收到（包括 Node 2 自己，幂等无副作用）
    ↓
每个节点 onInterruptSignal:
    interruptLocal(key, USER)
        ↓
Node 1 的 activeStreams 找到 token → cancel
    ↓
Node 1 上 ReAct loop 抛 AgentInterruptedException → SSE 优雅 complete
```

详见 `ChatService.interrupt` / `onInterruptSignal` 源码。

### 13.4.3 HITL 批准跨节点（无需特殊机制）

HITL 批准走 sync POST，不依赖 SSE。任意节点都能：

1. 拿锁
2. 从 PG 加载 memory
3. `toolkit.invoke(target)` 执行工具
4. 写回 PG
5. 返回 `ChatTurn` JSON

旧 SSE 早已 complete，不需要"唤醒"。前端拿到响应后自己渲染新消息。

### 13.4.4 配置热更新（危险工具清单）

`ToolConfirmationHook` 在构造时订阅 `config:hitl-dangerous-tools` 频道。`PUT /api/hitl/dangerous-tools` 调 `setDangerousToolsAndBroadcast(...)`：

```
Node 2: PUT /api/hitl/dangerous-tools ["delete_file"]
    ↓
hook.setDangerousToolsAndBroadcast(["delete_file"]):
    setDangerousTools(["delete_file"])    ← 本地立即生效
    notificationBus.publish(CONFIG_CHANNEL, "[\"delete_file\"]")
    ↓
所有节点（含 Node 2 自己，幂等）收到广播
    ↓
hook.onRemoteUpdate("[\"delete_file\"]"):
    parse JSON → setDangerousTools(...)
```

→ 一处改全集群秒级生效，无需重启。

## 13.5 数据模型

### PostgreSQL Schema

`db/migration/V1__init_distributed_schema.sql` 由 Flyway 在启动时自动执行：

```sql
conversations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    conv_id     VARCHAR(64) NOT NULL,
    title       TEXT,
    provider    VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, conv_id)              -- 自然键
);
CREATE INDEX idx_conv_user_updated ON conversations (user_id, updated_at DESC);

session_memory (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_key VARCHAR(128) NOT NULL,    -- "userId__convId"
    slot        VARCHAR(32)  NOT NULL DEFAULT 'memory',
    payload     JSONB        NOT NULL,    -- Msg[] 序列化（多态 ContentBlock 走 @JsonTypeInfo）
    version     BIGINT       NOT NULL DEFAULT 0,    -- 乐观锁，预留
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (session_key, slot)
);
```

### 为什么 JSONB 不会成为瓶颈

- PG 自动 TOAST（行 > 2KB 切到外部存储），单行理论可达 1GB
- JSONB 是二进制 + 压缩格式（不是文本）
- 我们的 message 即使再长也远低于这个量级
- 通过 `?::jsonb` SQL cast 写入，无需 PGobject 类（runtime scope 也能跑）

## 13.6 配置与启动

### 单机模式（默认）

```bash
./mvnw spring-boot:run
```

完全不需要 PG / Redis。`application.properties` 默认排除了 DataSource / Flyway / Redis autoconfig。

### 分布式模式

```bash
SPRING_PROFILES_ACTIVE=distributed \
PG_URL=jdbc:postgresql://localhost:5432/agentscope \
PG_USER=agent \
PG_PASSWORD=secret \
REDIS_HOST=localhost \
REDIS_PORT=6379 \
DEEPSEEK_API_KEY=sk-xxx \
./mvnw spring-boot:run
```

`application-distributed.properties` 取消排除 + 配数据源。Flyway 第一次启动自动建表。

### 一键部署（推荐）

```bash
docker build -t my-agentscope-java:latest -f deploy/Dockerfile .
cd deploy
docker compose up -d
# → http://localhost
```

编排了 3 个 agent + PG + Redis + Nginx，详见 `deploy/README.md`。

## 13.7 Nginx Sticky Routing

完整配置见 `deploy/nginx.conf`。关键 3 行：

```nginx
upstream agent_nodes {
    hash $arg_user consistent;        # ① 同 user 永远同节点
}

location / {
    proxy_pass http://agent_nodes;
    proxy_buffering off;              # ② SSE 命脉，不能关
    proxy_read_timeout 1h;            # ③ LLM 思考可能很久
}
```

### 为什么是 consistent hash 不是 ip_hash

| | ip_hash | hash $arg_user consistent |
|---|---------|--------------------------|
| 同用户多次访问 | ✅ 同一节点 | ✅ 同一节点 |
| 节点上下线 | 全部 user 重哈希 | 仅少量受影响 |
| NAT 后多用户共享出口 IP | ❌ 全部到一个节点 | ✅ 按 user 分散 |

### 为什么要 `proxy_buffering off`

SSE 是按 chunk 推送的。Nginx 默认会聚一个 buffer 满了再下发，结果用户体验是"等几秒，唰一下出现一大段"，没了打字机效果。这一行是 SSE 的命门。

## 13.8 容量与运维

### 资源估算（参考第 12 章 §单机容量分析的扩展）

| 节点配置 | 单节点并发 | 3 节点理论上限 |
|---------|-----------|---------------|
| 2C / 4G | 50 | 150 |
| 4C / 8G | 100 | 300 |
| 8C / 16G | 200 | 600 |

数字是"同时在思考中"的会话数。注册的对话数（不活跃）几乎无上限（PG 单表千万行无压力）。

### 真正的瓶颈通常是

1. **LLM provider 速率限制**（Anthropic 默认 50 RPM，DeepSeek 充值后 1000+ RPM）
2. **PG 连接池**：每节点默认 10 个 HikariCP 连接，3 节点 = 30 个 PG 连接，确保 PG 的 `max_connections` 充足
3. **Redis 单线程**：本项目用法（pub/sub + 短锁）轻量，单 Redis 实例支撑数百节点没问题

### 节点崩溃 / 重启

- 持锁节点崩溃 → Redisson watchdog 停止续约 → 锁 30s 内被 Redis 自动释放 → 其它节点能接管
- SSE 连接断 → 浏览器 EventSource 自动重连（默认 3s 重试）→ 通过 sticky 命中其它节点 → 从 PG 加载历史 → 用户体验是短暂卡顿
- 整个集群重启 → memory 在 PG 完好无损，重启即恢复
- HITL 挂起态 → 也在 PG，重启不丢，下次访问自动恢复输入卡

## 13.9 监控建议

| 指标 | 来源 | 关注点 |
|------|------|--------|
| `Tomcat thread pool busy %` | Spring Actuator `tomcat.threads.busy` | > 80% 加节点 |
| `HikariCP active connections` | `hikaricp.connections.active` | 持续打满需调大 pool 或加 PG |
| `Redis ops/sec` | `redis_commands_processed_total` | < 1k/s 都是闲的 |
| `LLM API latency p95` | 自己埋点 | 看 provider 健康 |
| `interrupt 频率` | publish 计数 | 异常高可能用户在反复中断 |

可以挂一个 Prometheus + Grafana，Spring Boot Actuator 自带 `/actuator/prometheus` endpoint。

## 13.10 常见问题

### Q: PG 连接数撞墙

调 `application-distributed.properties`：

```properties
spring.datasource.hikari.maximum-pool-size=20    # 默认 10
```

或者 PG 这边调：

```sql
ALTER SYSTEM SET max_connections = 200;
```

### Q: Redis 连接超时

Redisson 默认 connect-timeout 10s。境内访问云 Redis 可能慢，调：

```properties
spring.data.redis.timeout=5000
```

### Q: 我能不能不用 Nginx，让 LB 直接转发？

可以，任何支持 consistent hash 的 LB 都行：HAProxy、Traefik、AWS ALB（要配 sticky cookie）、k8s Service + ingress（注意 ingress controller 默认不带 sticky，要加 annotation）。

### Q: 我能不能跑 1 节点的"分布式模式"

可以。`cluster.mode=distributed` 但只起一个节点，等同于"单节点 + PG 持久化"，比单机模式更可靠（重启不丢）。某些场景这是合理的中间态。

## 13.11 自检

- [ ] 我能列出 4 个 cluster SPI 接口及各自单机 / 分布式实现
- [ ] 我能解释跨节点 interrupt 走的是 Redis pub/sub 而不是 RPC
- [ ] 我知道为什么 HITL 批准不需要跨节点唤醒机制
- [ ] 我能描述 Nginx 配置的 3 个关键点
- [ ] 我能描述节点崩溃后 watchdog 释放锁的过程
