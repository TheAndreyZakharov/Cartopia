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

import java.util.*;

public class OvergroundPipelinesGenerator {

    // ===== Материалы =====
    private static final Block PIPE_BLOCK     = Blocks.IRON_BLOCK;
    private static final Block SUPPORT_WALL   = Blocks.POLISHED_BLACKSTONE_WALL;

    // ===== Параметры =====
    private static final int   LAYER_STEP        = 5;    // +5 блоков на каждый layer
    private static final int   SUPPORT_EVERY     = 20;   // опора каждые 20 блоков пути по XZ
    private static final int   GROUND_END_OFFSET = 0;    // конец линии «ложится» на землю (ground+1)
    private static final int   RAMP_MAX_LEN      = 20;   // максимум длины рампы; если места меньше — ужимаем

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Границы зоны генерации
    private int limitMinX, limitMaxX, limitMinZ, limitMaxZ;

    public OvergroundPipelinesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ===== Типы =====
    private static final class PipeLine {
        final List<int[]> pts  = new ArrayList<>();   // [x,z]
        final List<Integer> offs = new ArrayList<>(); // offset над грунтом для каждой точки (для этой линии)
        @SuppressWarnings("unused")
        final int defaultOffset;
        PipeLine(int defaultOffset) { this.defaultOffset = defaultOffset; }
    }
    private static long key(int x, int z) { return (((long)x) << 32) ^ (z & 0xffffffffL); }

    // Собранные линии; степень узла; макс-оффсет узла по всем линиям; где уже ставили опоры
    private final List<PipeLine> lines = new ArrayList<>();
    private final Map<Long, Integer> nodeDegree = new HashMap<>();
    private final Map<Long, Integer> nodeMaxOffset = new HashMap<>();
    private final Set<Long> placedSupports = new HashSet<>();

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

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "Pipes: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "Pipes: no center/bbox — skipping."); return; }

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
        final int worldMinX = Math.min(a[0], b[0]);
        final int worldMaxX = Math.max(a[0], b[0]);
        final int worldMinZ = Math.min(a[1], b[1]);
        final int worldMaxZ = Math.max(a[1], b[1]);

        this.limitMinX = worldMinX;
        this.limitMaxX = worldMaxX;
        this.limitMinZ = worldMinZ;
        this.limitMaxZ = worldMaxZ;

        // ===== Сбор фич (стриминг/батч) =====
        int read = 0;
        try {
            if (store != null) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collect(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        read++;
                        if (read % 2000 == 0) broadcast(level, "Pipes: features read ~" + read);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "Pipes: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size()==0) { broadcast(level,"Pipes: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Pipes: error reading features: " + ex.getMessage());
        }

        if (lines.isEmpty()) { broadcast(level, "Pipes: no suitable overground pipelines — done."); return; }

        // ===== Постройка =====
        long totalSegs = 0;
        for (PipeLine pl : lines) totalSegs += Math.max(0, pl.pts.size()-1);

        long doneSegs = 0;
        for (PipeLine pl : lines) {
            if (pl.pts.size() < 2) continue;

            int supportCounterXZ = 0; // считаем только смены XZ

            for (int i=0; i<pl.pts.size()-1; i++) {
                int[] p0 = pl.pts.get(i);
                int[] p1 = pl.pts.get(i+1);

                long k0 = key(p0[0], p0[1]);
                long k1 = key(p1[0], p1[1]);

                int plateau = pl.offs.get(i); // уровень линии

                // Цели на концах сегмента: земля для висящих концов, иначе — максимум по узлу
                int target0 = (nodeDegree.getOrDefault(k0, 0) <= 1) ? GROUND_END_OFFSET
                              : nodeMaxOffset.getOrDefault(k0, plateau);
                int target1 = (nodeDegree.getOrDefault(k1, 0) <= 1) ? GROUND_END_OFFSET
                              : nodeMaxOffset.getOrDefault(k1, plateau);

                placePipeSegmentSupercover(
                        p0[0], p0[1], p1[0], p1[1],
                        target0, target1, plateau,
                        worldMinX, worldMaxX, worldMinZ, worldMaxZ,
                        supportCounterXZ
                );

                supportCounterXZ = lastSupportCounterXZ;
                doneSegs++;
                if (totalSegs > 0 && doneSegs % Math.max(1, totalSegs/5) == 0) {
                    int pct = (int)Math.round(100.0 * doneSegs / Math.max(1, totalSegs));
                    broadcast(level, "Pipes: lines ~" + pct + "%");
                }
            }
        }

        broadcast(level, String.format(Locale.ROOT,
                "Pipes: done. Lines: %d, segments: %d", lines.size(), doneSegs));
    }

    // ===== Сбор одной фичи =====
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        String type = optString(e, "type");
        if (t == null || type == null) return;

        if (!"way".equals(type)) return;
        if (!isOvergroundPipeline(t)) return;

