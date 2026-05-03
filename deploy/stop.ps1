# 停止集群（Windows PowerShell）
#   .\stop.ps1                只停容器，保留 PG 数据卷
#   .\stop.ps1 -Purge         连数据卷一起删（清空所有会话历史，慎用）
[CmdletBinding()]
param([switch]$Purge)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Purge) {
    Write-Host "==> 停止 + 删除数据卷..." -ForegroundColor Yellow
    docker compose down -v
} else {
    Write-Host "==> 停止容器（数据卷保留）..." -ForegroundColor Cyan
    docker compose down
}
Write-Host "==> 已停。重新启动用 .\start.ps1" -ForegroundColor Green
