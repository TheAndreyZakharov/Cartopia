package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class PublicTransportShelterGenerator {

    // Материалы
    private static final Block COL_BLOCK      = Blocks.GLOWSTONE;
    private static final Block WALL_GLASS     = Blocks.GLASS;
    private static final Block ROOF_BLOCK     = Blocks.SMOOTH_QUARTZ;
    private static final Block BENCH_STAIRS   = Blocks.OAK_STAIRS;
    private static final Block EXTRA_CAULDRON = Blocks.CAULDRON;

    // Параметры
    private static final int SHELTER_LEN_ALONG = 6; // 6 блоков по оси дороги
    private static final int SHELTER_DEPTH     = 2; // 2 блока глубина (0=FRONT, 1=BACK)
    private static final int SHELTER_HEIGHT    = 3; // уровень крыши: base+1+3

    private static final int COL_HEIGHT        = 3;
    private static final int WALL_HEIGHT       = 3;

    private static final int DEFAULT_ROAD_WIDTH = 12;

    // Сервис
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // bbox → блоки (клип)
    private int minX, maxX, minZ, maxZ;

    public PublicTransportShelterGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ==== запуск ====
    public void generate() {
        broadcast(level, "Generating stop shelters (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping PublicTransportShelterGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        final double centerLat  = center.get("lat").getAsDouble();
        final double centerLng  = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // bbox → блоки
        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]); maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]); maxZ = Math.max(a[1], b[1]);

        // ===== PASS1: собираем узлы-остановки (stream) =====
        Map<Long, int[]> stopNodeXZ = new HashMap<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                if (!"node".equals(opt(f, "type"))) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isStopLike(tags)) continue;

                Long id = asLong(f, "id");
                Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                if (id == null || lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                stopNodeXZ.put(id, xz);
            }
        } catch (Exception ex) {
            broadcast(level, "Error PASS1 (nodes): " + ex.getMessage());
            return;
        }

        // ===== PASS2a: лучшая авто-дорога, содержащая узел =====
        Map<Long, NodeChoice> best = new HashMap<>();
        Map<Long, DDir>       fallbackDir = new HashMap<>();

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;
                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                int rank   = roadPriorityRank(wtags);
                boolean vehicular = (rank >= 30);
                int width  = widthFromWayTagsOrDefault(wtags);

                JsonArray nds  = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                JsonArray geom = way.has("geometry") ? way.getAsJsonArray("geometry") : null;
                if (nds == null || nds.size() < 2) continue;

                for (int i = 0; i < nds.size(); i++) {
                    long nid = nds.get(i).getAsLong();
                    int[] xz = stopNodeXZ.get(nid);
                    if (xz == null) continue;

                    if (geom != null && geom.size() >= 2) {
                        DirAndSide ds = directionAndSideNearPointFromGeometry(geom, xz[0], xz[1],
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        if (ds == null || ds.dir == null || ds.dir.isZero()) continue;

                        if (vehicular) {
                            NodeChoice prev = best.get(nid);
                            if (prev == null || rank > prev.rank || (rank == prev.rank && width > prev.width)) {
                                best.put(nid, new NodeChoice(nid, xz[0], xz[1], ds.dir, width, ds.sideSign, rank, 0.0));
                            }
                        } else {
                            fallbackDir.putIfAbsent(nid, ds.dir);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error PASS2a (ways containing node): " + ex.getMessage());
        }

        // ===== PASS2b: для непривязанных — ближайшая авто-дорога =====
        Set<Long> pending = new HashSet<>(stopNodeXZ.keySet());
        pending.removeAll(best.keySet());

        if (!pending.isEmpty()) {
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject way : fs) {
                    if (pending.isEmpty()) break;
                    if (!"way".equals(opt(way, "type"))) continue;
                    JsonObject wtags = tagsOf(way);
                    if (wtags == null) continue;

                    int rank = roadPriorityRank(wtags);
                    if (rank < 30) continue;

                    int width = widthFromWayTagsOrDefault(wtags);
                    JsonArray geom = way.has("geometry") ? way.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;

                    List<int[]> pts = new ArrayList<>(geom.size());
                    for (int i = 0; i < geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                    }

                    for (Long nid : new ArrayList<>(pending)) {
                        int[] xz = stopNodeXZ.get(nid);
                        if (xz == null) { pending.remove(nid); continue; }

                        NearestProj np = nearestOnPolyline(pts, xz[0], xz[1]);
                        if (np == null) continue;

                        DDir dir = new DDir(np.segDx, np.segDz).unitNormalized();
                        if (dir.isZero()) continue;

                        DDir cdir = dir.perp().unitNormalized();
                        double vx = xz[0] - np.nearX;
                        double vz = xz[1] - np.nearZ;
                        int sideSign = (vx * cdir.dx + vz * cdir.dz >= 0) ? +1 : -1;

                        NodeChoice prev = best.get(nid);
                        if (prev == null
                                || np.dist2 < prev.dist2
                                || (Math.abs(np.dist2 - prev.dist2) < 1e-6 && (rank > prev.rank || (rank == prev.rank && width > prev.width)))) {
                            best.put(nid, new NodeChoice(nid, xz[0], xz[1], dir, width, sideSign, rank, np.dist2));
                        }
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Error PASS2b (nearest road): " + ex.getMessage());
            }
        }

        // ===== Строим =====
        int built = 0;
        for (NodeChoice c : best.values()) {
            if (c == null) continue;
            buildShelterAtEdge(c.x, c.z, c.dir, c.width, c.sideSign);
            built++;
            if (built % 20 == 0) broadcast(level, "Shelters: ~" + built);
        }

        for (Map.Entry<Long,int[]> e : stopNodeXZ.entrySet()) {
            long nid = e.getKey();
            if (best.containsKey(nid)) continue;
            int[] xz = e.getValue();
            DDir dir = fallbackDir.getOrDefault(nid, new DDir(1, 0));
            buildShelterAtEdge(xz[0], xz[1], dir, DEFAULT_ROAD_WIDTH, +1);
            built++;
        }

        broadcast(level, "Shelters built: " + built);
    }

    // ==== Постройка одного павильона у кромки дороги ====
    private void buildShelterAtEdge(int x0, int z0, DDir alongDir, int roadWidthBlocks, int sideSign) {
        if (alongDir == null || alongDir.isZero()) alongDir = new DDir(1,0);

        // Кардинальные шаги: stepA — вдоль дороги; stepCOut — НАРУЖУ от дороги (на сторону остановки!)
        int[] stepA   = cardinalStep(alongDir);                 // (-1|0|+1, 0|±1)
        int[] stepLeft= new int[]{ -stepA[1], stepA[0] };       // «влево» относительно along
        int[] stepCOut= new int[]{ stepLeft[0]*sideSign, stepLeft[1]*sideSign }; // наружу от проезжей части на стороне остановки

        // смещение от оси дороги к ОБОЧИНЕ: от центра дороги на half+2 наружу (ТОЛЬКО в сторону обочины)
        int half = Math.max(1, roadWidthBlocks / 2);
        int offShoulder = half + 2;

        // центр фронта павильона (у дороги), гарантированно «сбоку от дороги», а не на ней
        int bx = x0 + stepCOut[0] * offShoulder;
        int bz = z0 + stepCOut[1] * offShoulder;

        // диапазон t для длины 6: t=-3..+2
        int tLeft  = -SHELTER_LEN_ALONG / 2;        // -3
        int tRight = tLeft + SHELTER_LEN_ALONG - 1; // +2

        // === 1) Колонны на фронте: t=-3 и t=+2
        placeColumnOnTerrain(bx + stepA[0]*tLeft,  bz + stepA[1]*tLeft,  COL_HEIGHT, COL_BLOCK);
        placeColumnOnTerrain(bx + stepA[0]*tRight, bz + stepA[1]*tRight, COL_HEIGHT, COL_BLOCK);

        // === 2) Стеклянная стена 6×3 на BACK (d=1) — цельная линия
        for (int t = tLeft; t <= tRight; t++) {
            int gx = bx + stepA[0]*t + stepCOut[0]*1; // BACK = наружу от дороги
            int gz = bz + stepA[1]*t + stepCOut[1]*1;
            placeWallH3OnTerrain(gx, gz, WALL_HEIGHT, WALL_GLASS);
        }

        // === 3) Крыша 2×6, сплошным полотном (d=0..1), на уровне +3
        for (int t = tLeft; t <= tRight; t++) {
            for (int d = 0; d < SHELTER_DEPTH; d++) {
                int rx = bx + stepA[0]*t + stepCOut[0]*d;
                int rz = bz + stepA[1]*t + stepCOut[1]*d;
                placeBlockOnTerrainLayer(rx, rz, ROOF_BLOCK, SHELTER_HEIGHT);
            }
        }

        // === 4) Скамейка РОВНО ПО ЦЕНТРУ ФАСАДА: t=-1 и t=0 на FRONT (d=0)
        // Направление ступеней задаём «ОТ дороги» (stepCOut) — так визуально они СМОТРЯТ НА дорогу.
        int benchX = bx + stepA[0] * (-1);
        int benchZ = bz + stepA[1] * (-1);
        Direction benchFacingToRoad = stepToDir(stepCOut[0], stepCOut[1]); // ключевой фикс ориентации
        buildBenchFacing(benchX, benchZ, benchFacingToRoad);

        // === 5) CAULDRON: у правой фронтальной колонны, на стороне БЕЗ стекла (к дороге)
        int colRX = bx + stepA[0]*tRight;
        int colRZ = bz + stepA[1]*tRight;
        int roadSideX = -stepCOut[0]; // к дороге (обратная нормаль)
        int roadSideZ = -stepCOut[1];
        placeBlockOnTerrain(colRX + roadSideX, colRZ + roadSideZ, EXTRA_CAULDRON);
    }

    // ==== Строительные примитивы ====

    private void placeColumnOnTerrain(int x, int z, int height, Block b) {
        if (!inBounds(x, z)) return;
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return;
        for (int i = 1; i <= height; i++) {
            level.setBlock(new BlockPos(x, base + i, z), b.defaultBlockState(), 3);
        }
    }

    private void placeWallH3OnTerrain(int x, int z, int height, Block b) {
        if (!inBounds(x, z)) return;
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return;
        for (int i = 1; i <= height; i++) {
            level.setBlock(new BlockPos(x, base + i, z), b.defaultBlockState(), 3);
        }
    }

    private void placeBlockOnTerrain(int x, int z, Block b) {
        if (!inBounds(x, z)) return;
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, base + 1, z), b.defaultBlockState(), 3);
    }

    private void placeBlockOnTerrainLayer(int x, int z, Block b, int layerOffset) {
        if (!inBounds(x, z)) return;
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, base + 1 + layerOffset, z), b.defaultBlockState(), 3);
    }

    // ==== Скамейка (две ступени вплотную), без табличек ====
    private void buildBenchFacing(int x, int z, Direction facing) {
        int[] axis = dirToStep(facing);             // «вперёд»
        int[] left = new int[]{-axis[1], axis[0]};  // «влево» от facing — это вдоль фасада

        int sx1 = x,           sz1 = z;                   // t = -1
        int sx2 = x + left[0], sz2 = z + left[1];         // t = 0

        placeStairsOnTerrain(sx1, sz1, facing);
        placeStairsOnTerrain(sx2, sz2, facing);
    }

    private void placeStairsOnTerrain(int x, int z, Direction facing) {
        if (!inBounds(x, z)) return;
        int base = groundY(x, z);
        if (base == Integer.MIN_VALUE) return;
        BlockState st = BENCH_STAIRS.defaultBlockState();
        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        level.setBlock(new BlockPos(x, base + 1, z), st, 3);
    }

    // ==== Геометрия/утилиты ====

    private static int[] cardinalStep(DDir v) {
        // сводим произвольное направление к 4-кардинальным шагам по сетке
        if (Math.abs(v.dx) >= Math.abs(v.dz)) {
            return new int[]{ v.dx >= 0 ? 1 : -1, 0 };
        } else {
            return new int[]{ 0, v.dz >= 0 ? 1 : -1 };
        }
    }

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
    private static final class DirAndSide {
        final DDir dir; final int sideSign;
        DirAndSide(DDir dir, int sideSign){ this.dir = dir; this.sideSign = sideSign; }
    }
    private static final class NodeChoice {
        @SuppressWarnings("unused")
        final long id; final int x, z, width; final DDir dir; final int sideSign; final int rank; final double dist2;
        NodeChoice(long id, int x, int z, DDir dir, int width, int sideSign, int rank, double dist2){
            this.id=id; this.x=x; this.z=z; this.dir=dir;
            this.width=Math.max(1,width);
            this.sideSign = (sideSign>=0?+1:-1);
            this.rank = rank;
            this.dist2 = dist2;
        }
    }
    private static final class NearestProj {
        final double nearX, nearZ, segDx, segDz, dist2;
        NearestProj(double nearX, double nearZ, double segDx, double segDz, double dist2){
            this.nearX=nearX; this.nearZ=nearZ; this.segDx=segDx; this.segDz=segDz; this.dist2=dist2;
        }
    }

    private static Direction stepToDir(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return (dx >= 0) ? Direction.EAST : Direction.WEST;
        return (dz >= 0) ? Direction.SOUTH : Direction.NORTH;
    }
    private static int[] dirToStep(Direction d) {
        switch (d) {
            case EAST:  return new int[]{+1, 0};
            case WEST:  return new int[]{-1, 0};
            case SOUTH: return new int[]{0, +1};
            case NORTH: return new int[]{0, -1};
            default:    return new int[]{1, 0};
        }
    }

    private static NearestProj nearestOnPolyline(List<int[]> pts, int x, int z) {
        if (pts == null || pts.size() < 2) return null;
        double bestD2 = Double.MAX_VALUE;
        double nearX = x, nearZ = z, segDxBest = 0, segDzBest = 0;

        for (int i = 0; i < pts.size() - 1; i++) {
            int[] A = pts.get(i), B = pts.get(i+1);
            double ax = A[0], az = A[1], bx = B[0], bz = B[1];
            double vx = bx - ax, vz = bz - az, wx = x - ax,  wz = z - az;

            double vv = vx*vx + vz*vz;
            if (vv < 1e-9) continue;
            double t = (vx*wx + vz*wz) / vv;
            if (t < 0) t = 0; else if (t > 1) t = 1;

            double px = ax + t * vx, pz = az + t * vz;
            double dx = x - px, dz = z - pz;
            double d2 = dx*dx + dz*dz;
            if (d2 < bestD2) {
                bestD2 = d2; nearX = px; nearZ = pz; segDxBest = vx; segDzBest = vz;
            }
        }
        return new NearestProj(nearX, nearZ, segDxBest, segDzBest, bestD2);
    }

    private static DirAndSide directionAndSideNearPointFromGeometry(JsonArray geom,
                                                                    int x, int z,
                                                                    double centerLat, double centerLng,
                                                                    double east, double west, double north, double south,
                                                                    int sizeMeters, int centerX, int centerZ) {
        if (geom == null || geom.size() < 2) return null;

        int bestIdx = -1; double bestD2 = Double.MAX_VALUE; int bestX = 0, bestZ = 0;
        for (int i = 0; i < geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            double dx = xz[0] - x, dz = xz[1] - z, d2 = dx*dx + dz*dz;
            if (d2 < bestD2) { bestD2 = d2; bestIdx = i; bestX = xz[0]; bestZ = xz[1]; }
        }
        if (bestIdx == -1) return null;

        int i0 = Math.max(0, bestIdx - 1), i1 = Math.min(geom.size() - 1, bestIdx + 1);
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
        DDir dir = new DDir(B[0]-A[0], B[1]-A[1]).unitNormalized();

        DDir c = dir.perp().unitNormalized(); // «лево» относительно dir
        double vx = x - bestX, vz = z - bestZ;
        int sideSign = ((vx * c.dx + vz * c.dz) >= 0) ? +1 : -1;

        return new DirAndSide(dir, sideSign);
    }

    // ==== Рельеф/клип ====

    private int groundY(int x, int z) {
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
                    int minGX = g.get("minX").getAsInt();
                    int minGZ = g.get("minZ").getAsInt();
                    int w    = g.get("width").getAsInt();
                    int h    = g.get("height").getAsInt();
                    int ix = x - minGX, iz = z - minGZ;
                    if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz * w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private boolean inBounds(int x, int z) {
        return !(x < minX || x > maxX || z < minZ || z > maxZ);
    }

    // ==== Данные/утилиты ====

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

    private static JsonObject tagsOf(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }
    private static String opt(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Long   asLong  (JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsLong()   : null; } catch (Throwable ignore){return null;} }
    private static Double asDouble(JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsDouble() : null; } catch (Throwable ignore){return null;} }

    // PT-остановка?
    private static boolean isStopLike(JsonObject tags) {
        if (tags == null) return false;

        String highway = low(opt(tags, "highway"));
        String pt      = low(opt(tags, "public_transport"));
        String amenity = low(opt(tags, "amenity"));
        String railway = low(opt(tags, "railway"));

        if ("bus_stop".equals(highway)) return true;
        if ("taxi".equals(amenity)) return true;

        if ("platform".equals(pt) || "stop_position".equals(pt) || "station".equals(pt)) {
            if (isYes(low(opt(tags,"bus"))) || isYes(low(opt(tags,"tram"))) || isYes(low(opt(tags,"trolleybus"))) || isYes(low(opt(tags,"light_rail"))))
                return true;
        }
        if ("tram_stop".equals(railway)) return true;
        if ("platform".equals(pt) || "stop_position".equals(pt)) return true;

        return false;
    }
    private static boolean isYes(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("yes") || s.equals("true") || s.equals("1");
    }

    private static int roadPriorityRank(JsonObject tags) {
        String hwy = low(opt(tags, "highway"));
        if (hwy != null) {
            switch (hwy) {
                case "motorway": return 100;
                case "trunk":    return 95;
                case "primary":  return 90;
                case "secondary":return 80;
                case "tertiary": return 70;
                case "residential": return 60;
                case "living_street": return 55;
                case "unclassified":  return 50;
                case "service":       return 45;
                case "track":         return 40;
                case "pedestrian": case "footway": case "path": case "cycleway": return 10;
                default: return 35;
            }
        }
        return 5;
    }

    private static int widthFromWayTagsOrDefault(JsonObject tags) {
        Integer w = parseNumericWidth(tags);
        if (w != null && w > 0) return w;
        String hwy = low(opt(tags, "highway"));
        String aeroway = low(opt(tags, "aeroway"));
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

    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int)Math.round(centerX + dx), (int)Math.round(centerZ + dz)};
    }
}