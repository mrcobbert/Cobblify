@echo off
REM Cobblify SELF-HOSTED stats backend setup (Windows).
REM Only needed with a blank public-Release jar or a from-source build - official
REM (privately distributed) builds ship with the backend built in and skip this
REM entirely. Downloads and runs the PowerShell helper that deploys your own
REM free Cloudflare Worker.
title Cobblify stats backend setup
echo Starting Cobblify stats backend setup...
echo.

set "PS1=%TEMP%\bedwarsqol-setup.ps1"

powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/mrcobbert/Cobblify/main/installers/setup-windows.ps1' -OutFile '%PS1%' } catch { Write-Host 'Could not download the setup helper. Check your internet connection.' -ForegroundColor Red; exit 1 }"
if errorlevel 1 goto end

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"

:end
echo.
pause