        int layer = parseLayerSafe(t);
        int offset = Math.max(1, layer) * LAYER_STEP; // layer=1 → +5; layer=2 → +10; ...

        JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
        if (g == null || g.size() < 2) return;

        PipeLine pl = new PipeLine(offset);

        // точки линии + оффсеты этой линии; параллельно считаем nodeMaxOffset
        for (int i=0; i<g.size(); i++) {
            JsonObject p = g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(
                    p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

            pl.pts.add(new int[]{xz[0], xz[1]});
            pl.offs.add(offset);

            long k = key(xz[0], xz[1]);
            Integer prev = nodeMaxOffset.get(k);
            nodeMaxOffset.put(k, (prev == null) ? offset : Math.max(prev, offset));
        }

        // степень узлов (для концов/земли)
        for (int i=0; i<pl.pts.size()-1; i++) {
            int[] p0 = pl.pts.get(i);
            int[] p1 = pl.pts.get(i+1);
            incDegree(nodeDegree, key(p0[0], p0[1]));
            incDegree(nodeDegree, key(p1[0], p1[1]));
        }

        lines.add(pl);
    }

    private static void incDegree(Map<Long,Integer> map, long k) {
        Integer v = map.get(k);
        map.put(k, (v == null) ? 1 : v + 1);
    }

    private static boolean isOvergroundPipeline(JsonObject t) {
        String mm = low(optString(t, "man_made"));
        if (!"pipeline".equals(mm)) return false;

        String loc = low(optString(t, "location"));
        Integer layer = parseLayer(t);
        return "overground".equals(loc) || (layer != null && layer >= 1);
    }

