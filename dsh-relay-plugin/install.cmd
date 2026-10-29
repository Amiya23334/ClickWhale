@echo off
rem One-click install for dsh-relay-plugin.
rem Pure ASCII on purpose: cmd parses this file with the OEM code page,
rem and non-ASCII bytes eat the following characters.
setlocal
set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"

where node >nul 2>&1
if errorlevel 1 (
  echo [install] Node.js not found in PATH.
  echo [install] Install Node.js 18 or newer, then run this again.
  pause
  exit /b 1
)

node "%HERE%\install.mjs" %1
if errorlevel 1 (
  echo.
  echo [install] Install failed, see the messages above.
  pause
  exit /b 1
)

echo.
echo [install] Remember to restart DSH for the plugin to load.
pause
