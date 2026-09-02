@echo off
title Internet Restore Tool
color 0a
echo ========================================================
echo       BHARAT VPN - INSTANT INTERNET RESTORE TOOL
echo ========================================================
echo.
echo [*] Turning OFF Proxy and restoring direct internet...

reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 0 /f >nul 2>&1
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer /t REG_SZ /d "" /f >nul 2>&1

echo.
echo [✓] SUCCESS! Windows Proxy is now completely DISABLED.
echo [✓] Your normal high-speed direct internet is restored!
echo.
echo You can now open any website in Chrome / Edge.
echo ========================================================
pause
