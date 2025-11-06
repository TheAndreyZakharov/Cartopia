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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class PostBoxGenerator {

    // --- Материалы ---
    private static final Block BASE_FENCE = Blocks.OAK_FENCE; // стойка
    private static final Block CHEST      = Blocks.CHEST;     // «ящик» сверху

    private final ServerLevel level;
    private final JsonObject  coords;
    private final GenerationStore store;

    public PostBoxGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
    private static final class MailPoint {
        final int x, z;
        MailPoint(int x, int z){ this.x = x; this.z = z; }
    }

    // --- запуск ---
    public void generate() {
        if (coords == null) { broadcast(level, "PostBoxGenerator: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "PostBoxGenerator: no center/bbox — skipping."); return; }

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

        List<MailPoint> points = new ArrayList<>();
        // дедупликация
        Set<Long> seenNodeIds    = new HashSet<>();
        Set<Long> seenWayRelIds  = new HashSet<>();
        Set<Long> usedXZ         = new HashSet<>(); // (x<<32) ^ (z & 0xffffffff)

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectMailPoint(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "PostBoxGenerator: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "PostBoxGenerator: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collectMailPoint(el.getAsJsonObject(), points, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "PostBoxGenerator: error reading features: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            broadcast(level, "PostBoxGenerator: no post boxes found — done.");
            return;
        }

        long done = 0;
        for (MailPoint p : points) {
            try {
                if (p.x < minX || p.x > maxX || p.z < minZ || p.z > maxZ) continue;
                placeMailbox(p.x, p.z);
            } catch (Exception ex) {
                broadcast(level, "PostBoxGenerator: error at ("+p.x+","+p.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, points.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, points.size()));
                broadcast(level, "Post boxes: ~" + pct + "%");
            }
        }
        broadcast(level, "Post boxes: done, placed " + done + " pcs.");
    }

    // --- сбор почтовых ящиков ---
    private void collectMailPoint(JsonObject e, List<MailPoint> out,
                                  Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isPostBox(tags)) return;

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

        out.add(new MailPoint(px, pz));
    }

    /** Распознавание «почтового ящика и т.п.» по тегам. */
    private static boolean isPostBox(JsonObject t) {
        String amenity = low(optString(t, "amenity"));
        String manmade = low(optString(t, "man_made"));

        // основной тег в OSM
        if ("post_box".equals(amenity)) return true;

        // иногда встречается альтернативный синоним
        if ("letter_box".equals(amenity)) return true;
        if ("mailbox".equals(amenity))    return true; // редкое

        // на всякий случай — через man_made (редко)
        if ("post_box".equals(manmade) || "letter_box".equals(manmade)) return true;

        return false;
    }

    // --- постановка: забор + одиночный сундук прямо «в точке» ---
    private void placeMailbox(int x, int z) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return;

        // расчистим воздух на 3 блока
        clearAir(x, y + 1, z, 3);

        // стойка
        setBlock(x, y + 1, z, BASE_FENCE);

        // сундук (насильно SINGLE, чтобы рядом не слипался)
        BlockState chest = CHEST.defaultBlockState();
        try {
            chest = chest.setValue(ChestBlock.TYPE, ChestType.SINGLE);
            // ориентацию не навязываем — дефолт ок; при желании можно повернуть к дороге
        } catch (Throwable ignore) {}
        setBlockState(x, y + 2, z, chest);
    }

    // --- расчистка воздуха ---
    private void clearAir(int x, int yStart, int z, int height) {
        for (int dy = 0; dy < height; dy++) {
            level.setBlock(new BlockPos(x, yStart + dy, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // --- низкоуровневые set'ы ---
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }
    private void setBlockState(int x, int y, int z, BlockState st) {
        level.setBlock(new BlockPos(x, y, z), st, 3);
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