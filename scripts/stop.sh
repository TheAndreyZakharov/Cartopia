#!/usr/bin/env bash
set -euo pipefail

# перейти в корень репо
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
cd "$ROOT"

# Убираем старые служебные файлы (если были от Node-версии)
rm -f .server.pid server.log 2>/dev/null || true

# На всякий случай прибиваем всё, что слушает :4567
if command -v lsof >/dev/null 2>&1; then
  PIDS="$(lsof -ti :4567 || true)"
  if [ -n "${PIDS:-}" ]; then
    echo "⛔ Останавливаю процессы на :4567 ($PIDS)…"
    kill $PIDS 2>/dev/null || true
    sleep 1
    kill -9 $PIDS 2>/dev/null || true
  fi
fi

echo "✅ Порт 4567 свободен. Встроенный веб-сервер мода сам гасится при выходе из игры."