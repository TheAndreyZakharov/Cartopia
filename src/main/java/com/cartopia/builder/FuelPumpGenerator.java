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
    private static final int MAX_PUMPS_PER_AREA = 6;     // лимит колонок на один навес/здание
    private static final int PUMP_SPACING = 6;           // шаг между колонками вдоль
    private static final int EDGE_MARGIN  = 2;           // отступ от краёв проекции

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
        Direction lengthDir;             // направление вдоль дороги (E/W/N/S)
        int ux, uz;                      // ед. вектор вдоль lengthDir
        Direction sideA, sideB;          // две длинные боковые стороны (перпендикуляр к lengthDir)
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
            fsx.canopyOrNull = nearestPoly(roofs, fsx.x, fsx.z, SEARCH_RADIUS_BLOCKS);
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
            fsx.lengthDir = dir;
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

        // --- 1) amenity=fuel → станция (+ roof-полигон самой станции считаем навесом)
        if ("fuel".equals(optString(tags,"amenity"))) {
            if ("node".equals(type)) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() == 1) {
                    JsonObject p = g.get(0).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    stations.add(new FuelStation(xz[0], xz[1]));
                }
            } else if ("way".equals(type) || "relation".equals(type)) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() >= 4) {
                    int[] xs = new int[g.size()], zs = new int[g.size()];
                    for (int i=0;i<g.size();i++){
                        JsonObject p=g.get(i).getAsJsonObject();
                        int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        xs[i]=xz[0]; zs[i]=xz[1];
                    }
                    PolyXZ poly = new PolyXZ(xs, zs);
                    stations.add(new FuelStation(poly.cx, poly.cz));

                    if (isRoofLike(tags)) {
                        roofs.add(poly);   // amenity=fuel, building=roof → это и есть навес
                    } else if (isBuilding(tags)) {
                        builds.add(poly);  // обычное здание, полезно как fallback
                    }
                }
            }
            return; // не продолжаем обработку той же фичи
        }

        // --- 2) крыша-навес (building=roof / man_made=canopy / building=carport)
        if (isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 4) {
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++) {
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                roofs.add(new PolyXZ(xs, zs));
            }
            return;
        }

        // --- 3) обычное здание (building=*, not roof)
        if (isBuilding(tags) && !isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 4) {
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++) {
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                builds.add(new PolyXZ(xs, zs));
            }
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
        PolyXZ area = (st.canopyOrNull != null ? st.canopyOrNull : st.buildingOrNull);
        if (area == null) {
            placeSimplePair(st, minX, maxX, minZ, maxZ);
            return;
        }

        List<int[]> anchors = anchorsAlongArea(area, st, MAX_PUMPS_PER_AREA);
        if (anchors.isEmpty()) {
            anchors.add(new int[]{area.cx, area.cz});
        }

        for (int[] a : anchors) {
            int ax = a[0], az = a[1];
            if (ax<minX||ax>maxX||az<minZ||az>maxZ) continue;
            placePumpAt(ax, az, st);
        }
    }

    private List<int[]> anchorsAlongArea(PolyXZ area, FuelStation st, int limit) {
        ArrayList<int[]> out = new ArrayList<>();
        if (st.lengthDir == Direction.EAST || st.lengthDir == Direction.WEST) {
            int z0 = clampToInsideZ(area, area.cz);
            int minX = area.minX + EDGE_MARGIN, maxX = area.maxX - EDGE_MARGIN;
            if (minX > maxX) { out.add(new int[]{area.cx, z0}); return out; }
            int start = minX + ((PUMP_SPACING/2));
            for (int x = start; x <= maxX; x += PUMP_SPACING) {
                if (area.contains(x, z0)) {
                    out.add(new int[]{x, z0});
                    if (out.size() >= limit) break;
                }
            }
        } else {
            int x0 = clampToInsideX(area, area.cx);
            int minZ = area.minZ + EDGE_MARGIN, maxZ = area.maxZ - EDGE_MARGIN;
            if (minZ > maxZ) { out.add(new int[]{x0, area.cz}); return out; }
            int start = minZ + ((PUMP_SPACING/2));
            for (int z = start; z <= maxZ; z += PUMP_SPACING) {
                if (area.contains(x0, z)) {
                    out.add(new int[]{x0, z});
                    if (out.size() >= limit) break;
                }
            }
        }
        return out;
    }

    private int clampToInsideZ(PolyXZ area, int zPref) {
        for (int d=0; d<=8; d++) {
            if (area.contains(area.cx, zPref+d)) return zPref+d;
            if (area.contains(area.cx, zPref-d)) return zPref-d;
        }
        return Math.max(area.minZ+1, Math.min(area.maxZ-1, zPref));
    }
    private int clampToInsideX(PolyXZ area, int xPref) {
        for (int d=0; d<=8; d++) {
            if (area.contains(xPref+d, area.cz)) return xPref+d;
            if (area.contains(xPref-d, area.cz)) return xPref-d;
        }
        return Math.max(area.minX+1, Math.min(area.maxX-1, xPref));
    }

    private void placeSimplePair(FuelStation st, int minX, int maxX, int minZ, int maxZ) {
        int gap = PUMP_SPACING;
        int ax1 = st.x - st.ux * gap, az1 = st.z - st.uz * gap;
        int ax2 = st.x + st.ux * gap, az2 = st.z + st.uz * gap;
        if (ax1>=minX&&ax1<=maxX&&az1>=minZ&&az1<=maxZ) placePumpAt(ax1, az1, st);
        if (ax2>=minX&&ax2<=maxX&&az2>=minZ&&az2<=maxZ) placePumpAt(ax2, az2, st);
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
}