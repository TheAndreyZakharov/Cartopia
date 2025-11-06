package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.cartopia.store.TerrainGridStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;


public class PierGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;
    private final TerrainGridStore grid;

    private static final String PIER_BLOCK_ID = "minecraft:spruce_planks";
    private static final int DEFAULT_LINE_WIDTH = 3;

    public PierGenerator(ServerLevel level, JsonObject coords) {
        this(level, coords, null);
    }
    public PierGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
        this.grid  = (store != null) ? store.grid : null;
    }

    // --- широковещалка ---
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

    // ==== публичный запуск ====
    public void generate() {
        broadcast(level, "Generating piers/jetties (placing on top of water)...");

        if (coords == null) {
            broadcast(level, "coords == null — skipping PierGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "No center/bbox — skipping piers.");
            return;
        }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int sizeMeters   = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble())
                : 0;
        final int centerZ = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble())
                : 0;

        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        boolean streaming = (store != null);
        int total = 0;

        // Подсчёт (stream)
        if (streaming) {
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                    if (!isPierCandidate(tags)) continue;
                    String type = optString(e,"type");
                    if (!"way".equals(type) && !"relation".equals(type)) continue;
                    JsonArray geom = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;
                    total++;
                }
            } catch (Exception ex) {
                broadcast(level, "NDJSON counting error: " + ex.getMessage() + " — falling back to coords.features.");
                streaming = false;
            }
        }

        if (streaming) {
            int processed = 0;
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                    if (!isPierCandidate(tags)) continue;

                    String type = optString(e,"type");
                    if (!"way".equals(type) && !"relation".equals(type)) continue;

                    JsonArray geom = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;

                    renderGeometry(geom, tags,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ,
                            minX, maxX, minZ, maxZ);

                    processed++;
                    if (total > 0 && processed % Math.max(1, total/10) == 0) {
                        int pct = (int)Math.round(100.0 * processed / Math.max(1, total));
                        broadcast(level, "Piers: ~" + pct + "%");
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "NDJSON render error: " + ex.getMessage());
            }
            broadcast(level, "Piers ready (stream).");
            return;
        }

        // === fallback: coords.features.elements ===
        if (!coords.has("features")) {
            broadcast(level, "No features in coords — skipping PierGenerator.");
            return;
        }
        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements are empty — skipping piers.");
            return;
        }

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (!isPierCandidate(tags)) continue;
            String type = optString(e,"type");
            if (!"way".equals(type) && !"relation".equals(type)) continue;
            JsonArray geom = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (geom == null || geom.size() < 2) continue;
            totalWays++;
        }

        int processed = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (!isPierCandidate(tags)) continue;

            String type = optString(e,"type");
            if (!"way".equals(type) && !"relation".equals(type)) continue;

            JsonArray geom = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (geom == null || geom.size() < 2) continue;

            renderGeometry(geom, tags,
                    centerLat, centerLng, east, west, north, south,
                    sizeMeters, centerX, centerZ,
                    minX, maxX, minZ, maxZ);

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Piers: ~" + pct + "%");
            }
        }

        broadcast(level, "Piers ready (fallback).");
    }

    // === Рендер одного элемента (линия/полигон) ===
    private void renderGeometry(JsonArray geom, JsonObject tags,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ,
                                int minX, int maxX, int minZ, int maxZ) {

        Block plank = resolveBlock(PIER_BLOCK_ID);

        if (isClosed(geom)) {
            // Площадной пирс → заливка
            List<int[]> poly = latlonGeomToBlockPoly(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (poly.size() >= 3) fillPolygon(poly, plank, minX, maxX, minZ, maxZ);
            return;
        }

        // Линейный пирс → как дорога с шириной
        int widthBlocks = widthFromTagsOrDefault(tags, DEFAULT_LINE_WIDTH);
        int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;

        for (int i=0; i<geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            double lat = p.get("lat").getAsDouble();
            double lon = p.get("lon").getAsDouble();
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            int x = xz[0], z = xz[1];

            if (prevX != Integer.MIN_VALUE) {
                paintSegmentAsRibbon(prevX, prevZ, x, z, widthBlocks, plank, minX, maxX, minZ, maxZ);
            }
            prevX = x; prevZ = z;
        }
    }

    // === Отбор подходящих OSM-тегов ===
    // ВКЛ: man_made=pier|jetty|mooring
    // ИСКЛ: man_made=quay, public_transport=quay, explicit "quay" key
    private static boolean isPierCandidate(JsonObject tags) {
        if (tags == null) return false;

        String mm = optString(tags, "man_made");
        if (mm != null) {
            String v = mm.trim().toLowerCase(Locale.ROOT);
            if ("pier".equals(v) || "jetty".equals(v) || "mooring".equals(v)) return true;
            if ("quay".equals(v)) return false; // набережные исключаем
        }

        // Явная платформа/набережная — исключаем
        String pt = optString(tags, "public_transport");
        if (pt != null && "quay".equals(pt.trim().toLowerCase(Locale.ROOT))) return false;
        if (tags.has("quay")) return false;

        return false;
    }

    // === Сегмент «как дорога» (лента шириной width) ===
    private void paintSegmentAsRibbon(int x1, int z1, int x2, int z2, int width, Block plank,
                                      int minX, int maxX, int minZ, int maxZ) {
        List<int[]> line = bresenhamLine(x1, z1, x2, z2);
        boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        int half = Math.max(0, width / 2);

        for (int[] pt : line) {
            int x = pt[0], z = pt[1];
            for (int w = -half; w <= half; w++) {
                int xx = horizontalMajor ? x : x + w;
                int zz = horizontalMajor ? z + w : z;
                if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;
                placePierAtCell(xx, zz, plank);
            }
        }
    }

    // === Полигон: заполнение клеток пирсом ===
    private void fillPolygon(List<int[]> polygon, Block plank,
                             int minX, int maxX, int minZ, int maxZ) {
        if (polygon == null || polygon.size() < 3) return;

        int pxMin = Integer.MAX_VALUE, pxMax = Integer.MIN_VALUE, pzMin = Integer.MAX_VALUE, pzMax = Integer.MIN_VALUE;
        for (int[] v : polygon) {
            pxMin = Math.min(pxMin, v[0]);
            pxMax = Math.max(pxMax, v[0]);
            pzMin = Math.min(pzMin, v[1]);
            pzMax = Math.max(pzMax, v[1]);
        }

        int sx = Math.max(minX, pxMin);
        int ex = Math.min(maxX, pxMax);
        int sz = Math.max(minZ, pzMin);
        int ez = Math.min(maxZ, pzMax);

        for (int x = sx; x <= ex; x++) {
            for (int z = sz; z <= ez; z++) {
                if (pointInPolygonInt(x, z, polygon)) {
                    placePierAtCell(x, z, plank);
                }
            }
        }
    }

    // === Установка доски пирса в клетке (РАЗРЕШАЕМ поверх воды) ===
    private void placePierAtCell(int x, int z, Block plank) {
        Integer gY = terrainGroundY(x, z);
        if (gY == null) return;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int y0 = gY + 1; // уровень воды/поверхности
        if (y0 < worldMin || y0 > worldMax) return;

        BlockPos pos = new BlockPos(x, y0, z);
        BlockState top = level.getBlockState(pos);

        // --- Разрешаем ставить, если:
        //  1) там воздух, ИЛИ
        //  2) это вода / пузырь / водоросли (замещаем), ИЛИ
        //  3) это высокая трава воды (seagrass) — замещаем.
        Block tb = top.getBlock();
        boolean isAir   = top.isAir();
        boolean isWater = (tb == Blocks.WATER) || (tb == Blocks.BUBBLE_COLUMN);
        boolean isPlants= (tb == Blocks.SEAGRASS) || (tb == Blocks.TALL_SEAGRASS)
                       || (tb == Blocks.KELP) || (tb == Blocks.KELP_PLANT);

        if (!(isAir || isWater || isPlants)) {
            // На суше (камень/земля и т.п.) пирс по умолчанию не ставим — чтобы не перетирать берег.
            return;
        }

        level.setBlock(pos, plank.defaultBlockState(), 3);
    }

    // === Чтение groundY из TerrainGridStore/coords.terrainGrid ===
    private Integer terrainGroundY(int x, int z) {
        try {
            if (grid != null && grid.inBounds(x, z)) {
                int v = grid.groundY(x, z);
                return (v == Integer.MIN_VALUE) ? null : v;
            }
        } catch (Throwable ignore) {}
        return terrainGroundYFromCoords(x, z);
    }

    private Integer terrainGroundYFromCoords(int x, int z) {
        try {
            if (coords == null || !coords.has("terrainGrid")) return null;
            JsonObject tg = coords.getAsJsonObject("terrainGrid");
            if (tg == null) return null;
            int minX = tg.get("minX").getAsInt();
            int minZ = tg.get("minZ").getAsInt();
            int width = tg.get("width").getAsInt();
            int idx = (z - minZ) * width + (x - minX);
            if (idx < 0) return null;

            if (tg.has("grids") && tg.get("grids").isJsonObject()) {
                JsonObject grids = tg.getAsJsonObject("grids");
                if (!grids.has("groundY")) return null;
                JsonArray arr = grids.getAsJsonArray("groundY");
                if (idx >= arr.size()) return null;
                return arr.get(idx).getAsInt();
            }

            if (tg.has("data") && tg.get("data").isJsonArray()) {
                JsonArray arr = tg.getAsJsonArray("data");
                if (idx >= arr.size()) return null;
                return arr.get(idx).getAsInt();
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // === Утилиты ===
    private static Block resolveBlock(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        return (b != null ? b : Blocks.OAK_PLANKS);
    }

    private static String optString(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static boolean isClosed(JsonArray geom) {
        if (geom == null || geom.size() < 4) return false;
        JsonObject a = geom.get(0).getAsJsonObject();
        JsonObject b = geom.get(geom.size()-1).getAsJsonObject();
        double la = a.get("lat").getAsDouble(), lo = a.get("lon").getAsDouble();
        double lb = b.get("lat").getAsDouble(), lob = b.get("lon").getAsDouble();
        return Math.abs(la - lb) < 1e-9 && Math.abs(lo - lob) < 1e-9;
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

    private static List<int[]> latlonGeomToBlockPoly(JsonArray geom,
                                                     double centerLat, double centerLng,
                                                     double east, double west, double north, double south,
                                                     int sizeMeters, int centerX, int centerZ) {
        List<int[]> poly = new ArrayList<>();
        for (int i=0;i<geom.size();i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            double lat = p.get("lat").getAsDouble();
            double lon = p.get("lon").getAsDouble();
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            poly.add(new int[]{xz[0], xz[1]});
        }
        return poly;
    }

    private static List<int[]> bresenhamLine(int x0, int z0, int x1, int z1) {
        List<int[]> pts = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int x = x0, z = z0;

        if (dx >= dz) {
            int err = dx / 2;
            while (x != x1) {
                pts.add(new int[]{x, z});
                err -= dz;
                if (err < 0) { z += sz; err += dx; }
                x += sx;
            }
        } else {
            int err = dz / 2;
            while (z != z1) {
                pts.add(new int[]{x, z});
                err -= dx;
                if (err < 0) { x += sx; err += dz; }
                z += sz;
            }
        }
        pts.add(new int[]{x1, z1});
        return pts;
    }

    // point-in-polygon (целочисл. координаты XZ)
    private static boolean pointInPolygonInt(int px, int pz, List<int[]> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i=0, j=n-1; i<n; j=i++) {
            int xi = poly.get(i)[0], zi = poly.get(i)[1];
            int xj = poly.get(j)[0], zj = poly.get(j)[1];
            boolean intersect = ((zi > pz) != (zj > pz)) &&
                    (px < (double)(xj - xi) * (pz - zi) / (double)(zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /** width из тегов, если есть; иначе дефолт. */
    private static int widthFromTagsOrDefault(JsonObject tags, int def) {
        if (tags == null) return Math.max(1, def);
        String[] keys = new String[] { "width", "est_width", "pier:width", "jetty:width" };
        for (String k : keys) {
            String v = optString(tags, k);
            if (v == null) continue;
            v = v.trim().toLowerCase(Locale.ROOT).replace(',', '.');
            StringBuilder num = new StringBuilder();
            boolean dotSeen = false;
            for (char c : v.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c=='.' && !dotSeen) { num.append('.'); dotSeen = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters); // 1м ≈ 1 блок
                if (blocks >= 1) return blocks;
            } catch (Exception ignore) { }
        }
        return Math.max(1, def);
    }
}