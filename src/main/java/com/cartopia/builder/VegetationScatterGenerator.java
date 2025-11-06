package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;
import java.util.function.Predicate;


public class VegetationScatterGenerator {

    // ==============================
    // ---------- КОНСТАНТЫ --------
    // ==============================

    // --- Список цветов (строго по ТЗ) ---
    private static final Block[] FLOWERS = new Block[] {
            Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM, Blocks.AZURE_BLUET,
            Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP,
            Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY
    };

    // --- Базовые саженцы ---
    private static final Block OAK         = Blocks.OAK_SAPLING;
    private static final Block BIRCH       = Blocks.BIRCH_SAPLING;
    private static final Block DARK_OAK    = Blocks.DARK_OAK_SAPLING;
    private static final Block JUNGLE      = Blocks.JUNGLE_SAPLING;
    private static final Block CHERRY      = Blocks.CHERRY_SAPLING;
    private static final Block ACACIA      = Blocks.ACACIA_SAPLING;
    private static final Block SPRUCE      = Blocks.SPRUCE_SAPLING;
    private static final Block MANGROVE    = Blocks.MANGROVE_PROPAGULE;

    @SuppressWarnings("unused")
    // Лиственные (для broadleaved)
    private static final Block[] BROADLEAVED_POOL = new Block[] {
            OAK, BIRCH, DARK_OAK, JUNGLE, CHERRY, ACACIA
    };
    @SuppressWarnings("unused")
    // Хвойные (для needleleaved)
    private static final Block[] NEEDLELEAVED_POOL = new Block[] {
            SPRUCE
    };

    // --- Редкость вишни среди лиственных (кроме садов): по умолчанию ~1% ---
    private static final double CHERRY_RARE_PROB = 0.01;
    private static Block pickBroadleaved(Random rnd) {
        if (rnd.nextDouble() < CHERRY_RARE_PROB) return CHERRY;
        // равномерно среди остальных (без повторного учёта вишни)
        Block[] pool = new Block[] { OAK, BIRCH, DARK_OAK, JUNGLE, ACACIA };
        return pool[rnd.nextInt(pool.length)];
    }

    private static Block pickNeedle(Random rnd) { return SPRUCE; }

    // --- Базовые вероятности (можно крутить) ---
    private static final double BASE_FOREST_TREE_PROB   = 0.10; // леса (и болота) — шанс саженца на клетку
    private static final double BASE_URBAN_TREE_PROB    = 0.05; // городские (residential/urban)
    private static final double BASE_OTHER_TREE_PROB    = 0.10; // прочие «травяные» места вне спец-зон
    private static final double BASE_GRASS_FLORA_PROB   = 0.35; // шанс посадки травы/цветов на клетку, когда "часто"
    private static final double RARE_GRASS_FLORA_PROB   = 0.08; // когда "редко"/"иногда" и т.п.

    // --- Коэффициенты интенсивности по зонам (и для деревьев, и для травы/цветов) ---
    // Ключи — наши внутренние AreaType (строки для гибкости), значения — множители k in [0..+inf)
    private static final Map<String, Double> TREE_K = new HashMap<>();
    private static final Map<String, Double> FLORA_K = new HashMap<>();
    static {
        // Можно «крутить ручки» не перекомпилируя логику
        putK("FOREST_BROAD", 1.0, 1.0);
        putK("FOREST_NEEDLE", 1.0, 1.0);
        putK("FOREST_MIXED", 1.0, 1.0);
        putK("FOREST_LEAFLESS", 0.25, 0.15); // редкие кактусы/дедбуш
        putK("WETLAND", 0.5, 0.6);           // деревья доминируют; травы умеренно
        putK("MEADOW", 0.0, 1.0);            // без деревьев; травы «часто»
        putK("GRASSLAND", 0.0, 0.9);         // без деревьев; травы «часто»
        putK("VILLAGE_GREEN", 0.0, 0.25);    // деревья нельзя; цветы/ягоды иногда
        putK("GARDEN", 0.0, 1.25);           // засаживаем цветами плотно
        putK("PARK", 0.0, 0.20);             // редко цветы (если не лес+парк)
        putK("RECREATION_GROUND", 0.0, 0.20);
        putK("FARMLAND", 0.0, 0.0);          // деревья нельзя; сеем пшеницу отдельно
        putK("SHRUBBERY", 0.0, 0.8);
        putK("SCRUB", 0.0, 0.9);
        putK("HEATH", 0.0, 0.9);
        putK("VINEYARD", 0.0, 0.0);          // особая логика линий листвы
        putK("RESIDENTIAL", 1.0, 0.35);      // деревья с базой 5% и ТОЛЬКО на мхе
        putK("URBAN", 0.7, 0.25);            // ещё пореже можно настроить
        putK("OTHER_OUTSIDE", 1.0, 0.5);     // вне зон: деревья ~10%, травы умеренно
        // запретные зоны не добавляем (dog_park/golf_course/pitch)
    }
    private static void putK(String key, double kTree, double kFlora) {
        TREE_K.put(key, kTree); FLORA_K.put(key, kFlora);
    }
    private static double kTree(String key){ return TREE_K.getOrDefault(key, 1.0); }
    private static double kFlora(String key){ return FLORA_K.getOrDefault(key, 1.0); }

    // --- Типы зон (минимально необходимый набор) ---
    private enum ZoneType {
        // Полигональные зоны:
        VILLAGE_GREEN, GRASSLAND, MEADOW, GARDEN, PARK, RECREATION_GROUND,
        SHRUBBERY, SCRUB, HEATH, VINEYARD, FARMLAND,
        FOREST_BROAD, FOREST_NEEDLE, FOREST_MIXED, FOREST_LEAFLESS,
        TUNDRA, WETLAND,
        RESIDENTIAL, URBAN,
        FORBIDDEN,        // dog_park/golf_course/pitch
        // Линейные/точечные:
        TREE_ROW,         // way
        // «виртуальные»:
        OTHER_OUTSIDE
    }

    // ===============================
    // --------- ИНФРАСТРУКТУРА -----
    // ===============================
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public VegetationScatterGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

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

