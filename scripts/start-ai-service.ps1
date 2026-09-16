# 启动 Python 执行面（FastAPI）
#
# 默认 MockLLM：不需要任何 API Key、不联网、不下载模型权重。
# 需要真实模型时改 .env 里的 TRUSTAGENT_LLM_PROVIDER=openai_compatible 并填好 BASE_URL / API_KEY。

#Requires -Version 5.1
[CmdletBinding()]
param(
    [int]$Port = 8000,
    [string]$BindHost = '127.0.0.1'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$Root = Get-TrustAgentRoot
if (-not (Import-TrustAgentEnv -Root $Root)) { exit 1 }

$serviceDir = Join-Path $Root 'ai-service'
$venvDir = Join-Path $serviceDir '.venv'
$python = Get-VenvPython -VenvDir $venvDir

if (-not $python) {
    Write-Host "[ai] 未发现虚拟环境，正在创建：$venvDir" -ForegroundColor Yellow
    $base = Get-Command python -ErrorAction SilentlyContinue
    if (-not $base) { throw '找不到 python，请先安装 Python 3.11 或 3.12 并加入 PATH' }

    & $base.Source -m venv $venvDir
    $python = Get-VenvPython -VenvDir $venvDir
    if (-not $python) { throw "虚拟环境创建失败：$venvDir" }

    Write-Host '[ai] 安装依赖（requirements.txt）...' -ForegroundColor Yellow
    & $python -m pip install --upgrade pip
    & $python -m pip install -r (Join-Path $serviceDir 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw '依赖安装失败' }
}

# 命令行参数优先于 .env
Set-Item -Path 'Env:TRUSTAGENT_PORT' -Value "$Port"
Set-Item -Path 'Env:TRUSTAGENT_HOST' -Value $BindHost

Write-Host "[ai] 解释器 : $python" -ForegroundColor DarkGray
Write-Host "[ai] Provider: $($env:TRUSTAGENT_LLM_PROVIDER) / $($env:TRUSTAGENT_LLM_MODEL)" -ForegroundColor DarkGray
Write-Host "[ai] 监听   : http://${BindHost}:$Port   （Ctrl+C 停止）" -ForegroundColor Green
Write-Host "[ai] 提示   : 未设置 TRUSTAGENT_INTERNAL_TOKEN 时，所有内部接口一律 401（fail closed）" -ForegroundColor DarkGray

Push-Location $serviceDir
try {
    & $python -m uvicorn app.main:app --host $BindHost --port $Port
} finally {
    Pop-Location
}
