package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.*;


public class RailStopLineGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Параметры стоп-линии
    private static final int STOP_OFFSET = 4;    // как далеко от узла вдоль дороги ставим линию (в блоках)
    private static final int LINE_THICKNESS = 1; // толщина линии вдоль дороги (1 блок)
    private static final int DEFAULT_WIDTH = 12; // fallback ширины, если не найдена по тегам

    // bbox в блоках (для клиппинга)
    private int minX, maxX, minZ, maxZ;

    public RailStopLineGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }
    public RailStopLineGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

    private static void broadcast(ServerLevel level, String msg) {
        try {
            if (level.getServer() != null)
                for (ServerPlayer p : level.getServer().getPlayerList().getPlayers())
                    p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }

    // ===== Запуск =====
    public void generate() {
        broadcast(level, "Generating stop lines at railway crossings (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping RailwayCrossingStopLineGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        double centerLat  = center.get("lat").getAsDouble();
        double centerLng  = center.get("lng").getAsDouble();
        int sizeMeters    = coords.get("sizeMeters").getAsInt();

        double south = bbox.get("south").getAsDouble();
        double north = bbox.get("north").getAsDouble();
        double west  = bbox.get("west").getAsDouble();
        double east  = bbox.get("east").getAsDouble();

        int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // bbox → блоки
        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]);
        maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]);
        maxZ = Math.max(a[1], b[1]);

        // ===== PASS1: узлы ЖД-переездов =====
        Map<Long, int[]> crossingNodeXZ = new HashMap<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                if (!"node".equals(opt(f, "type"))) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isRailCrossingNode(tags)) continue;

                Long id = asLong(f, "id");
                Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                if (id == null || lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                // держим, даже если чуть за bbox — отрисовка всё равно влезет в клиппинг
                crossingNodeXZ.put(id, xz);
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1 (nodes): " + ex.getMessage());
            return;
        }

        // ===== PASS2: пути (stream) — выбор лучшей автодороги, содержащей узел =====
        Map<Long, NodeChoice> best = new HashMap<>();

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;

                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                // интересны только ways с автомобильной проезжей частью
                String hwy = opt(wtags, "highway");
                if (hwy == null || !RoadGenerator.hasRoadMaterial(hwy)) continue;

                int width = Math.max(1, widthFromWayTagsOrDefault(wtags));

                JsonArray nds = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                if (nds == null || nds.size() < 2) continue;

                JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                        ? way.getAsJsonArray("geometry") : null;

                for (int i = 0; i < nds.size(); i++) {
                    long nid = nds.get(i).getAsLong();
                    int[] xz = crossingNodeXZ.get(nid);
                    if (xz == null) continue; // это не наш ЖД-переезд

                    DDir dir = null;

                    // Направление берём у ближайшей точки geometry к узлу
                    if (geom != null && geom.size() >= 2) {
                        dir = directionNearPointFromGeometry(geom, xz[0], xz[1],
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                    if (dir == null || dir.isZero()) continue;

                    // сохраняем лучший выбор по ширине
                    NodeChoice prev = best.get(nid);
                    if (prev == null || width > prev.width) {
                        best.put(nid, new NodeChoice(nid, xz[0], xz[1], dir, width));
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS2 (ways): " + ex.getMessage());
        }

        // ===== DRAW: для каждого найденного узла рисуем 2 линии — по обе стороны на ±STOP_OFFSET =====
        int drawn = 0;
        for (NodeChoice c : best.values()) {
            // линия «перед» переездом (одно направление)
            drawStopLineAcrossFullWidthD(c.x, c.z, c.dir, c.width, +STOP_OFFSET);
            // линия «с другой стороны»
            drawStopLineAcrossFullWidthD(c.x, c.z, c.dir, c.width, -STOP_OFFSET);
            drawn += 2;
        }

        broadcast(level, "Stop lines placed at railway crossings: " + drawn);
    }

    // ====== Отрисовка перпендикулярной линии через всю ширину дороги ======
    private void drawStopLineAcrossFullWidthD(int x0, int z0, DDir along, int width, int offsetAlong) {
        DDir a = along.unitNormalized();     // вдоль дороги
        DDir c = a.perp().unitNormalized();  // поперёк дороги

        // центр линии смещаем вдоль дороги на offsetAlong (±4 блока)
        double cx = x0 + a.dx * offsetAlong;
        double cz = z0 + a.dz * offsetAlong;

        // поперёк кладём всю ширину (чётные ряды как в Crosswalk — можно без антислипания, т.к. линия одна)
        final int half = width / 2;

        for (int w = -half; w <= half; w++) {
            // координата полосы поперёк
            double fx = cx + c.dx * w;
            double fz = cz + c.dz * w;

            // толщина линии вдоль дороги: 1 блок (можно расширить при желании)
            for (int t = -(LINE_THICKNESS/2); t <= (LINE_THICKNESS/2); t++) {
                double lx = fx + a.dx * t;
                double lz = fz + a.dz * t;

                int bx = (int)Math.round(lx);
                int bz = (int)Math.round(lz);

                if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) continue;

                Integer by = terrainGroundY(bx, bz);
                if (by == null) by = scanTopY(bx, bz);
                if (by == null) continue;

                level.setBlock(new BlockPos(bx, by, bz), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            }
        }
    }

    // ====== Геометрия / направления ======

    /** Направление у ближайшей точки геометрии к (x,z). */
    private static DDir directionNearPointFromGeometry(JsonArray geom,
                                                       int x, int z,
                                                       double centerLat, double centerLng,
                                                       double east, double west, double north, double south,
                                                       int sizeMeters, int centerX, int centerZ) {
        if (geom == null || geom.size() < 2) return null;

        // найдём индекс ближайшей вершины
        int bestIdx = -1;
        double bestD2 = Double.MAX_VALUE;

        for (int i = 0; i < geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            double dx = xz[0] - x;
            double dz = xz[1] - z;
            double d2 = dx*dx + dz*dz;
            if (d2 < bestD2) { bestD2 = d2; bestIdx = i; }
        }

        if (bestIdx == -1) return null;

        // вектор касательной: берём соседей (если край — ближайший сегмент)
        int i0 = Math.max(0, bestIdx - 1);
        int i1 = Math.min(geom.size() - 1, bestIdx + 1);
        if (i0 == i1) {
            if (bestIdx > 0) i0 = bestIdx - 1;
            if (bestIdx < geom.size() - 1) i1 = bestIdx + 1;
            if (i0 == i1) return null;
        }

        int[] A = latlngToBlock(geom.get(i0).getAsJsonObject().get("lat").getAsDouble(),
                                geom.get(i0).getAsJsonObject().get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] B = latlngToBlock(geom.get(i1).getAsJsonObject().get("lat").getAsDouble(),
                                geom.get(i1).getAsJsonObject().get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        return new DDir(B[0]-A[0], B[1]-A[1]).unitNormalized();
    }

    // ====== Ширина / теги ======
    private static int widthFromWayTagsOrDefault(JsonObject tags) {
        Integer w = parseNumericWidth(tags);
        if (w != null && w > 0) return w;

        String hwy = opt(tags, "highway");
        String aeroway = opt(tags, "aeroway");

        if (hwy != null && RoadGenerator.hasRoadMaterial(hwy)) {
            return RoadGenerator.getRoadWidth(hwy);
        }
        if (aeroway != null && RoadGenerator.hasRoadMaterial("aeroway:" + aeroway)) {
            return RoadGenerator.getRoadWidth("aeroway:" + aeroway);
        }
        return DEFAULT_WIDTH;
    }

    private static Integer parseNumericWidth(JsonObject tags) {
        if (tags == null) return null;
        String[] keys = new String[] { "width:carriageway", "width", "est_width", "runway:width", "taxiway:width" };
        for (String k : keys) {
            String v = opt(tags, k);
            if (v == null || v.isBlank()) continue;
            String s = v.trim().toLowerCase(Locale.ROOT).replace(",", ".");
            StringBuilder num = new StringBuilder();
            boolean dot = false;
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c == '.' && !dot) { num.append('.'); dot = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters);   // 1 м ~ 1 блок
                if (blocks > 0) return blocks;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // ====== Узел — ЖД-переезд? ======
    private static boolean isRailCrossingNode(JsonObject tags) {
        String railway = opt(tags, "railway");
        if (railway == null) return false;
        railway = railway.toLowerCase(Locale.ROOT);
        // Основной случай — автомобильный ж/д переезд:
        if ("level_crossing".equals(railway)) return true;
        // Иногда встречается railway=crossing (чаще пешеходный), возьмём,
        // но стоп-линия появится только если этот узел попадёт на автодорогу (см. PASS2).
        return "crossing".equals(railway);
    }

    // ====== Рельеф ======
    private Integer terrainGroundY(int x, int z) {
        try {
            if (store != null && store.grid != null && store.grid.inBounds(x, z)) {
                int v = store.grid.groundY(x, z);
                if (v != Integer.MIN_VALUE) return v;
            }
        } catch (Throwable ignore) {}
        Integer fromCoords = terrainGroundYFromCoords(x, z);
        if (fromCoords != null) return fromCoords;
        return scanTopY(x, z);
    }

    private Integer terrainGroundYFromCoords(int x, int z) {
        try {
            if (coords == null || !coords.has("terrainGrid")) return null;
            JsonObject tg = coords.getAsJsonObject("terrainGrid");
            if (tg == null) return null;

            int minGX = tg.get("minX").getAsInt();
            int minGZ = tg.get("minZ").getAsInt();
            int width = tg.get("width").getAsInt();
            int idx = (z - minGZ) * width + (x - minGX);
            if (idx < 0) return null;

            if (tg.has("grids") && tg.get("grids").isJsonObject()) {
                JsonObject grids = tg.getAsJsonObject("grids");
                if (!grids.has("groundY")) return null;
                JsonArray groundY = grids.getAsJsonArray("groundY");
                if (idx >= groundY.size() || groundY.get(idx).isJsonNull()) return null;
                return groundY.get(idx).getAsInt();
            }

            if (tg.has("data") && tg.get("data").isJsonArray()) {
                JsonArray data = tg.getAsJsonArray("data");
                if (idx >= data.size() || data.get(idx).isJsonNull()) return null;
                return data.get(idx).getAsInt();
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private Integer scanTopY(int x, int z) {
        final int max = level.getMaxBuildHeight() - 1;
        final int min = level.getMinBuildHeight();
        for (int y = max; y >= min; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return null;
    }

    // ====== JSON утилиты ======
    private static String opt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Long asLong(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsLong() : null; } catch (Throwable ignore){return null;} }
    private static Double asDouble(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsDouble() : null; } catch (Throwable ignore){return null;} }
    private static JsonObject tagsOf(JsonObject e) { return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null; }

    // ====== Преобразования координат ======
    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int)Math.round(centerX + dx), (int)Math.round(centerZ + dz)};
    }

    // ====== Вспомогательные классы ======
    private static final class DDir {
        final double dx, dz;
        DDir(double dx, double dz){ this.dx = dx; this.dz = dz; }
        boolean isZero(){ return Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9; }
        double len(){ return Math.hypot(dx, dz); }
        DDir unitNormalized(){
            double L = len();
            if (L < 1e-9) return new DDir(0, 0);
            return new DDir(dx / L, dz / L);
        }
        DDir perp(){ return new DDir(-dz, dx); }
    }

    private static final class NodeChoice {
        @SuppressWarnings("unused")
        final long id; final int x, z, width; final DDir dir;
        NodeChoice(long id, int x, int z, DDir dir, int width){ this.id=id; this.x=x; this.z=z; this.dir=dir; this.width=Math.max(1,width); }
    }
}