#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

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

echo "▶️  Запускаю Minecraft dev client (Forge)…"
./gradlew runClient