    // --- Геометрия полигонов/линий ---
    private static final class Ring {
        final int[] xs, zs; final int n;
        final int minX, maxX, minZ, maxZ;
        Ring(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE, mnz = Integer.MAX_VALUE, mxz = Integer.MIN_VALUE;
            for (int i=0;i<n;i++) {
                mnx=Math.min(mnx,xs[i]); mxx=Math.max(mxx,xs[i]);
                mnz=Math.min(mnz,zs[i]); mxz=Math.max(mxz,zs[i]);
            }
            minX=mnx; maxX=mxx; minZ=mnz; maxZ=mxz;
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
        final ZoneType type;
        final List<Ring> outers = new ArrayList<>();
        final List<Ring> inners = new ArrayList<>();
        int minX=Integer.MAX_VALUE, maxX=Integer.MIN_VALUE, minZ=Integer.MAX_VALUE, maxZ=Integer.MIN_VALUE;
        Area(ZoneType type){ this.type = type; }
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

    private static final class TreePoint {
        final int x, z;
        final Block sapling; // может быть null => выбрать по leaf_type/default
        final String leafType; // "broadleaved"/"needleleaved"/"mixed"/null
        TreePoint(int x, int z, Block sapling, String leafType){
            this.x=x; this.z=z; this.sapling=sapling; this.leafType=leafType;
        }
    }

    private static final class TreeRow {
        final List<int[]> polyline; // список x,z вдоль линии
        final String leafType;
        TreeRow(List<int[]> polyline, String leafType){
            this.polyline = polyline; this.leafType = leafType;
        }
    }

    // ===============================
    // -------- ВСПОМОГАТЕЛЬНОЕ -----
    // ===============================
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

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    @SuppressWarnings("unused")
    private boolean isEmptyAbove(LevelAccessor lvl, int x, int y, int z) {
        return lvl.isEmptyBlock(new BlockPos(x, y + 1, z));
    }

    @SuppressWarnings("unused")
    private boolean isDoubleEmptyAbove(LevelAccessor lvl, int x, int y, int z) {
        return lvl.isEmptyBlock(new BlockPos(x, y + 1, z)) && lvl.isEmptyBlock(new BlockPos(x, y + 2, z));
    }

    private static boolean isGrasslikeBlock(Block b) {
        return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.PODZOL
                || b == Blocks.ROOTED_DIRT || b == Blocks.MOSS_BLOCK || b == Blocks.MUD
                || b == Blocks.MUDDY_MANGROVE_ROOTS;
    }

    // ===============================
    // ------------- ЗАПУСК ----------
    // ===============================
    public void generate() {
        if (coords == null) { broadcast(level, "VegetationScatter: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "VegetationScatter: no center/bbox — skipping."); return; }

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

        // --- коллекции ---
        List<Area> areas = new ArrayList<>();
        List<Area> forbidAreas = new ArrayList<>(); // dog_park/golf_course/pitch
        List<TreePoint> treePoints = new ArrayList<>();
        List<TreeRow> treeRows = new ArrayList<>();

        broadcast(level, "VegetationScatter: reading zones (stream=" + (store!=null) + ")…");

        // ---- чтение OSM (stream / batch) ----
        try {
            if (store != null) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        classifyAndCollect(e, areas, forbidAreas, treePoints, treeRows,
                                centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "VegetationScatter: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "VegetationScatter: features.elements are empty — skipping."); return; }
                for (JsonElement el : elements) {
                    classifyAndCollect(el.getAsJsonObject(), areas, forbidAreas, treePoints, treeRows,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "VegetationScatter: error reading features: " + ex.getMessage());
        }

        // --- ПЕРВАЯ очередь — одиночные деревья natural=tree
        broadcast(level, "VegetationScatter: placing individual trees (natural=tree)...");
        long tpPlaced = 0;
        int tpIdx = 0;
        for (TreePoint tp : treePoints) {
            tpIdx++;
            if (tpIdx % 500 == 0) broadcast(level, "Single trees: " + tpIdx + "…");
            if (tp.x < worldMinX || tp.x > worldMaxX || tp.z < worldMinZ || tp.z > worldMaxZ) continue;
            // правило: под саженец — мох; можно заменять существующие блоки (исключение из «ставим только на свободные»)
            Block sap = resolveSapling(tp.sapling, tp.leafType, new Random(seedFor(tp.x, tp.z)));
            if (sap == MANGROVE) {
                // мангрув — только в болотах, одиночные по данным вне болот игнорируем
                continue;
            }
            if (placeTreeWithMoss(level, tp.x, tp.z, sap, true, false,
                                worldMinX, worldMaxX, worldMinZ, worldMaxZ)) tpPlaced++;
        }
        broadcast(level, "VegetationScatter: individual trees placed: " + tpPlaced);

        // --- ВТОРАЯ очередь — ряды деревьев natural=tree_row
        broadcast(level, "VegetationScatter: building tree rows (natural=tree_row)…");
        long trPlaced = 0;
        int trIdx = 0;
        for (TreeRow row : treeRows) {
            trIdx++;
            trPlaced += placeTreeRow(row, 5, worldMinX, worldMaxX, worldMinZ, worldMaxZ); // шаг 5 блоков
            if (trIdx % 50 == 0) broadcast(level, "Rows processed: " + trIdx + "…");
        }
        broadcast(level, "VegetationScatter: trees planted in rows: " + trPlaced);

        // --- Спец-зоны: виноградники, сады, farmland
        long vineyardBlocks = 0, orchardTrees = 0, farmlandCrops = 0;

        for (Area area : areas) {
            if (area.type == ZoneType.VINEYARD) {
                vineyardBlocks += buildVineyard(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ);
            }
            if (area.type == ZoneType.FARMLAND) {
                farmlandCrops += seedFarmland(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ);
            }
            if (area.type == ZoneType.FORBIDDEN) {
                // пропуск
            }
        }
        broadcast(level, "VegetationScatter: vineyards — leaf blocks: " + vineyardBlocks);
        broadcast(level, "VegetationScatter: wheat seeded on farmland — " + farmlandCrops);

        // --- Сады (orchard) — сетка CHERRY 10×5
        for (Area area : areas) {
            if (area.type == ZoneType.OTHER_OUTSIDE) continue;
            if (isOrchard(area)) {
                orchardTrees += plantOrchard(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ);
            }
        }
        broadcast(level, "VegetationScatter: fruit orchards (cherry) — planted: " + orchardTrees);

        // --- Леса/болота/прочие растительные зоны: деревья, затем подлесок/цветы
        broadcast(level, "VegetationScatter: planting forests/wetlands/meadows/fields...");
        long treesPlaced = 0, floraPlaced = 0;

        int idx = 0;
        for (Area area : areas) {
            idx++;
            if (area.type == ZoneType.FORBIDDEN || area.type == ZoneType.VINEYARD || area.type == ZoneType.FARMLAND) continue;
            long t = plantAreaTrees(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, forbidAreas);
            treesPlaced += t;
            long f = plantAreaFlora(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, forbidAreas);
            floraPlaced += f;
            if (idx % 10 == 0) broadcast(level, "Areas processed: " + idx + "/" + areas.size());
        }
        broadcast(level, "VegetationScatter: total trees planted: " + treesPlaced + ", grass/flowers/bushes: " + floraPlaced);

        // --- ВНЕ ЗОН (outside): деревья/трава с вероятностями (residential/urban/other)
        broadcast(level, "VegetationScatter: Outside zones:");
        long outsideTrees = 0, outsideFlora = 0;
        long totalCells = (long)(worldMaxX - worldMinX + 1) * (worldMaxZ - worldMinZ + 1);
        long stepReport = Math.max(1, totalCells / 10);

        Random rndOutside = new Random(0xBADC0FFEE0DDF00DL ^ worldMinX ^ (worldMaxZ<<16));

        for (int x = worldMinX; x <= worldMaxX; x++) {
            for (int z = worldMinZ; z <= worldMaxZ; z++) {
                long idxCell = ((long)(x - worldMinX)) * (worldMaxZ - worldMinZ + 1L) + (z - worldMinZ);
                if (idxCell % stepReport == 0) broadcast(level, String.format(Locale.ROOT, "Outside zones: ~%d%%", (int)(100.0*idxCell/Math.max(1,totalCells))));

                // пропускаем если в запретной зоне
                if (insideAny(x, z, forbidAreas)) continue;
                // пропускаем если попадает в любую из известных зон — это уже обработано
                boolean inKnown = false;
                for (Area a0 : areas) {
                    if (a0.contains(x, z)) { inKnown = true; break; }
                }
                if (inKnown) continue;

                int y = terrainYFromCoordsOrWorld(x, z);
                BlockPos ground = new BlockPos(x, y, z);
                Block groundBlock = level.getBlockState(ground).getBlock();

                // Разрешаем только «мягкие» поверхности
                if (!isGrasslikeBlock(groundBlock)) continue;

                // дерево?
                double tProb = BASE_OTHER_TREE_PROB * kTree("OTHER_OUTSIDE");
                if (rndOutside.nextDouble() < tProb) {
                    // Под дерево — мох
                    placeMoss(ground);
                    Block pick = (rndOutside.nextBoolean() ? pickBroadleaved(rndOutside) : pickNeedle(rndOutside));
                    if (placeTreeIfAir(x, z, pick)) outsideTrees++;
                    continue; // после дерева траву не ставим
                }

                // трава/цветы?
                double fProb = BASE_GRASS_FLORA_PROB * kFlora("OTHER_OUTSIDE");
                if (rndOutside.nextDouble() < fProb) {
                    outsideFlora += placeRandomFloraGeneric(x, z, rndOutside);
                }
            }
        }
        broadcast(level, "VegetationScatter: outside zones — trees: " + outsideTrees + ", grass/flowers: " + outsideFlora);

        broadcast(level, "VegetationScatter: done.");
    }

    // ===============================
    // --------- КЛАССИФИКАЦИЯ ------
    // ===============================
    private void classifyAndCollect(JsonObject e,
                                   List<Area> outAreas, List<Area> outForbid,
                                   List<TreePoint> treePoints, List<TreeRow> treeRows,
                                   double centerLat, double centerLng,
                                   double east, double west, double north, double south,
                                   int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String type = optString(e, "type");
        String natural = low(optString(tags, "natural"));
        String landuse = low(optString(tags, "landuse"));
        String leisure = low(optString(tags, "leisure"));
        String wetland = low(optString(tags, "wetland"));
        String leafType = low(optString(tags, "leaf_type"));
        String wood     = low(optString(tags, "wood"));
        String genus   = low(optString(tags, "genus"));
        String species = low(optString(tags, "species"));

        // --- Точки дерева ---
        if ("node".equals(type) && "tree".equals(natural)) {
            double lat = e.has("lat") ? e.get("lat").getAsDouble() : Double.NaN;
            double lng = e.has("lon") ? e.get("lon").getAsDouble() : Double.NaN;
            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                int[] xz = latlngToBlock(lat, lng, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                Block sap = resolveSaplingByGenusSpecies(genus, species, leafType);
                treePoints.add(new TreePoint(xz[0], xz[1], sap, leafType));
            }
            return;
        }

        // --- Ряды деревьев ---
        if ("way".equals(type) && "tree_row".equals(natural)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 2) {
                List<int[]> pl = new ArrayList<>();
                for (JsonElement pEl : g) {
                    JsonObject p = pEl.getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    pl.add(new int[]{xz[0], xz[1]});
                }
                treeRows.add(new TreeRow(pl, leafType));
            }
            return;
        }

        // --- Полигональные зоны ---
        ZoneType z = null;

        // явные запреты
        if ("dog_park".equals(leisure) || "golf_course".equals(leisure) || "pitch".equals(leisure)) {
            z = ZoneType.FORBIDDEN;
        }

        if (z == null) {
            // зелёные/травяные
            if ("grass".equals(landuse)) { z = ZoneType.GRASSLAND; } // открытый газон без деревьев
            else if ("village_green".equals(landuse)) z = ZoneType.VILLAGE_GREEN;
            else if ("grassland".equals(natural)) z = ZoneType.GRASSLAND;
            else if ("meadow".equals(landuse) || "meadow".equals(natural)) z = ZoneType.MEADOW;

            // сады/парки
            else if ("garden".equals(leisure)) z = ZoneType.GARDEN;
            else if ("park".equals(leisure)) z = ZoneType.PARK;
            else if ("recreation_ground".equals(landuse)) z = ZoneType.RECREATION_GROUND;

            // кустарники
            else if ("shrubbery".equals(natural)) z = ZoneType.SHRUBBERY;
            else if ("scrub".equals(natural))     z = ZoneType.SCRUB;
            else if ("heath".equals(natural))     z = ZoneType.HEATH;

            // сельское хозяйство
            else if ("vineyard".equals(landuse))  z = ZoneType.VINEYARD;
            else if ("farmland".equals(landuse))  z = ZoneType.FARMLAND;

            // леса
            else if ("wood".equals(natural) || "forest".equals(landuse)) {
                // Нормализуем тип листвы: сначала берем leaf_type, если его нет — смотрим wood=*
                String lt = leafType;
                if (lt == null) {
                    if ("coniferous".equals(wood) || "evergreen".equals(wood)) lt = "needleleaved";
                    else if ("deciduous".equals(wood) || "broadleaved".equals(wood)) lt = "broadleaved";
                    else if ("mixed".equals(wood)) lt = "mixed";
                }
                // Поддержим синонимы
                if ("broadleaved".equals(lt) || "deciduous".equals(lt)) {
                    z = ZoneType.FOREST_BROAD;
                } else if ("needleleaved".equals(lt) || "needleleaf".equals(lt) || "coniferous".equals(lt)) {
                    z = ZoneType.FOREST_NEEDLE;
                } else if ("mixed".equals(lt)) {
                    z = ZoneType.FOREST_MIXED;
                } else if ("leafless".equals(lt)) {
                    z = ZoneType.FOREST_LEAFLESS;
                } else {
                    z = ZoneType.FOREST_BROAD; // дефолт — лиственный
                }
            }

            // безлистная/тундра/болота
            if (z == null && "tundra".equals(natural)) z = ZoneType.TUNDRA;

            if (z == null && ("wetland".equals(natural) || wetland != null || "swamp".equals(natural))) {
                z = ZoneType.WETLAND;
            }

            // городские
            if (z == null && "residential".equals(landuse)) z = ZoneType.RESIDENTIAL;
            if (z == null && "urban".equals(landuse))       z = ZoneType.URBAN;
        }

        // «парк+лес» — разрешаем как лес (по ТЗ)
        if (z == ZoneType.PARK && ("wood".equals(natural) || "forest".equals(landuse))) {
            if ("broadleaved".equals(leafType)) z = ZoneType.FOREST_BROAD;
            else if ("needleleaved".equals(leafType)) z = ZoneType.FOREST_NEEDLE;
            else if ("leafless".equals(leafType)) z = ZoneType.FOREST_LEAFLESS;
            else z = ZoneType.FOREST_BROAD;
        }

        if (z == null) return; // ничего добавлять не надо

        // собрать геометрию
        if ("way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 3) return;
            Area area = new Area(z);
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            area.addOuter(new Ring(xs, zs));
            if (z == ZoneType.FORBIDDEN) outForbid.add(area); else outAreas.add(area);
            return;
        }

        if ("relation".equals(type)) {
            String rtype = low(optString(tags, "type"));
            JsonArray members = (e.has("members") && e.get("members").isJsonArray())
                    ? e.getAsJsonArray("members") : null;
            if (rtype == null || !"multipolygon".equalsIgnoreCase(rtype) || members == null || members.size()==0) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 3) return;
                Area area = new Area(z);
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                area.addOuter(new Ring(xs, zs));
                if (z == ZoneType.FORBIDDEN) outForbid.add(area); else outAreas.add(area);
                return;
            }

            Area area = new Area(z);
            for (JsonElement mEl : members) {
                JsonObject m = mEl.getAsJsonObject();
                String role = low(optString(m, "role"));
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
                if ("inner".equals(role)) area.addInner(r); else area.addOuter(r);
            }
            // Fallback: у членов нет geometry → попробуем взять geometry самого relation
            if (area.outers.isEmpty()) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() >= 3) {
                    int[] xs = new int[g.size()], zs = new int[g.size()];
                    for (int i=0;i<g.size();i++){
                        JsonObject p=g.get(i).getAsJsonObject();
                        int[] xz = latlngToBlock(
                            p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        xs[i]=xz[0]; zs[i]=xz[1];
                    }
                    area.addOuter(new Ring(xs, zs));
                }
            }
            if (!area.outers.isEmpty()) {

                if (z == ZoneType.FORBIDDEN) outForbid.add(area); else outAreas.add(area);
            }
        }
    }

    private boolean isOrchard(Area area) {
        return false; 
    }

    // ===============================
    // ---------- ПОСАДКИ ------------
    // ===============================

    // --- Мох под точкой ---
    private void placeMoss(BlockPos ground) {
        level.setBlock(ground, Blocks.MOSS_BLOCK.defaultBlockState(), 3);
    }
    private void placeMossAt(int x, int z) {
        int y = terrainYFromCoordsOrWorld(x, z);
        level.setBlock(new BlockPos(x, y, z), Blocks.MOSS_BLOCK.defaultBlockState(), 3);
    }

    // --- Дерево с мхом (для одиночных/рядов и болот) ---
    private boolean placeTreeWithMoss(LevelAccessor lvl, int x, int z, Block sapling,
                                    boolean forceReplaceTop, boolean swampPlatform,
                                    int wMinX, int wMaxX, int wMinZ, int wMaxZ) {

        // за пределы — ни шагу
        if (x < wMinX || x > wMaxX || z < wMinZ || z > wMaxZ) return false;

        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos ground = new BlockPos(x, y, z);
        BlockPos above  = new BlockPos(x, y+1, z);

        if (swampPlatform) {
            // ковёр 10×10, но НЕ вываливаемся за bbox
            for (int dx = -5; dx <= 4; dx++) {
                for (int dz = -5; dz <= 4; dz++) {
                    int xx = x + dx, zz = z + dz;
                    if (xx < wMinX || xx > wMaxX || zz < wMinZ || zz > wMaxZ) continue;
                    int yy = terrainYFromCoordsOrWorld(xx, zz);
                    BlockPos gg = new BlockPos(xx, yy, zz);
                    level.setBlock(gg, Blocks.MOSS_BLOCK.defaultBlockState(), 3);
                }
            }
        } else {
            // один блок мха под деревом
            level.setBlock(ground, Blocks.MOSS_BLOCK.defaultBlockState(), 3);
        }

        // если не хотим насильно затирать верхний блок — можно уйти
        if (!lvl.isEmptyBlock(above) && !forceReplaceTop) return false;

        // форсим посадку: "несмотря ни на что"
        lvl.setBlock(above, sapling.defaultBlockState(), 3);
        return true;
    }

    private boolean placeTreeIfAir(int x, int z, Block sapling) {
        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos above = new BlockPos(x, y+1, z);
        if (!level.isEmptyBlock(above)) return false;
        level.setBlock(above, sapling.defaultBlockState(), 3);
        return true;
    }

    private long placeTreeRow(TreeRow row, int step,
                            int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        long placed = 0;
        if (row.polyline.isEmpty()) return 0;
        Random rnd = new Random(0xCCAA33L ^ row.polyline.size());
        Block sapPickBroad = pickBroadleaved(rnd);
        Block sapPickNeedle = pickNeedle(rnd);

        for (int i = 0; i < row.polyline.size()-1; i++) {
            int[] a = row.polyline.get(i);
            int[] b = row.polyline.get(i+1);
            int ax=a[0], az=a[1], bx=b[0], bz=b[1];
            int dx = bx - ax, dz = bz - az;
            double dist = Math.max(1.0, Math.hypot(dx, dz));
            int steps = (int)Math.floor(dist / step);
            double sx = dx / dist * step;
            double sz = dz / dist * step;

            for (int k=0; k<=steps; k++) {
                int x = (int)Math.round(ax + sx*k);
                int z = (int)Math.round(az + sz*k);
                Block sap;
                if ("needleleaved".equals(row.leafType)) sap = sapPickNeedle;
                else if ("broadleaved".equals(row.leafType)) sap = sapPickBroad;
                else sap = pickBroadleaved(rnd); // дефолт дуб/лиственные
                if (sap == MANGROVE) sap = pickBroadleaved(rnd); // мангровые не для рядов
                // не ставим, если точка вне bbox
                if (x < wMinX || x > wMaxX || z < wMinZ || z > wMaxZ) continue;

                // РЯДЫ — как одиночные: мох + форс-замена верхнего блока
                if (placeTreeWithMoss(level, x, z, sap, true, false,
                                    wMinX, wMaxX, wMinZ, wMaxZ)) placed++;
            }
        }
        return placed;
    }

    @SuppressWarnings("unused")
    // --- Виноградник: параллельные линии 2 блока высотой, листья азалии, расстояние между линиями = 2 блока ---
    private long buildVineyard(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        long placed = 0;
        Random rnd = new Random(seedFor(minX, minZ) ^ 0x55AA);

        // Выберем ориентацию — по X или по Z (фиксируем детерминированно)
        boolean alongX = ((minX ^ minZ) & 1) == 0;

        if (alongX) {
            for (int z = minZ; z <= maxZ; z += 3) { // 1 блок линия + 2 блока промежуток
                for (int x = minX; x <= maxX; x++) {
                    if (!area.contains(x, z)) continue;
                    placed += placeLeafColumn(x, z);
                }
            }
        } else {
            for (int x = minX; x <= maxX; x += 3) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!area.contains(x, z)) continue;
                    placed += placeLeafColumn(x, z);
                }
            }
        }
        return placed;
    }
    private long placeLeafColumn(int x, int z) {
        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos p1 = new BlockPos(x, y+1, z);
        BlockPos p2 = new BlockPos(x, y+2, z);
        Block state1 = Blocks.AZALEA_LEAVES;
        // делаем листья "персистентными", чтобы не увядали (если есть свойство)
        try {
            level.setBlock(p1, state1.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
        } catch (Throwable t) {
            level.setBlock(p1, state1.defaultBlockState(), 3);
        }
        if (level.isEmptyBlock(p2)) {
            try {
                level.setBlock(p2, state1.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
            } catch (Throwable t) {
                level.setBlock(p2, state1.defaultBlockState(), 3);
            }
            return 2;
        }
        return 1;
    }

    // --- Посев пшеницы на FARMLAND ---
    private long seedFarmland(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        long planted = 0;
        Random rnd = new Random(seedFor(minX, minZ) ^ 0xFA11);

        long total = Math.max(1, (long)(maxX-minX+1)*(maxZ-minZ+1));
        long progStep = Math.max(1, total / 4);

        long seen = 0;
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                if (!area.contains(x, z)) continue;
                seen++;
                int y = terrainYFromCoordsOrWorld(x, z);
                BlockPos ground = new BlockPos(x, y, z);
                BlockPos above  = new BlockPos(x, y+1, z);
                if (level.getBlockState(ground).getBlock() == Blocks.FARMLAND && level.isEmptyBlock(above)) {
                    // WHEAT с произвольным возрастом
                    try {
                        IntegerProperty AGE = (IntegerProperty) Blocks.WHEAT.getStateDefinition().getProperty("age");
                        if (AGE != null) {
                            int age = rnd.nextInt(AGE.getPossibleValues().size());
                            level.setBlock(above, Blocks.WHEAT.defaultBlockState().setValue(AGE, age), 3);
                        } else {
                            level.setBlock(above, Blocks.WHEAT.defaultBlockState(), 3);
                        }
                    } catch (Throwable t) {
                        level.setBlock(above, Blocks.WHEAT.defaultBlockState(), 3);
                    }
                    planted++;
                }
                if (seen % progStep == 0) {
                    int pct = (int)Math.round(100.0 * seen / (double)total);
                    broadcast(level, String.format(Locale.ROOT, "Farmland fields: ~%d%%", pct));
                }
            }
        }
        return planted;
    }

    // --- Лес/болото: деревья ---
    private long plantAreaTrees(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, List<Area> forbid) {
        // В зонах, где деревья нельзя — выходим
        switch (area.type) {
            case VILLAGE_GREEN: case GRASSLAND: case MEADOW: case GARDEN: case PARK:
            case RECREATION_GROUND: case SHRUBBERY: case SCRUB: case HEATH:
            case VINEYARD: case FARMLAND: case TUNDRA:
                return 0;
            default: break;
        }

        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        long placed = 0;
        long total = Math.max(1, (long)(maxX-minX+1)*(maxZ-minZ+1));
        long step  = Math.max(1, total / 5);

        Random rnd = new Random(seedFor(minX, minZ) ^ 0xA11A);

        double baseProb = BASE_FOREST_TREE_PROB;
        String kKey;
        switch (area.type) {
            case FOREST_BROAD: kKey="FOREST_BROAD"; break;
            case FOREST_NEEDLE: kKey="FOREST_NEEDLE"; break;
            case FOREST_MIXED: kKey="FOREST_MIXED"; break;
            case FOREST_LEAFLESS: kKey="FOREST_LEAFLESS"; baseProb = 1.0/60.0; break; // редкая растительность
            case WETLAND: kKey="WETLAND"; break;
            case RESIDENTIAL: kKey="RESIDENTIAL"; baseProb = BASE_URBAN_TREE_PROB; break;
            case URBAN: kKey="URBAN"; baseProb = BASE_URBAN_TREE_PROB * 0.8; break;
            default: kKey="FOREST_BROAD"; break;
        }
        double prob = baseProb * kTree(kKey);

        long seen=0;
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                if (!area.contains(x, z)) continue;
                seen++;
                if (insideAny(x, z, forbid)) continue;

                int y = terrainYFromCoordsOrWorld(x, z);
                BlockPos ground = new BlockPos(x, y, z);
                Block groundBlock = level.getBlockState(ground).getBlock();

                // В RESIDENTIAL/URBAN — сажаем ТОЛЬКО на мох
                if ((area.type == ZoneType.RESIDENTIAL || area.type == ZoneType.URBAN) && groundBlock != Blocks.MOSS_BLOCK) {
                    continue;
                }

                if (rnd.nextDouble() < prob) {
                    // выбор саженца по типу леса
                    Block sap;
                    boolean swampPlat = false;

                    if (area.type == ZoneType.WETLAND) {
                        // Доминирует мангровый, остальное — по типу «леса» (грубо: 70% мангровый)
                        if (rnd.nextDouble() < 0.70) {
                            sap = MANGROVE;
                            swampPlat = true;
                        } else {
                            sap = pickBroadleaved(rnd); // по умолчанию — лиственные (если нужен «смешанный» — можно чередовать)
                        }
                    } else if (area.type == ZoneType.FOREST_NEEDLE) {
                        sap = pickNeedle(rnd);
                    } else if (area.type == ZoneType.FOREST_MIXED) {
                        sap = (rnd.nextBoolean() ? pickBroadleaved(rnd) : pickNeedle(rnd));
                    } else if (area.type == ZoneType.RESIDENTIAL || area.type == ZoneType.URBAN) {
                        // В городских/жилых — смешанный тип, но сажаем только если низ = мох (проверка выше уже есть)
                        sap = (rnd.nextBoolean() ? pickBroadleaved(rnd) : pickNeedle(rnd));
                    } else if (area.type == ZoneType.FOREST_LEAFLESS) {
                        // leafless — кактусы/дедбуш: деревья не сажаем здесь (дальше flora pass)
                        continue;
                    } else sap = pickBroadleaved(rnd);

                    // Манговые ТОЛЬКО в болотах
                    if (sap == MANGROVE && area.type != ZoneType.WETLAND) {
                        sap = pickBroadleaved(rnd);
                    }

                    // Под саженец — мох. Для болот — площадка 10×10 и в центр.
                    if (placeTreeWithMoss(level, x, z, sap, false, swampPlat,
                                        wMinX, wMaxX, wMinZ, wMaxZ)) placed++;
                }

                if (seen % step == 0) {
                    int pct = (int)Math.round(100.0 * seen / (double)total);
                    broadcast(level, String.format(Locale.ROOT, "%s: trees ~%d%%", area.type.name(), pct));
                }
            }
        }
        return placed;
    }

    // --- Зоны: трава/цветы/кусты ---
    private long plantAreaFlora(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, List<Area> forbid) {
        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        Random rnd = new Random(seedFor(minX, minZ) ^ 0xFEED);
        long placed = 0;

        // Профили по ТЗ
        Predicate<Block> allowGround = VegetationScatterGenerator::isGrasslikeBlock;

        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                if (!area.contains(x, z)) continue;
                if (insideAny(x, z, forbid)) continue;

                int y = terrainYFromCoordsOrWorld(x, z);
                BlockPos ground = new BlockPos(x, y, z);
                Block g = level.getBlockState(ground).getBlock();
                if (!allowGround.test(g)) continue;

                switch (area.type) {
                    case VILLAGE_GREEN:
                        placed += floraVillageGreen(x, z, rnd);
                        break;
                    case GRASSLAND:
                        placed += floraGrassland(x, z, rnd);
                        break;
                    case MEADOW:
                        placed += floraMeadow(x, z, rnd);
                        break;
                    case GARDEN:
                        placed += floraGarden(x, z, rnd);
                        break;
                    case PARK:
                    case RECREATION_GROUND:
                        placed += floraParkRare(x, z, rnd);
                        break;
                    case SHRUBBERY:
                        placed += floraShrubbery(x, z, rnd);
                        break;
                    case SCRUB:
                        placed += floraScrub(x, z, rnd);
                        break;
                    case HEATH:
                        placed += floraHeath(x, z, rnd);
                        break;
                    case FOREST_BROAD:
                    case FOREST_NEEDLE:
                    case FOREST_MIXED:
                        placed += floraForestUnderstory(x, z, rnd);
                        break;
                    case FOREST_LEAFLESS:
                        placed += floraLeafless(x, z, rnd);
                        break;
                    case TUNDRA:
                        placed += floraTundra(x, z, rnd);
                        break;
                    case WETLAND:
                        placed += floraWetland(x, z, rnd);
                        break;
                    case RESIDENTIAL:
                    case URBAN:
                        // в городских — допускаем редкие цветы/трава, но только если сверху воздух и низ — мох
                        if (g == Blocks.MOSS_BLOCK) {
                            if (rnd.nextDouble() < RARE_GRASS_FLORA_PROB * kFlora(area.type==ZoneType.RESIDENTIAL?"RESIDENTIAL":"URBAN")) {
                                placed += placeRandomFloraGeneric(x, z, rnd);
                            }
                        }
                        break;
                    default:
                        // остальные — ничего
                        break;
                }
            }
        }

        return placed;
    }

    // --- Наборы правил подзон ---

    private long floraVillageGreen(int x, int z, Random rnd) {
        // «иногда» цветы + иногда sweet berries, lilac, rose bush, peony; «редко» обычная grass
        double p = RARE_GRASS_FLORA_PROB * kFlora("VILLAGE_GREEN");
        long placed = 0;
        if (rnd.nextDouble() < p) { placed += placeOneOf(x, z, rnd,
                Blocks.SWEET_BERRY_BUSH, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY); }
        if (rnd.nextDouble() < p) { placed += placeRandomFlower(x, z, rnd); }
        if (rnd.nextDouble() < p*0.5) { placed += placeShortGrass(x, z); }
        return placed;
    }

    private long floraGrassland(int x, int z, Random rnd) {
        // часто GRASS/FERN; реже TALL_GRASS/LARGE_FERN; иногда sweet berries и цветы
        long placed = 0;
        double pOften = BASE_GRASS_FLORA_PROB * kFlora("GRASSLAND");
        double pRare  = RARE_GRASS_FLORA_PROB * kFlora("GRASSLAND");
        if (rnd.nextDouble() < pOften) placed += placeOneOf(x, z, rnd, Blocks.GRASS, Blocks.FERN);
        if (rnd.nextDouble() < pRare)  placed += placeOneOfTall(x, z, rnd, Blocks.TALL_GRASS, Blocks.LARGE_FERN);
        if (rnd.nextDouble() < pRare)  placed += placeBlockAgeable(x, z, Blocks.SWEET_BERRY_BUSH, rnd);
        if (rnd.nextDouble() < pRare)  placed += placeRandomFlower(x, z, rnd);
        return placed;
    }

    private long floraMeadow(int x, int z, Random rnd) {
        // часто TALL_GRASS/LARGE_FERN; реже SHORT_GRASS/FERN; иногда sweet berries
        long placed = 0;
        double pOften = BASE_GRASS_FLORA_PROB * kFlora("MEADOW");
        double pRare  = RARE_GRASS_FLORA_PROB * kFlora("MEADOW");
        if (rnd.nextDouble() < pOften) placed += placeOneOfTall(x, z, rnd, Blocks.TALL_GRASS, Blocks.LARGE_FERN);
        if (rnd.nextDouble() < pRare)  placed += placeOneOf(x, z, rnd, Blocks.GRASS, Blocks.FERN);
        if (rnd.nextDouble() < pRare)  placed += placeBlockAgeable(x, z, Blocks.SWEET_BERRY_BUSH, rnd);
        return placed;
    }

    private long floraGarden(int x, int z, Random rnd) {
        // засаживаем цветами «плотно»
        double p = Math.min(1.0, BASE_GRASS_FLORA_PROB * 1.5 * kFlora("GARDEN"));
        long placed = 0;
        if (rnd.nextDouble() < p) placed += placeRandomFlower(x, z, rnd);
        if (rnd.nextDouble() < p) placed += placeRandomFlower(x, z, rnd);
        if (rnd.nextDouble() < p*0.6) placed += placeOneOf(x, z, rnd, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY);
        return placed;
    }

    private long floraParkRare(int x, int z, Random rnd) {
        double p = RARE_GRASS_FLORA_PROB * kFlora("PARK");
        long placed = 0;
        if (rnd.nextDouble() < p) placed += placeRandomFlower(x, z, rnd);
        return placed;
    }

    private long floraShrubbery(int x, int z, Random rnd) {
        // ухоженные кустарники: часто LARGE_FERN, реже FERN/SHORT_GRASS; иногда LILAC/ROSE_BUSH/PEONY
        long placed = 0;
        double pOften = BASE_GRASS_FLORA_PROB * kFlora("SHRUBBERY");
        double pRare  = RARE_GRASS_FLORA_PROB * kFlora("SHRUBBERY");
        if (rnd.nextDouble() < pOften) placed += placeOneOfTall(x, z, rnd, Blocks.LARGE_FERN);
        if (rnd.nextDouble() < pRare)  placed += placeOneOf(x, z, rnd, Blocks.FERN, Blocks.GRASS);
        if (rnd.nextDouble() < pRare)  placed += placeOneOf(x, z, rnd, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY);
        return placed;
    }

    private long floraScrub(int x, int z, Random rnd) {
        // дикий кустарник: часто LARGE_FERN/FERN/DEAD_BUSH; иногда TALL_GRASS/SHORT_GRASS и SWEET_BERRY_BUSH
        long placed = 0;
        double pOften = BASE_GRASS_FLORA_PROB * kFlora("SCRUB");
        double pRare  = RARE_GRASS_FLORA_PROB * kFlora("SCRUB");
        if (rnd.nextDouble() < pOften) placed += placeOneOf(x, z, rnd, Blocks.LARGE_FERN, Blocks.FERN, Blocks.DEAD_BUSH);
        if (rnd.nextDouble() < pRare)  placed += placeOneOf(x, z, rnd, Blocks.TALL_GRASS, Blocks.GRASS);
        if (rnd.nextDouble() < pRare)  placed += placeBlockAgeable(x, z, Blocks.SWEET_BERRY_BUSH, rnd);
        return placed;
    }

    private long floraHeath(int x, int z, Random rnd) {
        // пустошь: часто DEAD_BUSH/FERN/SHORT_GRASS; иногда TALL_GRASS/LARGE_FERN/SWEET_BERRY_BUSH
        long placed = 0;
        double pOften = BASE_GRASS_FLORA_PROB * kFlora("HEATH");
        double pRare  = RARE_GRASS_FLORA_PROB * kFlora("HEATH");
        if (rnd.nextDouble() < pOften) placed += placeOneOf(x, z, rnd, Blocks.DEAD_BUSH, Blocks.FERN, Blocks.GRASS);
        if (rnd.nextDouble() < pRare)  placed += placeOneOf(x, z, rnd, Blocks.TALL_GRASS, Blocks.LARGE_FERN);
        if (rnd.nextDouble() < pRare)  placed += placeBlockAgeable(x, z, Blocks.SWEET_BERRY_BUSH, rnd);
        return placed;
    }

    private long floraForestUnderstory(int x, int z, Random rnd) {
        // после леса — трава и папоротники и цветы (умеренно)
        double p = RARE_GRASS_FLORA_PROB * 1.25 * kFlora("FOREST_BROAD");
        long placed = 0;
        if (rnd.nextDouble() < p) placed += placeOneOf(x, z, rnd, Blocks.GRASS, Blocks.FERN);
        if (rnd.nextDouble() < p*0.6) placed += placeOneOfTall(x, z, rnd, Blocks.TALL_GRASS, Blocks.LARGE_FERN);
        if (rnd.nextDouble() < p*0.2) placed += placeRandomFlower(x, z, rnd);
        return placed;
    }

    private long floraLeafless(int x, int z, Random rnd) {
        // leafless: редкие кактусы (1..8 высота) и dead_bush с большим интервалом
        if (rnd.nextDouble() < (1.0/50.0) * kFlora("FOREST_LEAFLESS")) {
            return placeCactusOrDeadBush(x, z, rnd);
        }
        return 0;
    }

    private long floraTundra(int x, int z, Random rnd) {
        // тундра: dead_bush, sweet berries, fern, short grass
        double p = RARE_GRASS_FLORA_PROB * 1.1 * kFlora("TUNDRA");
        long placed = 0;
        if (rnd.nextDouble() < p) placed += placeOneOf(x, z, rnd, Blocks.DEAD_BUSH, Blocks.FERN, Blocks.GRASS);
        if (rnd.nextDouble() < p) placed += placeBlockAgeable(x, z, Blocks.SWEET_BERRY_BUSH, rnd);
        return placed;
    }

    private long floraWetland(int x, int z, Random rnd) {
        // болота: травы умеренно; деревья обрабатываются в tree-pass
        double p = RARE_GRASS_FLORA_PROB * 0.8 * kFlora("WETLAND");
        long placed = 0;
        if (rnd.nextDouble() < p) placed += placeOneOf(x, z, rnd, Blocks.GRASS, Blocks.FERN);
        if (rnd.nextDouble() < p*0.4) placed += placeOneOfTall(x, z, rnd, Blocks.TALL_GRASS, Blocks.LARGE_FERN);
        return placed;
    }

    // --- Вспомогательные посадки трав/цветов/кустов ---

    private long placeRandomFlower(int x, int z, Random rnd) {
        Block b = FLOWERS[rnd.nextInt(FLOWERS.length)];
        return placeSimplePlant(x, z, b);
    }

    private long placeShortGrass(int x, int z) {
        return placeSimplePlant(x, z, Blocks.GRASS);
    }

    private long placeOneOf(int x, int z, Random rnd, Block... blocks) {
        Block b = blocks[rnd.nextInt(blocks.length)];
        if (b == Blocks.LARGE_FERN || b == Blocks.TALL_GRASS) {
            return placeOneOfTall(x, z, rnd, b);
        }
        return placeSimplePlant(x, z, b);
    }

    private long placeOneOfTall(int x, int z, Random rnd, Block... tallBlocks) {
        Block b = tallBlocks[rnd.nextInt(tallBlocks.length)];
        return placeDoublePlant(x, z, b);
    }

    private long placeSimplePlant(int x, int z, Block plant) {
        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos above = new BlockPos(x, y+1, z);
        if (!level.isEmptyBlock(above)) return 0;
        level.setBlock(above, plant.defaultBlockState(), 3);
        return 1;
        }

    private long placeBlockAgeable(int x, int z, Block plant, Random rnd) {
        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos above = new BlockPos(x, y+1, z);
        if (!level.isEmptyBlock(above)) return 0;
        try {
            IntegerProperty AGE = (IntegerProperty) plant.getStateDefinition().getProperty("age");
            if (AGE != null) {
                int age = rnd.nextInt(AGE.getPossibleValues().size());
                level.setBlock(above, plant.defaultBlockState().setValue(AGE, age), 3);
                return 1;
            }
        } catch (Throwable ignore){}
        level.setBlock(above, plant.defaultBlockState(), 3);
        return 1;
    }

    private long placeDoublePlant(int x, int z, Block tallPlant) {
        int y = terrainYFromCoordsOrWorld(x, z);
        BlockPos p1 = new BlockPos(x, y+1, z);
        BlockPos p2 = new BlockPos(x, y+2, z);
        if (!level.isEmptyBlock(p1) || !level.isEmptyBlock(p2)) return 0;
        try {
            // для double plants просто ставим нижнюю часть — игра сама корректно заполнит верх при setBlock?
            // На всякий случай поставим обе.
            level.setBlock(p1, tallPlant.defaultBlockState(), 3);
            level.setBlock(p2, tallPlant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 3);
            return 2;
        } catch (Throwable t) {
            // fallback как обычное растение
            level.setBlock(p1, tallPlant.defaultBlockState(), 3);
            return 1;
        }
    }

    private long placeCactusOrDeadBush(int x, int z, Random rnd) {
        // Ставим либо столб кактуса (1..8), заменяем грунт на песок; либо dead_bush
        if (rnd.nextBoolean()) {
            int h = 1 + rnd.nextInt(8);
            int y = terrainYFromCoordsOrWorld(x, z);
            BlockPos base = new BlockPos(x, y, z);
            level.setBlock(base, Blocks.SAND.defaultBlockState(), 3);
            long ok = 0;
            for (int i=1; i<=h; i++) {
                BlockPos p = new BlockPos(x, y+i, z);
                if (!level.isEmptyBlock(p)) break;
                level.setBlock(p, Blocks.CACTUS.defaultBlockState(), 3);
                ok++;
            }
            return ok;
        } else {
            return placeSimplePlant(x, z, Blocks.DEAD_BUSH);
        }
    }

    private long placeRandomFloraGeneric(int x, int z, Random rnd) {
        // простой микс: трава/папоротник/цветок
        double r = rnd.nextDouble();
        if (r < 0.5) return placeShortGrass(x, z);
        else if (r < 0.75) return placeOneOf(x, z, rnd, Blocks.FERN, Blocks.TALL_GRASS);
        else return placeRandomFlower(x, z, rnd);
    }

    // --- Orchard (CHERRY сетка 10×5) ---
    private long plantOrchard(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        int minX = clamp(area.clipMinX(wMinX), wMinX, wMaxX);
        int maxX = clamp(area.clipMaxX(wMaxX), wMinX, wMaxX);
        int minZ = clamp(area.clipMinZ(wMinZ), wMinZ, wMaxZ);
        int maxZ = clamp(area.clipMaxZ(wMaxZ), wMinZ, wMaxZ);

        long planted = 0;
        for (int x=minX; x<=maxX; x+=10) {
            for (int z=minZ; z<=maxZ; z+=5) {
                if (!area.contains(x, z)) continue;
                // под каждый саженец — мох
                placeMossAt(x, z);
                if (placeTreeIfAir(x, z, CHERRY)) planted++;
            }
        }
        return planted;
    }

    // ===============================
    // --------- ПОДДЕРЖКА ----------
    // ===============================
    private boolean insideAny(int x, int z, List<Area> areas) {
        for (Area a : areas) if (a.contains(x, z)) return true;
        return false;
    }
    private long seedFor(int x, int z) {
        return (((long)x) << 32) ^ (z * 0x9E3779B97F4A7C15L) ^ 0x1234ABCDCAFEBABEL;
    }
    private Block resolveSapling(Block explicit, String leafType, Random rnd) {
        if (explicit != null) return explicit;
        if ("needleleaved".equals(leafType)) return pickNeedle(rnd);
        if ("mixed".equals(leafType)) return (rnd.nextBoolean()? pickBroadleaved(rnd) : pickNeedle(rnd));
        // default (вкл. broadleaved или не указано)
        return pickBroadleaved(rnd);
    }
    private Block resolveSaplingByGenusSpecies(String genus, String species, String leafType) {
        if (genus != null) {
            if (genus.contains("picea") || genus.contains("pinus") || genus.contains("abies") || genus.contains("larix")) return SPRUCE;
            if (genus.contains("betula")) return BIRCH;
            if (genus.contains("quercus")) return OAK;
            if (genus.contains("acacia")) return ACACIA;
            if (genus.contains("prunus")) return CHERRY;
            if (genus.contains("ficus") || genus.contains("swietenia") || genus.contains("ceiba")) return JUNGLE;
        }
        // по leaf_type
        if ("needleleaved".equals(leafType)) return SPRUCE;
        // дефолт — лиственные
        return OAK;
    }
}