@echo off
title BharatVPN Launcher
color 0b
echo ========================================================
echo         BHARAT VPN - INDIA STATE AND CITY TUNNEL
echo ========================================================
echo.
echo [*] Starting BharatVPN Windows Native Engine...
start /B powershell.exe -ExecutionPolicy Bypass -NoProfile -File "%~dp0vpn_engine.ps1"

echo [*] Initializing Engine...
timeout /t 2 /nobreak >nul

echo [*] Launching Desktop App Interface...
start msedge --app=http://127.0.0.1:4589 || start chrome --app=http://127.0.0.1:4589 || start http://127.0.0.1:4589 || start "" "%~dp0index.html"

echo.
echo ========================================================
echo [OK] BharatVPN is Running!
echo [!] Keep this window open while using the VPN.
echo [!] Close this window when you want to stop.
echo ========================================================
echo.
pause
