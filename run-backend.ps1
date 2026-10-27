# Moodify Backend Runner PowerShell Script
Set-Location -LiteralPath $PSScriptRoot
$javaHomeCandidates = @($env:JAVA_HOME, 'C:\jdk-24.0.1', (Join-Path $env:ProgramFiles 'Java\jdk-24'))
$javaHome = $javaHomeCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $javaHome) {
    throw 'JDK not found. Set JAVA_HOME or install JDK 24 at C:\jdk-24.0.1.'
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Write-Host "[Moodify] Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
& "$env:JAVA_HOME\bin\java.exe" -version
Write-Host "[Moodify] Starting Spring Boot application..." -ForegroundColor Green
./mvnw.cmd spring-boot:run
