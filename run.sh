#!/usr/bin/env bash
# 一键编译并启动（Git Bash / macOS / Linux）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN="${JAVA_BIN:-E:/ruanjiangongcheng/IntelliJ IDEA 2026.2/jbr/bin}"

if [[ ! -x "$JAVA_BIN/javac" && ! -x "$JAVA_BIN/javac.exe" ]]; then
  echo "[ERROR] javac not found in: $JAVA_BIN"
  echo "Set JAVA_BIN to your JDK bin directory, e.g. export JAVA_BIN=/c/Program\ Files/Java/jdk-25/bin"
  exit 1
fi
JAVAC="$JAVA_BIN/javac"; [[ -x "$JAVA_BIN/javac.exe" ]] && JAVAC="$JAVA_BIN/javac.exe"
JAVA="$JAVA_BIN/java";   [[ -x "$JAVA_BIN/java.exe" ]]  && JAVA="$JAVA_BIN/java.exe"

export APP_PORT="${APP_PORT:-8080}"
export APP_DATA_DIR="$ROOT/data"
export APP_WEB_DIR="$ROOT/web"
export APP_JWT_SECRET="${APP_JWT_SECRET:-dev-only-change-me-32bytes-min}"
export APP_RUSH_MONTHLY_LIMIT="${APP_RUSH_MONTHLY_LIMIT:-5}"
export APP_SLA_ESCALATE="${APP_SLA_ESCALATE:-true}"

mkdir -p "$ROOT/build/classes" "$ROOT/data"
echo "[1/2] Compiling Java sources ..."
"$JAVAC" -J-Dfile.encoding=UTF-8 --add-modules jdk.httpserver -encoding UTF-8 \
  -d "$ROOT/build/classes" $(find "$ROOT/src/main/java" -name '*.java')

echo "[2/2] Starting server at http://127.0.0.1:$APP_PORT"
echo "PC workbench: http://127.0.0.1:$APP_PORT/"
echo "Mobile:       http://127.0.0.1:$APP_PORT/m.html"
exec "$JAVA" --add-modules jdk.httpserver -cp "$ROOT/build/classes" com.salesorder.App
