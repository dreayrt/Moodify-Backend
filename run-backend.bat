@echo off
title Moodify Backend Runner
cd /d "%~dp0"
echo [Moodify] Selecting an installed JDK...
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\jdk-24.0.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [Moodify] JDK not found. Set JAVA_HOME or install JDK 24 at C:\jdk-24.0.1.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [Moodify] Checking Java version:
"%JAVA_HOME%\bin\java.exe" -version
echo.
echo [Moodify] Starting Spring Boot application on port 8080...
call mvnw.cmd spring-boot:run
pause
