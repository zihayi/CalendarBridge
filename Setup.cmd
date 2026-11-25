@echo off
setlocal
cd /d "%~dp0"
if defined CALENDAR_PYTHON (
  "%CALENDAR_PYTHON%" setup_receiver.py %*
) else if exist "C:\Python314\python.exe" (
  "C:\Python314\python.exe" setup_receiver.py %*
) else (
  python setup_receiver.py %*
)
pause
