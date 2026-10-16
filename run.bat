@echo off
title Moodify Backend (Spring Boot - Port 8080)
echo ====================================================
echo Dang khoi dong Moodify Backend (Spring Boot)...
echo ====================================================
cd /d "%~dp0"
call mvnw.cmd spring-boot:run
pause
