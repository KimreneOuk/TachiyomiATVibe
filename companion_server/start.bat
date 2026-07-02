@echo off
setlocal EnableDelayedExpansion
title Manga Translation Server

:: ═══════════════════════════════════════════════════════════════
::  Manga Translation Server - Launcher
:: ═══════════════════════════════════════════════════════════════

:: Always run from this script's directory
cd /d "%~dp0"

:: Config
set "DEFAULT_PORT=8765"
set "PORT=%DEFAULT_PORT%"
set "HOST=0.0.0.0"
if not "%~1"=="" set "PORT=%~1"

:: Temp file for capturing command output
set "TMP_OUT=%TEMP%\manga_server_tmp.txt"

:: ════════ Step 1: Check Python ════════
echo.
echo  [1/5] Checking Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo.
    echo  [FAILED] Python is not installed or not in PATH.
    echo.
    echo          Download: https://www.python.org/downloads/
    echo          Make sure to check "Add Python to PATH" during install.
    echo.
    pause
    exit /b 1
)
python --version > "%TMP_OUT%" 2>&1
set /p PY_VER=<"%TMP_OUT%"
echo         Found: %PY_VER%

:: ════════ Step 2: Verify files ════════
echo.
echo  [2/5] Locating server files...
if not exist "server.py" (
    echo.
    echo  [FAILED] server.py not found in:
    echo          %CD%
    echo.
    echo          Make sure start.bat is inside the companion_server folder.
    echo.
    pause
    exit /b 1
)
echo         OK - server.py found

:: ════════ Step 3: Dependencies ════════
echo.
echo  [3/5] Checking dependencies...
python -c "import fastapi, uvicorn, PIL, numpy, pydantic, yaml" >nul 2>&1
if errorlevel 1 (
    echo         Missing packages - installing...
    echo.
    call pip install -r requirements-cpu.txt
    if errorlevel 1 (
        echo.
        echo  [FAILED] Could not install dependencies.
        echo          Try manually:  pip install -r requirements-cpu.txt
        echo.
        pause
        exit /b 1
    )
    echo.
    echo         Dependencies installed.
) else (
    echo         All required packages present.
)

:: Warn if onnxruntime missing
python -c "import onnxruntime" >nul 2>&1
if errorlevel 1 (
    echo         [NOTE] onnxruntime not found - placeholder models will be used.
    echo                For real inference run:  pip install onnxruntime
)

:: ════════ Step 4: Port check ════════
:check_port
echo.
echo  [4/5] Checking port %PORT%...

netstat -ano > "%TMP_OUT%" 2>&1
findstr ":%PORT% " "%TMP_OUT%" >nul 2>&1
if errorlevel 1 goto :port_free

:: Also confirm it is LISTENING
findstr ":%PORT% " "%TMP_OUT%" | findstr "LISTENING" >nul 2>&1
if errorlevel 1 goto :port_free

:: Port is in use
echo.
echo  +---------------------------------------------------+
echo  ^|  PORT %PORT% IS ALREADY IN USE                    ^|
echo  ^|                                                   ^|
echo  ^|  The server may already be running.               ^|
echo  +---------------------------------------------------+
echo.
echo    [1]  Open existing server in browser
echo    [2]  Use a different port
echo    [3]  Kill process on port %PORT% and restart
echo    [4]  Exit
echo.
set /p "choice=  Choose [1-4]: "

if "!choice!"=="1" goto :open_existing
if "!choice!"=="2" goto :pick_port
if "!choice!"=="3" goto :kill_port
if "!choice!"=="4" exit /b 0
echo  Invalid choice.
goto :check_port

:open_existing
start "" "http://localhost:!PORT!/"
echo.
echo  Opened http://localhost:!PORT!/ in your browser.
pause
exit /b 0

:pick_port
echo.
set /p "PORT=  Enter new port (e.g. 8766): "
if "!PORT!"=="" set "PORT=%DEFAULT_PORT%"
goto :check_port

:kill_port
echo.
set "killed=0"
netstat -ano > "%TMP_OUT%" 2>&1
for /f "tokens=5" %%a in ('findstr ":%PORT% " "%TMP_OUT%" ^| findstr "LISTENING"') do (
    echo  Terminating PID %%a ...
    taskkill /PID %%a /F >nul 2>&1
    set "killed=1"
)
if "!killed!"=="0" (
    echo  Could not find the process. It may have stopped already.
)
echo  Waiting for port to free up...
timeout /t 2 >nul
goto :check_port

:port_free
echo         Port %PORT% is available.

:: ════════ Step 5: Start ════════
echo.
echo  [5/5] Starting server...
echo.

:: Detect LAN IP for phone connection
set "LAN_IP="
ipconfig > "%TMP_OUT%" 2>&1
for /f "tokens=2 delims=:" %%a in ('findstr /C:"IPv4" "%TMP_OUT%"') do (
    set "raw_ip=%%a"
    set "raw_ip=!raw_ip: =!"
    if "!LAN_IP!"=="" set "LAN_IP=!raw_ip!"
)

:: Banner
echo  ========================================================
echo            Manga Translation Server
echo  ========================================================
echo.
if "!LAN_IP!"=="" (
    echo    Web UI:  http://localhost:!PORT!/
) else (
    echo    Web UI:  http://localhost:!PORT!/
    echo    Phone:   http://!LAN_IP!:!PORT!/
)
echo    API docs: http://localhost:!PORT!/docs
echo.
echo    Press Ctrl+C to stop.
echo  ========================================================
echo.

:: Set env vars for server.py
set "MANGA_SERVER_PORT=!PORT!"
set "MANGA_SERVER_HOST=%HOST%"

:: Open browser after 3 seconds
start "" cmd /c "timeout /t 3 >nul & start http://localhost:!PORT!/"

:: Launch
python server.py

:: Server exited
echo.
echo  ========================================================
echo  Server has stopped.
echo  ========================================================
echo.
echo  If this was unexpected:
echo    - Check error messages above
echo    - Make sure port !PORT! is not in use
echo    - Re-run start.bat to try again
echo.
if exist "%TMP_OUT%" del "%TMP_OUT%" >nul 2>&1
pause
