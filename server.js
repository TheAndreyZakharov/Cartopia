const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');
const { exec } = require('child_process');

const app = express();
const PORT = 4567;

let playerCoords = null;

// ⬇️ Разрешаем большие JSON-запросы (до 10 МБ)
app.use(express.json({ limit: '10mb' }));
app.use(cors());
app.use(express.static(path.join(__dirname, 'public')));

// ⬅️ Сохраняем координаты игрока, полученные из клиента
app.post('/player', (req, res) => {
  playerCoords = req.body;
  console.log("👤 Координаты игрока:", playerCoords);
  res.send("OK");
});

// ⬅️ Сохраняем координаты области + features из карты
app.post('/save-coords', (req, res) => {
  const data = req.body;
  if (playerCoords) data.player = playerCoords;

  const filePath = path.join(__dirname, 'coords.json');

  fs.writeFile(filePath, JSON.stringify(data, null, 2), err => {
    if (err) {
      console.error('❌ Ошибка при сохранении файла:', err);
      return res.status(500).send('Ошибка при сохранении координат');
    }

    console.log('✅ Координаты сохранены:', data);

    // ⬇️ Запускаем генерацию Python-скриптом
    exec('python3 generate_world.py', { maxBuffer: 1024 * 1024 * 10 }, (error, stdout, stderr) => {
      if (error) {
        console.error('❌ Ошибка при генерации:', error.message);
        console.error(stderr);
      } else {
        console.log(stdout);
      }
    });

    res.send('OK');
  });
});

// Старт сервера
app.listen(PORT, () => {
  console.log(`🌐 Сервер запущен: http://localhost:${PORT}`);
});
