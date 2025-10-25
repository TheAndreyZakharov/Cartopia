/* ===================== Константы карты/геометрии ===================== */
const SOUTH_LIMIT = -60;
const NORTH_LIMIT = 85.05112878;
const HEADER_HEIGHT = 72;
const FOOTER_HEIGHT = 50;
const PAD_TOP_FROM_HEADER = 30;
const DEFAULT_OFFSET_TOP = HEADER_HEIGHT + PAD_TOP_FROM_HEADER;
const DEFAULT_OFFSET_RIGHT = 30;

/* ===================== Настройки Overpass/Nominatim ===================== */
const OVERPASS_ENDPOINTS = [
  "https://overpass-api.de/api/interpreter",
  "https://lz4.overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://overpass.openstreetmap.ru/api/interpreter",
  "https://overpass.nchc.org.tw/api/interpreter"
];

// Таймаут одного запроса, мс
const OVERPASS_TIMEOUT_MS = 120000;

/* ===================== Утилиты ===================== */
function clampLat(lat){ return Math.min(Math.max(lat, SOUTH_LIMIT), NORTH_LIMIT); }
function setStatus(text){ statusLine.textContent = text || ""; }

function waitTransition(el, fallbackMs = 500){
  return new Promise(resolve => {
    let done = false;
    const finish = () => { if (done) return; done = true; el.removeEventListener('transitionend', finish); resolve(); };
    el.addEventListener('transitionend', finish, { once: true });
    setTimeout(finish, fallbackMs);
  });
}

/* ===================== Инициализация карты ===================== */
const map = L.map('map', {
  attributionControl: false,
  worldCopyJump: true,
  maxBounds: L.latLngBounds([SOUTH_LIMIT, -540],[NORTH_LIMIT, 540]),
  maxBoundsViscosity: 1.0,
  minZoom: 2
}).setView([35, 0], 2);

L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  minZoom: 2,
  bounds: L.latLngBounds([SOUTH_LIMIT, -180],[NORTH_LIMIT, 180]),
  crossOrigin: 'anonymous'
}).addTo(map);

setTimeout(() => {
  document.querySelectorAll('.leaflet-bottom.leaflet-right, .leaflet-control-attribution, .leaflet-control-container').forEach(el => el.remove());
}, 500);

/* ===================== DOM ===================== */
const box = document.getElementById('selectionBox');
const controls = document.getElementById('controls');
const collapseTab = document.getElementById('collapseTab');
const dragTab = document.getElementById('dragTab');
const toggleBtn = document.getElementById('toggleBtn');
const sizeInput = document.getElementById('sizeInput');
const confirmBtn = document.getElementById('confirmBtn');
const searchInput = document.getElementById('searchInput');
const searchBtn = document.getElementById('searchBtn');
const areaInfo = document.getElementById('areaInfo');
const statusLine = document.getElementById('statusLine');
const scalePlusBtn  = document.getElementById('btnScalePlus');
const scaleMinusBtn = document.getElementById('btnScaleMinus');
const zoomInBtn  = document.getElementById('btnZoomIn');
const zoomOutBtn = document.getElementById('btnZoomOut');
/* === Dark theme toggle === */
const themeToggleBtn = document.getElementById('themeToggleBtn');

/* Визуальный «нажим» для кнопок панели */
const panelButtons = [searchBtn, toggleBtn, confirmBtn, collapseTab, dragTab, zoomInBtn, zoomOutBtn, scalePlusBtn, scaleMinusBtn];
panelButtons.forEach(btn => {
  if (!btn) return;

  // Pointer (мышь/тач/стилус)
  btn.addEventListener('pointerdown', () => btn.classList.add('is-pressed'));
  const cancelPress = () => btn.classList.remove('is-pressed');
  ['pointerup','pointercancel','mouseleave','blur'].forEach(ev =>
    btn.addEventListener(ev, cancelPress)
  );

  // Клавиатура: Space/Enter – коротко показываем «нажим»
  btn.addEventListener('keydown', e => {
    if (e.key === ' ' || e.key === 'Enter') btn.classList.add('is-pressed');
  });
  btn.addEventListener('keyup', () => btn.classList.remove('is-pressed'));
});

/* === Dark/Light theme with "last-writer-wins" (system vs user) === */
const THEME_KEYS = {
  actor:  'cartopiaThemeActor',   // 'system' | 'user'
  user:   'cartopiaUserTheme',    // 'light' | 'dark' (когда actor='user')
  system: 'cartopiaSystemTheme'   // последняя виденная системная тема
};

function getSystemTheme(){
  return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
    ? 'dark' : 'light';
}

function persistTheme({ actor, userTheme, systemTheme }){
  localStorage.setItem(THEME_KEYS.actor, actor);
  if (userTheme)  localStorage.setItem(THEME_KEYS.user, userTheme);
  if (systemTheme) localStorage.setItem(THEME_KEYS.system, systemTheme);
}

