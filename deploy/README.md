# 分布式部署

一键起 3 节点 + PG + Redis + Nginx。

## 最快路径（推荐）

```bash
# Linux / Mac / Git Bash
cd deploy
chmod +x start.sh stop.sh    # 首次记得加执行权限
export DEEPSEEK_API_KEY=sk-xxx       # 至少配一个 LLM key
./start.sh
# → http://localhost
./stop.sh                            # 停（保留数据）
```

```powershell
# Windows PowerShell
cd deploy
$env:DEEPSEEK_API_KEY = "sk-xxx"
.\start.ps1
# → http://localhost
.\stop.ps1                           # 停（保留数据）
```

启动脚本会：
1. 自动构建镜像（已存在则跳过，加 `--rebuild` / `-Rebuild` 强制重建）
2. `docker compose up -d` 起 6 个容器
3. 轮询 30 秒等 Nginx 健康
4. 输出 `docker compose ps` 状态表

## 命令速查

| 操作 | Linux / Mac | Windows |
|------|-------------|---------|
| 启动 | `./start.sh` | `.\start.ps1` |
| 启动 + 跟日志 | `./start.sh --logs` | `.\start.ps1 -Logs` |
| 改源码后重启 | `./start.sh --rebuild` | `.\start.ps1 -Rebuild` |
| 停（保留数据） | `./stop.sh` | `.\stop.ps1` |
| 停 + 清空数据 | `./stop.sh --purge` | `.\stop.ps1 -Purge` |
| 看某节点日志 | `docker compose logs -f agent-1` | 同左 |
| 看 nginx 路由 | `docker compose logs -f nginx` | 同左 |
| 进 PG 查数据 | `docker exec -it deploy-postgres-1 psql -U agent -d agentscope` | 同左 |

## 容器拓扑

| 服务 | 暴露端口 | 说明 |
|------|---------|------|
| **nginx** | **80** ← 浏览器入口 | sticky 路由 + SSE 透传 |
| **agent-1** | 8888 ← 直连 | 3 节点之一，方便调试 |
| agent-2 | 内网 8080 | 仅集群内访问 |
| agent-3 | 内网 8080 | 仅集群内访问 |
| postgres | 内网 5432 | 持久化（数据卷 `postgres_data`） |
| redis | 内网 6379 | 分布式锁 + pub/sub |

## 验证 sticky 路由是否工作

```bash
# 多用户多次请求，看 nginx 把每个 user hash 到哪个 upstream
for u in alice bob carol dave; do
    for i in 1 2 3; do
        curl -s --noproxy '*' "http://localhost/api/users/$u/conversations" -o /dev/null
    done
done
docker compose logs nginx | grep sticky_key | tail -20
```

期望：同一 user 的 3 次请求命中同一 upstream IP；不同 user 大概率分散到不同节点。

## 验证跨节点协调

```bash
# agent-1 改危险工具清单
curl -s --noproxy '*' -X PUT http://localhost:8888/api/hitl/dangerous-tools \
     -H 'Content-Type: application/json' -d '["delete_file","transfer_money"]'

# agent-3 立即看到（Redis pub/sub 广播）
docker exec deploy-agent-3-1 wget -qO- http://localhost:8080/api/hitl/dangerous-tools
# 应该返回 ["transfer_money","delete_file"]
```

## 文件清单

| 文件 | 作用 |
|------|------|
| `Dockerfile` | 容器镜像构建脚本 |
| `docker-compose.yml` | 编排 PG + Redis + 3 agent + Nginx |
| `nginx.conf` | sticky 路由（path/query 双取 user）+ SSE buffering off + 1h 长连接 |
| `start.sh` / `start.ps1` | 一键启动 |
| `stop.sh` / `stop.ps1` | 一键停止 |
| `README.md` | 本文 |

详细架构 / 调优 / 排错见 [docs/13-distributed-deployment.md](../docs/13-distributed-deployment.md)。

## 常见问题

### `docker build` 拉镜像失败

国内镜像源偶尔 401，可手动 `docker pull maven:3.9-eclipse-temurin-21` 拉好基础镜像再 `start.sh`。或换国内可用的 base image 改 Dockerfile。

### 8888 / 80 端口被占

改 `docker-compose.yml` 里 `ports:` 字段。

### LLM API 报 401 / 403

检查 `DEEPSEEK_API_KEY` 等环境变量是不是 export 了。`docker compose exec agent-1 env | grep API_KEY` 看容器内是否能拿到。

### Redis 报 `ERR AUTH <password> called without...`

老 bug 已修。确保 `application-distributed.properties` 里**没有** `spring.data.redis.password=...` 这行（默认空字符串会让 Redisson 发 AUTH 命令）。需要密码时直接 `export SPRING_DATA_REDIS_PASSWORD=xxx`。
