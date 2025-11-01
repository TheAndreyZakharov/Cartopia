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

@SuppressWarnings("unused")
public class CraneGenerator {

    // ===== Материалы =====
    private static final Block CRANE_BLOCK   = Blocks.YELLOW_CONCRETE;  // каркас, балки, линии
    private static final Block CABIN_BLOCK   = Blocks.SMOOTH_QUARTZ;    // кабина кат.1
    private static final Block CABIN_GLASS   = Blocks.GLASS;            // стеклянная стенка
    private static final Block COUNTER_BLOCK = Blocks.STONE_BRICKS;     // противовес кат.1
    private static final Block GLOW          = Blocks.GLOWSTONE;        // светокамень из Недера

    // ===== Размеры / дефолты =====
    private static final int DEFAULT_HEIGHT  = 30; // блоков ~= метров
    private static final int DEFAULT_BOOM    = 20; // кат.1: длина стрелы

    // Кат.1 башня
    private static final int TOWER_SIDE      = 5;
    private static final int TOWER_HALF      = TOWER_SIDE/2; // 2

    private static final int BOOM_SEC_W      = 2;
    private static final int BOOM_SEC_H      = 2;
    private static final int BOOM_ANCHOR_DY  = 2;

    private static final int COUNTER_LEN     = 5;
    private static final int COUNTER_WH      = 4;

    private static final int CABIN_SIZE      = 3;

    // Кат.2 рама
    private static final int CAT2_SPAN       = 10; // расстояние между опорами по осям
    private static final int CAT2_HALF       = CAT2_SPAN/2; // 5
    private static final int POST_SIDE       = 3;  // опора 3×3 решёткой
    private static final int POST_HALF       = POST_SIDE/2; // 1
    private static final int HBEAM_SEC       = 3;  // сечение горизонтальной балки 3×3

    // Подсветка
    private static final int LIGHT_STEP      = 5;  // каждые 5 блоков

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public CraneGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ===== Вещалка =====
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
    private static final class Crane {
        final int x, z;
        int height;   // высота опоры/башни
        int boomLen;  // только для кат.1
        int cat;      // 1 или 2
        Crane(int x, int z, int height, int boomLen, int cat) {
            this.x = x; this.z = z; this.height = height; this.boomLen = boomLen; this.cat = cat;
        }
    }

    // ===== Запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "Cranes: coords == null — пропуск."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "Cranes: нет center/bbox — пропуск."); return; }

        final double centerLat  = center.get("lat").getAsDouble();
        final double centerLng  = center.get("lng").getAsDouble();
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

        List<Crane> list1 = new ArrayList<>();
        List<Crane> list2 = new ArrayList<>();