/* Применяем тему + обновляем подпись кнопки */
function applyTheme(mode){
  const isDark = (mode === 'dark');
  document.body.classList.toggle('theme-dark', isDark);
  if (themeToggleBtn){
    themeToggleBtn.textContent = isDark ? 'Enable light theme' : 'Enable dark theme';
    themeToggleBtn.setAttribute('aria-pressed', String(isDark));
    themeToggleBtn.title = isDark ? 'Enable light theme' : 'Enable dark theme';
  }
}

/* Инициализация и подписка на системные изменения — система всегда говорит последней,
   пока пользователь явно не кликнет. */
(function initThemeLW(){
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const systemNow = getSystemTheme();

  let actor      = localStorage.getItem(THEME_KEYS.actor) || 'system';
  let userTheme  = localStorage.getItem(THEME_KEYS.user)  || 'dark';
  const lastSeen = localStorage.getItem(THEME_KEYS.system);

  // Если ранее пользователь выбирал тему, но ПОКА страница была закрыта
  // системная тема успела измениться — считаем, что последнее слово за системой.
  if (actor === 'user' && lastSeen && lastSeen !== systemNow){
    actor = 'system';
  }

  // Применяем актуальную тему
  persistTheme({ actor, userTheme, systemTheme: systemNow });
  applyTheme(actor === 'user' ? userTheme : systemNow);

  // Всегда слушаем систему: любое её изменение немедленно применяем и запоминаем как последнее.
  mq.addEventListener('change', e => {
    const sys = e.matches ? 'dark' : 'light';
    persistTheme({ actor: 'system', userTheme: localStorage.getItem(THEME_KEYS.user) || 'dark', systemTheme: sys });
    applyTheme(sys);
  });
})();

/* Кнопка: пользователь сказал последнее слово — фиксируем и применяем */
if (themeToggleBtn){
  themeToggleBtn.addEventListener('click', () => {
    const sys = getSystemTheme();
    const next = document.body.classList.contains('theme-dark') ? 'light' : 'dark';
    persistTheme({ actor: 'user', userTheme: next, systemTheme: sys });
    applyTheme(next);
  });
}

function showGlassDialog({ title = 'Saved!', message = '', okText = 'OK' } = {}){
  return new Promise(resolve => {
    // backdrop
    const bg = document.createElement('div');
    bg.className = 'glass-modal-backdrop';
    // dialog
    const dlg = document.createElement('div');
    dlg.className = 'glass-modal';
    dlg.setAttribute('role', 'dialog');
    dlg.setAttribute('aria-modal', 'true');

    const inner = document.createElement('div');
    inner.className = 'glass-modal__inner';

    const h = document.createElement('h3');
    h.className = 'glass-modal__title';
    h.textContent = title;

    const p = document.createElement('div');
    p.className = 'glass-modal__msg';
    p.innerHTML = message;

    const actions = document.createElement('div');
    actions.className = 'glass-modal__actions';

    const ok = document.createElement('button');
    ok.type = 'button';
    ok.className = 'ui-button ui-button--primary';
    ok.textContent = okText;

    actions.appendChild(ok);
    inner.append(h, p, actions);
    dlg.appendChild(inner);
    document.body.append(bg, dlg);

    // show
    requestAnimationFrame(() => { bg.classList.add('visible'); dlg.classList.add('visible'); });

    const close = () => {
      bg.classList.remove('visible'); dlg.classList.remove('visible');
      setTimeout(() => { bg.remove(); dlg.remove(); resolve(); }, 200);
    };

    bg.addEventListener('click', close);
    ok.addEventListener('click', close);
    document.addEventListener('keydown', function onKey(e){
      if (e.key === 'Escape'){ document.removeEventListener('keydown', onKey); close(); }
    });

    // фокус на кнопку
    setTimeout(() => ok.focus(), 10);
  });
}

if (zoomInBtn)  zoomInBtn.addEventListener('click',  () => map.zoomIn());
if (zoomOutBtn) zoomOutBtn.addEventListener('click', () => map.zoomOut());

function syncZoomButtons(){
  if (!zoomInBtn || !zoomOutBtn) return;
  const z = map.getZoom();
  const minZ = map.getMinZoom ? map.getMinZoom() : 0;
  const maxZ = map.getMaxZoom ? map.getMaxZoom() : 18;
  zoomOutBtn.disabled = z <= minZ;
  zoomInBtn.disabled  = z >= maxZ;
}
map.on('zoomend', syncZoomButtons);
map.whenReady(syncZoomButtons);


