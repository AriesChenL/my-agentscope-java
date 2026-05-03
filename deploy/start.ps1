# =====================================================================
# 一键启动 my-agentscope-java 分布式集群（Windows PowerShell）
# 用法：
#   cd deploy
#   .\start.ps1                          # 启动
#   .\start.ps1 -Rebuild                 # 强制重新 build 镜像
#   .\start.ps1 -Logs                    # 启动后跟随日志
# =====================================================================
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$Logs
)
$ErrorActionPreference = 'Stop'

Set-Location $PSScriptRoot
$RepoRoot = (Resolve-Path "..").Path
$Image = "my-agentscope-java:latest"

# 1. 构建镜像（首次或 -Rebuild）
$imgExists = $null -ne (docker image inspect $Image 2>$null)
if ($Rebuild -or -not $imgExists) {
    Write-Host "==> 构建镜像 $Image..." -ForegroundColor Cyan
    docker build -t $Image -f "$RepoRoot/deploy/Dockerfile" $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "docker build 失败" }
} else {
    Write-Host "==> 镜像 $Image 已存在，跳过 build。加 -Rebuild 强制重建" -ForegroundColor Yellow
}

# 2. 检查 LLM key
if (-not $env:DEEPSEEK_API_KEY -and -not $env:ANTHROPIC_API_KEY -and -not $env:GEMINI_API_KEY) {
    Write-Host "==> 警告：没设置任何 LLM API key，agent 跑不了实际 chat" -ForegroundColor Yellow
    Write-Host '    `$env:DEEPSEEK_API_KEY = "sk-xxx"  # 然后再启动'
}

# 3. 起容器
Write-Host "==> 启动容器..." -ForegroundColor Cyan
docker compose up -d
if ($LASTEXITCODE -ne 0) { throw "docker compose up 失败" }

# 4. 等待健康
Write-Host "==> 等待服务就绪..." -ForegroundColor Cyan
$ok = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost/api/providers" -UseBasicParsing -TimeoutSec 2 -NoProxy 2>$null
        if ($resp.StatusCode -eq 200) { $ok = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}

if ($ok) {
    Write-Host "==> ✅ 启动成功！访问 http://localhost" -ForegroundColor Green
    Write-Host "    agent-1 直连：http://localhost:8888"
    docker compose ps
    if ($Logs) {
        Write-Host "==> 跟随日志（Ctrl+C 退出）..." -ForegroundColor Cyan
        docker compose logs -f
    }
} else {
    Write-Host "==> ❌ 30s 内 Nginx 没响应，看日志诊断：" -ForegroundColor Red
    docker compose ps
    Write-Host "    docker compose logs nginx"
    Write-Host "    docker compose logs agent-1"
    exit 1
}
