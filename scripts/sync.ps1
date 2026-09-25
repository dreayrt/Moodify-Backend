# ========================================================
# Moodify - One-Click Database Sync Pipeline
# Pulls remote music catalog -> Local MongoDB -> Local MySQL
# ========================================================

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  MOODIFY: STARTING AUTOMATED DATABASE SYNC PIPELINE" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$scriptPath = Join-Path $PSScriptRoot "sync_data.py"

python $scriptPath

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n[SUCCESS] Sync pipeline finished successfully!" -ForegroundColor Green
} else {
    Write-Host "`n[ERROR] Sync pipeline encountered an error. Exit code: $LASTEXITCODE" -ForegroundColor Red
}