function changeSizeBy(stepDir){
  const step = parseInt(sizeInput.step || '100', 10);
  const min  = parseInt(sizeInput.min  || '200', 10);
  const max  = 20000000;

  let v = parseInt(sizeInput.value, 10);
  if (isNaN(v)) v = selectionSize;

  v = Math.min(Math.max(v + stepDir * step, min), max);
  sizeInput.value = v;
  selectionSize = v;

  updateAreaInfo();
  if (selecting){ animateSelectionResize(); updateBoxAndCenter(); }
}

if (scalePlusBtn)  scalePlusBtn.addEventListener('click', () => changeSizeBy(+1));
if (scaleMinusBtn) scaleMinusBtn.addEventListener('click', () => changeSizeBy(-1));

function animateSelectionResize(){
  if (!box) return;
  box.classList.add('size-anim');
  clearTimeout(animateSelectionResize._t);
  animateSelectionResize._t = setTimeout(
    () => box.classList.remove('size-anim'),
    230 // чуть больше, чем 200ms в CSS
  );
}

// Размеры итогового GeoTIFF (single-band) под твой bbox
const OLM_TIFF_WIDTH   = 1024;   // при больших окнах можно 2048–4096
const OLM_TIFF_HEIGHT  = 1024;

/* ===================== Состояния ===================== */
let selecting = false;
let selectionSize = parseInt(sizeInput.value, 10);
let centerLatLng = null;
let centerSquare = null;
let userMoved = false;

/* ===================== DRAG панели ===================== */
(function enableControlsDrag() {
  let dragging = false;
  let startX = 0, startY = 0;
  let startLeft = 0, startTop = 0;

  function clamp(v, a, b){ return Math.min(Math.max(v, a), b); }

  function onPointerDown(clientX, clientY) {
    if (controls.classList.contains('collapsed')) return;
    dragging = true;
    userMoved = true;

    const rect = controls.getBoundingClientRect();
    controls.style.position = 'fixed';
    controls.style.left = rect.left + 'px';
    controls.style.top  = rect.top  + 'px';
    controls.style.right = 'auto';
    controls.style.transform = 'translate(0,0)';

    startX = clientX;
    startY = clientY;
    startLeft = rect.left;
    startTop  = rect.top;

    document.addEventListener('mousemove', onMouseMove, { passive: false });
    document.addEventListener('mouseup', onMouseUp, { passive: false });
    document.addEventListener('touchmove', onTouchMove, { passive: false });
    document.addEventListener('touchend', onTouchEnd, { passive: false });
  }

  function onMouseDown(e){ e.preventDefault(); onPointerDown(e.clientX, e.clientY); }
  function onTouchStart(e){
    if (!e.touches || e.touches.length === 0) return;
    const t = e.touches[0]; e.preventDefault(); onPointerDown(t.clientX, t.clientY);
  }

  function applyMove(clientX, clientY){
    const dx = clientX - startX;
    const dy = clientY - startY;

    let left = startLeft + dx;
    let top  = startTop  + dy;

    const pad = 8;
    const maxLeft = window.innerWidth - controls.offsetWidth - pad;
    const maxTop  = window.innerHeight - controls.offsetHeight - pad - FOOTER_HEIGHT;

    left = clamp(left, pad, Math.max(pad, maxLeft));
    top  = clamp(top, HEADER_HEIGHT + pad, Math.max(HEADER_HEIGHT + pad, maxTop));

    controls.style.left = left + 'px';
    controls.style.top  = top  + 'px';
  }

  function onMouseMove(e){ if (!dragging) return; e.preventDefault(); applyMove(e.clientX, e.clientY); }
  function onTouchMove(e){
    if (!dragging || !e.touches || e.touches.length === 0) return;
    e.preventDefault(); const t = e.touches[0]; applyMove(t.clientX, t.clientY);
  }

  function stopDrag(){
    dragging = false;
    document.removeEventListener('mousemove', onMouseMove);
    document.removeEventListener('mouseup', onMouseUp);
    document.removeEventListener('touchmove', onTouchMove);
    document.removeEventListener('touchend', onTouchEnd);
  }
  function onMouseUp(){ stopDrag(); }
  function onTouchEnd(){ stopDrag(); }

  dragTab.addEventListener('mousedown', onMouseDown, { passive: false });
  dragTab.addEventListener('touchstart', onTouchStart, { passive: false });
})();

