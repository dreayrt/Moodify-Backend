@echo off
title Moodify Backend Runner
cd /d "%~dp0"
echo [Moodify] Setting JAVA_HOME to JDK 26...
set "JAVA_HOME=C:\Program Files\Java\jdk-26"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [Moodify] Checking Java version:
"%JAVA_HOME%\bin\java.exe" -version
echo.
echo [Moodify] Starting Spring Boot application on port 8088...
call mvnw.cmd spring-boot:run
pause
