#!/bin/bash

# Открыть терминал для node-сервера
osascript -e 'tell application "Terminal" to do script "cd \"'"$(pwd)"'\" && node server.js"'

# Запустить relight (освещение)
python3 /Users/andrey/Documents/projects/Cartopia/relight_world.py

# Запустить клиента
./gradlew runClient