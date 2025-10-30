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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class BeachResortGenerator {

    // ---- Материалы ----
    private static final Block SLAB_QZ   = Blocks.SMOOTH_QUARTZ_SLAB;
    private static final Block STAIR_QZ  = Blocks.SMOOTH_QUARTZ_STAIRS;
    private static final Block FENCE_BIR = Blocks.BIRCH_FENCE;

    // ---- Параметры модуля ----
    private static final int MODULE_W = 4;   // ширина X
    private static final int MODULE_L = 4;   // длина Z
    private static final int GAP      = 3;   // зазор между модулями (мин. 3)

    // геометрия модуля (локальные координаты в пределах 0..W-1, 0..L-1)
    // Ступень в (2,1), перед ней полублок в (2,2), колонна слева в (1,1),
    // крыша 3×3 над колонной (центр в (1,1) -> покрытие [0..2]×[0..2]).
    private static final int STEP_U = 2, STEP_V = 1;
    private static final int FRONTSLAB_U = 2, FRONTSLAB_V = 2;
    private static final int PILLAR_U = 1, PILLAR_V = 1;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public BeachResortGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ---- Геометрия многоугольников (как в примере) ----
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
        if (coords == null) { broadcast(level, "BeachResortGenerator: coords == null — пропускаю."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "BeachResortGenerator: нет center/bbox — пропускаю."); return;
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
                    broadcast(level, "BeachResortGenerator: нет coords.features — пропускаю."); return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "BeachResortGenerator: features.elements пуст — пропускаю."); return;
                }
                for (JsonElement el : elements) {
                    collectArea(el.getAsJsonObject(), areas,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "BeachResortGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (areas.isEmpty()) {
            broadcast(level, "BeachResortGenerator: подходящих пляжных зон не найдено — готово."); return;
        }

        int idx = 0;
        for (Area area : areas) {
            idx++;
            tileArea(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, areas.size());
        }
    }

    // ---- Сбор зон пляжных КУРОРТОВ ----
    private void collectArea(JsonObject e, List<Area> out,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        // ВАЖНО: только leisure=beach_resort
        if (!isBeachResort(tags)) return;

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
            // теги relation тоже проверены выше -> только те multipolygon, где leisure=beach_resort на самой relation
            String rtype = optString(tags, "type"); // multipolygon
            JsonArray members = (e.has("members") && e.get("members").isJsonArray())
                    ? e.getAsJsonArray("members") : null;

            if (!"multipolygon".equalsIgnoreCase(rtype) || members == null || members.size()==0) {
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

    // СТРОГО: только leisure=beach_resort
    private static boolean isBeachResort(JsonObject t) {
        String leisure = low(optString(t,"leisure"));
        return "beach_resort".equals(leisure);
    }

    // ---- Раскладка модулей по зоне ----
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
            broadcast(level, String.format(Locale.ROOT, "Пляжи %d/%d: зона слишком узкая для модулей.", idx, total));
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
                    broadcast(level, String.format(Locale.ROOT, "Пляжи %d/%d: ~%d%%", idx, total, pct));
                }
            }
        }
        broadcast(level, String.format(Locale.ROOT, "Пляжи %d/%d: 100%% (поставлено %d лежаков)", idx, total, done));
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

    // ---- Построение модуля (одного лежака) ----
    private void buildModule(int x0, int z0, Area area) {
        // Координаты ключевых точек
        int xStep = x0 + STEP_U, zStep = z0 + STEP_V;
        int xSlab = x0 + FRONTSLAB_U, zSlab = z0 + FRONTSLAB_V;
        int xP    = x0 + PILLAR_U,    zP    = z0 + PILLAR_V;

        // 1) Ступень (кварц), «вперёд» к +Z (SOUTH), нижняя половина
        if (area.contains(xStep, zStep)) {
            int y = terrainY(xStep, zStep);
            if (y != Integer.MIN_VALUE) placeStairBottomFacing(xStep, y + 1, zStep, Direction.SOUTH);
        }

        // 2) Перед ступенькой — НИЖНИЙ полублок кварца
        if (area.contains(xSlab, zSlab)) {
            int y = terrainY(xSlab, zSlab);
            if (y != Integer.MIN_VALUE) placeSlabBottom(xSlab, y + 1, zSlab, SLAB_QZ);
        }

        // 3) Колонна из берёзового забора слева от ступеньки, высота 3
        if (area.contains(xP, zP)) {
            int yBase = terrainY(xP, zP);
            if (yBase != Integer.MIN_VALUE) {
                for (int h=1; h<=3; h++) placeBlockSafe(xP, yBase + h, zP, FENCE_BIR);
            }
        }

        // 4) Крыша 3×3 из НИЖНИХ полублоков кварца, центр над колонной.
        // Высота крыши — «на 3 выше уровня земли в каждой клетке» (повтор рельефа).
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                int xr = xP + du, zr = zP + dv;
                if (!area.contains(xr, zr)) continue;
                int y = terrainY(xr, zr);
                if (y == Integer.MIN_VALUE) continue;
                placeSlabBottom(xr, y + 1 + 3, zr, SLAB_QZ); // строго НИЖНИЙ тип
            }
        }
    }

    // ---- Низкоуровневые постановщики ----
    private void placeSlabBottom(int x, int y, int z, Block slab) {
        BlockState st = slab.defaultBlockState();
        try {
            if (st.getBlock() instanceof SlabBlock) {
                st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM); // нижняя половина
            }
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x,y,z), st, 3);
    }

    private void placeStairBottomFacing(int x, int y, int z, Direction facing) {
        BlockState st = STAIR_QZ.defaultBlockState();
        try {
            if (st.getBlock() instanceof StairBlock) {
                st = st.setValue(StairBlock.FACING, facing);
                st = st.setValue(StairBlock.HALF,   Half.BOTTOM); // нижняя половина
            }
        } catch (Throwable ignore) {}
        level.setBlock(new BlockPos(x,y,z), st, 3);
    }

    private void placeBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
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