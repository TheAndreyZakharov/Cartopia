const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');
const { exec } = require('child_process');

const { pipeline } = require('stream/promises');

const downloadGmrtDem = async (bbox, outFile) => {
  const params = new URLSearchParams({
    minlatitude: bbox.south,
    maxlatitude: bbox.north,
    minlongitude: bbox.west,
    maxlongitude: bbox.east,
    format: 'geotiff',
    layer: 'topo'
  });
  const url = `https://www.gmrt.org/services/GridServer?${params.toString()}`;
  //console.log('⬇️ Скачиваем DEM с GMRT:', url);

  const response = await fetch(url);

  if (!response.ok) throw new Error(`Ошибка GMRT: ${response.statusText}`);

  // response.body теперь это web stream! Конвертируем в Node.js stream
  const nodeReadable = require('stream').Readable.fromWeb(response.body);

  await pipeline(
    nodeReadable,
    fs.createWriteStream(outFile)
  );
};



const app = express();
const PORT = 4567;

let playerCoords = null;

// Разрешаем большие JSON-запросы (до 10 МБ)
app.use(express.json({ limit: '10mb' }));
app.use(cors());
app.use(express.static(path.join(__dirname, 'public')));

// Сохраняем координаты игрока, полученные из клиента
app.post('/player', (req, res) => {
  playerCoords = req.body;
  //console.log("👤 Координаты игрока:", playerCoords);
  res.send("OK");
});

// Сохраняем координаты области + features из карты + скачиваем DEM
app.post('/save-coords', async (req, res) => {
  const data = req.body;
  if (playerCoords) data.player = playerCoords;

  const filePath = path.join(__dirname, 'coords.json');
  const demPath = path.join(__dirname, 'dem.tif');
  const bbox = data.bbox;

    if (
    Math.abs(bbox.north - bbox.south) > 20 ||
    Math.abs(bbox.east - bbox.west) > 20
  ) {
    return res.status(400).send('Область слишком большая для GMRT (максимум 20x20 градусов)');
  }

  fs.writeFileSync(filePath, JSON.stringify(data, null, 2));

  try {
    // Скачиваем DEM!
    await downloadGmrtDem(bbox, demPath);
    //console.log('✅ DEM успешно скачан:', demPath);
  } catch (err) {
    console.error('❌ Ошибка при скачивании DEM:', err);
    return res.status(500).send('Ошибка при скачивании DEM');
  }

  // Генерируем мир
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


// Старт сервера
app.listen(PORT, () => {
  console.log(`🌐 Сервер запущен: http://localhost:${PORT}`);
});