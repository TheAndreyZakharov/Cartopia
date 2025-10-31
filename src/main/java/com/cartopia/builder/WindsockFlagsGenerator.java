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
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class WindsockFlagsGenerator {

    // --- Что ставим ---
    private static final Block BANNER_BLOCK = Blocks.RED_BANNER;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public WindsockFlagsGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // --- точка установки ---
    private static final class FlagPoint {
        final int x, z;
        FlagPoint(int x, int z){ this.x = x; this.z = z; }
    }

    // --- запуск ---
    public void generate() {
        if (coords == null) { broadcast(level, "WindsockFlagsGenerator: coords == null — пропуск."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "WindsockFlagsGenerator: нет center/bbox — пропуск."); return; }

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

        List<FlagPoint> points = new ArrayList<>();
        Set<Long> seenNodeIds   = new HashSet<>();
        Set<Long> seenWayRelIds = new HashSet<>();
        Set<Long> usedXZ        = new HashSet<>();

        // ---- чтение OSM (stream / batch) ----
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFlagPoint(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "WindsockFlagsGenerator: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "WindsockFlagsGenerator: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    collectFlagPoint(el.getAsJsonObject(), points, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "WindsockFlagsGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            broadcast(level, "WindsockFlagsGenerator: подходящих точек не найдено — готово.");
            return;
        }

        // ---- постановка баннеров ----
        long placed = 0;
        for (FlagPoint p : points) {
            if (p.x < worldMinX || p.x > worldMaxX || p.z < worldMinZ || p.z > worldMaxZ) continue;
            if (placeBannerOnTop(p.x, p.z)) placed++;
        }
        broadcast(level, "Флажки (windsock): поставлено " + placed + " шт.");
    }

    // --- фильтр тегов + геометрия -> точка ---
    private void collectFlagPoint(JsonObject e,
                                  List<FlagPoint> out,
                                  Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isWindFlagFeature(tags)) return;

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

            // Возьмём центр масс по узлам geometry / members.geometry
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;

            // relation (multipolygon) может не иметь geometry на корне — попробуем members
            if ((g == null || g.size() == 0) && "relation".equals(type) && e.has("members") && e.get("members").isJsonArray()) {
                JsonArray members = e.getAsJsonArray("members");
                List<int[]> all = new ArrayList<>();
                for (JsonElement mEl : members) {
                    JsonObject m = mEl.getAsJsonObject();
                    JsonArray mg = (m.has("geometry") && m.get("geometry").isJsonArray()) ? m.getAsJsonArray("geometry") : null;
                    if (mg == null) continue;
                    for (JsonElement pEl : mg) {
                        JsonObject p = pEl.getAsJsonObject();
                        all.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                    }
                }
                if (all.isEmpty()) return;
                long sx=0, sz=0; for (int[] xz : all){ sx+=xz[0]; sz+=xz[1]; }
                px = (int)Math.round(sx / (double)all.size());
                pz = (int)Math.round(sz / (double)all.size());
            } else {
                if (g == null || g.size() == 0) return;
                long sx=0, sz=0; int n=0;
                for (int i=0;i<g.size();i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    sx += xz[0]; sz += xz[1]; n++;
                }
                if (n==0) return;
                px = (int)Math.round(sx/(double)n);
                pz = (int)Math.round(sz/(double)n);
            }
        } else {
            return;
        }

        long key = (((long)px) << 32) ^ (pz & 0xffffffffL);
        if (!usedXZ.add(key)) return;

        out.add(new FlagPoint(px, pz));
    }

    /** какие теги считаем «точкой флажка/ветроуказателя» */
    private static boolean isWindFlagFeature(JsonObject t) {
        String aeroway   = low(optString(t, "aeroway"));       // основное: aeroway=windsock
        String man_made  = low(optString(t, "man_made"));      // доп.: флагштоки
        String flagType  = low(optString(t, "flag:type"));     // иногда указывают тип флага
        String flag      = low(optString(t, "flag"));          // редкость, но встречается

        if ("windsock".equals(aeroway)) return true;                             // 1) windsock
        if ("flagpole".equals(man_made) && ("windsock".equals(flagType) || "windsock".equals(flag))) return true; // 2) флагшток с windsock-флагом
        return false;
    }

    // --- установка баннера: на САМЫЙ ВЕРХНИЙ твёрдый блок колонки (включая крыши/кроны и т.д.) ---
    private boolean placeBannerOnTop(int x, int z) {
        int yTop = findTopSolidY(x, z);
        if (yTop == Integer.MIN_VALUE) return false;

        int placeY = yTop + 1;
        if (placeY >= level.getMaxBuildHeight()) return false;

        BlockPos pos = new BlockPos(x, placeY, z);
        BlockState st = BANNER_BLOCK.defaultBlockState();

        // Детерминированный поворот, чтобы не «дёргалось» между перезапусками
        int rot = Math.floorMod(((x * 734287 ^ z * 912367) * 31), 16);
        try {
            if (st.hasProperty(BannerBlock.ROTATION)) {
                st = st.setValue(BannerBlock.ROTATION, rot);
            }
        } catch (Throwable ignore) {}

        level.setBlock(pos, st, 3);
        return true;
    }

    /**
     * Находим верхний ТВЁРДЫЙ блок в колонке (x,z).
     * Берём WORLD_SURFACE как базу и, если верхний блок не выдерживает баннер (вода/растения),
     * отсканируем вниз до первого «стабильного» блока (state.isFaceSturdy(UP)).
     */
    private int findTopSolidY(int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; // действительно самый верхний «видимый» слой
        int minY = level.getMinBuildHeight();

        for (int yy = y; yy >= minY; yy--) {
            BlockPos p = new BlockPos(x, yy, z);
            BlockState s = level.getBlockState(p);
            try {
                // нужен блок, на который можно поставить сверху
                if (!s.isAir() && s.isFaceSturdy(level, p, Direction.UP)) {
                    return yy;
                }
            } catch (Throwable ignore) {}
        }
        return Integer.MIN_VALUE;
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