/* ===================== COLLAPSE панели: анимируем высоту «стекла» ===================== */
(function enableCollapse(){
  const pad = 12;
  const tabVisible = 32;
  let baseLeft = 0, baseTop = 0;
  let lastExpandedHeightPx = null;

  function setTabIcon() {
    if (controls.classList.contains('collapsed')) { collapseTab.textContent = '‹'; collapseTab.title = 'Show'; }
    else { collapseTab.textContent = '›'; collapseTab.title = 'Hide'; }
  }
  function getDefaultLeft() { return window.innerWidth - controls.offsetWidth - DEFAULT_OFFSET_RIGHT; }
  function getDefaultTop()  { return DEFAULT_OFFSET_TOP; }

  function syncCollapsedPosition() {
    if (!controls.classList.contains('collapsed')) return;
    const targetLeft = window.innerWidth - pad - tabVisible;
    const targetTop  = DEFAULT_OFFSET_TOP;
    const dx = targetLeft - baseLeft;
    const dy = targetTop  - baseTop;
    controls.style.transform = `translate(${dx}px, ${dy}px)`;
  }

  // аккуратно меряем «естественную» высоту стекла без анимаций
  function measureExpandedHeight(){
    const prevH = controlsGlass.style.height;
    const prevT = controlsGlass.style.transition;
    controlsGlass.style.transition = 'none';
    controlsGlass.style.height = 'auto';
    const h = controlsGlass.offsetHeight;
    controlsGlass.style.height = prevH;
    controlsGlass.style.transition = prevT;
    return h;
  }

  /* 1) СВЕРНУТЬ: стекло -> высота кнопки; затем «линия» уезжает вправо */
  async function collapse() {
    if (controls.classList.contains('collapsed')) return;

    // 1.1 зафиксировать текущие координаты панели
    const rect = controls.getBoundingClientRect();
    baseLeft = rect.left;
    baseTop  = rect.top;

    controls.style.position = 'fixed';
    controls.style.left = baseLeft + 'px';
    controls.style.top  = baseTop  + 'px';
    controls.style.right = 'auto';
    controls.style.transform = 'translate(0,0)';

    // 1.2 меряем целевую «большую» высоту (на будущее разворачивание)
    lastExpandedHeightPx = measureExpandedHeight();

    // фиксируем стартовую высоту и начинаем схлопывать
    controlsGlass.style.height = `${lastExpandedHeightPx}px`;
    await new Promise(r => requestAnimationFrame(r));
    controlsBody.classList.add('content-hidden');
    controlsGlass.classList.add('glass-collapsed');

    const collapsedH = collapseTab.getBoundingClientRect().height || 32;
    controlsGlass.style.height = `${collapsedH}px`;

    // дождаться конца анимации высоты
    await waitTransition(controlsGlass);

    // 1.3 теперь увозим «линию» к правому краю
    controls.classList.add('collapsed');
    setTabIcon();
    requestAnimationFrame(syncCollapsedPosition);
  }

  /* 2) РАЗВЕРНУТЬ: СНАЧАЛА «линия» возвращается в исходную точку, ПОТОМ стекло разжимается */
  async function expand() {
    if (!controls.classList.contains('collapsed')) return;

    const defLeft = getDefaultLeft();
    const defTop  = getDefaultTop();

    // 2.1 линия возвращается на место
    controls.style.left = defLeft + 'px';
    controls.style.top  = defTop  + 'px';
    controls.style.right = 'auto';

    const cur = controls.getBoundingClientRect();
    const backDx = cur.left - defLeft;
    const backDy = cur.top  - defTop;
    controls.style.transform = `translate(${backDx}px, ${backDy}px)`;
    await new Promise(r => requestAnimationFrame(r));
    controls.style.transform = 'translate(0,0)';
    await waitTransition(controls);

    // === закрепляем панель за правым краем и сбрасываем состояние
    controls.style.transform = '';                 // очистить inline-transform
    controls.style.left = 'auto';                  // больше не фиксируем слева
    controls.style.right = DEFAULT_OFFSET_RIGHT + 'px'; // 30px от правого края по умолчанию
    baseLeft = getDefaultLeft();                   // обновим базовые координаты
    baseTop  = getDefaultTop();
    userMoved = false;                             // считаем, что пользовательское положение сброшено

    // 2.2 выключаем состояние collapsed (линия уже на месте)
    controls.classList.remove('collapsed');
    setTabIcon();

    // 2.3 показываем контент, но высота пока остаётся «тонкой»

    // целевая высота — сохранённая (или пересчитаем на всякий случай)
    const targetH = lastExpandedHeightPx || measureExpandedHeight();

    // убеждаемся, что стартовая высота = высоте кнопки
    const collapsedH = collapseTab.getBoundingClientRect().height || 32;
    controlsGlass.style.height = `${collapsedH}px`;

    // 2.4 запускаем ЕДИНСТВЕННУЮ анимацию высоты
    await new Promise(r => requestAnimationFrame(r));
    controlsGlass.style.height = `${targetH}px`;
    await waitTransition(controlsGlass);

    // 2.4.1 ТЕПЕРЬ показываем контент и плавно проявляем
    controlsBody.classList.remove('content-hidden');
    controlsBody.classList.add('fade-start');
    await new Promise(r => requestAnimationFrame(r));
    controlsBody.classList.remove('fade-start');

    // 2.5 чистим временные стили/классы
    controlsGlass.style.height = '';           // auto
    controlsGlass.classList.remove('glass-collapsed');
  }

  collapseTab.addEventListener('click', () => {
    if (controls.classList.contains('collapsed')) expand(); else collapse();
  });

  window.addEventListener('resize', syncCollapsedPosition);
  setTabIcon();

  // экспорт для отладки
  window.__cartopiaToggle = { collapse, expand };
})();

