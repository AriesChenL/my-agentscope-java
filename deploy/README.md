# 分布式部署

一键起 3 节点 + PG + Redis + Nginx：

## 1. 准备 LLM API key（至少一个）

```bash
export DEEPSEEK_API_KEY=sk-xxx
# 或
export ANTHROPIC_API_KEY=...
export GEMINI_API_KEY=...
```

## 2. 构建镜像

在仓库**根目录**：

```bash
docker build -t my-agentscope-java:latest -f deploy/Dockerfile .
```

第一次约 3-5 分钟（拉 Maven 依赖 + 构建 fat jar）。

## 3. 启动

```bash
cd deploy
docker compose up -d
```

容器：

| 服务 | 端口 | 说明 |
|------|------|------|
| nginx | **80** | 浏览器入口 |
| agent-1/2/3 | 8080（容器内） | 3 个无状态节点 |
| postgres | 5432（容器内） | 持久化 |
| redis | 6379（容器内） | 协调层 |

## 4. 访问

打开 `http://localhost`，用法跟单机一致。

验证 sticky 路由：F12 看 response header 里的节点 id（生产建议自己加 `X-Node-Id` header）；同一 user 多次刷新应该命中同一 agent 容器。

## 5. 查看日志

```bash
docker compose logs -f agent-1
docker compose logs -f nginx
```

## 6. 停 + 清理

```bash
docker compose down            # 停容器，保留数据卷
docker compose down -v         # 停 + 删除 postgres_data 卷（清空所有会话）
```

## 文件说明

| 文件 | 作用 |
|------|------|
| `Dockerfile` | 多阶段构建：mvnw package → JRE 运行镜像 |
| `docker-compose.yml` | 编排 PG + Redis + 3 agent + Nginx |
| `nginx.conf` | sticky 路由 + SSE buffering=off + 1h 长连接 |

详细架构 / 调优 / 排错见 [docs/13-distributed-deployment.md](../docs/13-distributed-deployment.md)。
