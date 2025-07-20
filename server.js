const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');
const { exec } = require('child_process');
const { pipeline } = require('stream/promises');

// Пауза
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

// --- Скачивание DEM с GMRT ---
async function downloadGmrtDemWithRetry(bbox, outFile, maxRetries = 10, delayMs = 60000) {
  const downloadGmrtDem = async () => {
    const params = new URLSearchParams({
      minlatitude: bbox.south,
      maxlatitude: bbox.north,
      minlongitude: bbox.west,
      maxlongitude: bbox.east,
      format: 'geotiff',
      layer: 'topo'
    });
    const url = `https://www.gmrt.org/services/GridServer?${params.toString()}`;
    console.log('⬇️ GMRT DEM URL:', url);

    const response = await fetch(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; CartopiaBot/1.0; +https://your.site)'
      }
    });

    if (!response.ok) {
      const errText = await response.text();
      fs.writeFileSync(outFile + '.error.html', errText);
      throw new Error(`Ошибка GMRT: ${response.statusText}, details: ${errText.slice(0, 200)}`);
    }

    const nodeReadable = require('stream').Readable.fromWeb(response.body);
    await pipeline(
      nodeReadable,
      fs.createWriteStream(outFile)
    );
  };

  let attempt = 0;
  while (attempt < maxRetries) {
    attempt++;
    try {
      await downloadGmrtDem();

      // Проверка файла
      const stat = fs.statSync(outFile);
      if (stat.size === 0) {
        console.log(`❌ DEM пустой (0 байт), попытка ${attempt}/${maxRetries}. Ждём...`);
        await sleep(delayMs);
        continue;
      }

      // Проверка что файл — именно GeoTIFF (TIFF всегда начинается с II* или MM*)
      const buffer = fs.readFileSync(outFile);
      const isTiff =
        (buffer[0] === 0x49 && buffer[1] === 0x49 && buffer[2] === 0x2A && buffer[3] === 0x00) ||
        (buffer[0] === 0x4D && buffer[1] === 0x4D && buffer[2] === 0x00 && buffer[3] === 0x2A);

      const firstBytes = buffer.slice(0, 100).toString('utf8');
      if (!isTiff || firstBytes.startsWith('<') || firstBytes.toLowerCase().includes('html')) {
        fs.writeFileSync(outFile + '.error.html', buffer);
        console.log(`❌ DEM не GeoTIFF (или ошибка/HTML). Попытка ${attempt}/${maxRetries}. Ждём...`);
        await sleep(delayMs);
        continue;
      }

      console.log('✅ DEM успешно скачан!');
      return;
    } catch (err) {
      console.log(`❌ Ошибка скачивания DEM, попытка ${attempt}/${maxRetries}:`, err.message);
      await sleep(delayMs);
    }
  }
  throw new Error('Не удалось скачать DEM после максимального количества попыток');
}



const app = express();
const PORT = 4567;

let playerCoords = null;

app.use(express.json({ limit: '200mb' }));
app.use(cors());
app.use(express.static(path.join(__dirname, 'public')));

app.post('/player', (req, res) => {
  playerCoords = req.body;
  res.send("OK");
});

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
    await downloadGmrtDemWithRetry(bbox, demPath, 10, 60000);
  } catch (err) {
    console.error('❌ Ошибка при скачивании данных:', err);
    return res.status(500).send('Ошибка при скачивании DEM');
  }

  // Всё скачано — запускаем generate_world.py
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

app.listen(PORT, () => {
  console.log(`🌐 Сервер запущен: http://localhost:${PORT}`);
});