/* ===================== Resize: держим панель видимой ===================== */
(function keepPanelVisibleOnResize(){
  function clamp(v, a, b){ return Math.min(Math.max(v, a), b); }
  window.addEventListener('resize', () => {
    if (controls.classList.contains('collapsed')) return;
    if (userMoved) {
      const pad = 8;
      const rect = controls.getBoundingClientRect();
      const maxLeft = window.innerWidth  - controls.offsetWidth  - pad;
      const maxTop  = window.innerHeight - controls.offsetHeight - pad - FOOTER_HEIGHT;
      const left = clamp(rect.left, pad, Math.max(pad, maxLeft));
      const top  = clamp(rect.top,  HEADER_HEIGHT + pad, Math.max(HEADER_HEIGHT + pad, maxTop));
      controls.style.position = 'fixed';
      controls.style.left = left + 'px';
      controls.style.top  = top  + 'px';
      controls.style.right = 'auto';
      controls.style.transform = 'translate(0,0)';
    } else {
      const defTop  = DEFAULT_OFFSET_TOP;
      controls.style.position = 'fixed';
      controls.style.top  = defTop + 'px';
      controls.style.right = DEFAULT_OFFSET_RIGHT + 'px';
      controls.style.left = 'auto';
      controls.style.transform = 'translate(0,0)';
    }
  });
})();

/* ===================== Карта/селектор ===================== */
function updateAreaInfo() {
  const side = selectionSize;
  const area = side * side;
  const sideStr = Number.isFinite(side) ? side.toLocaleString() : String(side);
  const areaStr = Number.isFinite(area) ? area.toLocaleString() : String(area);
  areaInfo.innerHTML = side >= 1_000_000
    ? `Approx. area: ${sideStr} × ${sideStr} m²<br>= ${areaStr} m²`
    : `Approx. area: ${sideStr} × ${sideStr} m² = ${areaStr} m²`;
}
function getMapScreenCenterLatLng() {
  const mapSize = map.getSize();
  const centerPx = L.point(mapSize.x / 2, mapSize.y / 2);
  return map.containerPointToLatLng(centerPx);
}
function updateBoxAndCenter(){ if(!selecting) return; centerLatLng = getMapScreenCenterLatLng(); updateBox(); updateCenterSquare(); }
function updateBox(){
  if(!centerLatLng || !selecting) return;
  const lat = clampLat(centerLatLng.lat);
  const mpp = 40075016.686 * Math.cos(lat * Math.PI/180) / (256 * Math.pow(2, map.getZoom()));
  const px = selectionSize / mpp;
  box.style.width = px + 'px';
  box.style.height = px + 'px';
  box.style.left = `calc(50% - ${px/2}px)`;
  box.style.top  = `calc(50% - ${px/2}px)`;
}
function updateCenterSquare(){
  if(!centerLatLng || !selecting){ if(centerSquare){ map.removeLayer(centerSquare); centerSquare=null; } return; }
  const cl = clampLat(centerLatLng.lat);
  const lat10m = 10/111320, lng10m = 10/(111320*Math.cos(cl*Math.PI/180));
  const b = [[cl-lat10m/2, centerLatLng.lng-lng10m/2],[cl+lat10m/2, centerLatLng.lng+lng10m/2]];
  if(centerSquare) centerSquare.setBounds(b); else centerSquare = L.rectangle(b,{color:"red",weight:2,fillOpacity:0.5}).addTo(map);
}
map.on('zoom move', updateBoxAndCenter);
toggleBtn.onclick = () => { selecting = !selecting; toggleBtn.textContent = selecting ? 'Cancel selection' : 'Select area'; confirmBtn.disabled = !selecting; box.style.display = selecting ? 'block' : 'none'; if (selecting) updateBoxAndCenter(); else { centerLatLng=null; updateCenterSquare(); } };
sizeInput.addEventListener('input', () => {
  const v = parseInt(sizeInput.value,10);
  if(!isNaN(v)){
    selectionSize = v;
    updateAreaInfo();
    if (selecting){ animateSelectionResize(); updateBoxAndCenter(); }
  }
});

