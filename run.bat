@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1
set "ROOT=%~dp0"
set "JAVA_BIN=%JAVA_BIN%"
if "%JAVA_BIN%"=="" set "JAVA_BIN=E:\ruanjiangongcheng\IntelliJ IDEA 2026.2\jbr\bin"

if not exist "%JAVA_BIN%\javac.exe" (
  echo [ERROR] javac.exe not found: %JAVA_BIN%
  echo Please set JAVA_BIN to your JDK bin directory, e.g.
  echo   set JAVA_BIN=C:\Program Files\Java\jdk-25\bin
  exit /b 1
)

set "APP_PORT=8080"
set "APP_DATA_DIR=%ROOT%data"
set "APP_WEB_DIR=%ROOT%web"
set "APP_JWT_SECRET=dev-only-change-me-32bytes-min"
set "APP_RUSH_MONTHLY_LIMIT=5"
set "APP_SLA_ESCALATE=true"

if not exist "%ROOT%build\classes" mkdir "%ROOT%build\classes"
if not exist "%ROOT%data" mkdir "%ROOT%data"
(
  for /r "%ROOT%src\main\java" %%f in (*.java) do echo "%%f"
) > "%ROOT%build\sources.txt"

echo [1/2] Compiling Java sources ...
"%JAVA_BIN%\javac.exe" -J-Dfile.encoding=UTF-8 --add-modules jdk.httpserver -encoding UTF-8 -d "%ROOT%build\classes" @"%ROOT%build\sources.txt"
if errorlevel 1 (
  echo [ERROR] Compilation failed.
  exit /b 1
)

echo [2/2] Starting server at http://127.0.0.1:%APP_PORT%
echo PC workbench:  http://127.0.0.1:%APP_PORT%/
echo Mobile approvals: http://127.0.0.1:%APP_PORT%/m.html
"%JAVA_BIN%\java.exe" --add-modules jdk.httpserver -cp "%ROOT%build\classes" com.salesorder.App
