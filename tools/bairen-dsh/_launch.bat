@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

rem ============================================================
rem  BaiRen DSH launcher: find Python 3, then run the injector.
rem
rem  Deliberately ASCII-only: cmd.exe (cp65001) mis-splits batch
rem  lines that mix long CJK runs with %VAR% or quotes, which shows
rem  up as  "'ROFILE...' is not recognized as an internal command".
rem  All Chinese output comes from the Python program itself.
rem ============================================================

set "ACTION=%~1"
set "SCRIPT="
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=powershell"

rem The injector is the only *DSH*.py in this folder (its name is Chinese,
rem so it is matched by an ASCII wildcard instead of being spelled out).
for %%F in ("%~dp0*DSH*.py") do call :take_script "%%~fF"
if not defined SCRIPT for %%F in ("%~dp0*.py") do call :take_script "%%~fF"

if defined SCRIPT goto have_script
echo [ERROR] injector script not found in:
echo         %~dp0
echo         Expecting a file matching *DSH*.py  (e.g. <name>DSH<name>.py).
echo         Run the diagnose bat to list the folder contents.
goto end_pause

:have_script
echo ============================================================
echo  Target user dir : %USERPROFILE%
echo  Injector script : %SCRIPT%
echo ============================================================
net session >nul 2>&1
if errorlevel 1 goto not_elevated
echo [WARN] This is an elevated window. If UAC switched to a different
echo        account, files land in THAT account's profile and your DSH
echo        will not see them. Elevation is normally not required.
echo.
:not_elevated

call :find_python
if defined PY goto run
goto no_python

:run
echo [INFO] Python: %PY%
echo.
%PY% "%SCRIPT%" %ACTION%
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" goto end_pause
echo [ERROR] exit code %RC% - if this keeps failing, run the diagnose bat
echo         and send its full output.
goto end_pause

rem ------------------------------------------------------- locate Python 3
:find_python
set "PY="

where py >nul 2>nul
if not errorlevel 1 set "PY=py -3"
if defined PY goto py_check

where python >nul 2>nul
if not errorlevel 1 set "PY=python"
if defined PY goto py_check

where python3 >nul 2>nul
if not errorlevel 1 set "PY=python3"
if defined PY goto py_check

rem Not on PATH, or shadowed by the 0-byte WindowsApps store alias:
rem probe absolute paths + registry through PowerShell.
rem Output goes through a temp file: a quoted exe path inside for/f
rem (cmd /c "...") loses its quotes and fails silently.
set "PYTMP=%TEMP%\_bairen_python.txt"
del "%PYTMP%" >nul 2>nul
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0_findpy.ps1" > "%PYTMP%" 2>nul
if not exist "%PYTMP%" exit /b 0
set /p PY=<"%PYTMP%"
del "%PYTMP%" >nul 2>nul
if not defined PY exit /b 0

:py_check
rem Reject store aliases and broken launchers: the candidate must actually run.
%PY% -c "import sys" >nul 2>nul
if errorlevel 1 set "PY="
exit /b 0

:take_script
if defined SCRIPT exit /b 0
set "SCRIPT=%~1"
exit /b 0

:take_python
if defined PY exit /b 0
set "PY=%~1"
exit /b 0

:no_python
echo [ERROR] Python 3 not found.
echo.
echo         Tried: py / python / python3, the PythonCore registry keys,
echo         and the usual install folders.
echo.
echo         Fix it any one of these ways:
echo           1) Install Python 3.10+ and tick "Add python.exe to PATH"
echo              https://www.python.org/downloads/windows/
echo           2) Run the injector with a full path, for example:
echo              "D:\tools\python\python.exe" "%SCRIPT%" %ACTION%
echo           3) Add the python folder to your user PATH, then reopen
echo              this window:
echo              setx PATH "%%PATH%%;D:\tools\python"
goto end_pause

:end_pause
echo.
if not defined BAIREN_NO_PAUSE pause
exit /b 0
