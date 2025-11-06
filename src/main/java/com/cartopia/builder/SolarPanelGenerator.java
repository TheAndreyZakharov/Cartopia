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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;
import java.util.Locale;

public class SolarPanelGenerator {

    // ===== Визуал =====
    private static final Block MATERIAL_POST   = Blocks.POLISHED_BLACKSTONE_WALL;
    private static final Block MATERIAL_SENSOR = Blocks.DAYLIGHT_DETECTOR;

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public SolarPanelGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ===== Геометрия =====
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
            // Чётно-нечётный луч по X
            boolean inside = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                int xi = xs[i], zi = zs[i];
                int xj = xs[j], zj = zs[j];
                boolean intersect = ((zi > z) != (zj > z)) &&
                        (x < (long)(xj - xi) * (z - zi) / (long)(zj - zi + 0.0) + xi);
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
            for (Ring r : outers) if (r.containsPoint(x,z)) { inOuter = true; break; }
            if (!inOuter) return false;
            for (Ring r : inners) if (r.containsPoint(x,z)) return false;
            return true;
        }
    }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "SolarPanelAreaGenerator: coords == null — skipping.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "SolarPanelAreaGenerator: no center/bbox — skipping.");
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
        final int worldMinX = Math.min(a[0], b[0]);
        final int worldMaxX = Math.max(a[0], b[0]);
        final int worldMinZ = Math.min(a[1], b[1]);
        final int worldMaxZ = Math.max(a[1], b[1]);

        List<Area> areas = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectArea(e, areas,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "SolarPanelAreaGenerator: no coords.features — skipping.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "SolarPanelAreaGenerator: features.elements are empty — skipping.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectArea(el.getAsJsonObject(), areas,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "SolarPanelAreaGenerator: error reading features: " + ex.getMessage());
        }

        if (areas.isEmpty()) {
            broadcast(level, "SolarPanelAreaGenerator: no suitable panel areas found — done.");
            return;
        }

        int areaIdx = 0;
        for (Area area : areas) {
            areaIdx++;
            try {
                fillAreaWithPanels(area,
                        Math.max(worldMinX, area.minX),
                        Math.min(worldMaxX, area.maxX),
                        Math.max(worldMinZ, area.minZ),
                        Math.min(worldMaxZ, area.maxZ),
                        areaIdx, areas.size());
            } catch (Exception ex) {
                broadcast(level, "SolarPanelAreaGenerator: error in area #" + areaIdx + ": " + ex.getMessage());
            }
        }
    }

    // ===== Сбор зон из фич =====
    private void collectArea(JsonObject e, List<Area> out,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isSolarPanelPolygon(tags)) return; // ВАЖНО: только полигоны панелей, не границы станций

        String type = optString(e, "type");
        if (type == null) return;

        // way ⇒ одна внешняя граница
        if ("way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 3) return;
            Area area = new Area();
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i] = xz[0]; zs[i] = xz[1];
            }
            area.addOuter(new Ring(xs, zs));
            out.add(area);
            return;
        }

        // relation ⇒ multipolygon с outer/inner
        if ("relation".equals(type)) {
            String rtype = optString(tags, "type"); // часто "multipolygon"
            if (rtype == null || !"multipolygon".equalsIgnoreCase(rtype)) {
                // fallback: возьмём relation.geometry как простой полигон
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) return;
                Area area = new Area();
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i] = xz[0]; zs[i] = xz[1];
                }
                area.addOuter(new Ring(xs, zs));
                out.add(area);
                return;
            }

            JsonArray members = (e.has("members") && e.get("members").isJsonArray()) ? e.getAsJsonArray("members") : null;
            if (members == null || members.size() == 0) {
                // relation уже «слеплен» в geometry
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) return;
                Area area = new Area();
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i] = xz[0]; zs[i] = xz[1];
                }
                area.addOuter(new Ring(xs, zs));
                out.add(area);
                return;
            }

            Area area = new Area();
            for (JsonElement mEl : members) {
                JsonObject m = mEl.getAsJsonObject();
                String role = optString(m, "role");
                JsonArray g = (m.has("geometry") && m.get("geometry").isJsonArray()) ? m.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) continue;

                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++) {
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i] = xz[0]; zs[i] = xz[1];
                }

                Ring r = new Ring(xs, zs);
                if ("inner".equalsIgnoreCase(role)) area.addInner(r);
                else area.addOuter(r);
            }
            if (!area.outers.isEmpty()) out.add(area);
        }
    }


    // принимаем ТОЛЬКО полигоны самих полей панелей

    private static boolean isSolarPanelPolygon(JsonObject t) {
        String power        = low(optString(t,"power"));
        String genSource    = low(optString(t,"generator:source"));
        String genMethod    = low(optString(t,"generator:method"));
        String genType      = low(optString(t,"generator:type"));
        String plantSource  = low(optString(t,"plant:source"));
        String plantMethod  = low(optString(t,"plant:method"));
        String plantType    = low(optString(t,"plant:type"));
        String manMade      = low(optString(t,"man_made"));

        // Явные границы станций — НЕ строим
        if ("plant".equals(power)) return false;
        if (plantSource != null || plantMethod != null || plantType != null) return false;

        // Полигоны панелей через power=generator + generator:*
        if ("generator".equals(power)) {
            if (isSolarish(genSource) || isPhotovoltaic(genMethod) || containsSolarish(genType)) return true;
            // расширенный допуск: любые generator:* с solar/pv
            if (anyKeyContains(t, "generator:", v -> isSolarish(v) || isPhotovoltaic(v) || containsSolarish(v))) return true;
        }

        // Редкие случаи разметки
        if ("solar_panel".equals(manMade)) return true;

        return false;
    }

    // ===== Рендер одной зоны =====
    private void fillAreaWithPanels(Area area, int minX, int maxX, int minZ, int maxZ, int idx, int total) {
        if (minX > maxX || minZ > maxZ) return;

        long totalCells = (long)(maxX - minX + 1) * (long)(maxZ - minZ + 1);
        long nextReport = Math.max(1, totalCells / 5); // ~20%
        long done = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.contains(x, z)) { done++; if (done % nextReport == 0) report(idx, total, done, totalCells); continue; }

                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) { done++; if (done % nextReport == 0) report(idx, total, done, totalCells); continue; }

                // Полная «заливка»: стойка + датчик строго по рельефу
                placeBlock(x, yBase + 1, z, MATERIAL_POST);
                placeBlock(x, yBase + 2, z, MATERIAL_SENSOR);

                done++;
                if (done % nextReport == 0) report(idx, total, done, totalCells);
            }
        }
        broadcast(level, String.format(Locale.ROOT, "Solar area %d/%d: 100%%", idx, total));
    }

    private void report(int idx, int total, long done, long totalCells) {
        int pct = (int)Math.round(100.0 * done / Math.max(1, totalCells));
        broadcast(level, String.format(Locale.ROOT, "Solar area %d/%d: ~%d%%", idx, total, pct));
    }

    // ===== Низкоуровневые сеттеры и рельеф =====
    private void placeBlock(int x, int y, int z, Block block) {
        BlockPos pos = new BlockPos(x,y,z);
        BlockState st = block.defaultBlockState();
        level.setBlock(pos, st, 3);
    }

    private int terrainYFromCoordsOrWorld(int x, int z, Integer hintY) {
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

        // мир
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== Утилиты =====
    private static boolean anyKeyContains(JsonObject t, String prefix, java.util.function.Predicate<String> pred) {
        try {
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(prefix) && !e.getValue().isJsonNull()) {
                    String v = low(e.getValue().getAsString());
                    if (pred.test(v)) return true;
                }
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static boolean isSolarish(String v) {
        if (v == null) return false;
        return v.contains("solar");
    }
    private static boolean isPhotovoltaic(String v) {
        if (v == null) return false;
        return v.contains("photovoltaic") || v.equals("pv");
    }
    private static boolean containsSolarish(String v) {
        if (v == null) return false;
        return v.contains("solar") || v.contains("photovoltaic") || v.contains("pv");
    }

    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

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