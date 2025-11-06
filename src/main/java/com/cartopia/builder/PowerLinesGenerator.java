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


public class PowerLinesGenerator {

    // ===== Материалы =====
    private static final Block POLE_WALL          = Blocks.ANDESITE_WALL;   // столбы
    private static final Block CROSSARM_FENCE     = Blocks.SPRUCE_FENCE;    // крестовины / площадки / конус
    private static final Block TOWER_LATTICE      = Blocks.IRON_BARS;       // каркас башни
    private static final Block WIRE_FENCE         = Blocks.DARK_OAK_FENCE;  // провода

    // ===== Геометрия =====
    private static final int POLE_HEIGHT      = 5;     // 5 стенок
    private static final int POLE_WIRE_OFFSET = 6;     // +6 от рельефа (5 + крестовина)
    private static final int TOWER_HEIGHT     = 30;    // высота башни
    private static final int TOWER_BASE_W     = 8;     // основание решётки (квадрат 5×5)
    private static final int TOWER_TOP_W      = 4;     // к верху сужение до 1×1 периметра
    private static final int TOWER_PLAT_W     = 6;     // верхняя площадка 6×6 из spruce fence
    private static final int TOWER_WIRE_OFFSET= 31;    // +31 от рельефа (30 + платформа)
    private static final int GROUND_END_OFFSET = 2; 

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public PowerLinesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
    private static final class Support {
        final int x, z;
        final boolean tower; // true = вышка, false = столб
        Support(int x, int z, boolean tower) { this.x=x; this.z=z; this.tower=tower; }
        int offset() { return tower ? TOWER_WIRE_OFFSET : POLE_WIRE_OFFSET; }
    }
    private static final class Polyline {
        final List<int[]> pts = new ArrayList<>(); // список [x,z]
    }

    // Быстрый индекс опор по координате
    private final Map<Long, Support> supportsByXZ = new HashMap<>();
    private final List<Polyline> lines = new ArrayList<>();

