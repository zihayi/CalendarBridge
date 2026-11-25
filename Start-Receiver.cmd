@echo off
setlocal
cd /d "%~dp0"
if defined CALENDAR_PYTHON (
  "%CALENDAR_PYTHON%" server.py
) else if exist "C:\Python314\python.exe" (
  "C:\Python314\python.exe" server.py
) else (
  python server.py
)
pause
