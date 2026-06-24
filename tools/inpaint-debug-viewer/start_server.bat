@echo off
echo Starting Inpaint Debug Viewer Server on port 8080...
python -m uvicorn server:app --reload --port 8080
pause
