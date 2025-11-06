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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class ApiaryBeehivesGenerator {

    // --- Параметры размещения ---
    private static final int GRID_STEP = 3;                       // шаг сетки для пасек (между ульями)
    private static final Direction HIVE_FACING = Direction.SOUTH; // ориентация улья (не принципиально)

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public ApiaryBeehivesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // --- геометрия полигона ---
    private static final class Ring {
        final int[] xs, zs; final int n;
        final int minX, maxX, minZ, maxZ;
        Ring(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE, mnz = Integer.MAX_VALUE, mxz = Integer.MIN_VALUE;
            for (int i=0;i<n;i++) {
                mnx = Math.min(mnx, xs[i]); mxx = Math.max(mxx, xs[i]);
                mnz = Math.min(mnz, zs[i]); mxz = Math.max(mxz, zs[i]);
            }
            minX = mnx; maxX = mxx; minZ = mnz; maxZ = mxz;
        }
        boolean containsPoint(int x, int z) {
            boolean inside = false;
            for (int i=0, j=n-1; i<n; j=i++) {
                int xi=xs[i], zi=zs[i], xj=xs[j], zj=zs[j];
                boolean intersect = ((zi>z)!=(zj>z)) &&
                        (x < (long)(xj - xi) * (z - zi) / (double)(zj - zi) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        }
    }
    private static final class Area {
        final List<Ring> outers = new ArrayList<>();
        final List<Ring> inners = new ArrayList<>();
        int minX=Integer.MAX_VALUE, maxX=Integer.MIN_VALUE, minZ=Integer.MAX_VALUE, maxZ=Integer.MIN_VALUE;
        void addOuter(Ring r){ outers.add(r); grow(r); }
        void addInner(Ring r){ inners.add(r); grow(r); }
        private void grow(Ring r){
            minX=Math.min(minX,r.minX); maxX=Math.max(maxX,r.maxX);
            minZ=Math.min(minZ,r.minZ); maxZ=Math.max(maxZ,r.maxZ);
        }
        boolean contains(int x, int z) {
            boolean inOuter = false;
            for (Ring r:outers) if (r.containsPoint(x,z)) { inOuter = true; break; }
            if (!inOuter) return false;
            for (Ring r:inners) if (r.containsPoint(x,z)) return false;
            return true;
        }
        int clipMinX(int wMinX){ return Math.max(minX, wMinX); }
        int clipMaxX(int wMaxX){ return Math.min(maxX, wMaxX); }
        int clipMinZ(int wMinZ){ return Math.max(minZ, wMinZ); }
        int clipMaxZ(int wMaxZ){ return Math.min(maxZ, wMaxZ); }
    }

    // --- точечные ульи ---
    private static final class BeePoint {
        final int x, z;
        BeePoint(int x, int z){ this.x = x; this.z = z; }
    }

    // --- запуск ---
    public void generate() {
        if (coords == null) { broadcast(level, "ApiaryBeehivesGenerator: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "ApiaryBeehivesGenerator: no center/bbox — skipping."); return; }

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

        List<BeePoint> points = new ArrayList<>();
        List<Area>     areas  = new ArrayList<>();
        Set<Long> seenNodeIds    = new HashSet<>();
        Set<Long> seenWayRelIds  = new HashSet<>();
        Set<Long> usedXZ         = new HashSet<>();

        // ---- чтение OSM (stream / batch) ----
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, points, areas, seenNodeIds, seenWayRelIds, usedXZ,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "ApiaryBeehivesGenerator: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "ApiaryBeehivesGenerator: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), points, areas, seenNodeIds, seenWayRelIds, usedXZ,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "ApiaryBeehivesGenerator: error reading features: " + ex.getMessage());
        }

        // ---- постановка точечных ульев ----
        long placedPoints = 0;
        for (BeePoint p : points) {
            if (p.x < worldMinX || p.x > worldMaxX || p.z < worldMinZ || p.z > worldMaxZ) continue;
            placeHiveOnTerrain(p.x, p.z);
            placedPoints++;
        }
        if (placedPoints > 0) broadcast(level, "Beehives (points): placed " + placedPoints + " шт.");

        // ---- укладка ульев внутри зон ----
        int idx = 0;
        for (Area area : areas) {
            idx++;
            tileAreaWithHives(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, areas.size());
        }

        broadcast(level, "ApiaryBeehivesGenerator: done.");
    }

    // --- сбор признаков (и точки, и зоны) ---
    private void collectFeature(JsonObject e,
                                List<BeePoint> outPoints, List<Area> outAreas,
                                Set<Long> seenNodeIds, Set<Long> seenWayRelIds, Set<Long> usedXZ,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String type = optString(e, "type");
        if (type == null) return;

        // 1) Точки-ульи: теперь также craft=beekeeper и (на всякий) landuse=apiary
        if ("node".equals(type) && isBeeNode(tags)) {
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

            long key = (((long)xz[0]) << 32) ^ (xz[1] & 0xffffffffL);
            if (!usedXZ.add(key)) return;

            outPoints.add(new BeePoint(xz[0], xz[1]));
            return;
        }

        // 2) Полигоны пасек (way/relation)
        if (isApiaryArea(tags) && ("way".equals(type) || "relation".equals(type))) {
            Long id = optLong(e, "id");
            if (id != null && !seenWayRelIds.add(id)) return;

            if ("relation".equals(type)) {
                String rtype = optString(tags, "type"); // ожидаем multipolygon
                JsonArray members = (e.has("members") && e.get("members").isJsonArray())
                        ? e.getAsJsonArray("members") : null;

                if ("multipolygon".equalsIgnoreCase(String.valueOf(rtype)) && members != null && members.size() > 0) {
                    Area area = new Area();
                    for (JsonElement mEl : members) {
                        JsonObject m = mEl.getAsJsonObject();
                        String role = optString(m, "role");
                        JsonArray g = (m.has("geometry") && m.get("geometry").isJsonArray())
                                ? m.getAsJsonArray("geometry") : null;
                        if (g == null || g.size() < 3) continue;

                        int[] xs = new int[g.size()], zs = new int[g.size()];
                        for (int i=0;i<g.size();i++){
                            JsonObject p = g.get(i).getAsJsonObject();
                            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                            xs[i]=xz[0]; zs[i]=xz[1];
                        }
                        Ring r = new Ring(xs, zs);
                        if ("inner".equalsIgnoreCase(role)) area.addInner(r); else area.addOuter(r);
                    }
                    if (!area.outers.isEmpty()) outAreas.add(area);
                    return;
                }
                // fallback: возьмём geometry как у way
            }

            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 3) return;

            Area area = new Area();
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            area.addOuter(new Ring(xs, zs));
            outAreas.add(area);
        }
    }

    private static boolean isBeeNode(JsonObject t) {
        String manmade = low(optString(t, "man_made"));
        String craft   = low(optString(t, "craft"));
        String landuse = low(optString(t, "landuse"));
        // одиночный улей ставим для любого из этих вариантов на узле
        if ("beehive".equals(manmade)) return true;
        if ("beekeeper".equals(craft))  return true;
        if ("apiary".equals(landuse))   return true;
        return false;
    }

    private static boolean isApiaryArea(JsonObject t) {
        String landuse = low(optString(t, "landuse"));
        String craft   = low(optString(t, "craft"));
        // основная зона пасеки:
        if ("apiary".equals(landuse)) return true;
        // иногда рисуют area с craft=beekeeper — тоже считаем зоной
        return "beekeeper".equals(craft);
    }

    // --- сетка ульев по зоне ---
    private void tileAreaWithHives(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, int idx, int total) {
        // подбираем оффсет сетки (0..GRID_STEP-1) по X и Z, чтобы влезло максимум ульев
        int bestOx = 0, bestOz = 0, bestCount = -1;

        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        for (int ox=0; ox<GRID_STEP; ox++) {
            for (int oz=0; oz<GRID_STEP; oz++) {
                int cnt = simulateCount(area, minX, maxX, minZ, maxZ, ox, oz);
                if (cnt > bestCount) { bestCount = cnt; bestOx = ox; bestOz = oz; }
            }
        }

        if (bestCount <= 0) {
            broadcast(level, String.format(Locale.ROOT, "Apiary %d/%d: no space for beehives.", idx, total));
            return;
        }

        long done = 0, totalToPlace = bestCount;
        for (int x = minX + bestOx; x <= maxX; x += GRID_STEP) {
            for (int z = minZ + bestOz; z <= maxZ; z += GRID_STEP) {
                if (!area.contains(x, z)) continue;
                placeHiveOnTerrain(x, z);
                done++;
                if (done % Math.max(1, totalToPlace/5) == 0) {
                    int pct = (int)Math.round(100.0 * done / Math.max(1, totalToPlace));
                    broadcast(level, String.format(Locale.ROOT, "Apiary %d/%d: ~%d%%", idx, total, pct));
                }
            }
        }
        broadcast(level, String.format(Locale.ROOT, "Apiary %d/%d: placed %d beehives", idx, total, done));
    }

    private int simulateCount(Area area, int minX, int maxX, int minZ, int maxZ, int ox, int oz) {
        int cnt = 0;
        for (int x = minX + ox; x <= maxX; x += GRID_STEP) {
            for (int z = minZ + oz; z <= maxZ; z += GRID_STEP) {
                if (area.contains(x, z)) cnt++;
            }
        }
        return cnt;
    }

    // --- постановка улья «на рельеф» ---
    private void placeHiveOnTerrain(int x, int z) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return;

        // Ставим УЛЕЙ поверх грунта (yBase + 1), не затирая землю под ним.
        BlockState st = Blocks.BEEHIVE.defaultBlockState();
        try {
            if (st.hasProperty(HorizontalDirectionalBlock.FACING)) {
                st = st.setValue(HorizontalDirectionalBlock.FACING, HIVE_FACING);
            }
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x, yBase + 1, z), st, 3);
    }

    // --- рельеф ---
    private int terrainYFromCoordsOrWorld(int x, int z) {
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

        // fallback по миру: верх блока рельефа
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
    private static int clamp(int v, int a, int b){ return Math.max(a, Math.min(b, v)); }

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