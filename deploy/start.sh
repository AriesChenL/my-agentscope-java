#!/usr/bin/env bash
# =====================================================================
# 一键启动 my-agentscope-java 分布式集群
# 用法：
#   cd deploy && ./start.sh                # 启动 (3 agent + PG + Redis + Nginx)
#   ./start.sh --rebuild                   # 强制重新 build 镜像（改了源码后）
#   ./start.sh --logs                      # 启动后跟随日志
# 依赖：docker + docker compose 已装
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")"
REPO_ROOT="$(cd .. && pwd)"
IMAGE="my-agentscope-java:latest"

REBUILD=0
FOLLOW_LOGS=0
for arg in "$@"; do
    case "$arg" in
        --rebuild) REBUILD=1 ;;
        --logs)    FOLLOW_LOGS=1 ;;
        *) echo "未知参数：$arg" >&2; exit 1 ;;
    esac
done

# 1. 构建镜像（首次或 --rebuild）
if [ "$REBUILD" = "1" ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "==> 构建镜像 $IMAGE..."
    docker build -t "$IMAGE" -f "$REPO_ROOT/deploy/Dockerfile" "$REPO_ROOT"
else
    echo "==> 镜像 $IMAGE 已存在，跳过 build。加 --rebuild 强制重建"
fi

# 2. 提示 LLM key
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -z "${GEMINI_API_KEY:-}" ]; then
    echo "==> 警告：没设置任何 LLM API key 环境变量，agent 跑不了实际 chat"
    echo "    在启动前 export DEEPSEEK_API_KEY=sk-xxx 之类"
fi

# 3. 起容器
echo "==> 启动容器..."
docker compose up -d

# 4. 等待健康
echo "==> 等待服务就绪..."
for i in $(seq 1 30); do
    if curl -sf --max-time 2 http://localhost/api/providers > /dev/null 2>&1; then
        echo "==> ✅ 启动成功！访问 http://localhost"
        echo "    agent-1 直连：http://localhost:8888"
        docker compose ps
        if [ "$FOLLOW_LOGS" = "1" ]; then
            echo "==> 跟随日志（Ctrl+C 退出）..."
            docker compose logs -f
        fi
        exit 0
    fi
    sleep 2
done

echo "==> ❌ 30s 内 Nginx 没响应，看日志诊断："
docker compose ps
echo "    docker compose logs nginx"
echo "    docker compose logs agent-1"
exit 1
