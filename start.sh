#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

# 0) Гасим всё, что висит на :4567
lsof -ti :4567 | xargs -r kill || true

# 1) Проверяем Node и версию (server.js требует Node >= 18 из-за fetch)
if ! command -v node >/dev/null 2>&1; then
  echo "❌ Node.js не найден. Установи Node 18+ (brew install node@20 или nvm install 20)."
  exit 1
fi
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]')
if [ "$NODE_MAJOR" -lt 18 ]; then
  echo "❌ Нужен Node >= 18, сейчас: $(node -v). Обнови Node (nvm install 20; nvm use 20)."
  exit 1
fi

# 2) Запускаем Node-сервер в фоне, лог — server.log
echo "▶️  Запускаю Node-сервер на :4567 …"
nohup node server.js > server.log 2>&1 &
SERVER_PID=$!
echo $SERVER_PID > .server.pid
sleep 1

if lsof -i :4567 >/dev/null 2>&1; then
  echo "✅ Сервер слушает http://localhost:4567 (PID $SERVER_PID)"
else
  echo "⚠️  Порт :4567 пока не слушается. Смотри логи: tail -f server.log"
fi

# 3) Стартуем Minecraft-клиент (Forge dev runtime)
./gradlew runClient