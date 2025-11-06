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


public class CrosswalkGenerator {
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    private static final int STRIPE_LENGTH = 7;  // длина белой полосы вдоль дороги
    private static final int DEFAULT_WIDTH = 12; // fallback ширины

    // bbox в блоках (для клиппинга)
    private int minX, maxX, minZ, maxZ;

    public CrosswalkGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }
    public CrosswalkGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

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
        broadcast(level, "Generating crosswalks (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping CrosswalkGenerator.");
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

        // ===== PASS1: узлы crossing (минимум памяти) =====
        Map<Long, int[]> crossingNodeXZ = new HashMap<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                if (!"node".equals(opt(f, "type"))) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isCrossing(tags)) continue;

                Long id = asLong(f, "id");
                Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                if (id == null || lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                // необязательный клип для ускорения: если сам узел вне bbox — всё равно проверим way-геометрию позже
                if (xz[0] < minX || xz[0] > maxX || xz[1] < minZ || xz[1] > maxZ) {
                    // можно пропустить, но иногда геометрия пути чуть смещает — оставим
                }
                crossingNodeXZ.put(id, xz);
            }
        } catch (Exception ex) {
            broadcast(level, "PASS1 error (nodes): " + ex.getMessage());
            return;
        }

        // Выбор лучшей дороги и направления для каждого crossing-узла
        Map<Long, NodeChoice> best = new HashMap<>();
        int drawnImmediate = 0;

        // ===== PASS2: пути (stream) =====
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;

                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                // 2.A. ways с footway=crossing — рисуем сразу, перпендикуляр «переходу» (= параллельно дороге)
                if (isFootwayCrossing(wtags)) {
                    int[] mid = wayMidPointBlocks(way, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    DDir crossDir = wayDirectionFromGeometryD(way, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    if (mid != null && crossDir != null && !crossDir.isZero()) {
                        DDir roadDir = crossDir.perp().unitNormalized();
                        int width = Math.max(1, widthFromWayTagsOrDefault(wtags));
                        drawZebraAcrossFullWidthD(mid[0], mid[1], roadDir, width);
                        drawnImmediate++;
                    }
                    continue;
                }

                // 2.B. дороги, содержащие crossing-узлы (узнаём по списку nodes)
                JsonArray nds = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                if (nds == null || nds.size() < 2) continue;

                // подсказка ширины по тегам (используется для выбора «лучшей»)
                int width = Math.max(1, widthFromWayTagsOrDefault(wtags));

                // геометрия пути (для направления)
                JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                        ? way.getAsJsonArray("geometry") : null;

                // Пробегаем все узлы way; если среди них есть crossing — пытаемся вычислить направление у ближайшей точки геометрии
                for (int i = 0; i < nds.size(); i++) {
                    long nid = nds.get(i).getAsLong();
                    int[] xz = crossingNodeXZ.get(nid);
                    if (xz == null) continue; // это не crossing-узел

                    DDir dir = null;

                    // Главное: направление берём из GEOMETRY у ближайшей точки к узлу
                    if (geom != null && geom.size() >= 2) {
                        dir = directionNearPointFromGeometry(geom, xz[0], xz[1],
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }

                    // Если геометрии нет — пропускаем (не храним все node-координаты ради памяти)
                    if (dir == null || dir.isZero()) continue;

                    // Сохраняем лучший выбор по ширине (как в питоне — приоритет ширины)
                    NodeChoice prev = best.get(nid);
                    if (prev == null || width > prev.width) {
                        best.put(nid, new NodeChoice(nid, xz[0], xz[1], dir, width));
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "PASS2 error (ways): " + ex.getMessage());
        }

        // ===== DRAW: для node-crossing по лучшему way =====
        int drawnChosen = 0;
        for (NodeChoice c : best.values()) {
            drawZebraAcrossFullWidthD(c.x, c.z, c.dir, c.width);
            drawnChosen++;
        }

        broadcast(level, "Crosswalks: " + (drawnImmediate + drawnChosen)
                + " (immediate: " + drawnImmediate + ", by nodes: " + drawnChosen + ")");
    }

    // ====== Отрисовка с клиппингом и антислипанием ======
    private void drawZebraAcrossFullWidthD(int x0, int z0, DDir along, int width) {
        DDir a = along.unitNormalized();
        DDir c = a.perp().unitNormalized();

        final int half = width / 2;
        final int lenHalf = STRIPE_LENGTH / 2;

        Set<Long> lastStripe = null;

        for (int w = -half; w <= half; w++) {
            if ((Math.abs(w) & 1) != 0) continue; // «чётные» полосы только

            Set<Long> curStripe = new HashSet<>();

            for (int t = -lenHalf; t <= lenHalf; t++) {
                double fx = x0 + c.dx * w + a.dx * t;
                double fz = z0 + c.dz * w + a.dz * t;
                int bx = (int)Math.round(fx);
                int bz = (int)Math.round(fz);

                // Жёсткий клиппинг по bbox — никогда не выходим за зону генерации
                if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) continue;

                Integer by = terrainGroundY(bx, bz);
                if (by == null) by = scanTopY(bx, bz);
                if (by == null) continue;

                curStripe.add(BlockPos.asLong(bx, by, bz));
            }

            // Антислипание: если этот ряд совпал с предыдущим — пропустим
            if (lastStripe != null && !curStripe.isEmpty() && curStripe.equals(lastStripe)) {
                continue;
            }

            for (long key : curStripe) {
                BlockPos pos = BlockPos.of(key);
                // доп.клиппинг по XZ (Y уже найден)
                if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
                level.setBlock(pos, Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            }

            if (!curStripe.isEmpty()) lastStripe = curStripe;
        }
    }

    // ====== Геометрия / направления ======

    /** Направление у ближайшей точки геометрии к (x,z). */
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
            // попытка расширить окно
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

    private static DDir wayDirectionFromGeometryD(JsonObject way,
                                                  double centerLat, double centerLng,
                                                  double east, double west, double north, double south,
                                                  int sizeMeters, int centerX, int centerZ) {
        if (!way.has("geometry")) return null;
        JsonArray geom = way.getAsJsonArray("geometry");
        if (geom.size() < 2) return null;
        JsonObject p0 = geom.get(0).getAsJsonObject();
        JsonObject p1 = geom.get(geom.size()-1).getAsJsonObject();
        int[] a = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(p1.get("lat").getAsDouble(), p1.get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        return new DDir(b[0]-a[0], b[1]-a[1]).unitNormalized();
    }

    private static int[] wayMidPointBlocks(JsonObject way,
                                           double centerLat, double centerLng,
                                           double east, double west, double north, double south,
                                           int sizeMeters, int centerX, int centerZ) {
        if (!way.has("geometry")) return null;
        JsonArray geom = way.getAsJsonArray("geometry");
        if (geom.size() == 0) return null;
        JsonObject mid = geom.get(geom.size()/2).getAsJsonObject();
        return latlngToBlock(mid.get("lat").getAsDouble(), mid.get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
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

    private static boolean isCrossing(JsonObject tags) {
        String h = opt(tags, "highway");
        String f = opt(tags, "footway");
        String c = opt(tags, "crossing");
        return "crossing".equalsIgnoreCase(h) || "crossing".equalsIgnoreCase(f) || (c != null && !c.isBlank());
    }

    private static boolean isFootwayCrossing(JsonObject tags) {
        String footway = opt(tags, "footway");
        String highway = opt(tags, "highway");
        if ("crossing".equalsIgnoreCase(footway)) return true;
        return "footway".equalsIgnoreCase(highway) && "crossing".equalsIgnoreCase(footway);
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

    // ====== Преобразования координат ======
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

    private static final class NodeChoice {
        @SuppressWarnings("unused")
        final long id; final int x, z, width; final DDir dir;
        NodeChoice(long id, int x, int z, DDir dir, int width){ this.id=id; this.x=x; this.z=z; this.dir=dir; this.width=Math.max(1,width); }
    }
}