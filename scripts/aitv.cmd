@echo off
setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%USERPROFILE%\.config\opencode\tools\buildkit\buildkit.ps1" --project "%~dp0.." %*
if errorlevel 1 pause
endlocal
