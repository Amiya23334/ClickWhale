@echo off
REM Start dsh-relay: expose the DSH desktop UI to the local network.
REM The DSH desktop app must already be running.
setlocal
cd /d "%~dp0"

set "NODE=node"
if exist "%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\node\node.exe" set "NODE=%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\node\node.exe"

echo.
echo   DSH Relay - expose desktop DSH to your phone
echo   ===========================================
echo.

"%NODE%" dsh-relay.mjs %*

echo.
echo   Stopped. Press any key to close.
pause >nul