    private static Integer parseLayer(JsonObject t) {
        String s = optString(t, "layer");
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception ignore) { return null; }
    }

    private static int parseLayerSafe(JsonObject t) {
        Integer L = parseLayer(t);
        if (L == null) return 1;
        return L;
    }

    // ======= Суперковер + непрерывность по 3D =======
    private int lastSupportCounterXZ = 0;

    private void placePipeSegmentSupercover(int x0, int z0, int x1, int z1,
                                            int startTarget, int endTarget, int plateauOffset,
                                            int wMinX, int wMaxX, int wMinZ, int wMaxZ,
                                            int supportCounterStart) {

        // 1) строим путь по XZ без диагональных разрывов
        List<int[]> path = supercoverLine(x0, z0, x1, z1);
        if (path.isEmpty()) return;

        final int N = path.size() - 1; // число шагов по траектории

        // 2) выделим длины рамп на концах (<=20), остальное — плато
        int dStart = Math.abs(plateauOffset - startTarget);
        int dEnd   = Math.abs(endTarget   - plateauOffset);

        int wantStart = (dStart > 0) ? RAMP_MAX_LEN : 0;
        int wantEnd   = (dEnd   > 0) ? RAMP_MAX_LEN : 0;

        int Lstart, Lend;
        if (N >= wantStart + wantEnd) {
            Lstart = wantStart; Lend = wantEnd;
        } else {
            if (dStart == 0 && dEnd == 0) { Lstart = 0; Lend = 0; }
            else if (dStart == 0)         { Lstart = 0; Lend = Math.min(RAMP_MAX_LEN, N); }
            else if (dEnd == 0)           { Lstart = Math.min(RAMP_MAX_LEN, N); Lend = 0; }
            else {
                double sum = dStart + dEnd;
                Lstart = (int)Math.floor(N * (dStart / sum));
                Lstart = Math.min(Lstart, RAMP_MAX_LEN);
                Lend   = N - Lstart;
                Lend   = Math.min(Lend, RAMP_MAX_LEN);
            }
        }
        int plateauStartIdx = Lstart;
        int plateauEndIdx   = N - Lend;

        // 3) проходим по клеткам — гарантируем смежность по 3D
        Integer prevY = null;
        int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;

        int supportCounterXZ = supportCounterStart;

        for (int idx = 0; idx <= N; idx++) {
            int xi = path.get(idx)[0];
            int zi = path.get(idx)[1];

            int base = groundY(xi, zi);
            if (base == Integer.MIN_VALUE) base = level.getMinBuildHeight();

            // piecewise offset: стартовая рампа -> плато -> конечная рампа
            int off;
            if (idx < plateauStartIdx) {
                double t = (Lstart == 0) ? 1.0 : (idx / (double)Lstart);
                off = (int)Math.round(startTarget + (plateauOffset - startTarget) * clamp01(t));
            } else if (idx <= plateauEndIdx) {
                off = plateauOffset;
            } else {
                int pos = idx - plateauEndIdx;
                double t = (Lend == 0) ? 1.0 : (pos / (double)Lend);
                off = (int)Math.round(plateauOffset + (endTarget - plateauOffset) * clamp01(t));
            }
            int yTarget = base + 1 + off;

            if (idx == 0 || prevY == null) {
                // первая точка: просто ставим целевой блок
                if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, yTarget, zi, PIPE_BLOCK);
                prevY = yTarget; prevX = xi; prevZ = zi;
                continue;
            }

            boolean movedXZ = (xi != prevX) || (zi != prevZ);

            if (movedXZ) {
                // --- горизонтальный шаг: сначала «входной» блок на новом XZ на высоте prevY (но не ниже грунта нового XZ) ---
                int baseNew = base; // уже посчитан
                int yEntry = prevY;

                if (yEntry < baseNew + 1) {
                    // подтянем предыдущую колонну вверх, чтобы соединиться лицом
                    for (int y = prevY + 1; y <= baseNew; y++) {
                        if (inWorld(prevX, prevZ, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(prevX, y, prevZ, PIPE_BLOCK);
                    }
                    yEntry = baseNew + 1;
                }

                // ставим «вход» на новом XZ
                if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, yEntry, zi, PIPE_BLOCK);

                // --- затем вертикальная добивка до целевого yTarget по той же XZ-колонне ---
                if (yTarget > yEntry) {
                    for (int y = yEntry + 1; y <= yTarget; y++) {
                        if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, y, zi, PIPE_BLOCK);
                    }
                } else if (yTarget < yEntry) {
                    for (int y = yEntry - 1; y >= yTarget; y--) {
                        if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, y, zi, PIPE_BLOCK);
                    }
                }

                // учёт шага для опор: только при смене XZ
                supportCounterXZ++;
                if (supportCounterXZ >= SUPPORT_EVERY) {
                    if (tryPlaceSupport(xi, zi, yTarget)) {
                        supportCounterXZ = 0;
                    }
                }
            } else {
                // XZ не менялся (редко, в нашем проходе обычно меняется) — просто подтягиваем вертикально
                int yFrom = prevY;
                if (yTarget > yFrom) {
                    for (int y = yFrom + 1; y <= yTarget; y++) {
                        if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, y, zi, PIPE_BLOCK);
                    }
                } else if (yTarget < yFrom) {
                    for (int y = yFrom - 1; y >= yTarget; y--) {
                        if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, y, zi, PIPE_BLOCK);
                    }
                }
            }

            prevY = yTarget; prevX = xi; prevZ = zi;
        }

        lastSupportCounterXZ = supportCounterXZ;
    }

    /**
     * Supercover Bresenham: возвращает путь по XZ без диагональных разрывов.
     * При одновременном изменении X и Z добавляется промежуточная клетка (x, zPrev),
     * чтобы соседство шло по грани.
     */
    private static List<int[]> supercoverLine(int x0, int z0, int x1, int z1) {
        List<int[]> cells = new ArrayList<>();
        int x = x0, z = z0;
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = Integer.signum(x1 - x0);
        int sz = Integer.signum(z1 - z0);
        int err = dx - dz;

        cells.add(new int[]{x, z});

        while (x != x1 || z != z1) {
            int e2 = err << 1;
            int xp = x, zp = z;

            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 <  dx) { err += dx; z += sz; }

            // если шагнули и по X, и по Z — вставим мостик (x, zp)
            if (x != xp && z != zp) {
                int bx = x;
                int bz = zp;
                int[] last = cells.get(cells.size()-1);
                if (!(last[0]==bx && last[1]==bz)) {
                    cells.add(new int[]{bx, bz});
                }
            }

            int[] last2 = cells.get(cells.size()-1);
            if (!(last2[0]==x && last2[1]==z)) {
                cells.add(new int[]{x, z});
            }
        }
        return cells;
    }

    private static double clamp01(double v){ return (v<0)?0:(v>1)?1:v; }

    /** Опора: столб из WALL от ground+1 до (yPipe-1), если не запрещено основание. */
    private boolean tryPlaceSupport(int x, int z, int yPipe) {
        if (!inWorld(x, z, limitMinX, limitMaxX, limitMinZ, limitMaxZ)) return false;

        long k = key(x, z);
        if (placedSupports.contains(k)) return false;

        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return false;
        if (yPipe <= base + 1) return false;

        Block groundBlock = level.getBlockState(new BlockPos(x, base, z)).getBlock();
        if (groundBlock == Blocks.GRAY_CONCRETE || groundBlock == Blocks.WHITE_CONCRETE || groundBlock == Blocks.YELLOW_CONCRETE) {
            return false;
        }
        Block atop = level.getBlockState(new BlockPos(x, base + 1, z)).getBlock();
        if (isRailBlock(atop)) return false;

        for (int y = base + 1; y <= yPipe - 1; y++) {
            setBlock(x, y, z, SUPPORT_WALL);
        }
        placedSupports.add(k);
        return true;
    }

    // ===== Утилиты мира/рельефа =====
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
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

    private static boolean inWorld(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return !(x < minX || x > maxX || z < minZ || z > maxZ);
    }

    private static boolean isRailBlock(Block b) {
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    // ===== Общие утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

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