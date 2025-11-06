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

public class FountainGenerator { 
 
    // Материалы / размеры
    private static final Block MAT_BLOCK = Blocks.SMOOTH_QUARTZ; // «смус кварц»
    private static final Block WATER     = Blocks.WATER;

    private static final int BASE_SIDE = 5;          // квадрат 5×5 на «уровне земли»
    private static final int DEFAULT_PILLAR_H = 8;   // дефолтная высота столба

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public FountainGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
    private static final class Fountain {
        final int x, z;
        final int pillarHeight; // высота столба (в блоках)
        Fountain(int x, int z, int pillarHeight) { this.x = x; this.z = z; this.pillarHeight = pillarHeight; }
    }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "FountainGenerator: coords == null — skipping."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "FountainGenerator: no center/bbox — skipping."); return; }

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

        List<Fountain> list = new ArrayList<>();

        // ===== Сбор признаков со стримингом =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFountain(e, list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "FountainGenerator: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "FountainGenerator: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collectFountain(el.getAsJsonObject(), list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "FountainGenerator: error reading features: " + ex.getMessage());
        }

        if (list.isEmpty()) { broadcast(level, "FountainGenerator: no suitable fountains found — done."); return; }

        // ===== Построение =====
        int done = 0, total = list.size();
        for (Fountain f : list) {
            if (f.x < minX || f.x > maxX || f.z < minZ || f.z > maxZ) continue;
            try {
                buildFountainAdaptingTerrain(f.x, f.z, f.pillarHeight);
            } catch (Throwable t) {
                broadcast(level, "FountainGenerator: error at ("+f.x+","+f.z+"): " + t.getMessage());
            }
            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                broadcast(level, "Fountains: ~" + pct + "%");
            }
        }
    }

    // ===== Разбор признаков =====
    private void collectFountain(JsonObject e, List<Fountain> out,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isFountain(tags)) return;

        String type = optString(e, "type");
        int fx, fz;

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
            fx = xz[0]; fz = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            double[] ll = centroidLatLon(g);
            int[] xz = latlngToBlock(ll[0], ll[1], centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            fx = xz[0]; fz = xz[1];
        } else return;

        int h = extractPillarHeightBlocks(tags);
        out.add(new Fountain(fx, fz, h));
    }

    private static boolean isFountain(JsonObject t) {
        String amenity = optString(t, "amenity");
        if (amenity != null && amenity.equalsIgnoreCase("fountain")) return true;

        String mm = optString(t, "man_made");
        if (mm != null && mm.equalsIgnoreCase("fountain")) return true;

        String wf = optString(t, "water_feature");
        if (wf != null && wf.toLowerCase(Locale.ROOT).contains("fountain")) return true;

        String f = optString(t, "fountain");
        if (f != null && (f.equalsIgnoreCase("yes") || f.toLowerCase(Locale.ROOT).contains("fountain"))) return true;

        return false;
    }

    private int extractPillarHeightBlocks(JsonObject tags) {
        String[] keys = new String[] { "fountain:height", "height" };
        for (String k : keys) {
            String v = optString(tags, k);
            if (v != null) {
                Double d = parseFirstDouble(v);
                if (d != null && d > 0) {
                    int h = (int)Math.round(d);
                    if (h < 1) h = 1;
                    if (h > 64) h = 64; // санити-лимит
                    return h;
                }
            }
        }
        return DEFAULT_PILLAR_H;
    }

    // ===== Постройка фонтана с подгонкой под рельеф =====
    private void buildFountainAdaptingTerrain(int cx, int cz, int pillarH) {
        // 5×5 центрированный на (cx,cz)
        int half = BASE_SIDE / 2; // 2
        int minX = cx - half, maxX = cx + half;
        int minZ = cz - half, maxZ = cz + half;

        // 1) Находим «верхний» уровень грунта в пятне 5×5
        int topGy = Integer.MIN_VALUE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int gy = groundY(x, z);
                if (gy != Integer.MIN_VALUE) topGy = Math.max(topGy, gy);
            }
        }
        if (topGy == Integer.MIN_VALUE) return; // нет данных по высоте
        int baseY = topGy + 1;

        // 2) Под каждую клетку 5×5 дотягиваем «подпорку» до baseY и кладём базовый квадрат на baseY
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int gy = groundY(x, z);
                if (gy == Integer.MIN_VALUE) continue;

                // подпорка снизу до уровня baseY-1
                for (int y = gy + 1; y < baseY; y++) {
                    setBlock(x, y, z, MAT_BLOCK);
                }
                // базовый слой на «уровне земли»
                setBlock(x, baseY, z, MAT_BLOCK);
            }
        }

        // 3) Периметр на один уровень выше (кольцо по границе квадрата)
        int yPerim = baseY + 1;
        for (int x = minX; x <= maxX; x++) {
            setBlock(x, yPerim, minZ, MAT_BLOCK);
            setBlock(x, yPerim, maxZ, MAT_BLOCK);
        }
        for (int z = minZ; z <= maxZ; z++) {
            setBlock(minX, yPerim, z, MAT_BLOCK);
            setBlock(maxX, yPerim, z, MAT_BLOCK);
        }

        // 4) Центральный столб высотой pillarH, стартуя от baseY+1, и источник воды на верхушке
        int colX = cx, colZ = cz;
        for (int k = 1; k <= pillarH; k++) {
            setBlock(colX, baseY + k, colZ, MAT_BLOCK);
        }
        // Вода на верхушке столба (источник)
        setBlock(colX, baseY + pillarH + 1, colZ, WATER);
    }

    // ===== Блоки / рельеф =====
    private void setBlock(int x, int y, int z, Block block) {
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

    private static Double parseFirstDouble(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && !((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.' || s.charAt(i) == '-')) i++;
        if (i == len) return null;
        int j = i + 1;
        boolean dotSeen = (s.charAt(i) == '.');
        while (j < len) {
            char c = s.charAt(j);
            if ((c >= '0' && c <= '9') || (!dotSeen && c == '.')) {
                if (c == '.') dotSeen = true;
                j++;
            } else break;
        }
        try { return Double.parseDouble(s.substring(i, j)); } catch (Exception ignore) { return null; }
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