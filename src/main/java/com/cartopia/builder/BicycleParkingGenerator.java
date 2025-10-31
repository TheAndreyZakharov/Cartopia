package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class BicycleParkingGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public BicycleParkingGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }

    // ---- вещалка ----
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

    // ---- типы ----
    private static final class BikePoint {
        final int x, z;
        BikePoint(int x, int z){ this.x = x; this.z = z; }
    }

    /** Полилиния дороги для определения направления. */
    private static final class Polyline {
        final int[] xs, zs;
        Polyline(int[] xs, int[] zs){ this.xs = xs; this.zs = zs; }
    }

    private final List<Polyline> roads = new ArrayList<>();

    // bbox в блоках (для клиппинга)
    private int minX, maxX, minZ, maxZ;

    // ---- запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "BicycleParkingGenerator: coords == null — пропуск."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "BicycleParkingGenerator: нет center/bbox — пропуск."); return; }

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
        minX = Math.min(a[0], b[0]);
        maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]);
        maxZ = Math.max(a[1], b[1]);

        List<BikePoint> points = new ArrayList<>();
        Set<Long> seenNodeIds = new HashSet<>();
        Set<Long> seenWayRelIds = new HashSet<>();
        Set<Long> usedXZ = new HashSet<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectBikeParking(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        collectRoadPolyline(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "BicycleParkingGenerator: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "BicycleParkingGenerator: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    JsonObject e = el.getAsJsonObject();
                    collectBikeParking(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    collectRoadPolyline(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "BicycleParkingGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            broadcast(level, "BicycleParkingGenerator: велопарковок не найдено — готово.");
            return;
        }

        long done = 0;
        for (BikePoint p : points) {
            try {
                if (p.x < minX || p.x > maxX || p.z < minZ || p.z > maxZ) continue;
                int[] dir = dirAlongNearestRoad(p.x, p.z); // {dx, dz} ∈ {(-1,0),(1,0),(0,-1),(0,1)}
                placeBikeRack(p.x, p.z, dir[0], dir[1]);
            } catch (Exception ex) {
                broadcast(level, "BicycleParkingGenerator: ошибка на ("+p.x+","+p.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, points.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, points.size()));
                broadcast(level, "Велопарковки: ~" + pct + "%");
            }
        }
        broadcast(level, "Велопарковки: готово, поставлено " + done + " рядов.");
    }

    // ---- сбор велопарковок ----
    private void collectBikeParking(JsonObject e, List<BikePoint> out,
                                    Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isBikeParking(tags)) return;

        String type = optString(e, "type");
        if (type == null) return;

        int px, pz;

        if ("node".equals(type)) {
            Long nid = optLong(e, "id");
            if (nid != null && !seenNodeIds.add(nid)) return;

            Double lat = optDouble(e, "lat"), lon = optDouble(e, "lon");
            if (lat == null || lon == null) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble(); lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            px = xz[0]; pz = xz[1];

        } else if ("way".equals(type) || "relation".equals(type)) {
            Long id = optLong(e, "id");
            if (id != null && !seenWayRelIds.add(id)) return;

            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 1) return;

            long sx = 0, sz = 0; int n = 0;
            for (int i=0;i<g.size();i++) {
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                sx += xz[0]; sz += xz[1]; n++;
            }
            if (n == 0) return;
            px = (int)Math.round(sx / (double)n);
            pz = (int)Math.round(sz / (double)n);
        } else {
            return;
        }

        long key = (((long)px) << 32) ^ (pz & 0xffffffffL);
        if (!usedXZ.add(key)) return;

        out.add(new BikePoint(px, pz));
    }

    private static boolean isBikeParking(JsonObject t) {
        String amenity = low(optString(t, "amenity"));
        String parking = low(optString(t, "parking"));
        if ("bicycle_parking".equals(amenity)) return true;
        return "parking".equals(amenity) && "bicycle".equals(parking);
    }

    // ---- сбор дорог для ориентации ----
    private void collectRoadPolyline(JsonObject e,
                                     double centerLat, double centerLng,
                                     double east, double west, double north, double south,
                                     int sizeMeters, int centerX, int centerZ) {
        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        String hwy = low(optString(tags, "highway"));
        if (hwy == null || !isRoadLikeForOrientation(hwy)) return;

        if (!"way".equals(optString(e, "type"))) return;
        JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
        if (g == null || g.size() < 2) return;

        int[] xs = new int[g.size()], zs = new int[g.size()];
        for (int i=0;i<g.size();i++){
            JsonObject p=g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            xs[i]=xz[0]; zs[i]=xz[1];
        }
        roads.add(new Polyline(xs, zs));
    }

    private static boolean isRoadLikeForOrientation(String hwy) {
        switch (hwy) {
            case "motorway": case "trunk": case "primary": case "secondary": case "tertiary":
            case "unclassified": case "residential": case "living_street":
            case "service":
            case "pedestrian": case "footway": case "path": case "cycleway":
            case "track":
                return true;
            default: return false;
        }
    }

    // ---- постановка 5 lightning rod подряд вдоль направления ----
    private void placeBikeRack(int cx, int cz, int dx, int dz) {
        // центрируем ряд: t = -2..+2
        for (int t = -2; t <= 2; t++) {
            int x = cx + dx * t;
            int z = cz + dz * t;

            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

            int y = terrainY(x, z);
            if (y == Integer.MIN_VALUE) continue;

            // если грунт — вода, ставим в этот же блок (заменим воду), иначе на y+1
            boolean groundIsWater = level.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.WATER;

            // расчистка воздуха над предполагаемым местом
            clearAir(x, (groundIsWater ? y : y + 1), z, groundIsWater ? 1 : 2);

            BlockPos pos = new BlockPos(x, groundIsWater ? y : (y + 1), z);
            placeLightningRodUp(pos);
        }
    }

    private void placeLightningRodUp(BlockPos pos) {
        BlockState st = Blocks.LIGHTNING_ROD.defaultBlockState();
        try {
            // LightningRodBlock наследует RodBlock с FACING — ставим вертикально вверх
            st = st.setValue(LightningRodBlock.FACING, Direction.UP);
        } catch (Throwable ignore) { /* на всякий */ }
        level.setBlock(pos, st, 3);
    }

    private void clearAir(int x, int yStart, int z, int h) {
        for (int i = 0; i < h; i++) {
            level.setBlock(new BlockPos(x, yStart + i, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // ---- направление вдоль ближайшей дороги (кардинально) ----
    private int[] dirAlongNearestRoad(int x, int z) {
        double bestD2 = Double.POSITIVE_INFINITY;
        double vx = 1, vz = 0; // дефолт +X

        for (Polyline pl : roads) {
            int n = pl.xs.length;
            for (int i=0; i<n-1; i++) {
                double ax = pl.xs[i],    az = pl.zs[i];
                double bx = pl.xs[i+1],  bz = pl.zs[i+1];
                // проекция на сегмент
                double[] pr = projectPointOnSegment(x, z, ax, az, bx, bz); // {cx,cz,d2}
                double d2 = pr[2];
                if (d2 < bestD2) {
                    bestD2 = d2;
                    vx = bx - ax;
                    vz = bz - az;
                }
            }
        }
        // кардинализуем: вдоль X или Z, знак по компоненте
        if (Math.abs(vx) >= Math.abs(vz)) {
            return new int[]{ vx >= 0 ? 1 : -1, 0 };
        } else {
            return new int[]{ 0, vz >= 0 ? 1 : -1 };
        }
    }

    // ---- рельеф ----
    private int terrainY(int x, int z) {
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
                    int minGX = g.get("minX").getAsInt();
                    int minGZ = g.get("minZ").getAsInt();
                    int w     = g.get("width").getAsInt();
                    int h     = g.get("height").getAsInt();
                    int ix = x - minGX, iz = z - minGZ;
                    if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz * w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ---- утилиты ----
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Long optLong(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsLong() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Double optDouble(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsDouble() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

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

    /** проектирует точку на сегмент AB, возвращает {cx,cz,d2} */
    private static double[] projectPointOnSegment(double x, double z, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double wx = x - ax,  wz = z - az;
        double vv = vx*vx + vz*vz;
        double t = vv > 1e-9 ? (vx*wx + vz*wz) / vv : 0.0;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double cx = ax + t*vx, cz = az + t*vz;
        double dx = x - cx,   dz = z - cz;
        return new double[]{cx, cz, dx*dx + dz*dz};
    }
}