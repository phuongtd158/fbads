@echo off
chcp 65001 >nul
title FB Ads Auto
start "" http://localhost:3000
node server.js
pause
