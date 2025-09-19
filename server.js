const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');
const { pipeline } = require('stream/promises');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const { execFile } = require('child_process');

const PACKS_DIR = path.join(__dirname, 'area-packs');

const MAX_PACKS = parseInt(process.env.CARTOPIA_MAX_PACKS || '10', 10);

function trimPacksDir(max = MAX_PACKS) {
  try {
    ensureDir(PACKS_DIR);
    const entries = fs.readdirSync(PACKS_DIR, { withFileTypes: true })
      .filter(d => d.isDirectory() && d.name.startsWith('area_'))
      .map(d => {
        const full = path.join(PACKS_DIR, d.name);
        try {
          const st = fs.statSync(full);
          return { full, name: d.name, mtime: st.mtimeMs };
        } catch { return null; }
      })
      .filter(Boolean)
      .sort((a, b) => b.mtime - a.mtime); // новые → старые

    const toDelete = entries.slice(max);
    for (const e of toDelete) {
      try {
        fs.rmSync(e.full, { recursive: true, force: true });
        console.log('🧹 удалил старый пакет:', e.full);
      } catch (err) {
        console.warn('⚠️ не смог удалить', e.full, '-', err.message);
      }
    }
  } catch (e) {
    console.warn('⚠️ trimPacksDir ошибка:', e.message);
  }
}

function ensureDir(p){ fs.mkdirSync(p, { recursive: true }); return p; }
function isoStamp(){ return new Date().toISOString().replace(/[-:]/g,'').replace(/\..+/,'').replace('T','_'); }

function makePackDir(bbox){
  const lat = ((bbox.south + bbox.north)/2).toFixed(5);
  const lon = ((bbox.west  + bbox.east )/2).toFixed(5);
  const name = `area_${isoStamp()}_lat${lat}_lon${lon}`;
  return ensureDir(path.join(PACKS_DIR, name));
}

function run(cmd, args, opts={}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, opts, (err, stdout, stderr) => {
      if (err) { err.stdout = stdout; err.stderr = stderr; reject(err); }
      else resolve({ stdout, stderr });
    });
  });
}

async function hasGdal(){
  try { await run('gdal_translate', ['--version']); return true; }
  catch { return false; }
}

/** Режем COG OpenLandMap по bbox в EPSG:4326 */
async function clipOlmCogWithGdal(bbox, outFile){
  const src = 'https://s3.openlandmap.org/arco/lc_glc.fcs30d_c_30m_s_20220101_20221231_go_epsg.4326_v20231026.tif';
  const { west, east, south, north } = bbox;
  const args = [
    '-q',
    '-of','GTiff',
    '-projwin_srs','EPSG:4326',
    // ВАЖНО: порядок -projwin: WEST NORTH EAST SOUTH
    '-projwin', String(west), String(north), String(east), String(south),
    '/vsicurl/' + src,
    outFile,
    '-r','near',
    '-co','TILED=YES',
    '-co','COMPRESS=DEFLATE',
    '-co','PREDICTOR=2'
  ];
  console.log('⬇️ OLM COG clip via gdal_translate…');
  try { await run('gdal_translate', args); }
  catch (e) {
    const hint = e.stderr || e.stdout || e.message || String(e);
    throw new Error('gdal_translate failed: ' + hint.slice(0,400));
  }
}

