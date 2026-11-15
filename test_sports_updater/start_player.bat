@echo off
title Stream & Sports Live Tester
cd /d "%~dp0"
echo ============================================================
echo   Starting Local Stream Tester on http://localhost:8088
echo ============================================================
start "" "http://localhost:8088/stream_tester.html"
python -m http.server 8088
pause

