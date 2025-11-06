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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class AerialwayGenerator {

    // ===== Материалы =====
    private static final Block LATTICE      = Blocks.IRON_BARS;       // решётка опор/станций
    private static final Block CABLE        = Blocks.DARK_OAK_FENCE;  // трос (как у ЛЭП-проводов)

    // ===== Геометрия/параметры =====
    private static final int WIRE_OFFSET_Y        = 10;  // трос на +10 над рельефом
    private static final int SUPPORT_HEIGHT       = Math.max(3, WIRE_OFFSET_Y - 1); // опора до уровня под тросом
    private static final int STATION_HEIGHT       = Math.max(3, WIRE_OFFSET_Y - 1); // станция до уровня под тросом
    private static final int SUPPORT_BASE_W       = 4;   // у земли 4×4
    private static final int SUPPORT_TOP_W        = 2;   // у верха 2×2
    private static final int STATION_W            = 4;   // станция всегда 4×4
    private static final int STEP_METERS          = 50;  // шаг опор вдоль линий (м≈блоки)
    private static final int MIN_SUPPORT_SPACING  = 10;  // не ставить новую опору ближе 10 блоков к существующей

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public AerialwayGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
        final boolean station; // true=станция, false=обычная опора
        Support(int x, int z, boolean station) { this.x=x; this.z=z; this.station=station; }
        int offset() { return WIRE_OFFSET_Y; }
    }
    private static final class Polyline {
        final List<int[]> pts = new ArrayList<>(); // [x,z]
    }

    private final Map<Long, Support> supportsByXZ = new HashMap<>();
    private final List<Polyline> lines = new ArrayList<>();

    private static long key(int x, int z) { return (((long)x) << 32) ^ (z & 0xffffffffL); }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "Aerialway: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "Aerialway: no center/bbox — skipping."); return;
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
                        if (read % 1000 == 0) broadcast(level, "Aerialway: features read ~" + read);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "Aerialway: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "Aerialway: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Aerialway: error reading features: " + ex.getMessage());
        }

        // Добавляем промежуточные опоры каждые 50 м вдоль линий
        densifySupportsAlongLines();

        if (supportsByXZ.isEmpty() && lines.isEmpty()) {
            broadcast(level, "Aerialway: no suitable objects found — done.");
            return;
        }

        // ===== Постройка опор и станций =====
        int placed = 0;
        int total = supportsByXZ.size();
        for (Support s : supportsByXZ.values()) {
            if (!inWorld(s.x, s.z, worldMinX, worldMaxX, worldMinZ, worldMaxZ)) continue;
            try {
                if (s.station) buildStation(s.x, s.z);
                else buildSupport(s.x, s.z);
            } catch (Exception ex) {
                broadcast(level, "Aerialway: build error at ("+s.x+","+s.z+"): " + ex.getMessage());
            }
            placed++;
            if (placed % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * placed / Math.max(1, total));
                broadcast(level, "Aerialway: supports/stations ~" + pct + "%");
            }
        }

        // ===== Тросы по линиям =====
        long segs = 0, totalSegs = 0;
        for (Polyline pl : lines) totalSegs += Math.max(0, pl.pts.size()-1);

        for (Polyline pl : lines) {
            List<int[]> P = pl.pts;
            if (P.size() < 2) continue;
            for (int i=0; i<P.size()-1; i++) {
                int[] p0 = P.get(i), p1 = P.get(i+1);
                placeCableSegment(p0[0], p0[1], p1[0], p1[1],
                        worldMinX, worldMaxX, worldMinZ, worldMaxZ);
                segs++;
                if (segs % Math.max(1, totalSegs/5) == 0) {
                    int pct = (int)Math.round(100.0 * segs / Math.max(1, totalSegs));
                    broadcast(level, "Aerialway: cables ~" + pct + "%");
                }
            }
        }

        broadcast(level, String.format(Locale.ROOT,
                "Aerialway: done. Supports/stations: %d, lines: %d, segments: %d",
                placed, lines.size(), segs));
    }

    // ===== Сбор фич =====
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        String type = optString(e, "type");
        if (t == null || type == null) return;

        String aerialway = low(optString(t, "aerialway"));

        // Станции
        if ("station".equals(aerialway)) {
            int[] xz = nodeToXZ(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (xz != null) supportsByXZ.put(key(xz[0], xz[1]), new Support(xz[0], xz[1], true));
            return;
        }
        // Явные опоры/пилоны (если размечены)
        if ("pylon".equals(aerialway) || "tower".equals(aerialway) || "support".equals(aerialway)) {
            int[] xz = nodeToXZ(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (xz != null) supportsByXZ.put(key(xz[0], xz[1]), new Support(xz[0], xz[1], false));
            return;
        }

        // Линии подъёмников: aerialway=* (кроме station/pylon)
        if (isAerialwayLine(aerialway)) {
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

    private static boolean isAerialwayLine(String v) {
        if (v == null) return false;
        // любой подъёмник, кроме явных узлов объектов
        return !v.equals("station") && !v.equals("pylon") && !v.equals("tower") && !v.equals("support");
    }

    private int[] nodeToXZ(JsonObject e,
                           double centerLat, double centerLng,
                           double east, double west, double north, double south,
                           int sizeMeters, int centerX, int centerZ) {
        String type = optString(e,"type");
        if ("node".equals(type) && e.has("lat") && e.has("lon")) {
            return latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        }
        JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
        if (g == null || g.size()==0) return null;
        JsonObject p = g.get(0).getAsJsonObject();
        return latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
    }

    // ===== Опоры/Станции =====
    private void buildSupport(int cx, int cz) {
        // каркас 4×4 → 2×2, высота до уровня под тросом
        for (int h=1; h<=SUPPORT_HEIGHT-1; h++) {
            double t = (SUPPORT_HEIGHT<=2) ? 1.0 : (h-1) / (double)(SUPPORT_HEIGHT-2);
            int w = Math.max(SUPPORT_TOP_W, (int)Math.round(SUPPORT_BASE_W - (SUPPORT_BASE_W - SUPPORT_TOP_W) * t));
            drawPerimeterLevelFollowingTerrain(cx, cz, w, h, LATTICE);
        }
        // верхняя крышка 2×2 на уровне (локальный «верх»)
        fillCapFollowingTop(cx, cz, SUPPORT_TOP_W, SUPPORT_HEIGHT, LATTICE);
    }

    private void buildStation(int cx, int cz) {
        // постоянная 4×4 решётка до уровня под тросом
        for (int h=1; h<=STATION_HEIGHT-1; h++) {
            drawPerimeterLevelFollowingTerrain(cx, cz, STATION_W, h, LATTICE);
        }
        // верхняя крыша 4×4
        fillCapFollowingTop(cx, cz, STATION_W, STATION_HEIGHT, LATTICE);
    }

    // Периметр квадрата на уровне h; каждая клетка опирается на свой groundY
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
                setBlock(x, base + 1 + h - 1, z, block);
            }
        }
    }

    // Сплошная крышка w×w на локальном «верху» (на 1 блок ниже троса)
    private void fillCapFollowingTop(int cx, int cz, int w, int H, Block block) {
        Range r = rangeFor(w);
        for (int x=cx - r.left; x<=cx + r.right; x++) {
            for (int z=cz - r.left; z<=cz + r.right; z++) {
                int y = localTopY(x, z, H);
                if (y == Integer.MIN_VALUE) continue;
                setBlock(x, y, z, block);
            }
        }
    }

    private int localTopY(int x, int z, int H) {
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return base + (H); // крышка на 1 ниже троса; трос на base+WIRE_OFFSET_Y
    }

    // ===== Вставка промежуточных опор каждые 50 м =====
    private void densifySupportsAlongLines() {
        int added = 0;
        for (Polyline pl : lines) {
            if (pl.pts.size() < 2) continue;

            double acc = 0.0;
            int[] prev = pl.pts.get(0);

            @SuppressWarnings("unused")
            // если рядом уже станция/опора — начинаем от неё
            int lastX = prev[0], lastZ = prev[1];

            for (int i=1; i<pl.pts.size(); i++) {
                int[] cur = pl.pts.get(i);
                int dx = cur[0]-prev[0], dz = cur[1]-prev[1];
                double segLen = Math.hypot(dx, dz);
                if (segLen <= 0.0001) { prev = cur; continue; }

                double ux = dx / segLen, uz = dz / segLen;
                double pos = 0.0;
                while (acc + (segLen - pos) >= STEP_METERS) {
                    double need = STEP_METERS - acc; // сколько пройти по текущему сегменту
                    pos += need;
                    int xi = (int)Math.round(prev[0] + ux * pos);
                    int zi = (int)Math.round(prev[1] + uz * pos);

                    if (!hasSupportNear(xi, zi, MIN_SUPPORT_SPACING)) {
                        supportsByXZ.put(key(xi, zi), new Support(xi, zi, false));
                        added++;
                    }
                    acc = 0.0;
                    lastX = xi; lastZ = zi;
                }
                acc += (segLen - pos);
                prev = cur;
            }
        }
        if (added > 0) broadcast(level, "Aerialway: added intermediate supports: " + added);
    }

    private boolean hasSupportNear(int x, int z, int radius) {
        for (int dx=-radius; dx<=radius; dx++) {
            for (int dz=-radius; dz<=radius; dz++) {
                if (supportsByXZ.containsKey(key(x+dx, z+dz))) return true;
            }
        }
        return false;
    }

    // ===== Трос =====
    private void placeCableSegment(int x0, int z0, int x1, int z1,
                                   int wMinX, int wMaxX, int wMinZ, int wMaxZ) {

        Integer off0 = findSupportOffsetNear(x0, z0);
        Integer off1 = findSupportOffsetNear(x1, z1);
        if (off0 == null) off0 = WIRE_OFFSET_Y;
        if (off1 == null) off1 = WIRE_OFFSET_Y;

        int dx = x1 - x0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            int y = clampWireY(x0, z0, off0);
            y = liftAboveGround(x0, z0, y); // <-- гарантируем не ниже ground+1
            if (inWorld(x0, z0, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(x0, y, z0, CABLE);
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

            int yiTarget = clampWireY(xi, zi, off); // ground + offset

            // плавный перепад по 1 блоку на шаг
            int yi = yiTarget;
            if (prevY != null) {
                if (yi > prevY + 1) yi = prevY + 1;
                if (yi < prevY - 1) yi = prevY - 1;
            }

            // не ниже, чем на 1 блок над рельефом
            yi = liftAboveGround(xi, zi, yi);

            if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) {
                setBlock(xi, yi, zi, CABLE);
            }
            prevY = yi;

            // на последнем шаге — точное «зацепление», но тоже не ниже ground+1
            if (i == steps) {
                int yExact = liftAboveGround(xi, zi, yiTarget);
                if (inWorld(xi, zi, wMinX, wMaxX, wMinZ, wMaxZ)) setBlock(xi, yExact, zi, CABLE);
            }
        }
    }

    private Integer findSupportOffsetNear(int x, int z) {
        Support s = supportsByXZ.get(key(x,z));
        if (s != null) return s.offset();
        // небольшой радиус — линии часто смещены на 1 клетку от узла
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            if (dx==0 && dz==0) continue;
            s = supportsByXZ.get(key(x+dx, z+dz));
            if (s != null) return s.offset();
        }
        return null;
    }

    private int clampWireY(int x, int z, int offset) {
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) base = level.getMinBuildHeight();
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

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== Геометрические утилиты =====
    private static final class Range { final int left, right; Range(int l,int r){left=l; right=r;} }
    private static Range rangeFor(int w) {
        if (w <= 1) return new Range(0,0);
        if (w % 2 == 1) {
            int h = w/2; return new Range(h, h);
        } else {
            int h = w/2; return new Range(h-1, h);
        }
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

    // всегда минимум на 1 блок выше рельефа
    private int liftAboveGround(int x, int z, int y) {
        int gy = groundY(x, z);
        if (gy == Integer.MIN_VALUE) return y;
        int minY = gy + 1;
        return (y < minY) ? minY : y;
    }

}