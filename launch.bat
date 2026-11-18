@echo off
setlocal

:: ── Configuration ──────────────────────────────────────────────
:: LM Studio base URL (running locally on Windows)
set LLM_BASE_URL=http://localhost:1234

:: Model name as shown in LM Studio
set LLM_MODEL=mistralai/ministral-3-14b-reasoning

:: Random per-launch token unless the user explicitly set AGENTICA_TOKEN.
if "%AGENTICA_TOKEN%"=="" (
    for /f %%t in ('powershell -NoProfile -Command "([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')).Substring(0,32)"') do set "AGENTICA_TOKEN=%%t"
)

:: Fixed port (avoids hunting for the dynamic port each launch)
set AGENTICA_PORT=8080

:: Path to the fat-jar (adjust version if needed)
set JAR=%~dp0backend\target\agentica-backend-1.0-SNAPSHOT.jar

:: Path to the UI folder
set AGENTICA_UI_ROOT=%~dp0ui
:: ────────────────────────────────────────────────────────────────

if not exist "%JAR%" (
    echo ERROR: Fat-jar not found at %JAR%
    echo Build it first with:  mvn package -DskipTests
    pause
    exit /b 1
)

echo Starting Agentica backend...
echo Open your browser at: http://localhost:%AGENTICA_PORT%/#token=%AGENTICA_TOKEN%
echo Press Ctrl+C to stop.
echo.

java -jar "%JAR%"
