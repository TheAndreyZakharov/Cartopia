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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class SubstationGenerator {

    // ---- Материалы и размеры ----
    private static final Block SLAB_BLOCK          = Blocks.SMOOTH_QUARTZ_SLAB;      // периметр: гладкий кварц
    private static final Block FENCE_BLOCK         = Blocks.NETHER_BRICK_FENCE;      // стенки
    private static final Block LANTERN_BLOCK       = Blocks.LANTERN;                 // на заборах
    private static final Block IRON_BLOCK          = Blocks.IRON_BLOCK;              // сердцевина 2×5×2
    private static final Block BREWING_STAND_BLOCK = Blocks.BREWING_STAND;           // 2×2 центр
    private static final Block GRINDSTONE_BLOCK    = Blocks.GRINDSTONE;              // остальное
    private static final Block TRIPWIRE_HOOK_BLOCK = Blocks.TRIPWIRE_HOOK;           // боковые линии

    private static final int MODULE_W = 4;     // ширина X
    private static final int MODULE_L = 9;     // длина Z
    private static final int PERIM    = 1;     // толщина рамки
    private static final int GAP      = 4;     // зазор между модулями

    private static final int FENCE_HEIGHT = 3;
    private static final int IRON_HEIGHT  = 2;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public SubstationGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ---- Геометрия ----
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
    }

    // ---- Запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "SubstationAreaGenerator: coords == null — пропускаю."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "SubstationAreaGenerator: нет center/bbox — пропускаю."); return;
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
                                centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "SubstationAreaGenerator: нет coords.features — пропускаю."); return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "SubstationAreaGenerator: features.elements пуст — пропускаю."); return;
                }
                for (JsonElement el : elements) {
                    collectArea(el.getAsJsonObject(), areas,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "SubstationAreaGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (areas.isEmpty()) {
            broadcast(level, "SubstationAreaGenerator: подходящих зон подстанций не найдено — готово."); return;
        }

        int idx = 0;
        for (Area area : areas) {
            idx++;
            tileArea(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, areas.size());
        }
    }

    // ---- Сбор зон ----
    private void collectArea(JsonObject e, List<Area> out,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isSubstationArea(tags)) return;

        String type = optString(e, "type");
        if (type == null) return;

        if ("way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
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
            String rtype = optString(tags, "type"); // multipolygon
            JsonArray members = (e.has("members") && e.get("members").isJsonArray())
                    ? e.getAsJsonArray("members") : null;

            if (rtype == null || !"multipolygon".equalsIgnoreCase(rtype) || members == null || members.size()==0) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
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

    private static boolean isSubstationArea(JsonObject t) {
        String power = low(optString(t,"power"));
        String sub   = low(optString(t,"substation"));

        if ("plant".equals(power) || "line".equals(power) || "tower".equals(power)) return false;
        if (t.has("telecom")) return false;

        if ("substation".equals(power)) return true;
        if (sub != null && !sub.isEmpty()) return true;

        if ("transformer".equals(power)) return true;

        return false;
    }

    // ---- Укладка ----
    private void tileArea(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, int idx, int total) {
        final int stepX = MODULE_W + GAP;
        final int stepZ = MODULE_L + GAP;

        int bestOx = 0, bestOz = 0, bestCount = -1;

        for (int ox = 0; ox < stepX; ox++) {
            for (int oz = 0; oz < stepZ; oz++) {
                int count = simulateCount(area, wMinX, wMaxX, wMinZ, wMaxZ, stepX, stepZ, ox, oz);
                if (count > bestCount) { bestCount = count; bestOx = ox; bestOz = oz; }
            }
        }

        if (bestCount <= 0) {
            broadcast(level, String.format(Locale.ROOT, "Подстанции %d/%d: зона слишком узкая для модулей.", idx, total));
            return;
        }

        long done = 0, totalToPlace = bestCount;
        for (int x0 = clamp(area.minX, wMinX, wMaxX) + bestOx; x0 + MODULE_W - 1 <= Math.min(area.maxX, wMaxX); x0 += stepX) {
            for (int z0 = clamp(area.minZ, wMinZ, wMaxZ) + bestOz; z0 + MODULE_L - 1 <= Math.min(area.maxZ, wMaxZ); z0 += stepZ) {
                if (!rectangleFits(area, x0, z0, MODULE_W, MODULE_L)) continue;
                buildModule(x0, z0, area);
                done++;
                if (done % Math.max(1, totalToPlace/5) == 0) {
                    int pct = (int)Math.round(100.0 * done / Math.max(1, totalToPlace));
                    broadcast(level, String.format(Locale.ROOT, "Подстанции %d/%d: ~%d%%", idx, total, pct));
                }
            }
        }
        broadcast(level, String.format(Locale.ROOT, "Подстанции %d/%d: 100%% (поставлено %d модулей)", idx, total, done));
    }

    private int simulateCount(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ,
                              int stepX, int stepZ, int ox, int oz) {
        int cnt = 0;
        for (int x0 = clamp(area.minX, wMinX, wMaxX) + ox; x0 + MODULE_W - 1 <= Math.min(area.maxX, wMaxX); x0 += stepX) {
            for (int z0 = clamp(area.minZ, wMinZ, wMaxZ) + oz; z0 + MODULE_L - 1 <= Math.min(area.maxZ, wMaxZ); z0 += stepZ) {
                if (rectangleFits(area, x0, z0, MODULE_W, MODULE_L)) cnt++;
            }
        }
        return cnt;
    }

    private boolean rectangleFits(Area area, int x0, int z0, int w, int l) {
        for (int dx=0; dx<w; dx++) for (int dz=0; dz<l; dz++) {
            int x = x0 + dx, z = z0 + dz;
            if (!area.contains(x, z)) return false;
        }
        return true;
    }

    // ---- Построение модуля ----
    private void buildModule(int x0, int z0, Area area) {
        // 1) Периметр SLAB: нижняя половинка, прямо на грунт
        for (int dx=0; dx<MODULE_W; dx++) {
            for (int dz=0; dz<MODULE_L; dz++) {
                boolean border = (dx==0 || dz==0 || dx==MODULE_W-1 || dz==MODULE_L-1);
                if (!border) continue;
                int x = x0 + dx, z = z0 + dz;
                if (!area.contains(x,z)) continue;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                placeSlabBottom(x, yBase + 1, z, SLAB_BLOCK);
            }
        }

        // 2) Внутренние заборы: на z = 1 и z = MODULE_L-2; по X = 1..2; высота 3
        int zNorth = z0 + PERIM;                // 1
        int zSouth = z0 + MODULE_L - 1 - PERIM; // 7
        for (int x = x0 + PERIM; x <= x0 + MODULE_W - 1 - PERIM; x++) {
            placeFenceColumnIfInside(area, x, zNorth, FENCE_HEIGHT, true);
            placeFenceColumnIfInside(area, x, zSouth, FENCE_HEIGHT, true);
        }

        // 3) Сердцевина: железо 2×5×2 (x=1..2, z=2..6)
        for (int x = x0 + PERIM; x <= x0 + MODULE_W - 1 - PERIM; x++) {
            for (int z = z0 + PERIM + 1; z <= z0 + MODULE_L - 1 - PERIM - 1; z++) {
                if (!area.contains(x,z)) continue;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                for (int h=1; h<=IRON_HEIGHT; h++) placeBlockSafe(x, yBase + h, z, IRON_BLOCK);
            }
        }

        // 4) Верх: Brewing Stand 2×2 по центру полосы (z=3..4), остальное Grindstone
        for (int x = x0 + PERIM; x <= x0 + MODULE_W - 1 - PERIM; x++) {
            for (int z = z0 + PERIM + 1; z <= z0 + MODULE_L - 1 - PERIM - 1; z++) {
                if (!area.contains(x,z)) continue;
                int yTop = terrainYFromCoordsOrWorld(x, z, null);
                if (yTop == Integer.MIN_VALUE) continue;
                yTop = yTop + IRON_HEIGHT + 1;
                boolean brewing =
                        (z == z0 + PERIM + 2 || z == z0 + PERIM + 3) &&
                        (x == x0 + PERIM || x == x0 + PERIM + 1);
                placeBlockSafe(x, yTop, z, brewing ? BREWING_STAND_BLOCK : GRINDSTONE_BLOCK);
            }
        }

        // 5) Tripwire Hooks по бортам железной полосы (по 5 шт.)
        //    Западный край полосы: xWestIron = x0 + PERIM (т.е. 1); ставим крючок на xWestHook = xWestIron - 1, FACING=WEST.
        //    Восточный край полосы: xEastIron = x0 + MODULE_W - 1 - PERIM (т.е. 2); крючок xEastHook = xEastIron + 1, FACING=EAST.
        int xWestIron = x0 + PERIM;
        int xEastIron = x0 + MODULE_W - 1 - PERIM;

        for (int z = z0 + PERIM + 1; z <= z0 + MODULE_L - 1 - PERIM - 1; z++) {
            // левый ряд
            int xWestHook = xWestIron - 1;
            if (area.contains(xWestHook, z) && area.contains(xWestIron, z)) {
                int yWest = terrainYFromCoordsOrWorld(xWestIron, z, null);
                if (yWest != Integer.MIN_VALUE) placeTripwireHook(xWestHook, yWest + 2, z, Direction.WEST);
            }
            // правый ряд
            int xEastHook = xEastIron + 1;
            if (area.contains(xEastHook, z) && area.contains(xEastIron, z)) {
                int yEast = terrainYFromCoordsOrWorld(xEastIron, z, null);
                if (yEast != Integer.MIN_VALUE) placeTripwireHook(xEastHook, yEast + 2, z, Direction.EAST);
            }
        }
    }

    // ---- Низкоуровневые постановщики ----
    private void placeSlabBottom(int x, int y, int z, Block slab) {
        BlockState st = slab.defaultBlockState();
        try {
            if (st.getBlock() instanceof SlabBlock) {
                st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM); // нижняя половинка — «на землю»
            }
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x,y,z), st, 3);
    }

    private void placeFenceColumnIfInside(Area area, int x, int z, int height, boolean withLantern) {
        if (!area.contains(x,z)) return;
        int yBase = terrainYFromCoordsOrWorld(x, z, null);
        if (yBase == Integer.MIN_VALUE) return;
        for (int h=1; h<=height; h++) placeBlockSafe(x, yBase + h, z, FENCE_BLOCK);
        if (withLantern) placeBlockSafe(x, yBase + height + 1, z, LANTERN_BLOCK);
    }

    private void placeTripwireHook(int x, int y, int z, Direction facing) {
        BlockState st = TRIPWIRE_HOOK_BLOCK.defaultBlockState();
        try {
            st = st.setValue(TripWireHookBlock.FACING, facing);
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x,y,z), st, 3);
    }

    private void placeBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }

    // ---- Рельеф ----
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

    // ---- Утилиты ----
    private static int clamp(int v, int a, int b){ return Math.max(a, Math.min(b, v)); }
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