sizeInput.addEventListener('blur', () => {
  let v = parseInt(sizeInput.value,10);
  if(isNaN(v)) v = 200;
  v = Math.min(Math.max(v,200),20000000);
  sizeInput.value = v; selectionSize = v; updateAreaInfo();
  if (selecting){ animateSelectionResize(); updateBoxAndCenter(); }
});
searchBtn.onclick = async () => {
  const q = searchInput.value.trim(); if(!q) return;
  try{
    const url=`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${encodeURIComponent(q)}`;
    const r = await fetch(url,{headers:{"Accept-Language":"en"}});
    const d = await r.json();
    if(Array.isArray(d)&&d.length>0){
      const lat=clampLat(parseFloat(d[0].lat)); const lon=parseFloat(d[0].lon);
      map.setView([lat,lon],15);
    } else alert('Not found!');
  }catch(e){ console.error(e); alert('Search error!'); }
};
searchInput.addEventListener('keydown', e => { if (e.key === 'Enter') searchBtn.onclick(); });

/* ===================== Построение «монолитного» Overpass-запроса ===================== */

function buildMonolithicQuery(bboxStr, includeAllNodes){
  return `
[out:json][timeout:180];
(
  /* Дороги и связанные элементы */
  nwr["highway"](${bboxStr});
  node["highway"="crossing"](${bboxStr});
  way ["footway"="crossing"](${bboxStr});
  node["highway"="traffic_signals"](${bboxStr});
  nwr["traffic_calming"](${bboxStr});
  nwr["kerb"](${bboxStr});
  nwr["barrier"](${bboxStr});
  
  /* Площади-дороги (area:highway и пед. площади) */
  nwr["area:highway"](${bboxStr});
  relation["type"="multipolygon"]["area:highway"](${bboxStr});
  relation["type"="multipolygon"]["highway"="pedestrian"](${bboxStr});
  way["highway"="pedestrian"]["area"="yes"](${bboxStr});

  /* Рельсы, платформы, ОТ */
  nwr["railway"](${bboxStr});
  nwr["railway"~"^(platform|platform_edge|platform_section|halt|tram_stop|station)$"](${bboxStr});
  nwr["public_transport"~"^(platform|stop_position|station)$"](${bboxStr});
  relation["type"="public_transport"]["public_transport"="stop_area"](${bboxStr});
  node["highway"="bus_stop"](${bboxStr});

  /* Вода/берега */
  nwr["waterway"](${bboxStr});
  nwr["water"](${bboxStr});
  relation["type"="multipolygon"]["waterway"="riverbank"](${bboxStr});

  /* Авиа + канатки */
  nwr["aeroway"](${bboxStr});
  nwr["aeroway"="apron"](${bboxStr});                 /* аэроперроны */
  nwr["aerialway"](${bboxStr});

  /* Землепользование, покрытие, природные */
  nwr["landuse"](${bboxStr});
  nwr["landcover"](${bboxStr});
  nwr["natural"](${bboxStr});
  nwr["wetland"](${bboxStr});
  nwr["leisure"](${bboxStr});
  nwr["military"](${bboxStr});

  /* Здания/indoor */
  nwr["building"](${bboxStr});
  nwr["building:part"](${bboxStr});
  relation["type"="building"](${bboxStr});
  nwr["entrance"](${bboxStr});
  nwr["door"](${bboxStr});
  nwr["indoor"](${bboxStr});
  nwr["level"](${bboxStr});
  nwr["level:ref"](${bboxStr});

  /* POI и инфраструктура */
  nwr["amenity"](${bboxStr});
  nwr["shop"](${bboxStr});
  nwr["office"](${bboxStr});
  nwr["craft"](${bboxStr});
  nwr["tourism"](${bboxStr});
  nwr["sport"](${bboxStr});
  nwr["healthcare"](${bboxStr});
  nwr["emergency"](${bboxStr});
  nwr["historic"](${bboxStr});
  nwr["information"](${bboxStr});
  nwr["man_made"](${bboxStr});
  nwr["power"](${bboxStr});
  nwr["pipeline"](${bboxStr});
  nwr["telecom"](${bboxStr});

  /* Границы/нас.пункты */
  nwr["boundary"](${bboxStr});
  nwr["place"](${bboxStr});

  /* «как раньше»: ВСЕ узлы — осторожно! */
  ${includeAllNodes ? `node(${bboxStr});` : ''}
);
/* Рекурсивно подтянуть все зависимые элементы (узлы для путей, члены отношений) */
(._; >>;);
out body geom;
>;
out skel qt;
`.trim();
}

/* ===================== HTTP к Overpass (один удачный вызов) ===================== */
async function overpassPostOnce(url, query){
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), OVERPASS_TIMEOUT_MS);
  try{
    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "Accept": "application/json"
      },
      body: `data=${encodeURIComponent(query)}`,
      signal: controller.signal
    });
    clearTimeout(timer);
    if (!res.ok) {
      const txt = await res.text().catch(()=> '');
      const err = new Error(`HTTP ${res.status} ${res.statusText} ${txt.slice(0,200)}`);
      err.status = res.status;
      throw err;
    }
    const json = await res.json().catch(()=> ({}));
    return { ok:true, data: (json && Array.isArray(json.elements)) ? json : { elements: [] } };
  }catch(error){
    clearTimeout(timer);
    return { ok:false, error };
  }
}

