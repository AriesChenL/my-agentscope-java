#!/usr/bin/env bash
# 停止集群。
#   ./stop.sh           只停容器，保留 PG 数据卷
#   ./stop.sh --purge   连数据卷一起删（清空所有会话历史，慎用）
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--purge" ]; then
    echo "==> 停止 + 删除数据卷..."
    docker compose down -v
else
    echo "==> 停止容器（数据卷保留）..."
    docker compose down
fi
echo "==> 已停。重新启动用 ./start.sh"
