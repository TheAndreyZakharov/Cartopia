package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class CemeteryGravesGenerator {

    // ---- Параметры раскладки / окружения ----
    private static final int  CEMETERY_INNER_MARGIN = 0; // было 5 — убрал, чтобы ставить по всей территории
    private static final int  GRAVE_STEP = 8;            // было 4 — теперь +2 блока со всех сторон
    private static final double EMPTY_PLOT_PROB = 0.08;
    private static final int  SAFE_RADIUS_TO_ROADS = 5;  // оставил как было: дистанция от фактического полотна дорог в мире

    // ---- Материалы могил (70/15/15) ----
    private static final class GraveSet {
        final Block base, wall, slab;
        GraveSet(Block base, Block wall, Block slab){ this.base=base; this.wall=wall; this.slab=slab; }
    }
    private static final GraveSet SET_COBBLE = new GraveSet(Blocks.COBBLESTONE, Blocks.COBBLESTONE_WALL, Blocks.COBBLESTONE_SLAB);
    private static final GraveSet SET_MOSSY  = new GraveSet(Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.MOSSY_COBBLESTONE_SLAB);
    private static final GraveSet SET_WHITE  = new GraveSet(Blocks.SMOOTH_QUARTZ, Blocks.DIORITE_WALL, Blocks.SMOOTH_QUARTZ_SLAB);

    // ---- Допустимые основания: расширено (трава/земля/песок/гравий/грязь и т.п.) ----
    private static final Set<Block> ALLOWED_FOUNDATIONS = new HashSet<>(Arrays.asList(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL,
            Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS,
            Blocks.MOSS_BLOCK, Blocks.FARMLAND, Blocks.SNOW_BLOCK,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE
    ));

    // ---- Поверхности дорог (как в TrafficLightGenerator) ----
    private static final Set<Block> ROAD_SURFACE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.GRAY_CONCRETE,
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.SPRUCE_PLANKS,
            Blocks.CHISELED_STONE_BRICKS,
            Blocks.SEA_LANTERN
    ));

    // ---- Контекст ----
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public CemeteryGravesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ---- Вещалка ----
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

    // ---- Геометрия многоугольников ----
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
        double distanceToEdges(int x, int z) {
            double best = Double.POSITIVE_INFINITY;
            for (int i=0, j=n-1; i<n; j=i++) {
                best = Math.min(best, distPointToSegment(x, z, xs[j], zs[j], xs[i], zs[i]));
            }
            return best;
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
            boolean inOuter=false;
            for (Ring r:outers) if (r.containsPoint(x,z)) { inOuter=true; break; }
            if (!inOuter) return false;
            for (Ring r:inners) if (r.containsPoint(x,z)) return false;
            return true;
        }
        double edgeDistance(int x, int z) {
            double best = Double.POSITIVE_INFINITY;
            for (Ring r:outers) best = Math.min(best, r.distanceToEdges(x,z));
            return best;
        }
    }

    // ---- Дороги (для ориентации) ----
    private static final class Polyline {
        final int[] xs, zs; Polyline(int[] xs, int[] zs){ this.xs=xs; this.zs=zs; }
    }
    private final List<Polyline> roads = new ArrayList<>();

    // ---- Запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "CemeteryGravesGenerator: coords == null — пропускаю."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "CemeteryGravesGenerator: нет center/bbox — пропускаю."); return; }

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

        List<Area> cemeteries = new ArrayList<>();

        // ===== STREAM: кладбища + дороги =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectCemeteryArea(e, cemeteries,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        collectRoadPolyline(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "CemeteryGravesGenerator: нет coords.features — пропускаю."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "CemeteryGravesGenerator: features.elements пуст — пропускаю."); return; }
                for (JsonElement el : elements) {
                    JsonObject e = el.getAsJsonObject();
                    collectCemeteryArea(e, cemeteries, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    collectRoadPolyline(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "CemeteryGravesGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (cemeteries.isEmpty()) {
            broadcast(level, "CemeteryGravesGenerator: зон кладбищ не найдено — готово.");
            return;
        }

        int idx = 0;
        long totalPlaced = 0;
        for (Area area : cemeteries) {
            idx++;
            totalPlaced += tileCemetery(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, cemeteries.size());
        }
        broadcast(level, "Надгробия: готово, поставлено " + totalPlaced + " шт.");
    }

    // ---- Сбор кладбищ ----
    private void collectCemeteryArea(JsonObject e, List<Area> out,
                                     double centerLat, double centerLng,
                                     double east, double west, double north, double south,
                                     int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        if (!isCemetery(tags)) return;

        String type = optString(e, "type");
        if (type == null) return;

        if ("way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 3) return;
            Area area = new Area();
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            area.addOuter(new Ring(xs, zs));
            out.add(area);
            return;
        }

        if ("relation".equals(type)) {
            String rtype = low(optString(tags, "type"));
            JsonArray members = (e.has("members") && e.get("members").isJsonArray()) ? e.getAsJsonArray("members") : null;

            if (!"multipolygon".equals(rtype) || members == null || members.size()==0) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) return;
                Area area = new Area();
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                area.addOuter(new Ring(xs, zs));
                out.add(area);
                return;
            }

            Area area = new Area();
            for (JsonElement mEl : members) {
                JsonObject m = mEl.getAsJsonObject();
                String role = optString(m, "role");
                JsonArray g = (m.has("geometry") && m.get("geometry").isJsonArray())
                        ? m.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) continue;

                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }

                Ring r = new Ring(xs, zs);
                if ("inner".equalsIgnoreCase(role)) area.addInner(r); else area.addOuter(r);
            }
            if (!area.outers.isEmpty()) out.add(area);
        }
    }

    private static boolean isCemetery(JsonObject t) {
        String landuse = low(optString(t,"landuse"));
        String amenity = low(optString(t,"amenity"));
        return "cemetery".equals(landuse) || "grave_yard".equals(amenity);
    }

    // ---- Сбор дорог (ориентация) ----
    private void collectRoadPolyline(JsonObject e,
                                     double centerLat, double centerLng,
                                     double east, double west, double north, double south,
                                     int sizeMeters, int centerX, int centerZ) {
        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;
        String hwy = low(optString(tags, "highway"));
        if (hwy == null) return;
        if (!isRoadLikeForOrientation(hwy)) return;

        if (!"way".equals(optString(e,"type"))) return;
        JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
        if (g == null || g.size() < 2) return;

        int[] xs = new int[g.size()], zs = new int[g.size()];
        for (int i=0;i<g.size();i++){
            JsonObject p=g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            xs[i]=xz[0]; zs[i]=xz[1];
        }
        roads.add(new Polyline(xs, zs));
    }

    private static boolean isRoadLikeForOrientation(String hwy) {
        switch (hwy) {
            case "motorway": case "trunk": case "primary": case "secondary": case "tertiary":
            case "unclassified": case "residential": case "living_street":
            case "service": case "track":
            case "pedestrian": case "footway": case "path": case "cycleway":
                return true;
            default: return false;
        }
    }
    @SuppressWarnings("unused")
    // ---- Укладка по зоне (c подбором смещения сетки) ----
    private long tileCemetery(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, int idx, int total) {
        int minX = clamp(area.minX, wMinX, wMaxX);
        int maxX = Math.min(area.maxX, wMaxX);
        int minZ = clamp(area.minZ, wMinZ, wMaxZ);
        int maxZ = Math.min(area.maxZ, wMaxZ);

        // Подберём смещение сетки (ox, oz) как в Beach/Substation — максимальное покрытие полигона
        int step = GRAVE_STEP;
        int bestOx = 0, bestOz = 0, bestCount = -1;
        for (int ox = 0; ox < step; ox++) {
            for (int oz = 0; oz < step; oz++) {
                int cnt = simulateCount(area, minX, maxX, minZ, maxZ, step, ox, oz);
                if (cnt > bestCount) { bestCount = cnt; bestOx = ox; bestOz = oz; }
            }
        }

        if (bestCount <= 0) {
            broadcast(level, String.format(Locale.ROOT, "Кладбища %d/%d: зона слишком узкая для сетки.", idx, total));
            return 0;
        }

        long placed = 0;
        long candidates = 0;

        int startX = minX + floorMod(bestOx - (minX % step + step) % step, step);
        int startZ = minZ + floorMod(bestOz - (minZ % step + step) % step, step);

        for (int x = startX; x <= maxX; x += step) {
            for (int z = startZ; z <= maxZ; z += step) {
                if (!area.contains(x, z)) continue;
                if (CEMETERY_INNER_MARGIN > 0 && area.edgeDistance(x, z) < CEMETERY_INNER_MARGIN) continue;

                // направление к ближайшей дороге
                int[] face = faceDirToNearestRoad(x, z);
                if (face == null) face = new int[]{1, 0};
                int sx = x + face[0], sz = z + face[1];

                // база и slab ОБА должны быть внутри полигона
                if (!area.contains(sx, sz)) continue;

                // вода/дороги/дистанция/основания (и для базы, и для slab)
                if (isWaterAt(x, z) || isWaterAt(sx, sz)) continue;
                if (isRoadSurfaceAt(x, z) || isRoadSurfaceAt(sx, sz)) continue;
                if (nearRoadSurface(x, z, SAFE_RADIUS_TO_ROADS)) continue;
                if (!allowedFoundationAt(x, z) || !allowedFoundationAt(sx, sz)) continue;

                // редкий пропуск
                if (hash01(x, z) < EMPTY_PLOT_PROB) continue;

                if (placeGraveWithKnownDir(x, z, face[0], face[1])) placed++;
                candidates++;
            }
        }

        broadcast(level, String.format(Locale.ROOT,
                "Кладбища %d/%d: ~100%% (смещение %d,%d; кандидатов %d; поставлено %d)",
                idx, total, bestOx, bestOz, candidates, placed));
        return placed;
    }

    @SuppressWarnings("unused")
    // Подсчёт потенциальных позиций для выбора лучшего смещения
    private int simulateCount(Area area, int minX, int maxX, int minZ, int maxZ, int step, int ox, int oz) {
        int cnt = 0;
        int startX = minX + floorMod(ox - (minX % step + step) % step, step);
        int startZ = minZ + floorMod(oz - (minZ % step + step) % step, step);
        for (int x = startX; x <= maxX; x += step) {
            for (int z = startZ; z <= maxZ; z += step) {
                if (!area.contains(x, z)) continue;
                if (CEMETERY_INNER_MARGIN > 0 && area.edgeDistance(x, z) < CEMETERY_INNER_MARGIN) continue;
                // грубо считаем, что slab будет в соседней клетке; направления тут не знаем — возьмём 4 кардинальных и проверим «хоть куда-то»
                boolean okAny = false;
                int[][] dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    int sx = x + d[0], sz = z + d[1];
                    if (area.contains(sx, sz)) { okAny = true; break; }
                }
                if (okAny) cnt++;
            }
        }
        return cnt;
    }

    // ---- Построение надгробия (когда направление уже посчитано) ----
    private boolean placeGraveWithKnownDir(int x, int z, int dx, int dz) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return false;

        int sx = x + dx, sz = z + dz;
        int ySlab = terrainY(sx, sz);
        if (ySlab == Integer.MIN_VALUE) return false;

        clearColumnAir(x,  y + 1, z, 3);
        clearColumnAir(sx, ySlab + 1, sz, 2);

        GraveSet gs = pickGraveSet(x, z);

        setBlockSafe(x, y + 1, z, gs.base);
        BlockState wallSt = gs.wall.defaultBlockState();
        try {
            if (!(gs.wall instanceof WallBlock)) {
                setBlockSafe(x, y + 2, z, gs.wall);
            } else {
                setBlockState(x, y + 2, z, wallSt);
            }
        } catch (Throwable ignore) {
            setBlockSafe(x, y + 2, z, gs.wall);
        }

        placeSlabBottom(sx, ySlab + 1, sz, gs.slab);
        return true;
    }

    private GraveSet pickGraveSet(int x, int z) {
        double r = hash01(x * 31 + 7, z * 17 + 11);
        if (r < 0.70) return SET_COBBLE;
        if (r < 0.85) return SET_MOSSY;
        return SET_WHITE;
    }

    // ---- Направление к ближайшей дороге ----
    private int[] faceDirToNearestRoad(int x, int z) {
        double bestD2 = Double.POSITIVE_INFINITY;
        double bestDx = 1, bestDz = 0;

        for (Polyline pl : roads) {
            int n = pl.xs.length;
            for (int i = 0; i < n - 1; i++) {
                double px = pl.xs[i], pz = pl.zs[i];
                double qx = pl.xs[i+1], qz = pl.zs[i+1];
                double[] proj = projectPointOnSegment(x, z, px, pz, qx, qz);
                double d2 = proj[2];
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestDx = proj[0] - x;
                    bestDz = proj[1] - z;
                }
            }
        }
        double L = Math.hypot(bestDx, bestDz);
        if (L < 1e-9) return new int[]{1, 0};
        double ux = bestDx / L, uz = bestDz / L;
        if (Math.abs(ux) >= Math.abs(uz)) return new int[]{ ux >= 0 ? 1 : -1, 0 };
        else return new int[]{ 0, uz >= 0 ? 1 : -1 };
    }

    // ---- Проверки окружения ----
    private boolean allowedFoundationAt(int x, int z) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return false;
        Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        if (b == Blocks.WATER) return false;
        if (ROAD_SURFACE_BLOCKS.contains(b)) return false;
        return ALLOWED_FOUNDATIONS.contains(b);
    }

    private boolean isRoadSurfaceAt(int x, int z) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return false;
        Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        return ROAD_SURFACE_BLOCKS.contains(b);
    }

    private boolean nearRoadSurface(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (isRoadSurfaceAt(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    private boolean isWaterAt(int x, int z) {
        int y = terrainY(x, z);
        if (y == Integer.MIN_VALUE) return false;
        Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        return b == Blocks.WATER;
    }

    // ---- Низкоуровневые постановщики ----
    private void placeSlabBottom(int x, int y, int z, Block slab) {
        BlockState st = slab.defaultBlockState();
        try {
            if (st.getBlock() instanceof SlabBlock) {
                st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
            }
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x,y,z), st, 3);
    }

    private void clearColumnAir(int x, int yStart, int z, int height) {
        for (int h=0; h<height; h++) {
            level.setBlock(new BlockPos(x, yStart + h, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }

    private void setBlockState(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x,y,z), state, 3);
    }

    // ---- Рельеф ----
    private int terrainY(int x, int z) {
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

    // ---- Гео-утилиты ----
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }
    private static int clamp(int v, int a, int b){ return Math.max(a, Math.min(b, v)); }
    private static int floorMod(int x, int y){ int r = x % y; return (r<0)? r+y : r; }

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

    // расстояние точка-сегмент (2D)
    private static double distPointToSegment(double x, double z, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double wx = x - ax,  wz = z - az;
        double vv = vx*vx + vz*vz;
        if (vv <= 1e-9) return Math.hypot(x - ax, z - az);
        double t = (vx*wx + vz*wz) / vv;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double px = ax + t*vx, pz = az + t*vz;
        return Math.hypot(x - px, z - pz);
    }
    // проекция точки на сегмент, возвращает {cx, cz, d2}
    private static double[] projectPointOnSegment(double x, double z, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double wx = x - ax,  wz = z - az;
        double vv = vx*vx + vz*vz;
        double cx = ax, cz = az;
        if (vv > 1e-9) {
            double t = (vx*wx + vz*wz) / vv;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            cx = ax + t*vx; cz = az + t*vz;
        }
        double dx = x - cx, dz = z - cz;
        return new double[]{cx, cz, dx*dx + dz*dz};
    }

    // детерминированный [0..1)
    private static double hash01(long x, long z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return ( (h >>> 11) & ((1L<<53)-1) ) / (double)(1L<<53);
    }
}