/** Пробуем по очереди эндпоинты, пока не получим один успешный ответ */
async function tryOverpassEndpoints(query){
  let lastErr = null;
  for (let i=0;i<OVERPASS_ENDPOINTS.length;i++){
    const url = OVERPASS_ENDPOINTS[i];
    setStatus(`Querying Overpass (${i+1}/${OVERPASS_ENDPOINTS.length})…`);
    const r = await overpassPostOnce(url, query);
    if (r.ok) { setStatus(`Overpass OK via ${new URL(url).host}`); return { overpass_status:"ok", features:r.data }; }
    lastErr = r.error;
  }
  console.warn('All Overpass endpoints failed:', lastErr);
  setStatus('Overpass temporarily unavailable.');
  return { overpass_status:"failed", features:{ elements: [] } };
}

/* ===================== Confirm: вычисление bbox + один запрос + сохранение ===================== */
confirmBtn.onclick = async () => {
  if (!selecting) return;

  const size = selectionSize;
  const C = getMapScreenCenterLatLng();
  const lat = clampLat(C.lat);
  const lng = C.lng;

  const latMeters = 111320;
  const lngMeters = 111320 * Math.cos(lat * Math.PI / 180);
  const dLat = (size/2)/latMeters;
  const dLng = (size/2)/lngMeters;

  let south = lat - dLat, north = lat + dLat, west = lng - dLng, east = lng + dLng;
  if (south < SOUTH_LIMIT) south = SOUTH_LIMIT;
  if (north > NORTH_LIMIT) north = NORTH_LIMIT;

  const bboxStr = `${south},${west},${north},${east}`;

  // Предупреждение на очень больших окнах (данных будет ОЧЕНЬ много)
  if (size > 10000) {
    const km = (size/1000).toFixed(1);

  
    let sure = false;
    await showGlassDialog({
      title: 'Large area',
      message: `You selected a very large area: ${km} km.<br><br>One huge Overpass query — may be slow or denied.`,
      okText: 'Continue'
    }).then(() => sure = true);
    if (!sure) return;
  }

  // На супер-больших окнах выключим «все узлы» — иначе практически гарантированный отказ.
  const includeAllNodes = size <= 10000;

  const query = buildMonolithicQuery(bboxStr, includeAllNodes);

  let overpass_status = "ok";
  let features = { elements: [] };

  confirmBtn.disabled = true; toggleBtn.disabled = true;
  setStatus('Fetching OSM data (single request)…');

  try{
    const res = await tryOverpassEndpoints(query);
    overpass_status = res.overpass_status;
    features = res.features;
  }catch(e){
    console.error('Collector exception:', e);
    overpass_status = "failed";
    features = { elements: [] };
  }finally{
    confirmBtn.disabled = false; toggleBtn.disabled = false;
  }

 const payload = {
  center: { lat, lng },
  sizeMeters: size,
  bbox: { south, north, west, east },
  overpass_status,
  features,
  // Говорим модулю «не скачивай онлайн» — используем локальный файл, который сделает сервер.
  olm: { mode: 'file' },

  // (опционально) те же тюнинги сглаживания
  tuning: { surfaceBlurIters: 15, surfaceBlurMinMajority: 4 }
};


  try {
    
    const r = await fetch('http://localhost:4567/save-coords', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const txt = await r.text().catch(()=> '');
    if (!r.ok) {
      console.error('Backend error:', r.status, txt);
      await showGlassDialog({
        title: `Backend error ${r.status}`,
        message: txt ? txt : 'See console for details.',
        okText: 'Close'
      });
      return;
    }
      await showGlassDialog({
        title: 'Saved!',
        message: overpass_status === "ok"
          ? 'Your selection has been saved.'
          : 'Saved! (Overpass failed — payload is empty)',
        okText: 'OK'
      });
  } catch (e2) {
    console.error(e2);
    await showGlassDialog({
      title: 'Save failed',
      message: 'Could not save selection to backend (network error).',
      okText: 'Close'
    });
  } finally {
    setStatus('');
  }
};


/* ===================== Go ===================== */
updateAreaInfo();

/* ВХОДНЫЕ АНИМАЦИИ ПРИ ЗАГРУЗКЕ: хедер/футер и панель просто появляются */
requestAnimationFrame(() => {
  setTimeout(() => {
    document.body.classList.add('page-ready');
    // пересчитать размеры контейнера карты, т.к. родителю задали высоту
    map.invalidateSize(true);
  }, 40);
});


/* ========= Плавный «переезд» футера при смене макета (FLIP) ========= */
(function animateFooterFLIP(){
  const footer = document.getElementById('footer');
  if (!footer) return;

  // что именно двигаем — два блока внутри футера
  const items = Array.from(footer.querySelectorAll('.footer-text, .footer-logos'));

  const measure = () => new Map(items.map(el => [el, el.getBoundingClientRect()]));

  function play(first, last){
    items.forEach(el => {
      const f = first.get(el), l = last.get(el);
      const dx = f.left - l.left;
      const dy = f.top  - l.top;
      if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5){
        el.style.transition = 'none';
        el.style.transform  = `translate(${dx}px, ${dy}px)`;
        el.style.willChange = 'transform, opacity';
        el.style.opacity    = '0.94';
      }
    });

    requestAnimationFrame(() => {
      items.forEach(el => {
        el.style.transition = 'transform 420ms cubic-bezier(.22,.61,.36,1), opacity 320ms ease';
        el.style.transform  = 'translate(0,0)';
        el.style.opacity    = '1';
        el.addEventListener('transitionend', () => {
          el.style.transition = '';
          el.style.transform  = '';
          el.style.willChange = '';
          el.style.opacity    = '';
        }, { once:true });
      });
    });
  }

  function updateClasses({ noAnim } = {}){
    const wantCompact = window.innerWidth <= 760;
    const wantTiny    = window.innerWidth <= 420;

    const hasCompact = footer.classList.contains('compact');
    const hasTiny    = footer.classList.contains('tiny');

    const changed = (wantCompact !== hasCompact) || (wantTiny !== hasTiny);
    if (!changed) return;

    if (noAnim){
      footer.classList.toggle('compact', wantCompact);
      footer.classList.toggle('tiny',    wantTiny);
      return;
    }

    // FLIP: измерили, применили классы, измерили, сыграли анимацию
    const first = measure();
    footer.classList.toggle('compact', wantCompact);
    footer.classList.toggle('tiny',    wantTiny);
    const last  = measure();
    play(first, last);
  }

  // первичная установка без анимации
  updateClasses({ noAnim: true });

  // анимируем только на переходах через пороги ширины
  let raf = null;
  window.addEventListener('resize', () => {
    if (raf) return;
    raf = requestAnimationFrame(() => { raf = null; updateClasses({ noAnim: false }); });
  });
})();


