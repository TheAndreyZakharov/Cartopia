package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WindTurbineGenerator {

    // ===== Конфиг (крупные турбины — как было) =====
    private static final int DEFAULT_HEIGHT = 100;         // дефолт высота башни
    private static final int BLADE_LENGTH_DEFAULT = 50;    // дефолтная длина лопасти (радиус) для крупных, если нет diameter
    private static final int TOWER_RADIUS   = 3;           // диаметр 7 ⇒ радиус 3
    private static final Block MATERIAL     = Blocks.SMOOTH_QUARTZ; // материал

    // Гондола (крупная, «смотрит» на юг)
    private static final int NAC_LEN  = 15;   // длина по Z
    private static final int NAC_WID  = 7;    // ширина по X
    private static final int NAC_HEI  = 7;    // высота по Y
    private static final int NAC_FRONT_PROTRUSION = 3; // сколько вперёд от оси башни
    private static final int NAC_SHIFT_SOUTH      = 2; // смещение всей гондолы на юг

    // Нос-конический переход (крупная)
    private static final int HUB_CONE_LEN   = 4;  // длина по +Z
    private static final int HUB_CONE_R0    = 3;  // стартовый радиус сечения (в плоскости X–Y)

    // ===== Режим «маленький ротор» (тонкие линии) =====
    // Порог по диаметру ротора (м), ниже/равно — считаем маленьким и рисуем «мерседес» без объёма
    private static final double SMALL_ROTOR_DIAM_MAX_M = 6.0;

    // Ножка маленькой турбины: квадрат 2×2
    @SuppressWarnings("unused")
    private static final int SMALL_TOWER_SIZE = 2; // 2×2

    // Гондола маленькой: 3×3×5 (X×Y×Z), чуть смещена вперёд
    private static final int SMALL_NAC_WID  = 3;
    private static final int SMALL_NAC_HEI  = 3;
    private static final int SMALL_NAC_LEN  = 5;
    private static final int SMALL_NAC_FRONT_PROTRUSION = 1;
    private static final int SMALL_NAC_SHIFT_SOUTH      = 1;

    // Ограничители длин лопастей (в блоках) при вычислении из диаметра
    private static final int MIN_BLADE_LEN = 2;     // чтобы мелкое хоть видно было
    private static final int MAX_BLADE_LEN = 128;   // безопасный предел от странных тегов

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public WindTurbineGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ===== Вещание =====
    private static void broadcast(ServerLevel level, String msg) {
        try {
            if (level.getServer() != null) {
                for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                    p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
                }
            }
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }

    // ===== Типы =====
    private static final class PolyXZ {
        @SuppressWarnings("unused")
        final int[] xs, zs; final int n;
        final int cx, cz;
        PolyXZ(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            long sx = 0, sz = 0;
            for (int i=0;i<n;i++){ sx += xs[i]; sz += zs[i]; }
            this.cx = (int)Math.round(sx / (double)n);
            this.cz = (int)Math.round(sz / (double)n);
        }
    }
    private static final class Turbine {
        final int x, z;
        int heightBlocks;           // высота башни в блоках
        @SuppressWarnings("unused")
        Double rotorDiameterM;      // диаметр ротора (м), если есть
        boolean smallRotor;         // режим маленького ротора
        int bladeLenBlocks;         // длина (радиус) лопасти в блоках, уже посчитанный
        Turbine(int x, int z) { this.x = x; this.z = z; }
    }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "WindTurbineGenerator: coords == null — skipping.");
            return;
        }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "WindTurbineGenerator: no center/bbox — skipping.");
            return;
        }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        List<Turbine> list = new ArrayList<>();

        // ===== Сбор признаков со стримингом =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, list,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "WindTurbineGenerator: no coords.features — skipping.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "WindTurbineGenerator: features.elements are empty — skipping.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), list,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "WindTurbineGenerator: error reading features: " + ex.getMessage());
        }

        if (list.isEmpty()) {
            broadcast(level, "WindTurbineGenerator: no suitable wind turbines found — done.");
            return;
        }

        // ===== Рендер =====
        int done = 0;
        for (Turbine t : list) {
            try {
                if (t.x<minX||t.x>maxX||t.z<minZ||t.z>maxZ) continue;
                if (t.smallRotor) renderSmallTurbine(t); else renderBigTurbine(t);
            } catch (Exception ex) {
                broadcast(level, "WindTurbineGenerator: error at ("+t.x+","+t.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, list.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, list.size()));
                broadcast(level, "Wind turbines: ~" + pct + "%");
            }
        }
    }

    // ===== Разбор фич =====
    private void collectFeature(JsonObject e, List<Turbine> out,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isWindTurbine(tags)) return;

        String type = optString(e,"type");
        int tx, tz;

        if ("node".equals(type)) {
            if (e.has("lat") && e.has("lon")) {
                int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                tx = xz[0]; tz = xz[1];
            } else {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                tx = xz[0]; tz = xz[1];
            }
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 1) return;
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            PolyXZ poly = new PolyXZ(xs, zs);
            tx = poly.cx; tz = poly.cz;
        } else return;

        Turbine t = new Turbine(tx, tz);
        t.heightBlocks   = extractHeightBlocks(tags);

        Double diam = extractRotorDiameterMeters(tags);
        t.rotorDiameterM = diam;

        // Вычисляем длину лопасти (радиус) в блоках
        if (diam != null && diam > 0) {
            int byDiam = clampInt((int)Math.round(diam / 2.0), MIN_BLADE_LEN, MAX_BLADE_LEN);
            t.bladeLenBlocks = byDiam;
            t.smallRotor = (diam <= SMALL_ROTOR_DIAM_MAX_M);
        } else {
            t.bladeLenBlocks = BLADE_LENGTH_DEFAULT;
            t.smallRotor = false; // без диаметра — считаем «крупной» (как было)
        }

        out.add(t);
    }

    private static boolean isWindTurbine(JsonObject t) {
        String gm = optString(t, "generator:method");
        if (gm != null && gm.equalsIgnoreCase("wind_turbine")) return true;

        String source = optString(t, "generator:source");
        if (source != null && source.equalsIgnoreCase("wind")) return true;

        String power = optString(t, "power");
        if (power != null && power.equalsIgnoreCase("generator")) {
            String gtype = optString(t, "generator:type");
            if (gtype != null && gtype.toLowerCase(Locale.ROOT).contains("wind")) return true;
        }
        return false;
    }

    private int extractHeightBlocks(JsonObject t) {
        String[] keys = new String[]{
                "height", "tower:height", "hub:height"
        };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null) {
                Integer num = parseFirstInt(v);
                if (num != null && num > 0) return num;
            }
        }
        return DEFAULT_HEIGHT;
    }

    private Double extractRotorDiameterMeters(JsonObject t) {
        String[] keys = new String[] {
                "rotor:diameter", "rotor:diametre", "rotor_diameter", "rotor:radius" // radius будет трактоваться как диаметр≈2r? но оставим как есть ниже
        };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null) {
                Double d = parseFirstDouble(v);
                if (d != null && d > 0) {
                    if ("rotor:radius".equals(k)) return d * 2.0; // если вдруг радиус — конвертируем в диаметр
                    return d;
                }
            }
        }
        return null;
    }

    private static Integer parseFirstInt(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && !Character.isDigit(s.charAt(i))) i++;
        if (i == len) return null;
        int j = i;
        while (j < len && (Character.isDigit(s.charAt(j)))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    private static Double parseFirstDouble(String s) {
        // простенький парсинг первой [цифры][.цифры] в строке
        int len = s.length();
        int i = 0;
        while (i < len && !((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.' || s.charAt(i) == '-')) i++;
        if (i == len) return null;
        int j = i+1;
        boolean dotSeen = (s.charAt(i)=='.');
        while (j < len) {
            char c = s.charAt(j);
            if ((c >= '0' && c <= '9') || (!dotSeen && c == '.')) {
                if (c == '.') dotSeen = true;
                j++;
            } else break;
        }
        try { return Double.parseDouble(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    private static int clampInt(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }

    // ===== Рендер крупной турбины (как было, но с переменной длиной лопасти) =====
    private void renderBigTurbine(Turbine t) {
        final int H = Math.max(2, t.heightBlocks);
        final int bladeLen = t.bladeLenBlocks > 0 ? t.bladeLenBlocks : BLADE_LENGTH_DEFAULT;

        // Башня-цилиндр (каждая колонка «сидит» на своём рельефе)
        List<int[]> disk = diskOffsets(TOWER_RADIUS);
        int yTopMax = Integer.MIN_VALUE;
        for (int[] d : disk) {
            int x = t.x + d[0], z = t.z + d[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            int yStart = yBase + 1;
            int yEnd   = yStart + H - 1;
            for (int y = yStart; y <= yEnd; y++) setBlockSafe(x, y, z, MATERIAL);
            yTopMax = Math.max(yTopMax, yEnd + 1);
        }
        if (yTopMax == Integer.MIN_VALUE) return;

        // Гондола 15×7×7, скос «хвоста»
        final int halfW = NAC_WID / 2;
        final int nacMaxZ = t.z + NAC_FRONT_PROTRUSION + NAC_SHIFT_SOUTH; // южная грань
        final int nacMinZ = nacMaxZ - (NAC_LEN - 1);                       // северная грань

        for (int z = nacMinZ; z <= nacMaxZ; z++) {
            int backK = z - nacMinZ; // 0 у хвоста
            int topCut  = Math.max(0, 2 - backK);
            int sideCut = Math.max(0, 2 - backK);

            int allowHalfW = Math.max(0, halfW - sideCut);
            int minX = t.x - allowHalfW;
            int maxX = t.x + allowHalfW;

            for (int x = minX; x <= maxX; x++) {
                int yBaseLocal = localTopY(t, x, z, H);
                if (yBaseLocal == Integer.MIN_VALUE) continue;
                int yMin = yBaseLocal;
                int yMax = yBaseLocal + NAC_HEI - 1 - topCut;
                for (int y = yMin; y <= yMax; y++) setBlockSafe(x, y, z, MATERIAL);
            }
        }

        // Ступица/нос
        final int hubX = t.x;
        final int hubZ = nacMaxZ + 1;
        final int hubY = localTopY(t, t.x, nacMaxZ, H) + NAC_HEI / 2;

        // Плитка 3×3
        fillSquareXY(hubX, hubY, hubZ, 1, MATERIAL);
        // Конус
        buildHorizontalConeZ(hubX, hubY, hubZ + 1, HUB_CONE_LEN, HUB_CONE_R0, MATERIAL);

        // Три объёмные лопасти (мерседес)
        drawBladeTapered3D(hubX, hubY, hubZ, bladeLen, Math.toRadians(90));   // вверх
        drawBladeTapered3D(hubX, hubY, hubZ, bladeLen, Math.toRadians(210));  // вниз-влево
        drawBladeTapered3D(hubX, hubY, hubZ, bladeLen, Math.toRadians(330));  // вниз-вправо
    }

    // ===== Рендер маленькой турбины (тонкие линии, гондола 3×3×5, ножка 2×2) =====
    private void renderSmallTurbine(Turbine t) {
        final int H = Math.max(2, t.heightBlocks);
        final int bladeLen = clampInt(t.bladeLenBlocks > 0 ? t.bladeLenBlocks : 3, MIN_BLADE_LEN, 48);

        // Ножка 2×2: колонки сидят на местном рельефе, высота ровно H
        // Возьмём квадрат с углом в (t.x, t.z): {(0,0),(1,0),(0,1),(1,1)}
        int[][] sq = new int[][] { {0,0}, {1,0}, {0,1}, {1,1} };
        int yTopMax = Integer.MIN_VALUE;
        for (int[] o : sq) {
            int x = t.x + o[0], z = t.z + o[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            int yStart = yBase + 1;
            int yEnd   = yStart + H - 1;
            for (int y = yStart; y <= yEnd; y++) setBlockSafe(x, y, z, MATERIAL);
            yTopMax = Math.max(yTopMax, yEnd + 1);
        }
        if (yTopMax == Integer.MIN_VALUE) return;

        // Маленькая гондола 3×3×5, чуть смещена вперёд и на юг
        final int halfW = SMALL_NAC_WID / 2; // 1
        final int nacMaxZ = t.z + SMALL_NAC_FRONT_PROTRUSION + SMALL_NAC_SHIFT_SOUTH;
        final int nacMinZ = nacMaxZ - (SMALL_NAC_LEN - 1);

        for (int z = nacMinZ; z <= nacMaxZ; z++) {
            int minX = t.x - halfW;
            int maxX = t.x + halfW;
            for (int x = minX; x <= maxX; x++) {
                int yBaseLocal = localTopY(t, x, z, H);
                if (yBaseLocal == Integer.MIN_VALUE) continue;
                int yMin = yBaseLocal;
                int yMax = yBaseLocal + SMALL_NAC_HEI - 1;
                for (int y = yMin; y <= yMax; y++) setBlockSafe(x, y, z, MATERIAL);
            }
        }

        // Узел и центр лопастей
        final int hubX = t.x;
        final int hubZ = nacMaxZ + 1;
        final int hubY = localTopY(t, t.x, nacMaxZ, H) + SMALL_NAC_HEI / 2;

        // Маленькая центральная точка (1×1)
        setBlockSafe(hubX, hubY, hubZ, MATERIAL);

        // Три ТОНКИЕ лопасти «мерседес» (без объёма, толщина 1 блок)
        drawBladeLine2D(hubX, hubY, hubZ, bladeLen, Math.toRadians(90));   // вверх
        drawBladeLine2D(hubX, hubY, hubZ, bladeLen, Math.toRadians(210));  // вниз-влево
        drawBladeLine2D(hubX, hubY, hubZ, bladeLen, Math.toRadians(330));  // вниз-вправо
    }

    /** Локальный «верх башни» для (x,z): terrain+1+H. */
    private int localTopY(Turbine t, int x, int z, int H) {
        int yBase = terrainYFromCoordsOrWorld(x, z, null);
        if (yBase == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return yBase + 1 + H;
    }

    // ===== Лопасти =====

    /** Квадрат (2r+1)×(2r+1) в плоскости X–Y при фиксированном Z, центр (cx,cy). r=0 ⇒ 1×1, r=1 ⇒ 3×3, r=2 ⇒ 5×5. */
    private void fillSquareXY(int cx, int cy, int z, int r, Block block) {
        for (int ox = -r; ox <= r; ox++) {
            for (int oy = -r; oy <= r; oy++) {
                setBlockSafe(cx + ox, cy + oy, z, block);
            }
        }
    }

    /** Прямоугольный параллелепипед (2rXY+1)×(2rXY+1)×(2rZ+1), центр в (cx,cy,cz). */
    private void fillCubeCenteredZ(int cx, int cy, int cz, int rXY, int rZ, Block block) {
        for (int ox = -rXY; ox <= rXY; ox++) {
            for (int oy = -rXY; oy <= rXY; oy++) {
                for (int oz = -rZ; oz <= rZ; oz++) {
                    setBlockSafe(cx + ox, cy + oy, cz + oz, block);
                }
            }
        }
    }

    /** Лопасть 3D: у корня rXY=2 (5×5), rZ=1 (толщина 3), далее rXY=1,rZ=1, на концe rXY=0,rZ=0. */
    private void drawBladeTapered3D(int cx, int cy, int baseZ, int len, double angleRad) {
        double dx = Math.cos(angleRad);
        double dy = Math.sin(angleRad);

        int prevX = cx, prevY = cy, prevRxy = 2, prevRz = 1;

        for (int s = 0; s < len; s++) {
            int xi = cx + (int)Math.round(dx * s);
            int yi = cy + (int)Math.round(dy * s);

            int rxy, rz;
            if (s < 6) {                  // у корня
                rxy = 2; rz = 1;
            } else if (s < (int)(len * 0.7)) { // основная часть
                rxy = 1; rz = 1;
            } else {                       // кончик
                rxy = 0; rz = 0;
            }

            rasterLineZVariableCube(prevX, prevY, xi, yi, baseZ, prevRxy, rxy, prevRz, rz, MATERIAL);

            prevX = xi;
            prevY = yi;
            prevRxy = rxy;
            prevRz  = rz;
        }
    }

    /** Брезенхэм + линейная интерполяция rXY и rZ. На каждом пикселе кладём «куб» (сечение + толщина по Z). */
    private void rasterLineZVariableCube(int x0, int y0, int x1, int y1, int z, int rxy0, int rxy1, int rz0, int rz1, Block block) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int steps = Math.max(dx, dy);
        int step = 0;

        int x = x0, y = y0;
        while (true) {
            int rxy = (steps <= 0) ? rxy1 : rxy0 + (rxy1 - rxy0) * step / Math.max(1, steps);
            int rz  = (steps <= 0) ? rz1  :  rz0  + (rz1  -  rz0)  * step / Math.max(1, steps);
            fillCubeCenteredZ(x, y, z, rxy, rz, block);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
            step++;
        }
    }

    /** Тонкая лопасть (линия 1 блок толщиной) в плоскости X–Y при фиксированном Z. */
    private void drawBladeLine2D(int cx, int cy, int baseZ, int len, double angleRad) {
        double dx = Math.cos(angleRad);
        double dy = Math.sin(angleRad);

        int x0 = cx;
        int y0 = cy;
        int x1 = cx + (int)Math.round(dx * len);
        int y1 = cy + (int)Math.round(dy * len);

        rasterLineThin(x0, y0, x1, y1, baseZ, MATERIAL);
    }

    /** Обычный Брезенхэм, кладём по одному блоку на каждом шаге. */
    private void rasterLineThin(int x0, int y0, int x1, int y1, int z, Block block) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0, y = y0;
        while (true) {
            setBlockSafe(x, y, z, block);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    /** Горизонтальный «нос»-конус: по +Z, радиус плавно уменьшается с r0 до 0 (линейно по слоям). */
    private void buildHorizontalConeZ(int cx, int cy, int zStart, int len, int r0, Block block) {
        if (len <= 0 || r0 <= 0) return;

        for (int k = 0; k < len; k++) {
            double t  = (len == 1) ? 1.0 : (k / (double)(len - 1)); // 0..1
            double rf = r0 * (1.0 - t);                             // плавное сужение
            int r = Math.max(0, (int)Math.round(rf));

            if (r == 0 && k < len - 1) r = 1;

            int rr = r * r;
            int z  = zStart + k;
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r; oy++) {
                    if (ox*ox + oy*oy <= rr) {
                        setBlockSafe(cx + ox, cy + oy, z, block);
                    }
                }
            }
        }
    }

    // ===== Примитивы =====
    private List<int[]> diskOffsets(int r) {
        ArrayList<int[]> out = new ArrayList<>();
        int rr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx*dx + dz*dz <= rr + 1) out.add(new int[]{dx, dz});
            }
        }
        return out;
    }

    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }

    // ===== Высота рельефа =====
    private int terrainYFromCoordsOrWorld(int x, int z, Integer hintY) {
        try {
            if (store != null && store.grid != null && store.grid.inBounds(x, z)) {
                int gy = store.grid.groundY(x, z);
                if (gy != Integer.MIN_VALUE) return gy;
            }
        } catch (Throwable ignore) {}

        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                if (g.has("minX")) {
                    int minX = g.get("minX").getAsInt();
                    int minZ = g.get("minZ").getAsInt();
                    int w    = g.get("width").getAsInt();
                    int h    = g.get("height").getAsInt();
                    int ix = x - minX, iz = z - minZ;
                    if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz * w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== Утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        int x = (int)Math.round(centerX + dx);
        int z = (int)Math.round(centerZ + dz);
        return new int[]{x, z};
    }
}