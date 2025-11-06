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
import java.util.Locale;

public class CaveEntranceGenerator {

    // ===== Материал =====
    private static final Block MATERIAL = Blocks.MOSSY_COBBLESTONE; // «замшелый булыжник»

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public CaveEntranceGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // Хранилище позиций (с дедупликацией)
    private final LinkedHashSet<Long> entrances = new LinkedHashSet<>();
    private static long key(int x, int z){ return (((long)x)<<32) ^ (z & 0xffffffffL); }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "CaveEntrance: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "CaveEntrance: no center/bbox — skipping."); return;
        }

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
                        if (read % 1000 == 0) broadcast(level, "CaveEntrance: ~ features read ~" + read);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "CaveEntrance: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "CaveEntrance: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "CaveEntrance: error reading features: " + ex.getMessage());
        }

        if (entrances.isEmpty()) { broadcast(level, "CaveEntrance: no entrances found — done."); return; }

        // ===== Постройка кубиков 3×3×2 из мшистого булыжника =====
        int total = entrances.size(), done = 0, placed = 0;
        for (long k : entrances) {
            int x = (int)(k >> 32);
            int z = (int)(k & 0xffffffffL);
            if (!inWorld(x, z, worldMinX, worldMaxX, worldMinZ, worldMaxZ)) continue;

            try {
                placeCube3x3x2OnGround(x, z);
                placed++;
            } catch (Throwable t) {
                broadcast(level, "CaveEntrance: error at ("+x+","+z+"): " + t.getMessage());
            }

            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                broadcast(level, "CaveEntrance: ~" + pct + "%");
            }
        }

        broadcast(level, "CaveEntrance: done. Built: " + placed);
    }

    // ===== Разбор фич =====
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        String type = optString(e, "type");
        if (t == null || type == null) return;

        if (!isCaveEntrance(t)) return;

        int ax, az;
        if ("node".equals(type)) {
            Double lat = e.has("lat") ? e.get("lat").getAsDouble() : null;
            Double lon = e.has("lon") ? e.get("lon").getAsDouble() : null;
            if (lat == null || lon == null) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble(); lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            ax = xz[0]; az = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            double[] ll = centroidLatLon(g);
            int[] xz = latlngToBlock(ll[0], ll[1], centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            ax = xz[0]; az = xz[1];
        } else return;

        entrances.add(key(ax, az));
    }

    private boolean isCaveEntrance(JsonObject t) {
        String nat = low(optString(t, "natural"));
        if ("cave_entrance".equals(nat)) return true;
        // бонус: иногда встречается альтернативная разметка
        String entrance = low(optString(t, "entrance"));
        return "cave".equals(entrance);
    }

    // ===== Постройка кубика 3×3×2 на земле =====
    /** Каждый столбик «сидит» на СВОЁМ рельефе: ставим на (ground+1) и (ground+2) для каждой клетки 3×3. */
    private void placeCube3x3x2OnGround(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx, z = cz + dz;
                int gy = groundY(x, z);
                if (gy == Integer.MIN_VALUE) continue;
                setBlock(x, gy + 1, z, MATERIAL);
                setBlock(x, gy + 2, z, MATERIAL);
            }
        }
    }

    // ===== Низкоуровневые сеттеры/рельеф =====
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

        // fallback: по миру
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static boolean inWorld(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return !(x < minX || x > maxX || z < minZ || z > maxZ);
    }

    // ===== Утилиты координат =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static String low(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

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
        return new double[]{ slat / Math.max(1, n), slon / Math.max(1, n) };
    }
}