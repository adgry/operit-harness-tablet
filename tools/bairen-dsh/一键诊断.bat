@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

rem ASCII-only diagnostics (see the note in _launch.bat).
rem The Chinese status summary is printed by the Python program at the end.

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=powershell"
set "SCRIPT="
for %%F in ("%~dp0*DSH*.py") do call :take "%%~fF"

echo ============================================================
echo  BaiRen DSH - environment check
echo ============================================================
echo.
echo [1] cwd      : %CD%
echo     user     : %USERNAME%
echo     profile  : %USERPROFILE%
echo.
echo [2] folder contents:
dir /b "%~dp0"
echo.
echo [3] Python launchers on PATH:
where py      2>nul || echo     py      NOT FOUND
where python  2>nul || echo     python  NOT FOUND
where python3 2>nul || echo     python3 NOT FOUND
echo.
echo [4] absolute path + registry probe:
set "FOUND="
set "PYTMP=%TEMP%\_bairen_python.txt"
del "%PYTMP%" >nul 2>nul
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0_findpy.ps1" > "%PYTMP%" 2>nul
if not exist "%PYTMP%" goto probe_done
set /p FOUND=<"%PYTMP%"
del "%PYTMP%" >nul 2>nul
:probe_done
if not defined FOUND echo     nothing found
if defined FOUND echo     %FOUND%
echo.
echo [5] Python version:
if not defined FOUND goto ver_skip
"%FOUND%" -V 2>&1
goto ver_done
:ver_skip
echo     skipped (nothing found above)
:ver_done
echo.
echo [6] privilege:
net session >nul 2>&1
if errorlevel 1 echo     normal user
if not errorlevel 1 echo     elevated (administrator)
echo.
echo [7] DeepSeek Harness processes:
tasklist /fi "imagename eq DeepSeek Harness.exe" 2>nul | findstr /i "deepseek" || echo     not running (it must be closed before injecting)
echo.
echo [8] .dsh paths:
if exist "%USERPROFILE%\.dsh\AGENTS.md" echo     AGENTS.md                          present
if not exist "%USERPROFILE%\.dsh\AGENTS.md" echo     AGENTS.md                          MISSING
if exist "%USERPROFILE%\.dsh\plugins\bairen-persona" echo     plugins\bairen-persona          present
if not exist "%USERPROFILE%\.dsh\plugins\bairen-persona" echo     plugins\bairen-persona          MISSING
if exist "%USERPROFILE%\.dsh\skills\bairen-pentest" echo     skills\bairen-pentest           present
if not exist "%USERPROFILE%\.dsh\skills\bairen-pentest" echo     skills\bairen-pentest           MISSING
echo.

if not defined FOUND goto no_status
echo [9] current status (from the injector, Chinese output):
"%FOUND%" "%SCRIPT%" status
goto end_pause
:no_status
echo [9] status skipped: no Python available.
goto end_pause

:take
if defined SCRIPT exit /b 0
set "SCRIPT=%~1"
exit /b 0

:take_found
if defined FOUND exit /b 0
set "FOUND=%~1"
echo     %~1
exit /b 0

:end_pause
echo.
echo Send this whole screen when asking for help.
if not defined BAIREN_NO_PAUSE pause
exit /b 0
