@echo off
REM schedule_daily_task.bat — Script to register Daily Vault Automation in Windows Task Scheduler

echo =================================================================
echo Registering Daily Vault Automation Task in Windows Task Scheduler
echo =================================================================

set TASK_NAME=MockTestApp_DailyVaultAutomation
set SCRIPT_PATH=%~dp0run_daily_automation.py
set PYTHON_PATH=python

REM Create or update daily scheduled task to run every day at 12:05 AM
schtasks /Create /TN "%TASK_NAME%" /TR "%PYTHON_PATH% \"%SCRIPT_PATH%\"" /SC DAILY /ST 00:05 /F

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: Scheduled Task '%TASK_NAME%' has been created!
    echo It will run automatically every day at 12:05 AM.
    echo.
    echo To manually test trigger it right now, run:
    echo   schtasks /Run /TN "%TASK_NAME%"
) else (
    echo.
    echo WARNING: Task creation requires Administrator privileges.
    echo Please re-run this script in an Administrator Command Prompt or PowerShell window.
)

echo =================================================================
pause