    private static long key(int x, int z) { return (((long)x) << 32) ^ (z & 0xffffffffL); }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) { broadcast(level,"PowerLinesGenerator: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level,"PowerLinesGenerator: no center/bbox — skipping."); return;
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
        final int worldMinX = Math.min(a[0], b[0]);
        final int worldMaxX = Math.max(a[0], b[0]);
        final int worldMinZ = Math.min(a[1], b[1]);
        final int worldMaxZ = Math.max(a[1], b[1]);

        // ===== Чтение фич (стриминг/батч) =====
        boolean streaming = (store != null);
        int read = 0;
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collect(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        read++;
                        if (read % 1000 == 0) broadcast(level, "Power lines: features read ~" + read);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level,"PowerLinesGenerator: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size()==0) { broadcast(level,"PowerLinesGenerator: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "PowerLinesGenerator: error reading features: " + ex.getMessage());
        }

        // ===== Постройка опор =====
        if (supportsByXZ.isEmpty() && lines.isEmpty()) {
            broadcast(level, "PowerLinesGenerator: no suitable power-line objects found — done.");
            return;
        }

        int placedSupports = 0;
        for (Support s : supportsByXZ.values()) {
            if (s.x<worldMinX||s.x>worldMaxX||s.z<worldMinZ||s.z>worldMaxZ) continue;
            try {
                if (s.tower) buildTower(s.x, s.z);
                else buildPole(s.x, s.z);
                placedSupports++;
                if (placedSupports % Math.max(1, supportsByXZ.size()/5) == 0) {
                    int pct = (int)Math.round(100.0 * placedSupports / Math.max(1, supportsByXZ.size()));
                    broadcast(level, "Power lines: supports ~" + pct + "%");
                }
            } catch (Exception ex) {
                broadcast(level, "Power lines: error building support at ("+s.x+","+s.z+"): " + ex.getMessage());
            }
        }

        // ===== Провода по линиям =====
        long segs = 0, totalSegs = 0;
        for (Polyline pl : lines) totalSegs += Math.max(0, pl.pts.size()-1);

        for (Polyline pl : lines) {
            List<int[]> P = pl.pts;
            if (P.size() < 2) continue;
            for (int i=0; i<P.size()-1; i++) {
                int[] p0 = P.get(i), p1 = P.get(i+1);
                placeWireSegment(p0[0], p0[1], p1[0], p1[1], i==0, i==P.size()-2,
                        worldMinX, worldMaxX, worldMinZ, worldMaxZ);
                segs++;
                if (segs % Math.max(1, totalSegs/5) == 0) {
                    int pct = (int)Math.round(100.0 * segs / Math.max(1, totalSegs));
                    broadcast(level, "Power lines: wires ~" + pct + "%");
                }
            }
        }

        broadcast(level, String.format(Locale.ROOT,
                "Power lines: done. Supports: %d, lines: %d, segments: %d", placedSupports, lines.size(), segs));
    }

    // ===== Сбор фич =====
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        String type = optString(e, "type");
        if (t == null || type == null) return;

        // Столбы/вышки — как узлы (обычно node)
        if (isPole(t) || isTower(t)) {
            int[] xz = nodeToXZ(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (xz == null) return;
            boolean tower = isTower(t);
            supportsByXZ.put(key(xz[0], xz[1]), new Support(xz[0], xz[1], tower));
            return;
        }

        // Линии — ways с power=line|minor_line
        if (isPowerLine(t)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 2) return;
            Polyline pl = new Polyline();
            for (int i=0;i<g.size();i++){
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(
                        p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                pl.pts.add(new int[]{xz[0], xz[1]});
            }
            lines.add(pl);
        }
    }

    private static boolean isPole(JsonObject t) {
        String power = low(optString(t,"power"));
        String mm    = low(optString(t,"man_made"));
        return "pole".equals(power) || "utility_pole".equals(mm);
    }
    private static boolean isTower(JsonObject t) {
        String power = low(optString(t,"power"));
        String mm    = low(optString(t,"man_made"));
        return "tower".equals(power) || "pylon".equals(mm);
    }
    private static boolean isPowerLine(JsonObject t) {
        String power = low(optString(t,"power"));
        return "line".equals(power) || "minor_line".equals(power);
    }

    private int[] nodeToXZ(JsonObject e,
                           double centerLat, double centerLng,
                           double east, double west, double north, double south,
                           int sizeMeters, int centerX, int centerZ) {
        String type = optString(e,"type");
        if (!"node".equals(type)) {
            // иногда бывают ways с геометрией одной точкой…
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size()==0) return null;
            JsonObject p = g.get(0).getAsJsonObject();
            return latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        }
        double lat = e.get("lat").getAsDouble();
        double lon = e.get("lon").getAsDouble();
        return latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
    }

    // ===== Постройка опор =====
    private void buildPole(int x, int z) {
        int yBase = groundY(x, z);
        if (yBase == Integer.MIN_VALUE) return;

        // Столб: 5 стенок андезита
        for (int i=1; i<=POLE_HEIGHT; i++) setBlock(x, yBase + i, z, POLE_WALL);

        // Крестовина 3×3 из spruce fence на уровне (yBase + 6)
        int yCross = yBase + POLE_WIRE_OFFSET;
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            setBlock(x+dx, yCross, z+dz, CROSSARM_FENCE);
        }
    }

    private void buildTower(int cx, int cz) {
        // Каркас-«пирамида» из решётки: от 5×5 к 1×1 за 30 уровней
        for (int h=0; h<TOWER_HEIGHT; h++) {
            @SuppressWarnings("unused")
            double t = (TOWER_HEIGHT<=1) ? 1.0 : (h / (double)(TOWER_HEIGHT-1));
            int w = Math.max(TOWER_TOP_W, (int)Math.round( TOWER_BASE_W - (TOWER_BASE_W - TOWER_TOP_W) * t ));
            drawPerimeterLevelFollowingTerrain(cx, cz, w, h, TOWER_LATTICE);
        }

        // Платформа 6×6 из spruce fence на уровне локального «верха»: terrain+1+TOWER_HEIGHT
        fillPlatformFollowingTop(cx, cz, TOWER_PLAT_W, TOWER_HEIGHT, CROSSARM_FENCE);

        // «Плавный конус» вверх из тех же заборов: 4×4 → 2×2 → 1×1
        int yTop = localTopY(cx, cz, TOWER_HEIGHT);
        fillCenteredSquare(cx, cz, yTop + 1, 4, CROSSARM_FENCE);
        fillCenteredSquare(cx, cz, yTop + 2, 2, CROSSARM_FENCE);
        setBlock(cx, yTop + 3, cz, CROSSARM_FENCE);
    }

    // Квадрат-периметр на уровне h, «сидящий» на своём рельефе
    private void drawPerimeterLevelFollowingTerrain(int cx, int cz, int w, int h, Block block) {
        Range r = rangeFor(w);
        int minX = cx - r.left, maxX = cx + r.right;
        int minZ = cz - r.left, maxZ = cz + r.right;
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                boolean border = (x==minX || x==maxX || z==minZ || z==maxZ);
                if (!border) continue;
                int base = groundY(x, z);
                if (base == Integer.MIN_VALUE) continue;
                setBlock(x, base + 1 + h, z, block);
            }
        }
    }

    private void fillPlatformFollowingTop(int cx, int cz, int w, int H, Block block) {
        Range r = rangeFor(w);
        for (int x=cx - r.left; x<=cx + r.right; x++) {
            for (int z=cz - r.left; z<=cz + r.right; z++) {
                int y = localTopY(x, z, H);
                if (y == Integer.MIN_VALUE) continue;
                setBlock(x, y, z, block);
            }
        }
    }

    private void fillCenteredSquare(int cx, int cz, int y, int w, Block b) {
        Range r = rangeFor(w);
        for (int x=cx - r.left; x<=cx + r.right; x++)
            for (int z=cz - r.left; z<=cz + r.right; z++)
                setBlock(x, y, z, b);
    }

    // ===== Провода =====


    private void placeWireSegment(int x0, int z0, int x1, int z1,
                                  boolean isFirstSeg, boolean isLastSeg,
                                  int wMinX, int wMaxX, int wMinZ, int wMaxZ) {

        Integer off0 = findSupportOffsetNear(x0, z0);
        Integer off1 = findSupportOffsetNear(x1, z1);

        if (off0 == null && isFirstSeg) off0 = GROUND_END_OFFSET;            // мягкий заход на землю в начале линии
        if (off1 == null && isLastSeg)  off1 = GROUND_END_OFFSET;            // мягкий сход на землю в конце линии
        if (off0 == null) off0 = (off1 != null) ? off1 : POLE_WIRE_OFFSET; // по умолчанию считаем «полюсную» высоту
        if (off1 == null) off1 = off0;

        int dx = x1 - x0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            int y = clampWireY(x0, z0, off0);
            if (inWorld(x0,z0,wMinX,wMaxX,wMinZ,wMaxZ)) setBlock(x0, y, z0, WIRE_FENCE);
            return;
        }

        double sx = dx / (double) steps;
        double sz = dz / (double) steps;

        Integer prevY = null;
        for (int i=0; i<=steps; i++) {
            double t = i / (double) steps;
            int xi = (int)Math.round(x0 + sx * i);
            int zi = (int)Math.round(z0 + sz * i);
            int off = (int)Math.round(off0 + (off1 - off0) * t);

            int yiTarget = clampWireY(xi, zi, off);

            // ограничим «перепад» по 1 блоку/шаг
            int yi = yiTarget;
            if (prevY != null) {
                if (yi > prevY + 1) yi = prevY + 1;
                if (yi < prevY - 1) yi = prevY - 1;
            }

            if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) {
                setBlock(xi, yi, zi, WIRE_FENCE);
            }

            prevY = yi;

            // На самом последнем шаге — принудительно выставим точную целевую высоту,
            // чтобы «зацепиться» на нужной отметке (особенно для опор)
            if (i == steps) {
                int yExact = yiTarget;
                if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, yExact, zi, WIRE_FENCE);
            }
        }
    }

    private Integer findSupportOffsetNear(int x, int z) {
        // точное попадание
        Support s = supportsByXZ.get(key(x,z));
        if (s != null) return s.offset();
        // небольшой радиус 1 — часто геометрия ways сдвинута на 1 клетку от узла
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            if (dx==0 && dz==0) continue;
            s = supportsByXZ.get(key(x+dx, z+dz));
            if (s != null) return s.offset();
        }
        return null;
        // (При желании радиус можно расширить до 2, но 1 обычно хватает.)
    }

    private int clampWireY(int x, int z, int offset) {
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) base = level.getMinBuildHeight();
        // Никогда не ниже, чем на блок выше грунта:
        return base + Math.max(1, offset);
    }

    // ===== Низкоуровневые сеттеры и рельеф =====
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x,y,z), b.defaultBlockState(), 3);
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

        // мир
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== Геометрические утилиты =====
    private static final class Range { final int left, right; Range(int l,int r){left=l; right=r;} }
    /** Разбивка ширины на смещения от центра: поддерживает чётные и нечётные w. */
    private static Range rangeFor(int w) {
        if (w <= 1) return new Range(0,0);
        if (w % 2 == 1) { // нечётная: 5 → [-2..+2]
            int h = w/2; return new Range(h, h);
        } else {          // чётная: 6 → [-2..+3] (как в других генераторах)
            int h = w/2;  return new Range(h-1, h);
        }
    }

    private int localTopY(int x, int z, int H) {
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return base + 1 + H;
    }

    private static boolean inWorld(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return !(x<minX || x>maxX || z<minZ || z>maxZ);
    }

    // ===== Утилиты =====
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