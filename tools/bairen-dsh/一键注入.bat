@echo off
chcp 65001 >nul
title 白刃破甲 · DeepSeek Harness 一键注入
if not exist "%~dp0_launch.bat" (
  echo [ERROR] _launch.bat missing next to this file.
  echo         Extract the whole folder, then run this bat again.
  pause
  exit /b 1
)
call "%~dp0_launch.bat" inject
