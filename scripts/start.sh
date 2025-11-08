#!/usr/bin/env bash
set -euo pipefail

# перейти в корень репо (а не оставаться в scripts/)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
cd "$ROOT"

# На всякий случай освобождаем :4567 от старого Node/чего угодно
if command -v lsof >/dev/null 2>&1; then
  PIDS="$(lsof -ti :4567 || true)"
  if [ -n "${PIDS:-}" ]; then
    echo "⛔ Освобождаю порт 4567 ($PIDS)…"
    kill $PIDS 2>/dev/null || true
    sleep 1
    kill -9 $PIDS 2>/dev/null || true
  fi
fi
rm -f .server.pid server.log 2>/dev/null || true

JAVA_PROP=""
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  J17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  [[ -n "${J17}" ]] && JAVA_PROP="-Dorg.gradle.java.home=${J17}"
elif [[ -n "${JAVA_17_HOME:-}" ]]; then
  JAVA_PROP="-Dorg.gradle.java.home=${JAVA_17_HOME}"
fi

echo "▶️  Запускаю Minecraft dev client (Forge)…"
./gradlew ${JAVA_PROP} runClient