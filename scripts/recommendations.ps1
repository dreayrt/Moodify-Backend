param(
    [ValidateSet('setup','init','run','train','watch','smoke','audit','review-storage')]
    [string]$Mode = 'run',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$workerDirectory = Join-Path $backendRoot 'recommendation-worker'
$workerPython = Join-Path $workerDirectory '.venv\Scripts\python.exe'

if ($Mode -eq 'setup') {
    if (-not (Test-Path -LiteralPath $workerPython)) {
        & $Python -m venv (Join-Path $workerDirectory '.venv')
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the recommendation worker environment.' }
    }
    & $workerPython -m pip install -r (Join-Path $workerDirectory 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install recommendation worker dependencies.' }
    exit 0
}
if (-not (Test-Path -LiteralPath $workerPython)) { throw 'Run .\scripts\recommendations.ps1 -Mode setup first.' }
if ($Mode -eq 'smoke') {
    & $workerPython (Join-Path $workerDirectory 'integration_smoke.py')
} elseif ($Mode -eq 'review-storage') {
    & $workerPython (Join-Path $workerDirectory 'compact_storage.py') --env (Join-Path $backendRoot '.env')
} else {
    $workerArguments = @((Join-Path $workerDirectory 'worker.py'), '--env', (Join-Path $backendRoot '.env'))
    if ($Mode -eq 'init') { $workerArguments += @('--init-storage','--train') }
    if ($Mode -eq 'train') { $workerArguments += '--train' }
    if ($Mode -eq 'watch') { $workerArguments += @('--watch','--train','--interval','900','--train-interval-hours','24') }
    if ($Mode -eq 'audit') { $workerArguments += '--audit' }
    & $workerPython @workerArguments
}
if ($LASTEXITCODE -ne 0) { throw "Recommendation worker failed in mode $Mode." }
