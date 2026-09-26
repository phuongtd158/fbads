@echo off
chcp 65001 >nul
title FB Ads Auto
cd /d "%~dp0"
rem Lan dau (hoac khi thieu thu vien): cai thu vien cua server
if not exist node_modules\express (
  echo Dang cai thu vien cua server ^(npm install^), can ket noi mang...
  call npm install --omit=dev
  if errorlevel 1 (
    echo Cai thu vien that bai. Kiem tra mang va da cai Node.js chua, roi chay lai.
    pause
    exit /b 1
  )
)
start "" http://localhost:3000
node server.js
pause
