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
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class FuelPumpGenerator {

    // ====== конфиг ======
    private static final int SEARCH_RADIUS_BLOCKS = 120; // радиус поиска навеса/здания/дороги вокруг станции
    private static final int MAX_PUMPS_PER_AREA = 48;    // защитный лимит
    private static final int EDGE_MARGIN  = 2;           // отступ от краёв проекции

    // Геометрия одной колонки:
    // Колонка — 3 блока в длину (по направлению дороги), 1 блок в ширину, 2 блока высота.
    // Межколоночный зазор «со всех сторон» — 2 блока.
    private static final int PUMP_LEN = 3;
    private static final int PUMP_WIDTH = 1;
    private static final int CLEAR_GAP = 2;

    // Шаг сетки (центр-до-центра) под навесом:
    private static final int STEP_ALONG  = PUMP_LEN  + CLEAR_GAP;  // 3 + 2 = 5
    private static final int STEP_ACROSS = PUMP_WIDTH + CLEAR_GAP; // 1 + 2 = 3

    // Для «кольца» вокруг здания, если навеса нет
    private static final int RING_WIDTH = 3; // расстояние от границы building-полигона, где позволяем ставить

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public FuelPumpGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
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

    // ====== примитивы геометрии/структуры ======
    private static final class RoadSeg {
        final int x1, z1, x2, z2;
        RoadSeg(int x1, int z1, int x2, int z2) { this.x1=x1; this.z1=z1; this.x2=x2; this.z2=z2; }
    }
    private static final class PolyXZ {
        final int[] xs, zs;
        final int n;
        final int minX, maxX, minZ, maxZ;
        final int cx, cz; // целочисленный «центр» (по среднему)
        PolyXZ(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            int minx= Integer.MAX_VALUE, maxx= Integer.MIN_VALUE, minz= Integer.MAX_VALUE, maxz= Integer.MIN_VALUE;
            long sx = 0, sz = 0;
            for (int i=0;i<n;i++){ if(xs[i]<minx)minx=xs[i]; if(xs[i]>maxx)maxx=xs[i]; if(zs[i]<minz)minz=zs[i]; if(zs[i]>maxz)maxz=zs[i]; sx+=xs[i]; sz+=zs[i];}
            minX=minx; maxX=maxx; minZ=minz; maxZ=maxz;
            cx = (int)Math.round(sx / (double)n);
            cz = (int)Math.round(sz / (double)n);
        }
        boolean contains(int x, int z) {
            // ray-cast по X
            boolean inside = false;
            for (int i=0, j=n-1; i<n; j=i++) {
                int xi = xs[i], zi = zs[i];
                int xj = xs[j], zj = zs[j];
                boolean intersect = ((zi > z) != (zj > z)) &&
                        (x < (double)(xj - xi) * (z - zi) / (double)(zj - zi + 1e-9) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        }
    }
    private static final class FuelStation {
        final int x, z;                  // центр станции (по узлу amenity=fuel или центроиду полигона)
        PolyXZ canopyOrNull;             // ближайший навес (building=roof / man_made=canopy / building=carport)
        PolyXZ buildingOrNull;           // ближайшее здание (building=*, not roof)
        int ux, uz;                      // ед. вектор вдоль направления дороги
        Direction sideA, sideB;          // боковые стороны (перпендикуляр к длине)
        int sxA, szA, sxB, szB;          // их векторы (+/-1 по оси)
        FuelStation(int x, int z) { this.x=x; this.z=z; }
    }

    // ====== публичный запуск ======
    public void generate() {
        if (coords == null) {
            broadcast(level, "FuelPumpGenerator: coords == null — пропускаю.");
            return;
        }

        // Геопривязка
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "FuelPumpGenerator: нет center/bbox — пропускаю.");
            return;
        }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // точные блок-границы
        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        // ===== Сбор данных (двойной стрим) =====
        List<FuelStation> stations = new ArrayList<>();
        List<PolyXZ> roofs   = new ArrayList<>();
        List<PolyXZ> builds  = new ArrayList<>();
        List<RoadSeg> roads  = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            // --- PASS 1: собираем станции, крыши, здания, дороги
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, stations, roofs, builds, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "FuelPumpGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "FuelPumpGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), stations, roofs, builds, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "FuelPumpGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (stations.isEmpty()) {
            broadcast(level, "FuelPumpGenerator: amenity=fuel не найдено в области — готово.");
            return;
        }

        // --- матчим для каждой станции навес/здание и направление дороги
        for (FuelStation fsx : stations) {
            // навес
            fsx.canopyOrNull   = nearestPoly(roofs, fsx.x, fsx.z, SEARCH_RADIUS_BLOCKS);
            // если нет навеса — берём здание
            fsx.buildingOrNull = nearestPoly(builds, fsx.x, fsx.z, SEARCH_RADIUS_BLOCKS);

            // направление дороги
            Direction dir = nearestRoadDirection(roads, fsx.x, fsx.z, SEARCH_RADIUS_BLOCKS);
            if (dir == null) {
                // если дорога не нашлась — ориентируем по «длинной оси» навеса/здания, иначе по X
                PolyXZ p = (fsx.canopyOrNull != null ? fsx.canopyOrNull : fsx.buildingOrNull);
                if (p != null) {
                    int spanX = p.maxX - p.minX;
                    int spanZ = p.maxZ - p.minZ;
                    dir = (spanX >= spanZ) ? Direction.EAST : Direction.SOUTH;
                } else {
                    dir = Direction.EAST;
                }
            }
            // базис вдоль/поперёк
            if (dir == Direction.EAST || dir == Direction.WEST) {
                fsx.ux = (dir == Direction.EAST ? 1 : -1); fsx.uz = 0;
                fsx.sideA = Direction.NORTH; fsx.sideB = Direction.SOUTH;
                fsx.sxA = 0; fsx.szA = -1; fsx.sxB = 0; fsx.szB = 1;
            } else {
                fsx.ux = 0; fsx.uz = (dir == Direction.SOUTH ? 1 : -1);
                fsx.sideA = Direction.EAST; fsx.sideB = Direction.WEST;
                fsx.sxA = 1; fsx.szA = 0; fsx.sxB = -1; fsx.szB = 0;
            }
        }

        // ===== Рендер =====
        int done = 0;
        for (FuelStation fsx : stations) {
            try {
                renderStation(fsx, minX, maxX, minZ, maxZ);
            } catch (Exception ex) {
                broadcast(level, "FuelPumpGenerator: ошибка на станции ("+fsx.x+","+fsx.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, stations.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, stations.size()));
                broadcast(level, "Бензоколонки: ~" + pct + "%");
            }
        }
    }

    // ===== сбор признаков из одной фичи =====
    private void collectFeature(JsonObject e,
                                List<FuelStation> stations, List<PolyXZ> roofs, List<PolyXZ> builds, List<RoadSeg> roads,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String type = optString(e,"type");

        // --- 1) amenity=fuel → станция (+ roof/building полигоны как опора под расстановку)
        if ("fuel".equals(optString(tags,"amenity"))) {
            // центр станции
            int sx, sz;
            int[] cxz = null;

            if ("node".equals(type)) {
                if (e.has("lat") && e.has("lon")) {
                    cxz = latlngToBlock(
                            e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                    );
                } else {
                    JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                            ? e.getAsJsonArray("geometry") : null;
                    if (g != null && g.size() == 1) {
                        JsonObject p = g.get(0).getAsJsonObject();
                        cxz = latlngToBlock(
                                p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                        );
                    }
                }
            } else { // way/relation — берём либо center/bounds, либо центроид полигона
                PolyXZ poly = polyFromGeometryOrBounds(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                if (poly != null) {
                    cxz = new int[]{poly.cx, poly.cz};
                    // если это «roof» — сразу добавим как навес; если просто building — как fallback-здание
                    if (isRoofLike(tags)) roofs.add(poly);
                    else if (isBuilding(tags)) builds.add(poly);
                } else {
                    cxz = elementCenterXZ(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }

            if (cxz != null) {
                sx = cxz[0]; sz = cxz[1];
                stations.add(new FuelStation(sx, sz));
            }

            // дороги собираем отдельно ниже, а тут выходим
            return;
        }

        // --- 2) навес (building=roof / man_made=canopy / building=carport)
        if (isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            PolyXZ poly = polyFromGeometryOrBounds(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (poly != null) roofs.add(poly);
            return;
        }

        // --- 3) обычное здание (building=*, not roof)
        if (isBuilding(tags) && !isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            PolyXZ poly = polyFromGeometryOrBounds(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (poly != null) builds.add(poly);
            return;
        }

        // --- 4) дороги (только автомобильные)
        if (isCarRoad(tags) && "way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 2) {
                JsonObject p0 = g.get(0).getAsJsonObject();
                int[] prev = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                for (int i=1;i<g.size();i++) {
                    JsonObject pi = g.get(i).getAsJsonObject();
                    int[] cur = latlngToBlock(pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    roads.add(new RoadSeg(prev[0], prev[1], cur[0], cur[1]));
                    prev = cur;
                }
            }
        }
    }

    private static boolean isRoofLike(JsonObject t) {
        String b = optString(t,"building");
        String mm= optString(t,"man_made");
        String bp= optString(t,"building:part");
        return "roof".equals(b) || "roof".equals(bp) || "canopy".equals(mm) || "carport".equals(b);
    }
    private static boolean isBuilding(JsonObject t) {
        return t.has("building");
    }
    private static boolean isCarRoad(JsonObject t) {
        String hw = optString(t,"highway");
        if (hw == null) return false;
        if (hw.equals("footway") || hw.equals("path") || hw.equals("cycleway") ||
            hw.equals("bridleway") || hw.equals("steps") || hw.equals("pedestrian")) return false;
        return true;
    }

    // ===== выбор ближайших сущностей =====
    private PolyXZ nearestPoly(List<PolyXZ> polys, int x, int z, int radius) {
        PolyXZ best = null; long bestD2 = Long.MAX_VALUE;
        int r2 = radius*radius;
        for (PolyXZ p : polys) {
            int dx = p.cx - x, dz = p.cz - z;
            int d2 = dx*dx + dz*dz;
            if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; best = p; }
        }
        return best;
    }

    private Direction nearestRoadDirection(List<RoadSeg> segs, int x, int z, int radius) {
        long bestD2 = Long.MAX_VALUE;
        int vx = 0, vz = 0;
        int r2 = radius*radius;
        for (RoadSeg s : segs) {
            int[] proj = projectPointOnSegment(x, z, s.x1, s.z1, s.x2, s.z2);
            int dx = proj[0]-x, dz = proj[1]-z;
            int d2 = dx*dx + dz*dz;
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2;
                vx = s.x2 - s.x1; vz = s.z2 - s.z1;
            }
        }
        if (bestD2 == Long.MAX_VALUE) return null;
        if (Math.abs(vx) >= Math.abs(vz)) return (vx >= 0 ? Direction.EAST : Direction.WEST);
        else return (vz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static int[] projectPointOnSegment(int px, int pz, int x1, int z1, int x2, int z2) {
        double vx = x2 - x1, vz = z2 - z1;
        double wx = px - x1, wz = pz - z1;
        double c1 = vx*wx + vz*wz;
        if (c1 <= 0) return new int[]{x1,z1};
        double c2 = vx*vx + vz*vz;
        if (c2 <= c1) return new int[]{x2,z2};
        double t = c1 / c2;
        int x = (int)Math.round(x1 + t*vx);
        int z = (int)Math.round(z1 + t*vz);
        return new int[]{x,z};
    }

    // ===== рендер станции =====
    private void renderStation(FuelStation st, int minX, int maxX, int minZ, int maxZ) {
        // Сет плиток, запрещённых для новых колонок (учитывает «радиус» CLEAR_GAP).
        HashSet<Long> blocked = new HashSet<>();

        if (st.canopyOrNull != null) {
            // 1) Под навесом — плотно сеткой с зазорами
            List<int[]> anchors = anchorsFillUnderCanopy(st.canopyOrNull, st, MAX_PUMPS_PER_AREA);
            if (anchors.isEmpty()) anchors.add(new int[]{st.canopyOrNull.cx, st.canopyOrNull.cz});
            for (int[] a : anchors) {
                int ax = a[0], az = a[1];
                if (ax<minX||ax>maxX||az<minZ||az>maxZ) continue;
                if (!canPlacePumpAt(ax, az, st, blocked)) continue;
                placePumpAt(ax, az, st);
                markForbiddenAroundPump(ax, az, st, blocked);
            }
            return;
        }

        if (st.buildingOrNull != null) {
            // 2) Если навеса нет — ставим кольцом вокруг здания
            List<int[]> anchors = anchorsRingAroundBuilding(st.buildingOrNull, st, MAX_PUMPS_PER_AREA);
            if (anchors.isEmpty()) anchors.add(new int[]{st.buildingOrNull.cx, st.buildingOrNull.cz});
            for (int[] a : anchors) {
                int ax = a[0], az = a[1];
                if (ax<minX||ax>maxX||az<minZ||az>maxZ) continue;
                if (!canPlacePumpAt(ax, az, st, blocked)) continue;
                placePumpAt(ax, az, st);
                markForbiddenAroundPump(ax, az, st, blocked);
            }
            return;
        }

        // 3) Совсем без геометрии — фоллбэк из двух штук (с проверкой запрещённых зон)
        placeSimplePair(st, minX, maxX, minZ, maxZ, blocked);
    }

    // ===== генерация якорей под навесом: плотная сетка =====
    private List<int[]> anchorsFillUnderCanopy(PolyXZ area, FuelStation st, int limit) {
        ArrayList<int[]> out = new ArrayList<>();

        // Оси сетки: вдоль дороги (ux,uz) и поперёк (px,pz)
        int px = -st.uz, pz = st.ux;

        int xmin = area.minX + EDGE_MARGIN, xmax = area.maxX - EDGE_MARGIN;
        int zmin = area.minZ + EDGE_MARGIN, zmax = area.maxZ - EDGE_MARGIN;

        int bboxW = Math.max(0, xmax - xmin + 1);
        int bboxH = Math.max(0, zmax - zmin + 1);
        int rangeAlong  = (Math.max(bboxW, bboxH) / STEP_ALONG)  + 3;
        int rangeAcross = (Math.max(bboxW, bboxH) / STEP_ACROSS) + 3;

        // Центр сетки — центроид полигона
        int cx = area.cx, cz = area.cz;

        for (int s = -rangeAcross; s <= rangeAcross; s++) {
            for (int t = -rangeAlong; t <= rangeAlong; t++) {
                int x = cx + t*st.ux + s*px;
                int z = cz + t*st.uz + s*pz;

                // Футпринт из трёх точек (-1,0,+1 вдоль длины) должен быть внутри
                int xL = x - st.ux, zL = z - st.uz;
                int xC = x,         zC = z;
                int xR = x + st.ux, zR = z + st.uz;

                if (xC < xmin || xC > xmax || zC < zmin || zC > zmax) continue;
                if (!area.contains(xL, zL)) continue;
                if (!area.contains(xC, zC)) continue;
                if (!area.contains(xR, zR)) continue;

                out.add(new int[]{xC, zC});
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    // ===== якоря «кольцом» вокруг здания (если нет навеса) =====
    private List<int[]> anchorsRingAroundBuilding(PolyXZ building, FuelStation st, int limit) {
        ArrayList<int[]> out = new ArrayList<>();

        int px = -st.uz, pz = st.ux;

        // Чуть расширим bbox, чтобы был коридор снаружи
        int xmin = building.minX - RING_WIDTH - EDGE_MARGIN;
        int xmax = building.maxX + RING_WIDTH + EDGE_MARGIN;
        int zmin = building.minZ - RING_WIDTH - EDGE_MARGIN;
        int zmax = building.maxZ + RING_WIDTH + EDGE_MARGIN;

        int bboxW = Math.max(0, xmax - xmin + 1);
        int bboxH = Math.max(0, zmax - zmin + 1);
        int rangeAlong  = (Math.max(bboxW, bboxH) / STEP_ALONG)  + 3;
        int rangeAcross = (Math.max(bboxW, bboxH) / STEP_ACROSS) + 3;

        int cx = building.cx, cz = building.cz;

        int ring2 = RING_WIDTH * RING_WIDTH;

        for (int s = -rangeAcross; s <= rangeAcross; s++) {
            for (int t = -rangeAlong; t <= rangeAlong; t++) {
                int x = cx + t*st.ux + s*px;
                int z = cz + t*st.uz + s*pz;

                // Футпринт из трёх точек должен быть СНАРУЖИ здания
                int xL = x - st.ux, zL = z - st.uz;
                int xC = x,         zC = z;
                int xR = x + st.ux, zR = z + st.uz;

                if (xC < xmin || xC > xmax || zC < zmin || zC > zmax) continue;
                if (building.contains(xL, zL)) continue;
                if (building.contains(xC, zC)) continue;
                if (building.contains(xR, zR)) continue;

                // И при этом быть «рядом» с границей (не дальше RING_WIDTH)
                if (minDist2ToEdges(building, xL, zL) > ring2) continue;
                if (minDist2ToEdges(building, xC, zC) > ring2) continue;
                if (minDist2ToEdges(building, xR, zR) > ring2) continue;

                out.add(new int[]{xC, zC});
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** Минимальная квадрат дистанции от точки до рёбер полигона. */
    private static int minDist2ToEdges(PolyXZ p, int x, int z) {
        int best = Integer.MAX_VALUE;
        for (int i=0, j=p.n-1; i<p.n; j=i++) {
            int x1 = p.xs[j], z1 = p.zs[j];
            int x2 = p.xs[i], z2 = p.zs[i];
            int d2 = pointSegDist2(x, z, x1, z1, x2, z2);
            if (d2 < best) best = d2;
        }
        return best;
    }

    /** Квадрат расстояния от точки до отрезка. */
    private static int pointSegDist2(int px, int pz, int x1, int z1, int x2, int z2) {
        int vx = x2 - x1, vz = z2 - z1;
        int wx = px - x1, wz = pz - z1;
        int c1 = vx*wx + vz*wz;
        if (c1 <= 0) {
            int dx = px - x1, dz = pz - z1; return dx*dx + dz*dz;
        }
        int c2 = vx*vx + vz*vz;
        if (c2 <= c1) {
            int dx = px - x2, dz = pz - z2; return dx*dx + dz*dz;
        }
        // проекция
        double t = (double)c1 / (double)c2;
        double projx = x1 + t*vx;
        double projz = z1 + t*vz;
        double dx = px - projx, dz = pz - projz;
        return (int)Math.round(dx*dx + dz*dz);
    }

    // ===== установка одной колонки (в точке-«якоре») =====
    private void placePumpAt(int ax, int az, FuelStation st) {
        int[][] ofs = new int[][]{
                {-st.ux,  -st.uz},
                {0,        0     },
                { st.ux,   st.uz}
        };

        int[] yBottom = new int[3];
        int[] xCol = new int[3];
        int[] zCol = new int[3];
        for (int i=0;i<3;i++) {
            int x = ax + ofs[i][0];
            int z = az + ofs[i][1];
            int y = terrainYFromCoordsOrWorld(x, z, null);
            if (y == Integer.MIN_VALUE) return;
            y += 1; // ставим колонку на рельеф + 1
            xCol[i]=x; zCol[i]=z; yBottom[i]=y;

            setBlockSafe(x, y,   z, Blocks.IRON_BLOCK);
            setBlockSafe(x, y+1, z, Blocks.IRON_BLOCK);

            placeWallSignOnAllSides(x, y, z);
        }

        // Фонарь на центральной верхней железке
        placeLanternIfAir(xCol[1], yBottom[1] + 2, zCol[1]);

        // Боковые аксессуары: кнопка (лев.) → рычаг (центр) → рычаг (прав.)
        placeSideAccessories(
                xCol[1], yBottom[1]+1, zCol[1],
                xCol[0], yBottom[0]+1, zCol[0],
                xCol[2], yBottom[2]+1, zCol[2],
                st.sideA, st.sxA, st.szA
        );
        placeSideAccessories(
                xCol[1], yBottom[1]+1, zCol[1],
                xCol[0], yBottom[0]+1, zCol[0],
                xCol[2], yBottom[2]+1, zCol[2],
                st.sideB, st.sxB, st.szB
        );
    }

    /**
     * На одной стороне «стенки» размещаем подряд по оси длины:
     *  кнопка (левый верхний сегмент) → рычаг (центр) → рычаг (правый).
     * Все элементы ставятся в соседнюю клетку наружу (sideDir), чтобы прикрепиться к железному блоку.
     */
    private void placeSideAccessories(int cx, int cyTop, int cz,
                                     int lx, int lyTop, int lz,
                                     int rx, int ryTop, int rz,
                                     Direction sideDir, int sx, int sz) {

        int bx = lx + sx, by = lyTop, bz = lz + sz; // кнопка слева
        placeWallButton(bx, by, bz, sideDir);

        int midx = cx + sx, midy = cyTop, midz = cz + sz; // рычаг в центре
        placeWallLever(midx, midy, midz, sideDir, false);

        int rrx = rx + sx, rry = ryTop, rrz = rz + sz;    // рычаг справа
        placeWallLever(rrx, rry, rrz, sideDir, false);
    }

    // ===== утилиты установки навесных блоков =====

    private void placeWallSignOnAllSides(int coreX, int coreY, int coreZ) {
        placeWallSign(coreX+1, coreY, coreZ, Direction.EAST);
        placeWallSign(coreX-1, coreY, coreZ, Direction.WEST);
        placeWallSign(coreX, coreY, coreZ-1, Direction.NORTH);
        placeWallSign(coreX, coreY, coreZ+1, Direction.SOUTH);
    }

    private void placeWallSign(int x, int y, int z, Direction facingOutward) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.OAK_WALL_SIGN.defaultBlockState();
        try {
            st = st.setValue(WallSignBlock.FACING, facingOutward);
            if (st.hasProperty(BlockStateProperties.WATERLOGGED)) {
                st = st.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeWallButton(int x, int y, int z, Direction facingOutward) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState();
        try {
            st = st.setValue(ButtonBlock.FACE, AttachFace.WALL)
                   .setValue(ButtonBlock.POWERED, Boolean.FALSE)
                   .setValue(BlockStateProperties.HORIZONTAL_FACING, facingOutward);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeWallLever(int x, int y, int z, Direction facingOutward, boolean powered) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.LEVER.defaultBlockState();
        try {
            st = st.setValue(LeverBlock.FACE, AttachFace.WALL)
                   .setValue(LeverBlock.FACING, facingOutward)
                   .setValue(LeverBlock.POWERED, powered);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeLanternIfAir(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.LANTERN.defaultBlockState();
        try {
            if (st.hasProperty(BlockStateProperties.HANGING)) {
                st = st.setValue(BlockStateProperties.HANGING, Boolean.FALSE); // стоит на железе
            }
            if (st.hasProperty(BlockStateProperties.WATERLOGGED)) {
                st = st.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }

    // ===== высота рельефа (строго) =====
    /** Берём ИМЕННО уровень земли, не «верхний не-воздух»: store.grid → coords.terrainGrid → heightmap-1. */
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

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== утилиты проекции и геопривязки =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
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

    // ===== простой фоллбэк на 2 колонки по направлению дороги (с учётом блокировок) =====
    private void placeSimplePair(FuelStation st, int minX, int maxX, int minZ, int maxZ, Set<Long> blocked) {
        int gap = STEP_ALONG; // соблюдаем минимальный шаг, чтобы не пересекались
        int ax1 = st.x - st.ux * gap, az1 = st.z - st.uz * gap;
        int ax2 = st.x + st.ux * gap, az2 = st.z + st.uz * gap;
        if (ax1>=minX&&ax1<=maxX&&az1>=minZ&&az1<=maxZ) {
            if (canPlacePumpAt(ax1, az1, st, blocked)) {
                placePumpAt(ax1, az1, st);
                markForbiddenAroundPump(ax1, az1, st, (HashSet<Long>) blocked);
            }
        }
        if (ax2>=minX&&ax2<=maxX&&az2>=minZ&&az2<=maxZ) {
            if (canPlacePumpAt(ax2, az2, st, blocked)) {
                placePumpAt(ax2, az2, st);
                markForbiddenAroundPump(ax2, az2, st, (HashSet<Long>) blocked);
            }
        }
    }

    /** Центр relation/way: сначала center.lat/lon, иначе середина bounds. Вернёт null, если ничего нет. */
    private int[] elementCenterXZ(JsonObject e,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {
        try {
            if (e.has("center") && e.get("center").isJsonObject()) {
                JsonObject c = e.getAsJsonObject("center");
                if (c.has("lat") && c.has("lon")) {
                    return latlngToBlock(
                            c.get("lat").getAsDouble(), c.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                    );
                }
            }
            if (e.has("bounds") && e.get("bounds").isJsonObject()) {
                JsonObject b = e.getAsJsonObject("bounds");
                if (b.has("minlat") && b.has("minlon") && b.has("maxlat") && b.has("maxlon")) {
                    double minlat = b.get("minlat").getAsDouble();
                    double minlon = b.get("minlon").getAsDouble();
                    double maxlat = b.get("maxlat").getAsDouble();
                    double maxlon = b.get("maxlon").getAsDouble();
                    double clat = (minlat + maxlat) / 2.0;
                    double clon = (minlon + maxlon) / 2.0;
                    return latlngToBlock(
                            clat, clon,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                    );
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** Полигон из geometry; если его нет — прямоугольник по bounds. Вернёт null, если нет обоих. */
    private PolyXZ polyFromGeometryOrBounds(JsonObject e,
                                            double centerLat, double centerLng,
                                            double east, double west, double north, double south,
                                            int sizeMeters, int centerX, int centerZ) {
        try {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 4) {
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(
                            p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
                    );
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                return new PolyXZ(xs, zs);
            }
            if (e.has("bounds") && e.get("bounds").isJsonObject()) {
                JsonObject b = e.getAsJsonObject("bounds");
                if (b.has("minlat") && b.has("minlon") && b.has("maxlat") && b.has("maxlon")) {
                    double minlat = b.get("minlat").getAsDouble();
                    double minlon = b.get("minlon").getAsDouble();
                    double maxlat = b.get("maxlat").getAsDouble();
                    double maxlon = b.get("maxlon").getAsDouble();

                    int[] a = latlngToBlock(minlat, minlon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    int[] b1= latlngToBlock(minlat, maxlon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    int[] c = latlngToBlock(maxlat, maxlon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    int[] d = latlngToBlock(maxlat, minlon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    int[] xs = new int[]{a[0], b1[0], c[0], d[0]};
                    int[] zs = new int[]{a[1],  b1[1], c[1], d[1]};
                    return new PolyXZ(xs, zs);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ======== анти-слипание: проверка и маркировка «санитарной зоны» ========
    private static long packXZ(int x, int z) {
        return (((long)x) << 32) ^ (z & 0xffffffffL);
    }

    /** Можно ли поставить колонку в (ax,az), учитывая блокировки вокруг всех трёх сегментов. */
    private boolean canPlacePumpAt(int ax, int az, FuelStation st, Set<Long> blocked) {
        int[][] pts = new int[][]{
                {ax - st.ux, az - st.uz}, // левый
                {ax,         az        }, // центр
                {ax + st.ux, az + st.uz}  // правый
        };
        for (int[] p : pts) {
            int px = p[0], pz = p[1];
            for (int dx = -CLEAR_GAP; dx <= CLEAR_GAP; dx++) {
                for (int dz = -CLEAR_GAP; dz <= CLEAR_GAP; dz++) {
                    if (blocked.contains(packXZ(px + dx, pz + dz))) return false;
                }
            }
        }
        return true;
    }

    /** Помечаем квадратики CLEAR_GAP×CLEAR_GAP вокруг каждого из трёх сегментов как занятые. */
    private void markForbiddenAroundPump(int ax, int az, FuelStation st, HashSet<Long> blocked) {
        int[][] pts = new int[][]{
                {ax - st.ux, az - st.uz},
                {ax,         az        },
                {ax + st.ux, az + st.uz}
        };
        for (int[] p : pts) {
            int px = p[0], pz = p[1];
            for (int dx = -CLEAR_GAP; dx <= CLEAR_GAP; dx++) {
                for (int dz = -CLEAR_GAP; dz <= CLEAR_GAP; dz++) {
                    blocked.add(packXZ(px + dx, pz + dz));
                }
            }
        }
    }
}