/* ========= Клик по логотипу: «майнкрафт» 5s (скриншот + пикселизация) ========= */
(function setupMinecraftOnly(){
  const logo = document.querySelector('#header .brand-logo');
  const app  = document.getElementById('appRoot');
  if (!logo || !app) return;

  const FADE_IN  = 1000;  // 1s
  const HOLD     = 3000;  // 3s
  const FADE_OUT = 1000;  // 1s

  async function makeScreenshot() {
    // маленький scale => крупные «квадраты» и быстрее рендер
    const scale = 0.15;
    return html2canvas(app, {
      backgroundColor: null,
      useCORS: true,
      scale
    });
  }

  function blockAllEvents(el){
    const stop = e => { e.stopPropagation(); e.preventDefault(); };
    const types = ['click','mousedown','mouseup','mousemove','touchstart','touchmove','touchend','pointerdown','pointerup','pointermove','wheel','contextmenu','keydown'];
    types.forEach(t => el.addEventListener(t, stop, { passive:false, capture:true }));
    return () => types.forEach(t => el.removeEventListener(t, stop, { capture:true }));
  }

  async function runPixelate(){
    // «нажим» логотипа
    logo.classList.add('pressed');
    setTimeout(() => logo.classList.remove('pressed'), 150);

    let canvas;
    try {
      canvas = await makeScreenshot();
    } catch (e) {
      console.error('html2canvas failed:', e);
      return; // если скрин не получился — выходим без блокировок
    }

    // создаём оверлей-картинку
    const wrap = document.createElement('div');
    wrap.className = 'fx-pixel-overlay';
    wrap.tabIndex = 0;

    const img = document.createElement('img');
    img.className = 'fx-pixel-overlay__img';
    img.alt = '';
    img.src = canvas.toDataURL('image/png'); // рендерим скрин в картинку
    wrap.appendChild(img);

    document.body.appendChild(wrap);

    // блокируем ввод + прячем «живую» страницу от фокуса
    const unblock = blockAllEvents(wrap);
    app.setAttribute('aria-hidden','true');
    app.setAttribute('inert','');

    // плавно показать
    requestAnimationFrame(() => wrap.classList.add('visible'));

    // подержать и плавно убрать
    setTimeout(() => {
      wrap.classList.remove('visible');
      setTimeout(() => {
        unblock();
        wrap.remove();
        app.removeAttribute('inert');
        app.removeAttribute('aria-hidden');
      }, FADE_OUT);
    }, FADE_IN + HOLD);
  }

  // запуск по клику и по клавиатуре
  logo.addEventListener('click', runPixelate);
  logo.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); runPixelate(); }
  });
})();