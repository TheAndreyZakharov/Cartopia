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

public class WaterSourcesGenerator {

    // --- Материалы ---
    private static final Block BASE_BLOCK = Blocks.STONE_BRICKS; // основание
    private static final Block TOP_HOPPER = Blocks.HOPPER;       // сверху хоппер

    private final ServerLevel level;
    private final JsonObject  coords;
    private final GenerationStore store;

    public WaterSourcesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // --- вещалка ---
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

    // --- структура точки ---
    private static final class WaterPoint {
        final int x, z;
        WaterPoint(int x, int z){ this.x = x; this.z = z; }
    }

    // --- запуск ---
    public void generate() {
        if (coords == null) { broadcast(level, "WaterSourcesGenerator: coords == null — пропуск."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "WaterSourcesGenerator: нет center/bbox — пропуск."); return; }

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

        List<WaterPoint> points = new ArrayList<>();
        // дедупликация
        Set<Long> seenNodeIds = new HashSet<>();
        Set<Long> seenWayRelIds = new HashSet<>();
        Set<Long> usedXZ = new HashSet<>(); // (x<<32) ^ (z & 0xffffffff)

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectWaterPoint(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "WaterSourcesGenerator: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "WaterSourcesGenerator: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    collectWaterPoint(el.getAsJsonObject(), points, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "WaterSourcesGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            broadcast(level, "WaterSourcesGenerator: подходящих источников воды не найдено — готово.");
            return;
        }

        long done = 0;
        for (WaterPoint p : points) {
            try {
                if (p.x < minX || p.x > maxX || p.z < minZ || p.z > maxZ) continue;
                placeWaterBlock(p.x, p.z);
            } catch (Exception ex) {
                broadcast(level, "WaterSourcesGenerator: ошибка на ("+p.x+","+p.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, points.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, points.size()));
                broadcast(level, "Источники воды: ~" + pct + "%");
            }
        }
        broadcast(level, "Источники воды: готово, поставлено " + done + " шт.");
    }

    // --- сбор признаков воды ---
    private void collectWaterPoint(JsonObject e, List<WaterPoint> out,
                                   Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                   double centerLat, double centerLng,
                                   double east, double west, double north, double south,
                                   int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isWaterSource(tags)) return;

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

        out.add(new WaterPoint(px, pz));
    }

    private static boolean isWaterSource(JsonObject t) {
        String amenity = low(optString(t, "amenity"));
        String manmade = low(optString(t, "man_made"));
        String natural = low(optString(t, "natural"));

        // фонтаны не ставим
        if ("fountain".equals(amenity)) return false;

        // Явные «точки воды» — ставим всегда (не требуем drinking_water=yes)
        if ("drinking_water".equals(amenity)) return true; // питьевая колонка/кран
        if ("water_point".equals(amenity))    return true; // точка набора воды
        if ("water".equals(amenity))          return true; // редкое использование

        // Технические источники
        if ("water_well".equals(manmade))     return true; // колодец
        if ("water_tap".equals(manmade))      return true; // уличный кран/колонка

        // Природный источник
        if ("spring".equals(natural))         return true; // родник

        return false;
    }

    // --- постановка блока воды (БЕЗ оглядки на поверхность) ---
    private void placeWaterBlock(int x, int z) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return;

        // Мягко очищаем воздух над грунтом: 3 блока (под основание и хоппер)
        clearAir(x, y + 1, z, 3);

        // Ставим всегда прямо в координате: основание + хоппер
        setBlock(x, y + 1, z, BASE_BLOCK);
        setBlock(x, y + 2, z, TOP_HOPPER);
    }

    // --- расчистка воздуха над рельефом ---
    private void clearAir(int x, int yStart, int z, int height) {
        for (int dy = 0; dy < height; dy++) {
            level.setBlock(new BlockPos(x, yStart + dy, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // --- низкоуровневые set'ы ---
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }

    // --- высота рельефа ---
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

    // --- утилиты ---
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
}