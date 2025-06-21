#!/bin/bash

# Открыть терминал для node-сервера
osascript -e 'tell application "Terminal" to do script "cd \"'"$(pwd)"'\" && node server.js"'

# Запустить клиента (можно в этом же окне)
./gradlew runClient
