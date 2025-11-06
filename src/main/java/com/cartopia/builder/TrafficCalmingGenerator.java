package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.*;

public class TrafficCalmingGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Геометрия «бампа»
    private static final int LINE_THICKNESS = 1; // толщина вдоль дороги (1 блок)
    private static final int DEFAULT_WIDTH  = 12; // fallback ширина, если не нашли по тегам/материалам

    // bbox в блоках (для клиппинга)
    private int minX, maxX, minZ, maxZ;

    public TrafficCalmingGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }

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
        broadcast(level, "Generating traffic calming (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox")) {
            broadcast(level, "No coords/center/bbox — skipping TrafficCalmingGenerator.");
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

        // ===== PASS1: собираем ноды traffic_calming =====
        Map<Long, int[]> calmingNodeXZ = new HashMap<>();
        Set<Long> seenNodeIds = new HashSet<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject f : fs) {
                        if (!"node".equals(opt(f, "type"))) continue;
                        JsonObject tags = tagsOf(f);
                        if (tags == null) continue;
                        if (!isTrafficCalmingNode(tags)) continue;

                        Long id = asLong(f, "id");
                        Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                        if (id == null || lat == null || lon == null) continue;
                        if (!seenNodeIds.add(id)) continue;

                        int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        calmingNodeXZ.put(id, xz); // даже если чуть за bbox — клиппинг дальше
                    }
                }
            } else {
                // fallback: берём из coords.features
                if (!coords.has("features")) {
                    broadcast(level, "TrafficCalmingGenerator: no coords.features — skipping.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                for (JsonElement el : elements) {
                    JsonObject f = el.getAsJsonObject();
                    if (!"node".equals(opt(f, "type"))) continue;
                    JsonObject tags = tagsOf(f);
                    if (tags == null) continue;
                    if (!isTrafficCalmingNode(tags)) continue;

                    Long id = asLong(f, "id");
                    Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                    if (id == null || lat == null || lon == null) continue;
                    if (!seenNodeIds.add(id)) continue;

                    int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    calmingNodeXZ.put(id, xz);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1 (nodes): " + ex.getMessage());
            return;
        }

        if (calmingNodeXZ.isEmpty()) {
            broadcast(level, "TrafficCalming: no suitable nodes found — done.");
            return;
        }

        // ===== PASS2: ищем дороги, содержащие ноды, чтобы получить направление и ширину =====
        Map<Long, NodeChoice> best = new HashMap<>();
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject way : fs) {
                        if (!"way".equals(opt(way, "type"))) continue;

                        JsonObject wtags = tagsOf(way);
                        if (wtags == null) continue;

                        // только ways с автомобильной проезжей частью (как в других генераторах дорог)
                        String hwy = opt(wtags, "highway");
                        if (hwy == null || !RoadGenerator.hasRoadMaterial(hwy)) continue;

                        int width = Math.max(1, widthFromWayTagsOrDefault(wtags));

                        JsonArray nds = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                        if (nds == null || nds.size() < 2) continue;

                        JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                                ? way.getAsJsonArray("geometry") : null;

                        for (int i = 0; i < nds.size(); i++) {
                            long nid = nds.get(i).getAsLong();
                            int[] at = calmingNodeXZ.get(nid);
                            if (at == null) continue; // в этой дороге нет успокоителя

                            DDir dir = null;
                            if (geom != null && geom.size() >= 2) {
                                dir = directionNearPointFromGeometry(geom, at[0], at[1],
                                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                            }
                            if (dir == null || dir.isZero()) continue;

                            NodeChoice prev = best.get(nid);
                            if (prev == null || width > prev.width) {
                                best.put(nid, new NodeChoice(nid, at[0], at[1], dir, width));
                            }
                        }
                    }
                }
            } else {
                // fallback: перебираем features
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                for (JsonElement el : elements) {
                    JsonObject way = el.getAsJsonObject();
                    if (!"way".equals(opt(way, "type"))) continue;

                    JsonObject wtags = tagsOf(way);
                    if (wtags == null) continue;

                    String hwy = opt(wtags, "highway");
                    if (hwy == null || !RoadGenerator.hasRoadMaterial(hwy)) continue;

                    int width = Math.max(1, widthFromWayTagsOrDefault(wtags));

                    JsonArray nds = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                    if (nds == null || nds.size() < 2) continue;

                    JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                            ? way.getAsJsonArray("geometry") : null;

                    for (int i = 0; i < nds.size(); i++) {
                        long nid = nds.get(i).getAsLong();
                        int[] at = calmingNodeXZ.get(nid);
                        if (at == null) continue;

                        DDir dir = null;
                        if (geom != null && geom.size() >= 2) {
                            dir = directionNearPointFromGeometry(geom, at[0], at[1],
                                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        }
                        if (dir == null || dir.isZero()) continue;

                        NodeChoice prev = best.get(nid);
                        if (prev == null || width > prev.width) {
                            best.put(nid, new NodeChoice(nid, at[0], at[1], dir, width));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS2 (ways): " + ex.getMessage());
        }

        if (best.isEmpty()) {
            broadcast(level, "TrafficCalming: no node matched any roadway — done.");
            return;
        }

        // ===== DRAW: поперёк дороги, строго в точке ноды, на рельеф кладём BOTTOM-слаб =====
        int drawn = 0;
        for (NodeChoice c : best.values()) {
            drawSlabLineAcrossRoadAt(c.x, c.z, c.dir, c.width);
            drawn++;
        }
        broadcast(level, "Traffic calming placed: " + drawn);
    }

    // ====== Отрисовка поперёк дороги (на рельеф, полублоки каменных кирпичей) ======
    private void drawSlabLineAcrossRoadAt(int x0, int z0, DDir along, int width) {
        DDir a = along.unitNormalized();     // вдоль дороги
        DDir c = a.perp().unitNormalized();  // поперёк дороги

        // центр линии — ровно в ноде
        double cx = x0;
        double cz = z0;

        final int half = width / 2;

        for (int w = -half; w <= half; w++) {
            // позиция поперёк дороги
            double fx = cx + c.dx * w;
            double fz = cz + c.dz * w;

            // толщина вдоль дороги — 1 блок (можно расширить при желании)
            for (int t = -(LINE_THICKNESS/2); t <= (LINE_THICKNESS/2); t++) {
                double lx = fx + a.dx * t;
                double lz = fz + a.dz * t;

                int bx = (int)Math.round(lx);
                int bz = (int)Math.round(lz);
                if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) continue;

                Integer by = terrainGroundY(bx, bz);
                if (by == null) by = scanTopY(bx, bz);
                if (by == null) continue;

                placeBottomSlab(bx, by + 1, bz); // строго «на рельеф» (нижняя половинка)
            }
        }
    }

    private void placeBottomSlab(int x, int y, int z) {
        BlockState st = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        try {
            st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM); // нижняя половина, прилегает к верху грунта
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x, y, z), st, 3);
    }

    // ====== Геометрия / направления ======
    /** Направление у ближайшей точки geometry к (x,z). */
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

    // ====== Узел traffic calming? ======
    private static boolean isTrafficCalmingNode(JsonObject tags) {
        // Любой traffic_calming и/или highway=traffic_calming
        String tc  = opt(tags, "traffic_calming");
        String hwy = opt(tags, "highway");
        if (tc != null && !tc.isBlank()) return true;
        return "traffic_calming".equalsIgnoreCase(hwy);
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

    // ====== Преобразование координат ======
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
    
    @SuppressWarnings("unused")
    private static final class NodeChoice {
        final long id; final int x, z, width; final DDir dir;
        NodeChoice(long id, int x, int z, DDir dir, int width){
            this.id=id; this.x=x; this.z=z; this.dir=dir; this.width=Math.max(1,width);
        }
    }
}