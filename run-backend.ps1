# MediBook Backend Startup Script for PowerShell
$ErrorActionPreference = "Stop"

Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host " Starting MediBook Spring Boot Backend (Port 8081)" -ForegroundColor Cyan
Write-Host "===============================================================" -ForegroundColor Cyan

# 1. Use JDK 21 LTS if available (avoids Java 25 experimental compiler issues)
$jdk21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
if (Test-Path $jdk21) {
    $env:JAVA_HOME = $jdk21
    $env:PATH = "$jdk21\bin;$env:PATH"
    Write-Host "[OK] Using Java 21 LTS: $jdk21" -ForegroundColor Green
}

# 2. Set environment variables
$env:MEDIBOOK_PORT = "8081"
$env:MEDIBOOK_DB_URL = "jdbc:mysql://localhost:3306/medibook?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:MEDIBOOK_DB_USER = "root"
$env:MEDIBOOK_DB_PASSWORD = "hamsika 2006"
$env:MEDIBOOK_JWT_SECRET = "MediBookLocalDemoSecretKey2026SecureLongKey32Chars"
$env:MEDIBOOK_ADMIN_EMAIL = "admin@medibook.local"
$env:MEDIBOOK_ADMIN_PASSWORD = "Admin12345"
$env:MEDIBOOK_DEMO_DATA = "true"
$env:MEDIBOOK_DEMO_PASSWORD = "Demo12345"

Write-Host "[OK] Environment variables loaded." -ForegroundColor Green

# 3. Check and free port 8081 if another instance is already running
try {
    $existingPort = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
    if ($existingPort) {
        $pids = $existingPort.OwningProcess | Select-Object -Unique
        foreach ($p in $pids) {
            Write-Host "[INFO] Freeing port 8081 from previous process (PID: $p)..." -ForegroundColor Yellow
            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 1
        Write-Host "[OK] Port 8081 is ready." -ForegroundColor Green
    }
} catch {
    # Non-blocking if net connection query cannot run
}

Write-Host "Starting Maven Spring Boot..." -ForegroundColor Yellow

mvn spring-boot:run
