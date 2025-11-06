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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class TrafficLightGenerator {

    // Материалы
    private static final Block POLE_WALL = Blocks.ANDESITE_WALL;
    private static final Block SLIME     = Blocks.SLIME_BLOCK;
    private static final Block HONEY     = Blocks.HONEY_BLOCK;
    private static final Block REDSTONE  = Blocks.REDSTONE_BLOCK;
    private static final Block TOP_SLAB  = Blocks.ANDESITE_SLAB;

    // Дорожные покрытия + разметка, куда ставить НЕЛЬЗЯ
    private static final Set<Block> ROAD_SURFACE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.GRAY_CONCRETE,        // основное полотно
            Blocks.WHITE_CONCRETE,       // пешеходные переходы/разметка
            Blocks.YELLOW_CONCRETE,      // осевая/бордюры/разметка
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.SPRUCE_PLANKS,        // настилы/мостки
            Blocks.CHISELED_STONE_BRICKS,// «металл» из подмен
            Blocks.SEA_LANTERN           // огни ВПП
    ));

    private static final int DEFAULT_ROAD_WIDTH   = 12;
    private static final int MAX_EDGE_SEARCH      = 128; // идём вправо до стольки шагов
    private static final int EXTRA_AFTER_EDGE     = 1;   // доп. шаг после выхода с полотна

    // Контекст
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // bbox → блоки (клип)
    private int minX, maxX, minZ, maxZ;

    public TrafficLightGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ==== запуск ====
    public void generate() {
        broadcast(level, "Generating traffic lights (stream)...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping TrafficLightGenerator.");
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

        // ===== PASS1: узлы светофоров =====
        Map<Long, int[]> tlNodeXZ = new HashMap<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                if (!"node".equals(opt(f, "type"))) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isTrafficSignalLike(tags)) continue;

                Long id = asLong(f, "id");
                Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                if (id == null || lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                tlNodeXZ.put(id, xz);
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1 (nodes): " + ex.getMessage());
            return;
        }

        // ===== PASS2a: дорога, содержащая узел =====
        Map<Long, NodeChoice> best = new HashMap<>();
        Map<Long, DDir>       fallbackDir = new HashMap<>();
        Map<Long, Boolean>    isBridgeByNode = new HashMap<>();

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;
                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                int rank   = roadPriorityRank(wtags);
                boolean vehicular = (rank >= 30);
                int width  = widthFromWayTagsOrDefault(wtags);
                boolean isBridge = isBridgeWay(wtags);

                JsonArray nds  = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                JsonArray geom = way.has("geometry") ? way.getAsJsonArray("geometry") : null;
                if (nds == null || nds.size() < 2 || geom == null || geom.size() < 2) continue;

                for (int i = 0; i < nds.size(); i++) {
                    long nid = nds.get(i).getAsLong();
                    int[] xz = tlNodeXZ.get(nid);
                    if (xz == null) continue;

                    DirAndSide ds = directionAndSideNearPointFromGeometry(geom, xz[0], xz[1],
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    if (ds == null || ds.dir == null || ds.dir.isZero()) continue;

                    if (vehicular) {
                        NodeChoice prev = best.get(nid);
                        if (prev == null || rank > prev.rank || (rank == prev.rank && width > prev.width)) {
                            best.put(nid, new NodeChoice(nid, xz[0], xz[1], ds.dir, width, rank, 0.0));
                            isBridgeByNode.put(nid, isBridge);
                        }
                    } else {
                        fallbackDir.putIfAbsent(nid, ds.dir);
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS2a (ways containing node): " + ex.getMessage());
        }

        // ===== PASS2b: ближайшая дорога для непривязанных =====
        Set<Long> pending = new HashSet<>(tlNodeXZ.keySet());
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
                    boolean isBridge = isBridgeWay(wtags);

                    JsonArray geom = way.has("geometry") ? way.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;

                    List<int[]> pts = new ArrayList<>(geom.size());
                    for (int i = 0; i < geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                    }

                    for (Long nid : new ArrayList<>(pending)) {
                        int[] xz = tlNodeXZ.get(nid);
                        if (xz == null) { pending.remove(nid); continue; }

                        NearestProj np = nearestOnPolyline(pts, xz[0], xz[1]);
                        if (np == null) continue;

                        DDir dir = new DDir(np.segDx, np.segDz).unitNormalized();
                        if (dir.isZero()) continue;

                        NodeChoice prev = best.get(nid);
                        if (prev == null
                                || np.dist2 < prev.dist2
                                || (Math.abs(np.dist2 - prev.dist2) < 1e-6 && (rank > prev.rank || (rank == prev.rank && width > prev.width)))) {
                            best.put(nid, new NodeChoice(nid, xz[0], xz[1], dir, width, rank, np.dist2));
                            isBridgeByNode.put(nid, isBridge);
                        }
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Error in PASS2b (nearest road): " + ex.getMessage());
            }
        }

        // ===== Строим =====
        int built = 0;
        for (NodeChoice c : best.values()) {
            boolean onBridge = Boolean.TRUE.equals(isBridgeByNode.get(c.id));
            buildTrafficLightAtRoadRightEdge(c.x, c.z, c.dir, c.width, onBridge);
            built++;
            if (built % 50 == 0) broadcast(level, "Traffic lights: ~" + built);
        }

        // fallback
        for (Map.Entry<Long,int[]> e : tlNodeXZ.entrySet()) {
            if (best.containsKey(e.getKey())) continue;
            int[] xz = e.getValue();
            DDir dir = fallbackDir.getOrDefault(e.getKey(), new DDir(1,0));
            buildTrafficLightAtRoadRightEdge(xz[0], xz[1], dir, DEFAULT_ROAD_WIDTH, false);
            built++;
        }

        broadcast(level, "Traffic lights placed: " + built);
    }

    // ==== Постройка у ПРАВОЙ кромки дороги (жёстко до края) ====
    private void buildTrafficLightAtRoadRightEdge(int x0, int z0, DDir alongDir, int roadWidthBlocks, boolean bridge) {
        if (alongDir == null || alongDir.isZero()) alongDir = new DDir(1,0);

        // Кардинальные шаги
        int[] stepA     = cardinalStep(alongDir);       // вдоль
        int[] stepRight = new int[]{ +stepA[1], -stepA[0] }; // вправо по ходу

        // старт: примерно к правой стороне полотна, чтобы уменьшить длину поиска
        int offStart = Math.max(0, roadWidthBlocks / 2);
        int sx = x0 + stepRight[0] * offStart;
        int sz = z0 + stepRight[1] * offStart;

        // идём ВПРАВО до выхода с дорожного покрытия
        int[] edge = marchRightToRoadEdge(sx, sz, stepRight, MAX_EDGE_SEARCH, EXTRA_AFTER_EDGE);
        if (edge == null) return; // край не найден в пределах — пропуск

        int bx = edge[0], bz = edge[1];
        if (!inBounds(bx, bz)) return;

        int ground = groundY(bx, bz);
        if (ground == Integer.MIN_VALUE) return;

        int y0 = ground + 1 + (bridge ? 7 : 0);

        // Сборка столба
        setBlock(bx, y0,     bz, POLE_WALL);
        setBlock(bx, y0 + 1, bz, POLE_WALL);
        setBlock(bx, y0 + 2, bz, POLE_WALL);

        setBlock(bx, y0 + 3, bz, SLIME);
        setBlock(bx, y0 + 4, bz, HONEY);
        setBlock(bx, y0 + 5, bz, REDSTONE);

        BlockState slab = TOP_SLAB.defaultBlockState();
        if (slab.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            slab = slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        }
        setBlockState(bx, y0 + 6, bz, slab);
    }

    /** Идём вправо от (sx,sz), пока встречается дорожное покрытие; затем делаем ещё extra шагов наружу. */
    private int[] marchRightToRoadEdge(int sx, int sz, int[] stepRight, int maxSteps, int extraAfter) {
        int x = sx, z = sz;
        int steps = 0;

        // Если старт уже НЕ дорога — всё равно отступим extraAfter наружу, чтобы точно не задеть кромку
        if (!isRoadSurfaceAt(x, z)) {
            for (int i = 0; i < extraAfter; i++) {
                int nx = x + stepRight[0], nz = z + stepRight[1];
                if (!inBounds(nx, nz)) break;
                if (isRoadSurfaceAt(nx, nz)) break;
                x = nx; z = nz;
            }
            return new int[]{x, z};
        }

        // Пока под нами дорога — шагаем вправо
        while (steps < maxSteps && inBounds(x, z) && isRoadSurfaceAt(x, z)) {
            x += stepRight[0];
            z += stepRight[1];
            steps++;
        }
        if (!inBounds(x, z)) return null;          // вышли из области
        if (isRoadSurfaceAt(x, z)) return null;    // не смогли выйти с дороги в лимит

        // Доп. шаг(и) после выхода для надёжности
        for (int i = 0; i < extraAfter; i++) {
            int nx = x + stepRight[0], nz = z + stepRight[1];
            if (!inBounds(nx, nz)) break;
            if (isRoadSurfaceAt(nx, nz)) break;
            x = nx; z = nz;
        }
        return new int[]{x, z};
    }

    /** Дорожная поверхность в точке (x,z) по верхнему блоку рельефа. */
    private boolean isRoadSurfaceAt(int x, int z) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return false;
        Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        return ROAD_SURFACE_BLOCKS.contains(b);
    }

    // ==== Примитивы ====
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }
    private void setBlockState(int x, int y, int z, BlockState st) {
        level.setBlock(new BlockPos(x, y, z), st, 3);
    }

    // ==== Геометрия/утилиты ====
    private static int[] cardinalStep(DDir v) {
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
    @SuppressWarnings("unused")
    private static final class DirAndSide {
        final DDir dir; final int sideSign;
        DirAndSide(DDir dir, int sideSign){ this.dir = dir; this.sideSign = sideSign; }
    }
    private static final class NodeChoice {
        final long id; final int x, z, width; final DDir dir; final int rank; final double dist2;
        NodeChoice(long id, int x, int z, DDir dir, int width, int rank, double dist2){
            this.id=id; this.x=x; this.z=z; this.dir=dir;
            this.width=Math.max(1,width);
            this.rank = rank;
            this.dist2 = dist2;
        }
    }
    private static final class NearestProj {
        @SuppressWarnings("unused")
        final double nearX, nearZ, segDx, segDz, dist2;
        NearestProj(double nearX, double nearZ, double segDx, double segDz, double dist2){
            this.nearX=nearX; this.nearZ=nearZ; this.segDx=segDx; this.segDz=segDz; this.dist2=dist2;
        }
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

    private static boolean isTrafficSignalLike(JsonObject tags) {
        if (tags == null) return false;
        String hwy = low(opt(tags, "highway"));
        String ts  = low(opt(tags, "traffic_signals"));
        String crossing = low(opt(tags, "crossing"));
        if ("traffic_signals".equals(hwy)) return true;
        if (ts != null && !ts.isBlank()) return true;
        if ("traffic_signals".equals(crossing)) return true;
        if (containsValueLike(tags, "traffic_signal") || containsValueLike(tags, "traffic_signals")) return true;
        return false;
    }

    private static boolean isBridgeWay(JsonObject tags) {
        String bridge = low(opt(tags, "bridge"));
        if (bridge != null && !bridge.equals("no")) return true;
        String manmade = low(opt(tags, "man_made"));
        if ("bridge".equals(manmade)) return true;
        try {
            String layer = opt(tags, "layer");
            if (layer != null) {
                int lv = Integer.parseInt(layer.trim());
                if (lv >= 1) return true;
            }
        } catch (Throwable ignore) {}
        return false;
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

    private static boolean containsValueLike(JsonObject t, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String k : t.keySet()) {
            JsonElement v = t.get(k);
            if (v == null || v.isJsonNull()) continue;
            try {
                String s = v.getAsString().toLowerCase(Locale.ROOT);
                if (s.contains(n)) return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    // ==== Преобразования координат ====
    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int)Math.round(centerX + dx), (int)Math.round(centerZ + dz)};
    }
}