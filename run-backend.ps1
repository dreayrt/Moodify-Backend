# Moodify Backend Runner PowerShell Script
$env:JAVA_HOME = "C:\Program Files\Java\jdk-26"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Write-Host "[Moodify] Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
& "$env:JAVA_HOME\bin\java.exe" -version
Write-Host "[Moodify] Starting Spring Boot application..." -ForegroundColor Green
./mvnw.cmd spring-boot:run
