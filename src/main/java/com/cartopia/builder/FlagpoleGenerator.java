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


public class FlagpoleGenerator {

    // ===== Конфиг материалов/порогов =====
    private static final Block METAL_BLOCK = Blocks.IRON_BLOCK;
    private static final int   DEFAULT_HEIGHT = 15;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public FlagpoleGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // --- широковещалка
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

    // ===== внутренние типы =====
    private static final class Flagpole {
        final int x, z;
        final int height;  // в блоках
        Flagpole(int x, int z, int height){ this.x=x; this.z=z; this.height=height; }
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "FlagpoleGenerator: coords == null — skipping.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "FlagpoleGenerator: no center/bbox — skipping.");
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

        List<Flagpole> poles = new ArrayList<>();

        // ===== Сбор фич (стриминг если доступен) =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFlagpole(e, poles,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "FlagpoleGenerator: no coords.features — skipping.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "FlagpoleGenerator: features.elements is empty — skipping.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFlagpole(el.getAsJsonObject(), poles,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "FlagpoleGenerator: error reading features: " + ex.getMessage());
        }

        if (poles.isEmpty()) {
            broadcast(level, "FlagpoleGenerator: no flagpoles found — done.");
            return;
        }

        // ===== Рендер =====
        int done = 0, total = poles.size();
        for (Flagpole p : poles) {
            if (p.x<minX||p.x>maxX||p.z<minZ||p.z>maxZ) continue;
            try {
                renderFlagpole(p);
            } catch (Exception ex) {
                broadcast(level, "FlagpoleGenerator: error at ("+p.x+","+p.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1,total));
                broadcast(level, "Flagpoles: ~" + pct + "%");
            }
        }

        broadcast(level, "Flagpoles: done.");
    }

    // ===== сбор одной фичи =====
    private void collectFlagpole(JsonObject e, List<Flagpole> out,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isFlagpoleLike(tags)) return;

        String type = optString(e,"type");
        int cx, cz;

        if ("node".equals(type)) {
            double lat, lon;
            if (e.has("lat") && e.has("lon")) {
                lat = e.get("lat").getAsDouble();
                lon = e.get("lon").getAsDouble();
            } else {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble();
                lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            cx = xz[0]; cz = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 1) return;
            long sx=0, sz=0;
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                sx+=xz[0]; sz+=xz[1];
            }
            cx = (int)Math.round(sx/(double)g.size());
            cz = (int)Math.round(sz/(double)g.size());
        } else return;

        int hBlocks = extractHeightBlocks(tags);
        out.add(new Flagpole(cx, cz, hBlocks));
    }

    private static boolean isFlagpoleLike(JsonObject t) {
        String mm  = lower(optString(t, "man_made"));
        String ppt = lower(optString(t, "pole:type"));
        if ("flagpole".equals(mm)) return true;
        if ("flagpole".equals(ppt)) return true;
        // на всякий случай — любые значения, содержащие "flagpole"
        if (containsValueLike(t, "flagpole")) return true;
        return false;
    }

    private static String lower(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private int extractHeightBlocks(JsonObject t) {
        // подхватываем height из разных возможных ключей; метры ≈ блоки
        String[] keys = new String[]{
                "height",
                "flagpole:height",
                "flag:height"
        };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null && !v.isBlank()) {
                Double d = parseFirstNumber(v);
                if (d != null && d > 0) return Math.max(1, (int)Math.round(d));
            }
        }
        return DEFAULT_HEIGHT;
    }

    private static Double parseFirstNumber(String s) {
        String txt = s.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        StringBuilder num = new StringBuilder();
        boolean dot = false, seen = false, neg = false;
        for (int i=0;i<txt.length();i++){
            char c = txt.charAt(i);
            if ((c=='-' || c=='+') && !seen) { neg = (c=='-'); seen = true; continue; }
            if (Character.isDigit(c)) { num.append(c); seen = true; }
            else if (c=='.' && !dot) { num.append('.'); dot = true; seen = true; }
            else if (seen) break;
        }
        if (num.length()==0) return null;
        try { double val = Double.parseDouble(num.toString()); return neg ? -val : val; }
        catch (Exception ignore){ return null; }
    }

    // ===== рендер одного флагштока =====
    private void renderFlagpole(Flagpole p) {
        final int H = Math.max(1, p.height);

        // Выбираем сечение по порогам
        if (H > 100) {
            // круг диаметром 5 → радиус 2.5 (нечётный диаметр даём через double-порог).
            placeFilledDiskColumn(p.x, p.z, 2.5, H, METAL_BLOCK);
        } else if (H > 80) {
            // круг диаметром 4 → радиус 2.0
            placeFilledDiskColumn(p.x, p.z, 2.0, H, METAL_BLOCK);
        } else if (H > 50) {
            // квадрат 3×3
            placeFilledSquareColumnCentered(p.x, p.z, 3, H, METAL_BLOCK);
        } else {
            // квадрат 2×2
            placeFilledSquareColumnEven(p.x, p.z, 2, H, METAL_BLOCK);
        }
    }

    /** Заполненная колонна 2×2. Для чётного размера якорим в (x,z) как в левом нижнем углу. */
    private void placeFilledSquareColumnEven(int x, int z, int size, int height, Block material) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int bx = x + dx, bz = z + dz;
                int yBase = terrainY(bx, bz);
                if (yBase == Integer.MIN_VALUE) continue;
                for (int y = yBase + 1; y <= yBase + height; y++) {
                    level.setBlock(new BlockPos(bx, y, bz), material.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Заполненная колонна нечётного квадрата (3×3 и т.д.), центр в (x,z). */
    private void placeFilledSquareColumnCentered(int x, int z, int size, int height, Block material) {
        int k = size / 2; // для 3 → 1
        for (int dx = -k; dx <= k; dx++) {
            for (int dz = -k; dz <= k; dz++) {
                int bx = x + dx, bz = z + dz;
                int yBase = terrainY(bx, bz);
                if (yBase == Integer.MIN_VALUE) continue;
                for (int y = yBase + 1; y <= yBase + height; y++) {
                    level.setBlock(new BlockPos(bx, y, bz), material.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Заполненная колонна-диск радиуса r (double), центр в (x,z). */
    private void placeFilledDiskColumn(int x, int z, double radius, int height, Block material) {
        int ceilR = (int)Math.ceil(radius);
        double r2 = radius * radius + 1e-9;
        for (int dx = -ceilR; dx <= ceilR; dx++) {
            for (int dz = -ceilR; dz <= ceilR; dz++) {
                if ((dx*dx + dz*dz) <= r2) {
                    int bx = x + dx, bz = z + dz;
                    int yBase = terrainY(bx, bz);
                    if (yBase == Integer.MIN_VALUE) continue;
                    for (int y = yBase + 1; y <= yBase + height; y++) {
                        level.setBlock(new BlockPos(bx, y, bz), material.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // ===== высота рельефа =====
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

    // ===== утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static boolean containsValueLike(JsonObject t, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String k : t.keySet()) {
            JsonElement v = t.get(k);
            if (v == null || v.isJsonNull()) continue;
            try {
                String s = v.getAsString().toLowerCase(Locale.ROOT);
                if (s.contains(n)) return true;
            } catch (Exception ignore) {}
        }
        return false;
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