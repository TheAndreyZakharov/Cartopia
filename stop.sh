#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

# 1) Пробуем убить по PID из .server.pid
if [ -f .server.pid ]; then
  PID=$(cat .server.pid || true)
  if [ -n "$PID" ] && ps -p "$PID" >/dev/null 2>&1; then
    echo "⛔ Останавливаю Node-сервер (PID $PID)…"
    kill "$PID" 2>/dev/null || true
    sleep 1
    if ps -p "$PID" >/dev/null 2>&1; then
      echo "⚠️  Сервер не остановился, делаю kill -9…"
      kill -9 "$PID" 2>/dev/null || true
    fi
  else
    echo "ℹ️  В .server.pid записан неактивный PID ($PID)."
  fi
  rm -f .server.pid
fi

# 2) На всякий случай прибиваем всё, что слушает порт 4567
PIDS=$(lsof -ti :4567 || true)
if [ -n "$PIDS" ]; then
  echo "⛔ Дополнительно убиваю процессы на :4567 ($PIDS)…"
  kill $PIDS 2>/dev/null || true
  sleep 1
  kill -9 $PIDS 2>/dev/null || true
fi

echo "✅ Node-сервер остановлен."