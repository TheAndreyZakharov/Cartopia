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
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class MiningOresScatterGenerator {

    // --- параметры ---
    private static final double CELL_PLACE_PROB = 0.05; // 5% шанс поставить руду на клетке

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public MiningOresScatterGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // --- вещалка ---
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

    // --- геометрия полигона ---
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
        int clipMinX(int wMinX){ return Math.max(minX, wMinX); }
        int clipMaxX(int wMaxX){ return Math.min(maxX, wMaxX); }
        int clipMinZ(int wMinZ){ return Math.max(minZ, wMinZ); }
        int clipMaxZ(int wMaxZ){ return Math.min(maxZ, wMaxZ); }
    }

    // --- шансы руд (в процентах, суммарно 100) ---
    private static final class OreEntry {
        final Block block; final int weight;
        OreEntry(Block block, int weight){ this.block = block; this.weight = weight; }
    }
    // Все ванильные руды (Overworld + Deepslate + Nether),
    // веса — примерные, можно крутить под себя.
    private static final OreEntry[] ORES = new OreEntry[] {
        // Overworld (обычные)
        new OreEntry(Blocks.COAL_ORE,            20),
        new OreEntry(Blocks.IRON_ORE,            12),
        new OreEntry(Blocks.COPPER_ORE,           8),
        new OreEntry(Blocks.GOLD_ORE,             4),
        new OreEntry(Blocks.REDSTONE_ORE,         3),
        new OreEntry(Blocks.LAPIS_ORE,            2),
        new OreEntry(Blocks.DIAMOND_ORE,          1),
        new OreEntry(Blocks.EMERALD_ORE,          1),

        // Overworld (deepslate-варианты)
        new OreEntry(Blocks.DEEPSLATE_COAL_ORE,   20),
        new OreEntry(Blocks.DEEPSLATE_IRON_ORE,   13),
        new OreEntry(Blocks.DEEPSLATE_COPPER_ORE, 7),
        new OreEntry(Blocks.DEEPSLATE_GOLD_ORE,   4),
        new OreEntry(Blocks.DEEPSLATE_REDSTONE_ORE, 3),
        new OreEntry(Blocks.DEEPSLATE_LAPIS_ORE,  2),
        new OreEntry(Blocks.DEEPSLATE_DIAMOND_ORE,1),
        new OreEntry(Blocks.DEEPSLATE_EMERALD_ORE,1),

        // Nether
        new OreEntry(Blocks.NETHER_QUARTZ_ORE,    6),
        new OreEntry(Blocks.NETHER_GOLD_ORE,      3),
        new OreEntry(Blocks.ANCIENT_DEBRIS,       1)
    };
    private static final int TOTAL_WEIGHT = Arrays.stream(ORES).mapToInt(o -> o.weight).sum();

    private Block pickWeightedOre(Random rnd) {
        int r = rnd.nextInt(TOTAL_WEIGHT);
        int acc = 0;
        for (OreEntry e : ORES) {
            acc += e.weight;
            if (r < acc) return e.block;
        }
        return Blocks.COAL_ORE; // fallback
    }

    // --- запуск ---
    public void generate() {
        if (coords == null) { broadcast(level, "MiningOresScatterGenerator: coords == null — пропуск."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "MiningOresScatterGenerator: нет center/bbox — пропуск."); return; }

        final double centerLat  = center.get("lat").getAsDouble();
        final double centerLng  = center.get("lng").getAsDouble();
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

        // ---- чтение OSM (stream / batch) ----
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
                if (!coords.has("features")) { broadcast(level, "MiningOresScatterGenerator: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "MiningOresScatterGenerator: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    collectArea(el.getAsJsonObject(), areas,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "MiningOresScatterGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (areas.isEmpty()) {
            broadcast(level, "MiningOresScatterGenerator: подходящих зон добычи не найдено — готово."); return;
        }

        int idx = 0;
        long totalPlaced = 0;
        for (Area area : areas) {
            idx++;
            long placed = scatterOresInArea(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, areas.size());
            totalPlaced += placed;
        }
        broadcast(level, "MiningOresScatterGenerator: суммарно поставлено руд: " + totalPlaced);
    }

    // ---- Сбор зон (карьеры/шахты) ----
    private void collectArea(JsonObject e, List<Area> out,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isExtractionArea(tags)) return;

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

    private static boolean isExtractionArea(JsonObject t) {
        String landuse    = low(optString(t,"landuse"));
        String industrial = low(optString(t,"industrial"));
        // Основные случаи:
        if ("quarry".equals(landuse)) return true;               // карьеры
        if ("surface_mining".equals(landuse)) return true;       // встречается иногда
        // Иногда зонируют через landuse=industrial + industrial=mine/quarry
        if ("industrial".equals(landuse) && industrial != null) {
            if ("mine".equals(industrial) || "mining".equals(industrial) || "quarry".equals(industrial)) return true;
        }
        return false;
    }

    // ---- Раскидываем руды внутри одной зоны ----
    private long scatterOresInArea(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, int idx, int total) {
        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        // детерминированный рандом по зоне (чтобы не мигало при перегенерации)
        long seed = (((long)minX) << 48) ^ (((long)maxX) << 32) ^ (((long)minZ) << 16) ^ (long)maxZ ^ 0x9E3779B97F4A7C15L;
        Random rnd = new Random(seed);

        long placed = 0, seen = 0, toProgress = Math.max(1, ((long)(maxX-minX+1)) * (maxZ-minZ+1) / 5);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.contains(x, z)) continue;
                seen++;
                if (rnd.nextDouble() < CELL_PLACE_PROB) {
                    Block ore = pickWeightedOre(rnd);
                    if (placeOreOnSurface(x, z, ore)) placed++;
                }
                if (seen % toProgress == 0) {
                    int pct = (int)Math.round(100.0 * seen / Math.max(1.0, (maxX-minX+1.0)*(maxZ-minZ+1.0)));
                    broadcast(level, String.format(Locale.ROOT, "Карьер/шахта %d/%d: ~%d%%", idx, total, pct));
                }
            }
        }
        broadcast(level, String.format(Locale.ROOT, "Карьер/шахта %d/%d: поставлено %d руд.", idx, total, placed));
        return placed;
    }

    // --- постановка руды «на рельеф», без затирания грунта ---
    private boolean placeOreOnSurface(int x, int z, Block oreBlock) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return false;

        BlockPos target = new BlockPos(x, yBase + 1, z);
        // ставим только если в точке воздух — не трогаем существующие объекты
        if (!level.isEmptyBlock(target)) return false;

        level.setBlock(target, oreBlock.defaultBlockState(), 3);
        return true;
    }

    // --- рельеф ---
    private int terrainYFromCoordsOrWorld(int x, int z) {
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

        // fallback по миру: верх блока рельефа
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // --- утилиты ---
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