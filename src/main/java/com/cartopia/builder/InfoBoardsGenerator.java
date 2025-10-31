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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class InfoBoardsGenerator {

    // Материалы
    private static final Block PILLAR_BLOCK        = Blocks.DARK_OAK_PLANKS;
    private static final Block STANDING_SIGN_BLOCK = Blocks.DARK_OAK_SIGN;
    private static final Block WALL_SIGN_BLOCK     = Blocks.DARK_OAK_WALL_SIGN;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public InfoBoardsGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // Вещалка
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

    // Точка стенда
    private static final class InfoPoint {
        final int x, z;
        InfoPoint(int x, int z) { this.x = x; this.z = z; }
    }

    // ===== Запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "InfoBoardsGenerator: coords == null — пропускаю."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "InfoBoardsGenerator: нет center/bbox — пропускаю."); return; }

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

        List<InfoPoint> points = new ArrayList<>();
        Set<Long> seenNodeIds     = new HashSet<>();
        Set<Long> seenWayRelIds   = new HashSet<>();
        Set<Long> usedXZ          = new HashSet<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectInfoPoint(e, points, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "InfoBoardsGenerator: нет coords.features — пропускаю."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "InfoBoardsGenerator: features.elements пуст — пропускаю."); return; }
                for (JsonElement el : elements) {
                    collectInfoPoint(el.getAsJsonObject(), points, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "InfoBoardsGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            broadcast(level, "InfoBoardsGenerator: подходящих инфо-объектов не найдено — готово.");
            return;
        }

        long done = 0;
        for (InfoPoint p : points) {
            try {
                if (p.x < minX || p.x > maxX || p.z < minZ || p.z > maxZ) continue;
                buildInfoStand(p.x, p.z);
            } catch (Exception ignore) {}
            done++;
            if (done % Math.max(1, points.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, points.size()));
                broadcast(level, "Инфостенды: ~" + pct + "%");
            }
        }
        broadcast(level, "Инфостенды: готово, поставлено " + done + " шт.");
    }

    // ===== Сбор признаков =====
    private void collectInfoPoint(JsonObject e, List<InfoPoint> out,
                                  Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isInfoObject(tags)) return;

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

        out.add(new InfoPoint(px, pz));
    }

    private static boolean isInfoObject(JsonObject t) {
        String tourism = low(optString(t, "tourism"));
        String info    = low(optString(t, "information"));
        String amenity = low(optString(t, "amenity"));
        String adv     = low(optString(t, "advertising"));

        // исключаем рекламу
        if ("advertising".equals(amenity)) return false;
        if (adv != null && !adv.isEmpty()) return false;

        // допустимые подтипы
        final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
                "board","map","guidepost","terminal","tactile_map","route_marker","panel","sign"
        ));

        if ("information".equals(amenity)) return true; // на всякий случай

        if ("information".equals(tourism) && (info == null || ALLOWED.contains(info))) return true;
        if (info != null && ALLOWED.contains(info)) return true;

        return false;
    }

    // ===== Постройка узла =====
    private void buildInfoStand(int x, int z) {
        // База рельефа под центральной точкой
        int yBase = terrainY(x, z);
        if (yBase == Integer.MIN_VALUE) return;

        // Два блока тёмного дуба (столб) — ставим ТОЛЬКО в воздух
        placeBlockIfAir(x, yBase + 1, z, PILLAR_BLOCK);
        placeBlockIfAir(x, yBase + 2, z, PILLAR_BLOCK);

        // НИЖНИЙ ряд: обычные настенные — соседний блок со стороны, FACING = side (табличка «смотрит» от столба)
        placeWallSignAtSideIfAir(x, yBase + 1, z, Direction.EAST );
        placeWallSignAtSideIfAir(x, yBase + 1, z, Direction.WEST );
        placeWallSignAtSideIfAir(x, yBase + 1, z, Direction.SOUTH);
        placeWallSignAtSideIfAir(x, yBase + 1, z, Direction.NORTH);

        // ВЕРХНИЙ ряд: ТОЖЕ обычные настенные (никаких висячих), ровно как снизу
        placeWallSignAtSideIfAir(x, yBase + 2, z, Direction.EAST );
        placeWallSignAtSideIfAir(x, yBase + 2, z, Direction.WEST );
        placeWallSignAtSideIfAir(x, yBase + 2, z, Direction.SOUTH);
        placeWallSignAtSideIfAir(x, yBase + 2, z, Direction.NORTH);

        // Сверху — стоячая табличка (если там воздух)
        placeStandingSignIfAir(x, yBase + 3, z, 0);
    }

    /** Поставить настенную табличку в соседний блок со стороны side; FACING = side. Только если цель — воздух. */
    private void placeWallSignAtSideIfAir(int cx, int y, int cz, Direction side) {
        int sx = cx + side.getStepX();
        int sz = cz + side.getStepZ();
        placeWallSignIfAir(sx, y, sz, side);
    }

    // ===== Низкоуровневые постановщики БЕЗ затирания рельефа =====
    private void placeBlockIfAir(int x, int y, int z, Block block) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, block.defaultBlockState(), 3);
        }
    }

    private void placeWallSignIfAir(int x, int y, int z, Direction facing) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return; // ничего не затираем
        BlockState st = WALL_SIGN_BLOCK.defaultBlockState();
        try {
            if (st.hasProperty(HorizontalDirectionalBlock.FACING)) {
                st = st.setValue(HorizontalDirectionalBlock.FACING, facing);
            }
        } catch (Throwable ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeStandingSignIfAir(int x, int y, int z, int rotation0to15) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return; // не затираем
        BlockState st = STANDING_SIGN_BLOCK.defaultBlockState();
        try {
            if (st.hasProperty(StandingSignBlock.ROTATION)) {
                int rot = Math.max(0, Math.min(15, rotation0to15));
                st = st.setValue(StandingSignBlock.ROTATION, rot);
            }
        } catch (Throwable ignore) {}
        level.setBlock(pos, st, 3);
    }

    // ===== Рельеф =====
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

        // fallback — верхняя ненулевая колонна минус 1 => грунт
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== Утилиты =====
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