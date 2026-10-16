@echo off
REM Loads set-env.bat (if present) and starts the backend.
if exist set-env.bat (
  call set-env.bat
) else (
  echo [!] set-env.bat not found. Copy set-env.example.bat to set-env.bat and edit it first.
  exit /b 1
)

if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
    set "PATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin;%PATH%"
)

echo Checking port 8081...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8081 ^| findstr LISTENING') do (
    echo Freeing port 8081 from process %%a...
    taskkill /f /pid %%a >nul 2>&1
)

mvn spring-boot:run
