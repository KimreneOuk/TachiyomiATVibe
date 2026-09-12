@echo off
REM ------------------------------------------------------------------
REM  TachiyomiAT translation tools launcher (double-click from Explorer)
REM
REM  Starts:
REM    1. Translation Studio  - local web app, opens your browser.
REM       Keep its console window open; Ctrl+C there stops the server.
REM    2. Chapter Downloader  - plain desktop GUI window (no console).
REM
REM  Both tools can also be run standalone; see tools/*/README.md.
REM ------------------------------------------------------------------
setlocal
cd /d "%~dp0"

REM  Warn (don't block) if a studio instance is already listening on 8765.
netstat -ano | findstr ":8765" | findstr /i "LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo  NOTE: port 8765 is already in use - a Translation Studio may already be
    echo        running. If pages don't load, close the old studio console first.
    echo.
)

start "Translation Studio" cmd /k python tools\translation_studio\studio.py
if errorlevel 1 goto :fail

start "" pythonw tools\chapter_downloader\chapter_downloader.py
if errorlevel 1 goto :fail

endlocal
exit /b 0

:fail
echo.
echo  Failed to start a tool - is Python on PATH? Try running "python --version".
echo  Studio alone:  python tools\translation_studio\studio.py
echo  Downloader:    python tools\chapter_downloader\chapter_downloader.py
pause
exit /b 1