        // ===== Сбор признаков со стримингом =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectCategory(e, list1, list2,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "Cranes: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "Cranes: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    collectCategory(el.getAsJsonObject(), list1, list2,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Cranes: ошибка чтения features: " + ex.getMessage());
        }

        // ===== Построение: категория 1 =====
        if (!list1.isEmpty()) {
            broadcast(level, "Краны кат.1: старт построения…");
            int done = 0, total = list1.size();
            for (Crane c : list1) {
                if (c.x<minX||c.x>maxX||c.z<minZ||c.z>maxZ) continue;
                try {
                    buildCategory1(c, centerX, centerZ);
                } catch (Throwable t) {
                    broadcast(level, "Кат.1 ошибка у ("+c.x+","+c.z+"): " + t.getMessage());
                }
                done++;
                if (done % Math.max(1, total/5) == 0) {
                    int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                    broadcast(level, "Краны (кат.1): ~" + pct + "%");
                }
            }
            broadcast(level, "Краны: категория 1 — готово.");
        } else {
            broadcast(level, "Краны: подходящих кат.1 нет.");
        }

        // ===== Построение: категория 2 =====
        if (!list2.isEmpty()) {
            broadcast(level, "Краны кат.2: старт построения…");
            int done = 0, total = list2.size();
            for (Crane c : list2) {
                if (c.x<minX||c.x>maxX||c.z<minZ||c.z>maxZ) continue;
                try {
                    buildCategory2(c);
                } catch (Throwable t) {
                    broadcast(level, "Кат.2 ошибка у ("+c.x+","+c.z+"): " + t.getMessage());
                }
                done++;
                if (done % Math.max(1, total/5) == 0) {
                    int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                    broadcast(level, "Краны (кат.2): ~" + pct + "%");
                }
            }
            broadcast(level, "Краны: категория 2 — готово.");
        } else {
            broadcast(level, "Краны: подходящих кат.2 нет.");
        }
    }

    // ===== Разбор признаков: раскладываем по категориям =====
    private void collectCategory(JsonObject e, List<Crane> out1, List<Crane> out2,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        boolean isCat1 = isCategory1Crane(tags);
        boolean isCat2 = isCategory2Crane(tags);
        if (!isCat1 && !isCat2) return;

        String type = optString(e, "type");
        int cx, cz;

        if ("node".equals(type)) {
            // точное положение из ноды
            Double lat = e.has("lat") ? e.get("lat").getAsDouble() : null;
            Double lon = e.has("lon") ? e.get("lon").getAsDouble() : null;
            if (lat == null || lon == null) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble(); lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            cx = xz[0]; cz = xz[1];

        } else if ("way".equals(type) || "relation".equals(type)) {
            // центр геометрии по средним lat/lon (совпадает с тем, где обычно рисуют «иконку»)
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            double[] ll = centroidLatLon(g);
            int[] xz = latlngToBlock(ll[0], ll[1], centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            cx = xz[0]; cz = xz[1];

        } else return;

        int height = extractHeightBlocks(tags, DEFAULT_HEIGHT);
        int boom   = extractBoomLenBlocks(tags, DEFAULT_BOOM);

        if (isCat1) out1.add(new Crane(cx, cz, height, boom, 1));
        if (isCat2) out2.add(new Crane(cx, cz, height, 0, 2));
    }

    private boolean isCategory1Crane(JsonObject t) {
        String mm = low(optString(t, "man_made"));
        if (mm == null || !"crane".equals(mm)) return false;

        String ctype = low(optString(t, "crane:type"));
        if (ctype == null) ctype = low(optString(t, "crane"));
        if (ctype == null) return false;

        return ctype.contains("tower") || ctype.contains("portal");
    }

    private boolean isCategory2Crane(JsonObject t) {
        String mm = low(optString(t, "man_made"));
        if (mm == null || !"crane".equals(mm)) return false;

        String ctype = low(optString(t, "crane:type"));
        if (ctype == null) ctype = low(optString(t, "crane"));
        if (ctype == null) return false;

        // Gantry crane, Floor-mounted crane, Travel lift
        return ctype.contains("gantry")
            || ctype.contains("floor-mounted") || ctype.contains("floor_mounted") || ctype.contains("floor")
            || ctype.contains("travel lift") || ctype.contains("travel_lift") || ctype.contains("travellift");
    }

    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

    private int extractHeightBlocks(JsonObject t, int defVal) {
        String[] keys = new String[]{ "height", "crane:height", "structure:height" };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null) {
                Integer n = parseFirstInt(v);
                if (n != null && n > 0) return n;
            }
        }
        return defVal;
    }

    private int extractBoomLenBlocks(JsonObject t, int defVal) {
        String[] keys = new String[]{ "boom:length", "boom:length:meters", "crane:boom_length", "reach", "maxreach", "jib:length" };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null) {
                Integer n = parseFirstInt(v);
                if (n != null && n > 0) return n;
            }
        }
        return defVal;
    }

    private static Integer parseFirstInt(String s) {
        int len = s.length(), i = 0; while (i<len && !Character.isDigit(s.charAt(i))) i++;
        if (i==len) return null;
        int j=i; while (j<len && Character.isDigit(s.charAt(j))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    // ===== Постройка кат.1 =====
    private void buildCategory1(Crane c, int zoneCenterX, int zoneCenterZ) {
        final int cx = c.x, cz = c.z;
        final int H  = Math.max(2, c.height);
        final int L  = Math.max(1, c.boomLen);

        // Башня 5×5 решёткой
        int yTopMax = buildLatticeTower5x5(cx, cz, H);
        if (yTopMax == Integer.MIN_VALUE) return;

        // === СВЕТОКАМЕНЬ в центре башни каждые 5 блоков по высоте ===
        addVerticalGlowByTop(cx, cz, yTopMax);

        // Крепление стрелы
        int yAnchor = yTopMax - BOOM_ANCHOR_DY;

        // Направление на центр зоны
        int vdx = zoneCenterX - cx;
        int vdz = zoneCenterZ - cz;
        if (vdx == 0 && vdz == 0) vdz = 1;

        int sdx, sdz;
        if (Math.abs(vdx) > Math.abs(vdz)) { sdx = (vdx > 0 ? 1 : -1); sdz = (vdz==0 ? 0 : (vdz > 0 ? 1 : -1)); }
        else if (Math.abs(vdx) < Math.abs(vdz)) { sdz = (vdz > 0 ? 1 : -1); sdx = (vdx==0 ? 0 : (vdx > 0 ? 1 : -1)); }
        else { sdx = (vdx > 0 ? 1 : -1); sdz = (vdz > 0 ? 1 : -1); }

        int px = -sdz, pz = sdx;

        // Точка якоря — грань башни по направлению стрелы
        int ax = cx + sdx * TOWER_HALF;
        int ay = yAnchor;
        int az = cz + sdz * TOWER_HALF;

        // Стрела 2×2 (+ светокамни кажд. 5)
        drawBeam2x2_DDA(ax, az, sdx, sdz, L, ay, px, pz, CRANE_BLOCK, /*lightEvery5=*/true);

        // Противовес из каменных кирпичей
        drawCounterweight(ax, ay, az, sdx, sdz, px, pz, COUNTER_LEN, COUNTER_WH, COUNTER_WH, COUNTER_BLOCK);

        // Кабина 3×3×3 — снаружи
        placeCabinUnderAnchor(ax, ay, az, sdx, sdz);
    }

    // ===== Постройка кат.2 =====
    private void buildCategory2(Crane c) {
        final int cx = c.x, cz = c.z;
        final int H  = Math.max(2, c.height);

        // 1) Четыре опоры 3×3 решёткой по углам квадрата 10×10
        int xW = cx - CAT2_HALF, xE = cx + CAT2_HALF;
        int zN = cz - CAT2_HALF, zS = cz + CAT2_HALF;

        int topNW = buildLatticePost3x3(xW, zN, H);
        int topNE = buildLatticePost3x3(xE, zN, H);
        int topSE = buildLatticePost3x3(xE, zS, H);
        int topSW = buildLatticePost3x3(xW, zS, H);

        // === СВЕТОКАМЕНЬ каждые 5 блоков по центру каждой ножки ===
        addVerticalGlowByTop(xW, zN, topNW);
        addVerticalGlowByTop(xE, zN, topNE);
        addVerticalGlowByTop(xE, zS, topSE);
        addVerticalGlowByTop(xW, zS, topSW);

        int yBeam = Math.min(Math.min(topNW, topNE), Math.min(topSE, topSW)); // общий горизонт

        // 2) Горизонтальные сплошные балки 3×3 по периметру квадрата на уровне yBeam
        drawBeam3x3Between(xW, zN, xE, zN, yBeam, CRANE_BLOCK); // север
        drawBeam3x3Between(xE, zN, xE, zS, yBeam, CRANE_BLOCK); // восток
        drawBeam3x3Between(xE, zS, xW, zS, yBeam, CRANE_BLOCK); // юг
        drawBeam3x3Between(xW, zS, xW, zN, yBeam, CRANE_BLOCK); // запад

        // 3) ДВЕ ЦЕНТРАЛЬНЫЕ ОСЕВЫЕ ЛИНИИ (параллельно сторонам), 1×1 блок, пересекаются по центру
        int innerMinX = xW + POST_HALF; // отступ от опор на пол-толщины 3×3
        int innerMaxX = xE - POST_HALF;
        int innerMinZ = zN + POST_HALF;
        int innerMaxZ = zS - POST_HALF;

        // горизонтальная линия через центр (z = cz)
        drawThinLine(innerMinX, cz, innerMaxX, cz, yBeam, CRANE_BLOCK);
        // вертикальная линия через центр (x = cx)
        drawThinLine(cx, innerMinZ, cx, innerMaxZ, yBeam, CRANE_BLOCK);
    }

    // ==== Башня 5×5 (кат.1) решёткой ====
    private int buildLatticeTower5x5(int cx, int cz, int H) {
        int yTopMax = Integer.MIN_VALUE;

        for (int dx = -TOWER_HALF; dx <= TOWER_HALF; dx++) {
            for (int dz = -TOWER_HALF; dz <= TOWER_HALF; dz++) {
                boolean onPerimeter = (Math.abs(dx) == TOWER_HALF) || (Math.abs(dz) == TOWER_HALF);
                if (!onPerimeter) continue;

                int x = cx + dx, z = cz + dz;
                int yBase = groundY(x, z);
                if (yBase == Integer.MIN_VALUE) continue;

                int yStart = yBase + 1;
                int yEnd   = yStart + H - 1;

                boolean isCorner = (Math.abs(dx) == TOWER_HALF) && (Math.abs(dz) == TOWER_HALF);

                for (int y = yStart; y <= yEnd; y++) {
                    if (isCorner || (((x + z + y) & 1) == 0)) {
                        set(x, y, z, CRANE_BLOCK);
                    }
                }

                yTopMax = Math.max(yTopMax, yEnd);
            }
        }
        return yTopMax;
    }

    // ==== Опора 3×3 (кат.2) решёткой только по периметру ====
    private int buildLatticePost3x3(int cx, int cz, int H) {
        int yTopMax = Integer.MIN_VALUE;

        for (int dx = -POST_HALF; dx <= POST_HALF; dx++) {
            for (int dz = -POST_HALF; dz <= POST_HALF; dz++) {
                boolean onPerimeter = (Math.abs(dx) == POST_HALF) || (Math.abs(dz) == POST_HALF);
                if (!onPerimeter) continue;

                int x = cx + dx, z = cz + dz;
                int yBase = groundY(x, z);
                if (yBase == Integer.MIN_VALUE) continue;

                int yStart = yBase + 1;
                int yEnd   = yStart + H - 1;

                boolean isCorner = (Math.abs(dx) == POST_HALF) && (Math.abs(dz) == POST_HALF);

                for (int y = yStart; y <= yEnd; y++) {
                    if (isCorner || (((x + z + y) & 1) == 0)) {
                        set(x, y, z, CRANE_BLOCK);
                    }
                }

                yTopMax = Math.max(yTopMax, yEnd);
            }
        }
        return yTopMax;
    }

    // ==== Горизонтальная балка 3×3 между (x1,z1) и (x2,z2) на фиксированном Y ====
    private void drawBeam3x3Between(int x1, int z1, int x2, int z2, int y, Block mat) {
        int dx = x2 - x1, dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0) {
            for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) set(x1 + ox, y + oy, z1, mat);
            return;
        }
        double stepx = dx / (double) steps;
        double stepz = dz / (double) steps;

        int sx = Integer.compare(dx, 0);
        int sz = Integer.compare(dz, 0);
        int px = -sz;
        int pz =  sx;

        double fx = x1, fz = z1;
        int lx = Integer.MIN_VALUE, lz = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            int xi = (int)Math.round(fx);
            int zi = (int)Math.round(fz);
            if (xi != lx || zi != lz) {
                for (int w = -1; w <= 1; w++) {
                    int bx = xi + px * w;
                    int bz = zi + pz * w;
                    for (int h = -1; h <= 1; h++) {
                        set(bx, y + h, bz, mat);
                    }
                }
                lx = xi; lz = zi;
            }
            fx += stepx; fz += stepz;
        }
    }

    // ==== Тонкая 1-блочная линия в XZ на высоте y (для центрального креста) ====
    private void drawThinLine(int x1, int z1, int x2, int z2, int y, Block mat) {
        int dx = Math.abs(x2 - x1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;

        int x = x1, z = z1;
        while (true) {
            set(x, y, z, mat);
            if (x == x2 && z == z2) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 <  dx) { err += dx; z += sz; }
        }
    }

    // ==== Стрела 2×2 (кат.1) с опцией ставить светокамни каждые 5 ====
    private void drawBeam2x2_DDA(int x0, int z0, int dirX, int dirZ, int len, int yAnchor, int px, int pz, Block mat, boolean lightEvery5) {
        double vx = dirX, vz = dirZ;
        double m  = Math.max(Math.abs(vx), Math.abs(vz));
        if (m == 0) return;
        double stepx = vx / m;
        double stepz = vz / m;

        double fx = x0, fz = z0;
        int lastXi = Integer.MIN_VALUE, lastZi = Integer.MIN_VALUE;
        int progressed = 0;

        for (int s = 0; s < len; s++) {
            fx += stepx;
            fz += stepz;
            int xi = (int)Math.round(fx);
            int zi = (int)Math.round(fz);
            if (xi == lastXi && zi == lastZi) continue;
            lastXi = xi; lastZi = zi;

            // поперечное сечение 2×2
            for (int w = 0; w < BOOM_SEC_W; w++) {
                int bx = xi + px * w;
                int bz = zi + pz * w;
                for (int h = 0; h < BOOM_SEC_H; h++) {
                    set(bx, yAnchor + h, bz, mat);
                }
            }

            progressed++;
            if (lightEvery5 && (progressed % LIGHT_STEP == 0)) {
                // Один блок светокамня внизу сечения на «осевой» точке (xi,zi)
                set(xi, yAnchor, zi, GLOW);
            }
        }
    }

    // ==== Противовес (кат.1) ====
    private void drawCounterweight(int ax, int ay, int az,
                                   int dirX, int dirZ, int px, int pz,
                                   int len, int width, int height, Block mat) {
        int stepX = -dirX;
        int stepZ = -dirZ;

        for (int s = 1; s <= len; s++) {
            int cx = ax + stepX * s;
            int cz = az + stepZ * s;

            for (int w = 0; w < width; w++) {
                int bx = cx + px * (w - (width/2 - 1));
                int bz = cz + pz * (w - (width/2 - 1));
                for (int h = 0; h < height; h++) {
                    set(bx, ay - 1 + h, bz, mat);
                }
            }
        }
    }

    // ==== Кабина (кат.1) ====
    private void placeCabinUnderAnchor(int ax, int ay, int az, int dirX, int dirZ) {
        int size = CABIN_SIZE;
        int half = size / 2;
        int yMin = ay - size;
        int yMax = yMin + size - 1;

        int outward = half + 1;
        int cx = ax + dirX * outward;
        int cz = az + dirZ * outward;

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    boolean onShell = (x==cx-half||x==cx+half||z==cz-half||z==cz+half||y==yMin||y==yMax);
                    if (!onShell) continue;

                    boolean isFront;
                    if (Math.abs(dirX) >= Math.abs(dirZ)) {
                        isFront = (dirX > 0 && x == cx + half) || (dirX < 0 && x == cx - half);
                    } else {
                        isFront = (dirZ > 0 && z == cz + half) || (dirZ < 0 && z == cz - half);
                    }

                    set(x, y, z, isFront ? CABIN_GLASS : CABIN_BLOCK);
                }
            }
        }
    }

    // ==== Подсветка: вертикальная линия от базовой отметки до topY шагом 5 ====
    private void addVerticalGlowByTop(int cx, int cz, int topY) {
        int base = groundY(cx, cz) + 1;
        for (int y = base; y <= topY; y += LIGHT_STEP) {
            set(cx, y, cz, GLOW);
        }
    }

    // ===== Блоки / рельеф =====
    private void set(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }

    private int groundY(int x, int z) {
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

    private static double[] centroidLatLon(JsonArray g) {
        double slat = 0, slon = 0;
        int n = g.size();
        for (JsonElement el : g) {
            JsonObject p = el.getAsJsonObject();
            slat += p.get("lat").getAsDouble();
            slon += p.get("lon").getAsDouble();
        }
        return new double[]{ slat / Math.max(1,n), slon / Math.max(1,n) };
    }
}