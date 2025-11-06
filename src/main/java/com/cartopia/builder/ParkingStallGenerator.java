package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.*;


public class ParkingStallGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Размеры места
    private static final int BAR_LEN = 4;  // длина "перемычки"
    private static final int LEG_LEN = 5;  // длина ножки
    private static final int DEFAULT_ROAD_WIDTH = 12; // fallback ширины дороги (блоки)

    // bbox → блоки (клиппинг)
    private int minX, maxX, maxZ, minZ;

    public ParkingStallGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store  = store;
    }
    public ParkingStallGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

    // ===== Запуск =====
    public void generate() {
        broadcast(level, "Generating parking stalls (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping ParkingStallGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // bbox → блоки
        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]);
        maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]);
        maxZ = Math.max(a[1], b[1]);

        // ===== PASS1: парковочные полигоны (way + relation multipolygon) =====
        List<Ring> parkingRings = new ArrayList<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                JsonObject tags = tagsOf(f);
                if (tags == null || !isParkingArea(tags)) continue;

                List<List<int[]>> rings = parseRingsFromFeature(
                        f, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

                for (List<int[]> pts : rings) {
                    if (pts.size() < 4) continue; // минимум треугольник + замыкание
                    Ring ring = new Ring(pts);
                    if (ring.length() >= BAR_LEN) parkingRings.add(ring);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "PASS1 error (parking areas): " + ex.getMessage());
            return;
        }

        if (parkingRings.isEmpty()) {
            broadcast(level, "No parking areas found.");
            return;
        }

        // ===== PASS2: дороги (для запрета рисования на них и для рядов внутри парковки) =====
        List<RoadSeg> roadSegs = new ArrayList<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;
                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                String hwy = opt(wtags, "highway");
                if (hwy == null || !RoadGenerator.hasRoadMaterial(hwy)) continue;

                int width = widthFromWayTagsOrDefault(wtags);
                int half  = Math.max(1, width / 2);

                JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                        ? way.getAsJsonArray("geometry") : null;
                if (geom == null || geom.size() < 2) continue;

                int n = geom.size();
                int[] gx = new int[n];
                int[] gz = new int[n];
                for (int i = 0; i < n; i++) {
                    JsonObject p = geom.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(
                            p.get("lat").getAsDouble(),
                            p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                    );
                    gx[i] = xz[0]; gz[i] = xz[1];
                }

                for (int i = 0; i < n - 1; i++) {
                    int ax2 = gx[i], az2 = gz[i];
                    int bx2 = gx[i+1], bz2 = gz[i+1];
                    if (ax2 == bx2 && az2 == bz2) continue;
                    roadSegs.add(new RoadSeg(ax2, az2, bx2, bz2, half));
                }
            }
        } catch (Exception ex) {
            broadcast(level, "PASS2 error (roads): " + ex.getMessage());
        }

        // ===== DRAW =====
        int placed = 0;

        // A) По периметру парковочных зон (ножка ВСЕГДА внутрь зоны)
        for (Ring ring : parkingRings) {
            placed += drawAlongRing(ring, roadSegs);
        }

        // B) Вдоль дорог внутри каждой парковки:
        //    сегмент берём, если хотя бы один конец внутри ИЛИ сегмент пересекает границу кольца.
        for (Ring ring : parkingRings) {
            for (RoadSeg seg : roadSegs) {
                boolean aIn = ring.pointInside(seg.ax, seg.az);
                boolean bIn = ring.pointInside(seg.bx, seg.bz);
                boolean crosses = ring.segmentIntersects(seg.ax, seg.az, seg.bx, seg.bz);
                if (!(aIn || bIn || crosses)) continue;

                DDir segDir = new DDir(seg.bx - seg.ax, seg.bz - seg.az).unitNormalized();
                if (segDir.isZero()) continue;
                DDir nrm    = segDir.perp(); // "лево" от направления сегмента

                int offset = seg.halfWidth + 1;
                placed += drawRowAlongSegment(seg.ax, seg.az, seg.bx, seg.bz, segDir, nrm, +offset, roadSegs);
                placed += drawRowAlongSegment(seg.ax, seg.az, seg.bx, seg.bz, segDir, nrm, -offset, roadSegs);
            }
        }

        broadcast(level, "Parking stalls built (lines): " + placed);
    }

    // ==== Рисование вдоль одного сегмента (ряд П с шагом 4, ОДНА ножка слева) ====
    private int drawRowAlongSegment(int ax, int az, int bx, int bz, DDir along, DDir left, int normalOffset,
                                    List<RoadSeg> roads) {
        int placed = 0;
        double len = Math.hypot(bx - ax, bz - az);
        if (len < BAR_LEN) return 0;

        DDir n = left.unitNormalized(); // нормаль (влево от along)
        // смещённая линия, параллельная сегменту: туда кладём "перемычки"
        double ox = ax + n.dx * normalOffset;
        double oz = az + n.dz * normalOffset;

        // шаг вдоль сегмента — BAR_LEN
        int steps = (int)Math.floor((len - BAR_LEN) / BAR_LEN) + 1;
        for (int k = 0; k < steps; k++) {
            double s0 = k * BAR_LEN;

            // начало перемычки
            double sx0 = ox + along.dx * s0;
            double sz0 = oz + along.dz * s0;

            // перемычка (BAR_LEN блоков)
            for (int t = 0; t < BAR_LEN; t++) {
                double px = sx0 + along.dx * t;
                double pz = sz0 + along.dz * t;
                placed += placeWhite(px, pz, roads);
            }

            // ОДНА ножка (левая), длиной LEG_LEN — у начала перемычки
            placed += drawLeg(sx0, sz0, n, LEG_LEN, roads);
        }
        return placed;
    }

    private int drawLeg(double bx, double bz, DDir n, int len, List<RoadSeg> roads) {
        int placed = 0;
        for (int d = 1; d <= len; d++) {
            placed += placeWhite(bx + n.dx * d, bz + n.dz * d, roads);
        }
        return placed;
    }

    private int placeWhite(double fx, double fz, List<RoadSeg> roads) {
        int bx = (int)Math.round(fx);
        int bz = (int)Math.round(fz);

        // bbox клиппинг
        if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) return 0;

        // не ставим на дорогу
        if (isOnAnyRoad(bx, bz, roads)) return 0;

        Integer by = terrainGroundY(bx, bz);
        if (by == null) by = scanTopY(bx, bz);
        if (by == null) return 0;

        level.setBlock(new BlockPos(bx, by, bz), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
        return 1;
    }

    private boolean isOnAnyRoad(int x, int z, List<RoadSeg> roads) {
        for (RoadSeg s : roads) {
            double d = pointSegmentDistance(x, z, s.ax, s.az, s.bx, s.bz);
            if (d <= s.halfWidth + 0.25) return true;
        }
        return false;
    }

    // ==== Рисование по периметру кольца ====
    private int drawAlongRing(Ring ring, List<RoadSeg> roads) {
        int placed = 0;

        // inward: для CCW — слева, для CW — справа (ножка всегда внутрь)
        int inwardSign = ring.area() > 0 ? +1 : -1;

        List<int[]> pts = ring.points;
        for (int i = 0; i < pts.size() - 1; i++) {
            int[] A = pts.get(i);
            int[] B = pts.get(i + 1);
            int ax = A[0], az = A[1];
            int bx = B[0], bz = B[1];

            DDir edgeDir = new DDir(bx - ax, bz - az).unitNormalized();
            if (edgeDir.isZero()) continue;

            DDir left = edgeDir.perp().unitNormalized();
            DDir inward = new DDir(left.dx * inwardSign, left.dz * inwardSign);

            // перемычки по ребру (offset=0), одна ножка — внутрь
            placed += drawRowAlongSegment(ax, az, bx, bz, edgeDir, inward, 0, roads);
        }
        return placed;
    }

    // ======= Геометрия/утилиты =======
    private static double pointSegmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double wx = px - ax, wz = pz - az;
        double vv = vx*vx + vz*vz;
        if (vv <= 1e-9) return Math.hypot(px - ax, pz - az);
        double t = (wx*vx + wz*vz) / vv;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double cx = ax + t * vx, cz = az + t * vz;
        return Math.hypot(px - cx, pz - cz);
    }

    private static boolean isParkingArea(JsonObject tags) {
        String amenity = opt(tags, "amenity");
        String landuse = opt(tags, "landuse");
        String parking = opt(tags, "parking");
        if ("parking".equalsIgnoreCase(amenity)) return true;
        if ("parking".equalsIgnoreCase(landuse)) return true;
        return parking != null && !parking.isBlank();
    }

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
        return DEFAULT_ROAD_WIDTH;
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
                else if (c=='.' && !dot) { num.append('.'); dot = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters);
                if (blocks > 0) return blocks;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // ====== Парсинг парковочных колец из разных типов фич ======
    private static List<List<int[]>> parseRingsFromFeature(JsonObject f,
                                                           double centerLat, double centerLng,
                                                           double east, double west, double north, double south,
                                                           int sizeMeters, int centerX, int centerZ) {
        List<List<int[]>> out = new ArrayList<>();
        String type = opt(f, "type");
        if (type == null) return out;

        // WAY: одна ломаная; замыкаем
        if ("way".equals(type) && f.has("geometry") && f.get("geometry").isJsonArray()) {
            List<int[]> ring = readRingFromGeometry(f.getAsJsonArray("geometry"),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (!ring.isEmpty()) out.add(ring);
            return out;
        }

        // RELATION: multipolygon
        if ("relation".equals(type)) {
            // 1) members[] с role="outer" — основной источник правды
            if (f.has("members") && f.get("members").isJsonArray()) {
                JsonArray members = f.getAsJsonArray("members");
                for (int i = 0; i < members.size(); i++) {
                    JsonObject m = members.get(i).getAsJsonObject();
                    String role = opt(m, "role");
                    if (role != null && !"outer".equalsIgnoreCase(role)) continue;
                    if (!m.has("geometry") || !m.get("geometry").isJsonArray()) continue;
                    List<int[]> ring = readRingFromGeometry(m.getAsJsonArray("geometry"),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    if (!ring.isEmpty()) out.add(ring);
                }
                if (!out.isEmpty()) return out;
            }
            // 2) geometries[] с role="outer"
            if (f.has("geometries") && f.get("geometries").isJsonArray()) {
                JsonArray arr = f.getAsJsonArray("geometries");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject g = arr.get(i).getAsJsonObject();
                    String role = opt(g, "role");
                    if (role != null && !"outer".equalsIgnoreCase(role)) continue;
                    if (g.has("geometry") && g.get("geometry").isJsonArray()) {
                        List<int[]> ring = readRingFromGeometry(g.getAsJsonArray("geometry"),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        if (!ring.isEmpty()) out.add(ring);
                    }
                }
                if (!out.isEmpty()) return out;
            }
            // 3) relation.geometry как один внешний контур (редкий fallback)
            if (f.has("geometry") && f.get("geometry").isJsonArray()) {
                List<int[]> ring = readRingFromGeometry(f.getAsJsonArray("geometry"),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                if (!ring.isEmpty()) out.add(ring);
            }
        }

        return out;
    }

    private static List<int[]> readRingFromGeometry(JsonArray geom,
                                                    double centerLat, double centerLng,
                                                    double east, double west, double north, double south,
                                                    int sizeMeters, int centerX, int centerZ) {
        List<int[]> pts = new ArrayList<>(geom.size());
        for (int i = 0; i < geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            pts.add(latlngToBlock(
                    p.get("lat").getAsDouble(),
                    p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
            ));
        }
        if (pts.size() >= 3) {
            int[] p0 = pts.get(0);
            int[] pn = pts.get(pts.size()-1);
            if (p0[0] != pn[0] || p0[1] != pn[1]) pts.add(new int[]{p0[0], p0[1]});
        }
        return pts;
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

    // ====== JSON / utils ======
    private static String opt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static JsonObject tagsOf(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int)Math.round(centerX + dx), (int)Math.round(centerZ + dz)};
    }

    // ====== Векторы и структуры ======
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

    private static final class RoadSeg {
        final int ax, az, bx, bz;
        final int halfWidth;
        RoadSeg(int ax, int az, int bx, int bz, int halfWidth) {
            this.ax=ax; this.az=az; this.bx=bx; this.bz=bz; this.halfWidth=halfWidth;
        }
    }

    private static final class Ring {
        final List<int[]> points; // замкнутый: последний == первый
        Ring(List<int[]> pts){ this.points = pts; }

        double area() {
            long s = 0;
            for (int i = 0; i < points.size()-1; i++) {
                int[] a = points.get(i);
                int[] b = points.get(i+1);
                s += (long)a[0] * b[1] - (long)b[0] * a[1];
            }
            return 0.5 * s;
        }
        double length() {
            double L = 0;
            for (int i = 0; i < points.size()-1; i++) {
                int[] a = points.get(i);
                int[] b = points.get(i+1);
                L += Math.hypot(b[0]-a[0], b[1]-a[1]);
            }
            return L;
        }

        boolean pointInside(int x, int z) { // ray casting
            boolean inside = false;
            for (int i = 0, j = points.size()-1; i < points.size(); j = i++) {
                int xi = points.get(i)[0], zi = points.get(i)[1];
                int xj = points.get(j)[0], zj = points.get(j)[1];
                boolean intersect = ((zi > z) != (zj > z)) &&
                        (x < (double)(xj - xi) * (z - zi) / (double)(zj - zi + 0.0) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        }

        boolean segmentIntersects(int ax, int az, int bx, int bz) {
            for (int i = 0; i < points.size() - 1; i++) {
                int[] A = points.get(i);
                int[] B = points.get(i + 1);
                if (segmentsIntersect(ax, az, bx, bz, A[0], A[1], B[0], B[1])) return true;
            }
            return false;
        }

        private static boolean segmentsIntersect(int x1, int y1, int x2, int y2,
                                                 int x3, int y3, int x4, int y4) {
            long d1 = direction(x3, y3, x4, y4, x1, y1);
            long d2 = direction(x3, y3, x4, y4, x2, y2);
            long d3 = direction(x1, y1, x2, y2, x3, y3);
            long d4 = direction(x1, y1, x2, y2, x4, y4);

            if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;

            if (d1 == 0 && onSegment(x3, y3, x4, y4, x1, y1)) return true;
            if (d2 == 0 && onSegment(x3, y3, x4, y4, x2, y2)) return true;
            if (d3 == 0 && onSegment(x1, y1, x2, y2, x3, y3)) return true;
            if (d4 == 0 && onSegment(x1, y1, x2, y2, x4, y4)) return true;

            return false;
        }

        private static long direction(int ax, int ay, int bx, int by, int cx, int cy) {
            return (long)(bx - ax) * (cy - ay) - (long)(by - ay) * (cx - ax);
        }

        private static boolean onSegment(int ax, int ay, int bx, int by, int px, int py) {
            return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
                && Math.min(ay, by) <= py && py <= Math.max(ay, by);
        }
    }

    // ====== Логгирование ======
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
}