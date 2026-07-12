@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem One-file Manga Render Lab server helper.
rem Usage:
rem   manga_render_lab.bat          start server if not already running
rem   manga_render_lab.bat start    same as default
rem   manga_render_lab.bat status   show current server/port status
rem   manga_render_lab.bat stop     stop only if port is Manga Render Lab

set "HOST=127.0.0.1"
set "PORT=8765"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_ROOT=%%~fI"
set "LAB_DIR=%REPO_ROOT%\overlay_lab"
set "URL=http://%HOST%:%PORT%"
set "MODELS_URL=%URL%/api/models"
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"

if /I "%ACTION%"=="start" goto :START
if /I "%ACTION%"=="status" goto :STATUS
if /I "%ACTION%"=="stop" goto :STOP
if /I "%ACTION%"=="help" goto :HELP
if /I "%ACTION%"=="/help" goto :HELP
if /I "%ACTION%"=="-h" goto :HELP

echo [ERROR] Unknown action: %ACTION%
goto :HELP

:HELP
echo.
echo Manga Render Lab helper
echo.
echo Usage:
echo   bat\manga_render_lab.bat          start server
echo   bat\manga_render_lab.bat start    start server
echo   bat\manga_render_lab.bat status   check status
echo   bat\manga_render_lab.bat stop     stop lab server safely
echo.
echo URL: %URL%
exit /b 1

:STATUS
echo.
echo === Manga Render Lab Status ===
echo URL: %URL%
echo.
call :GET_PORT_PID
if not defined PORT_PID (
    echo [DOWN] Nothing is listening on %HOST%:%PORT%.
    exit /b 1
)
echo [PORT] LISTENING on %HOST%:%PORT% by PID !PORT_PID!
echo.
echo Process:
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter 'ProcessId=!PORT_PID!' | Select-Object ProcessId,CommandLine | Format-List"
call :IS_LAB
if errorlevel 1 (
    echo [WARN] Port is occupied, but /api/models did not respond as Manga Render Lab.
    exit /b 2
)
echo [OK] Manga Render Lab API is healthy.
exit /b 0

:STOP
echo.
echo === Stop Manga Render Lab ===
echo.
call :GET_PORT_PID
if not defined PORT_PID (
    echo [OK] No process is listening on %HOST%:%PORT%.
    exit /b 0
)
call :IS_LAB
if errorlevel 1 (
    echo [ERROR] Port %PORT% is owned by PID !PORT_PID!, but it does not look like Manga Render Lab.
    echo Refusing to kill unknown process.
    echo.
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter 'ProcessId=!PORT_PID!' | Select-Object ProcessId,CommandLine | Format-List"
    exit /b 2
)
echo [STOP] Stopping Manga Render Lab PID !PORT_PID! ...
taskkill /PID !PORT_PID! /F >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Failed to stop PID !PORT_PID!.
    exit /b 3
)
timeout /t 2 /nobreak >nul
echo [OK] Stopped.
exit /b 0

:START
echo.
echo === Manga Render Lab ===
echo Repo: %REPO_ROOT%
echo Lab : %LAB_DIR%
echo URL : %URL%
echo.

if not exist "%LAB_DIR%\backend\main.py" (
    echo [ERROR] overlay_lab backend not found: "%LAB_DIR%\backend\main.py"
    echo This launcher must stay inside the repo's bat\ folder.
    exit /b 1
)

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python not found on PATH.
    echo Start from a shell where the project's Python environment is available.
    exit /b 1
)

call :IS_LAB
if not errorlevel 1 (
    echo [OK] Manga Render Lab already running at %URL%
    exit /b 0
)

call :GET_PORT_PID
if defined PORT_PID (
    echo [ERROR] Port %PORT% is already in use by PID !PORT_PID!, but it is not Manga Render Lab.
    echo.
    echo Process details:
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter 'ProcessId=!PORT_PID!' | Select-Object ProcessId,CommandLine | Format-List"
    echo.
    echo Stop that process or run this lab on a different port before starting.
    exit /b 2
)

echo [START] Launching Manga Render Lab server...
echo A new console window will stay open for logs.
echo.
start "Manga Render Lab Server" cmd /k "cd /d "%LAB_DIR%" && python -m uvicorn backend.main:app --host %HOST% --port %PORT% --app-dir ."

timeout /t 5 /nobreak >nul
call :IS_LAB
if errorlevel 1 (
    echo [WARN] Server was launched, but health check did not pass yet.
    echo Check the "Manga Render Lab Server" console for errors.
    echo URL: %URL%
    exit /b 3
)

echo [OK] Manga Render Lab is running at %URL%
exit /b 0

:GET_PORT_PID
set "PORT_PID="
for /f "usebackq tokens=*" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "try { (Get-NetTCPConnection -LocalAddress '%HOST%' -LocalPort %PORT% -State Listen -ErrorAction Stop).OwningProcess } catch { '' }"`) do set "PORT_PID=%%P"
exit /b 0

:IS_LAB
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='SilentlyContinue';" ^
  "$r=Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 '%MODELS_URL%';" ^
  "if($r.StatusCode -eq 200 -and $r.Content -match 'seg_models'){ exit 0 } else { exit 1 }"
exit /b %ERRORLEVEL%
