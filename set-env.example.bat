@echo off
REM Copy this file to set-env.bat (it is git-ignored), edit the values, then run: call set-env.bat
REM Never commit real passwords.

set MEDIBOOK_DB_USER=medibook_user
set MEDIBOOK_DB_PASSWORD=CHANGE_ME_STRONG_PASSWORD

REM At least 32 random characters. Generate one in PowerShell:
REM   -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
set MEDIBOOK_JWT_SECRET=PASTE_A_LONG_RANDOM_STRING_HERE_AT_LEAST_32_CHARS

REM Optional: change the backend port (default 9091; if you change it, also edit the proxy target in frontend\vite.config.js)
REM set MEDIBOOK_PORT=9091

REM Admin account (created at first start if it does not exist). Needs 8+ chars with a letter and a number.
set MEDIBOOK_ADMIN_EMAIL=admin@medibook.local
set MEDIBOOK_ADMIN_PASSWORD=CHANGE_ME_Admin123

REM Demo mode: sample doctors + one sample patient. Set to false for a clean database.
set MEDIBOOK_DEMO_DATA=true
set MEDIBOOK_DEMO_PASSWORD=ChangeMe_Demo123