async function waitForModReady(timeoutMs = 300000) { // до 5 минут
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch('http://127.0.0.1:4568/health');
      if (r.ok) {
        const t = (await r.text()).trim();
        if (t === 'UP') return true;
      }
    } catch (_) {}
    await sleep(2000);
  }
  return false;
}

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
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CartopiaBot/1.0; +https://your.site)' }
    });

    if (!response.ok) {
      const errText = await response.text();
      fs.writeFileSync(outFile + '.error.html', errText);
      throw new Error(`Ошибка GMRT: ${response.statusText}, details: ${errText.slice(0, 200)}`);
    }

    const nodeReadable = require('stream').Readable.fromWeb(response.body);
    await pipeline(nodeReadable, fs.createWriteStream(outFile));
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

      // Проверка, что файл — GeoTIFF (TIFF: II* или MM*)
      const buffer = fs.readFileSync(outFile);
      const isTiff =
        (buffer[0] === 0x49 && buffer[1] === 0x49 && buffer[2] === 0x2A && buffer[3] === 0x00) ||
        (buffer[0] === 0x4D && buffer[1] === 0x4D && buffer[2] === 0x00 && buffer[3] === 0x2A);

      const firstBytes = buffer.slice(0, 100).toString('utf8');
      if (!isTiff || firstBytes.startsWith('<') || firstBytes.toLowerCase().includes('html')) {
        fs.writeFileSync(outFile + '.error.html', buffer);
        console.log(`❌ DEM не GeoTIFF (или HTML/ошибка). Попытка ${attempt}/${maxRetries}. Ждём...`);
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

  const bbox = data.bbox;
  if (
    Math.abs(bbox.north - bbox.south) > 20 ||
    Math.abs(bbox.east - bbox.west) > 20
  ) {
    return res.status(400).send('Область слишком большая для GMRT (максимум 20x20 градусов)');
  }

  // 1) Создаём папку-пакет
  const packDir = makePackDir(bbox);
  const coordsPath = path.join(packDir, 'coords.json');
  const demPath    = path.join(packDir, 'dem.tif');
  const olmPath    = path.join(packDir, 'olm_landcover.tif');

  // 2) Инъекция служебных полей и запись coords.json
  data.landcoverBounds = { west: bbox.west, east: bbox.east, south: bbox.south, north: bbox.north };
  data.paths = { dem: demPath, landcover: olmPath, packDir };
  fs.writeFileSync(coordsPath, JSON.stringify(data, null, 2));
  console.log('💾 coords.json записан:', coordsPath);

  // 3) DEM (как раньше), но в папку-пакет
  try {
    await downloadGmrtDemWithRetry(bbox, demPath, 10, 60000);
  } catch (err) {
    console.error('❌ Ошибка при скачивании DEM:', err);
    return res.status(500).send('Ошибка при скачивании DEM');
  }

  // 4) OpenLandMap landcover: режем COG локально (если есть GDAL)
  let landcoverOk = false;
  try {
    if (await hasGdal()) {
      await clipOlmCogWithGdal(bbox, olmPath);
      const st = fs.statSync(olmPath);
      landcoverOk = st.size > 0;
      console.log('✅ OLM landcover сохранён:', olmPath, `(${st.size} байт)`);
    } else {
      console.warn('⚠️ GDAL не найден в PATH — пропускаю клип OLM.');
    }
  } catch (e) {
    console.warn('⚠️ Не удалось вырезать OLM COG:', e.message);
  }

  console.log('⏳ Ждём готовности мода на 127.0.0.1:4568/health …');
  const up = await waitForModReady(300000);
  if (!up) {
    console.error('❌ Мод так и не вернул UP по /health. Отменяю /build.');
    return res.status(503).send('Мод не готов (нет UP по /health)');
  }

  // 5) Дёргаем мод, передаём все пути
  try {
    const payload = {
      coordsPath: coordsPath,
      demPath: demPath,
      landcoverPath: landcoverOk ? olmPath : null
    };
    const r = await fetch('http://127.0.0.1:4568/build', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!r.ok) {
      const txt = await r.text().catch(() => '');
      throw new Error(`HTTP ${r.status} ${r.statusText} ${txt}`);
    }
    console.log('🚀 Послал /build в мод. Пакет:', packDir);
  } catch (e) {
    console.error('❌ Не удалось дёрнуть мод на 127.0.0.1:4568/build', e);
    return res.status(502).send('Мод не принял запрос /build');
  }

  res.send('OK');

  setImmediate(() => trimPacksDir(MAX_PACKS));
  return;
});

app.listen(PORT, () => {
  console.log(`🌐 Сервер запущен: http://localhost:${PORT}`);
  console.log('ℹ️  Откройте Minecraft через ./start.sh, зайдите в одиночный мир.');
  console.log('ℹ️  Затем через страницу управления отправьте /save-coords (Node сам дождётся /health).');
});
