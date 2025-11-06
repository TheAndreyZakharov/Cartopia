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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;



public class AddressPointBuildingsGenerator {

    // ---- Конфиг ----
    private static final int WALL_H = 4;

    private static final int[][] SIZE_OPTIONS = new int[][]{
            {15,15}, {10,10}, {10,15}, {20,10}, {20,15}, {20,20}
    };

    private static final Block[] MATERIALS = new Block[] {
            // доски
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS,
            Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
            // камень/бетон/кирпичи
            Blocks.BRICKS, Blocks.NETHER_BRICKS, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.POLISHED_DIORITE, Blocks.DIORITE
    };

    private enum RoofType { FLAT, HIP, GABLE }

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public AddressPointBuildingsGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ---- Вспомогательные типы ----
    private static final class AddrNode {
        final int x, z;
        AddrNode(int x, int z){ this.x=x; this.z=z; }
    }

    // ---- Запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "AddressPointBuildings: coords == null - skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "AddressPointBuildings: no center/bbox — skipping."); return; }

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

        List<AddrNode> nodes = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        // ---- Чтение OSM (стрим/батч) ----
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectAddrNode(e, nodes, seenIds,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "AddressPointBuildings: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "AddressPointBuildings: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collectAddrNode(el.getAsJsonObject(), nodes, seenIds,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "AddressPointBuildings: error reading features: " + ex.getMessage());
        }

        if (nodes.isEmpty()) { broadcast(level, "AddressPointBuildings: no suitable points — done."); return; }

        // ---- Построение ----
        Random rngSeed = new Random( ( (long)worldMinX<<32) ^ worldMaxX ^ ((long)worldMinZ<<16) ^ worldMaxZ );
        int done = 0, total = nodes.size();

        for (AddrNode n : nodes) {
            if (n.x < worldMinX || n.x > worldMaxX || n.z < worldMinZ || n.z > worldMaxZ) continue;

            // случайные параметры домика
            Random rng = new Random(rngSeed.nextLong() ^ (((long)n.x)<<21) ^ (((long)n.z)<<7));
            int[] sz = SIZE_OPTIONS[rng.nextInt(SIZE_OPTIONS.length)];
            int W = sz[0], L = sz[1];

            Block wallMat = MATERIALS[rng.nextInt(MATERIALS.length)];
            Block roofMat;
            do { roofMat = MATERIALS[rng.nextInt(MATERIALS.length)]; } while (roofMat == wallMat);

            RoofType roof = RoofType.values()[rng.nextInt(RoofType.values().length)];

            // габариты по центру ноды
            int x0 = n.x - W/2;
            int z0 = n.z - L/2;
            int x1 = x0 + W - 1;
            int z1 = z0 + L - 1;

            // если дом выходит за край мира — пропускаем
            if (x0 < worldMinX || x1 > worldMaxX || z0 < worldMinZ || z1 > worldMaxZ) continue;

            // --- План дома (фиксируем дверь/окна) + проверка коллизий ---
            HousePlan plan = makePlan(x0, z0, x1, z1, roof, rng);

            // если есть коллизии — пропускаем постройку
            if (!canPlaceAll(x0, z0, x1, z1, roof, plan)) {
                broadcast(level, "Address building at (" + ((x0 + x1) / 2) + "," + ((z0 + z1) / 2) + "): skipped — collision with existing blocks.");
                continue;
            }

            // построить стены, окна, дверь и крышу
            try {
                buildHouse(x0, z0, x1, z1, wallMat, roofMat, roof, plan);
            } catch (Throwable t) {
                broadcast(level, "AddressPointBuildings: build error at ("+n.x+","+n.z+"): " + t.getMessage());
            }

            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                broadcast(level, "Address buildings: ~" + pct + "%");
            }
        }

        broadcast(level, "AddressPointBuildings: done.");
    }

    // ---- Отбор точек ----
    private void collectAddrNode(JsonObject e, List<AddrNode> out, Set<Long> seenIds,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {
        String type = optString(e, "type");
        if (!"node".equals(type)) return; // Нужны только точки (без полигонов)

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (!isBareBuildingPointCandidate(tags)) return;

        Long id = optLong(e, "id");
        if (id != null && !seenIds.add(id)) return;

        Double lat = optDouble(e, "lat"), lon = optDouble(e, "lon");
        if (lat == null || lon == null) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            JsonObject p = g.get(0).getAsJsonObject();
            lat = p.get("lat").getAsDouble(); lon = p.get("lon").getAsDouble();
        }
        int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        out.add(new AddrNode(xz[0], xz[1]));
    }

    private boolean isBareBuildingPointCandidate(JsonObject t) {
        if (t == null) return false;

        String buildingVal = low(optString(t, "building"));
        boolean hasBuilding = buildingVal != null && !"no".equals(buildingVal)
                && !BUILDING_BAD_VALUES.contains(buildingVal);

        // Требуем именно addr:housenumber для «адресных точек»
        boolean hasHouseNumber = hasHouseNumber(t);

        // Явные исключения — входы/двери/помещения/квартиры/этажи/части
        String[] entranceLikeKeys = new String[]{
                "entrance","door","indoor","building:part","level","level:ref",
                "addr:flats","addr:unit","addr:door","addr:floor","entrance:ref"
        };
        for (String k : entranceLikeKeys) if (t.has(k)) return false;

        // Прежние «не-здания» (магазины, транспорт и т.п.)
        String[] banKeys = new String[]{
                "shop","craft","office","tourism","leisure","sport","highway",
                "railway","public_transport","man_made","natural","historic","aeroway",
                "military","power","barrier","landuse","waterway","emergency","healthcare"
        };
        for (String k : banKeys) if (t.has(k)) return false;

        // Туалеты
        String amenity = low(optString(t, "amenity"));
        if ("toilets".equals(amenity)) return false;
        if ("toilets".equals(buildingVal) || "toilet".equals(buildingVal)) return false;

        // Разрешаем, если это реально здание (building=*) или «чистая адресная точка дома»
        return hasBuilding || hasHouseNumber;
    }

    // ДОБАВЬ НОВЫЙ ХЕЛПЕР:
    private boolean hasHouseNumber(JsonObject t) {
        String hn = optString(t, "addr:housenumber");
        return hn != null && !hn.isEmpty();
    }

    // ДОБАВЬ где-нибудь рядом с другими константами:
    private static final Set<String> BUILDING_BAD_VALUES = new HashSet<>(Arrays.asList(
            "entrance", "toilet", "toilets", "roof", "ruins", "platform", "bridge",
            "bunker", "tent", "shed", "part", "building_part"
    ));

    @SuppressWarnings("unused")
    private boolean hasAnyAddr(JsonObject t) {
        for (Map.Entry<String, JsonElement> en : t.entrySet()) {
            if (en.getKey() != null && en.getKey().startsWith("addr:")) return true;
        }
        return false;
    }

    // ---- Постройка одного дома ----
    private void buildHouse(int x0, int z0, int x1, int z1,
                            Block wallMat, Block roofMat, RoofType roof, HousePlan p) {
        // 1) Стены
        for (int x = x0; x <= x1; x++) { buildWallColumn(x, z0, wallMat); buildWallColumn(x, z1, wallMat); }
        for (int z = z0; z <= z1; z++) { buildWallColumn(x0, z, wallMat); buildWallColumn(x1, z, wallMat); }

        // 2) Окна по заранее выбранным оффсетам
        placeWindowsPlanned(Side.NORTH, x0, z0, x1, z1, p);
        placeWindowsPlanned(Side.SOUTH, x0, z0, x1, z1, p);
        placeWindowsPlanned(Side.WEST , x0, z0, x1, z1, p);
        placeWindowsPlanned(Side.EAST , x0, z0, x1, z1, p);

        // 3) Дверь
        placeDoor(p.doorSide, p.doorX, p.doorZ);

        // 4) Крыша
        int yRoofBase = maxPerimeterTopY(x0, z0, x1, z1);
        switch (roof) {
            case FLAT  -> buildRoofFlat (x0, z0, x1, z1, yRoofBase + 1, roofMat);
            case HIP   -> buildRoofHip  (x0, z0, x1, z1, yRoofBase + 1, roofMat);
            case GABLE -> buildRoofGable(x0, z0, x1, z1, yRoofBase + 1, roofMat, p.ridgeAlongX);
        }
    }

    private enum Side { NORTH, SOUTH, WEST, EAST;
        boolean isHorizontal(){ return this==NORTH || this==SOUTH; }
    }

    private static final class HousePlan {
        final Side doorSide;
        final int doorX, doorZ;
        final Map<Side, List<Integer>> win = new EnumMap<>(Side.class);
        final boolean ridgeAlongX;

        HousePlan(Side doorSide, int doorX, int doorZ, boolean ridgeAlongX) {
            this.doorSide = doorSide; this.doorX = doorX; this.doorZ = doorZ; this.ridgeAlongX = ridgeAlongX;
            win.put(Side.NORTH, new ArrayList<>());
            win.put(Side.SOUTH, new ArrayList<>());
            win.put(Side.WEST,  new ArrayList<>());
            win.put(Side.EAST,  new ArrayList<>());
        }
    }

    private void buildWallColumn(int x, int z, Block wall) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return;
        for (int dy=1; dy<=WALL_H; dy++) {
            setBlockSafe(x, yBase + dy, z, wall);
        }
    }

    private void placeDoor(Side side, int x, int z) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return;

        setAir(x, yBase + 1, z);
        setAir(x, yBase + 2, z);

        Direction facing = switch (side) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST  -> Direction.WEST;
            case EAST  -> Direction.EAST;
        };

        try {
            BlockState lower = Blocks.MANGROVE_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                    .setValue(DoorBlock.OPEN, false)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

            level.setBlock(new BlockPos(x, yBase + 1, z), lower, 3);
            level.setBlock(new BlockPos(x, yBase + 2, z), upper, 3);
        } catch (Throwable t) {
            setAir(x, yBase + 1, z);
            setAir(x, yBase + 2, z);
        }
    }

    @SuppressWarnings("unused")
    private void placeWindowsForWall(Side side, int x0, int z0, int x1, int z1,
                                     Side doorSide, int doorX, int doorZ, Random rng) {
        final int need = 2;
        int span = side.isHorizontal() ? (x1 - x0 + 1) : (z1 - z0 + 1);
        int minOff = 2;
        int maxOff = Math.max(minOff, span - 3);

        List<Integer> candidates = new ArrayList<>();
        for (int off=minOff; off<=maxOff-1; off++) {
            int wx = (side==Side.NORTH||side==Side.SOUTH) ? (x0 + off) : (side==Side.WEST ? x0 : x1);
            int wz = (side==Side.NORTH? z0 : (side==Side.SOUTH? z1 : (z0+off)));
            boolean overlapsDoor = (doorSide==side) && (
                    ((side.isHorizontal()) && (doorX==wx || doorX==wx+1) && (doorZ== (side==Side.NORTH? z0 : z1))) ||
                    ((!side.isHorizontal()) && (doorZ==wz || doorZ==wz+1) && (doorX== (side==Side.WEST? x0 : x1)))
            );
            if (!overlapsDoor) candidates.add(off);
        }

        Collections.shuffle(candidates, rng);
        List<Integer> chosen = new ArrayList<>();
        for (int off : candidates) {
            boolean ok = true;
            for (int c : chosen) if (Math.abs(c - off) < 3) { ok=false; break; }
            if (ok) { chosen.add(off); if (chosen.size()>=need) break; }
        }

        for (int off : chosen) {
            if (side==Side.NORTH) placeWindow2x2(x0 + off, z0);
            else if (side==Side.SOUTH) placeWindow2x2(x0 + off, z1);
            else if (side==Side.WEST)  placeWindow2x2(x0,        z0 + off);
            else                       placeWindow2x2(x1,        z0 + off);
        }
    }

    /** Окно 2×2; высота: на yBase+2..+3. */
    private void placeWindow2x2(int xOnWall, int zOnWall) {
        if (terrainYFromCoordsOrWorld(xOnWall, zOnWall) == Integer.MIN_VALUE) return;
        boolean northOrSouth = isPerimeterNorthSouth(xOnWall, zOnWall);

        if (northOrSouth) {
            for (int dx=0; dx<2; dx++) {
                int x = xOnWall + dx;
                int z = zOnWall;
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                setBlockSafe(x, yBase + 2, z, Blocks.GLASS);
                setBlockSafe(x, yBase + 3, z, Blocks.GLASS);
            }
        } else {
            for (int dz=0; dz<2; dz++) {
                int x = xOnWall;
                int z = zOnWall + dz;
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                setBlockSafe(x, yBase + 2, z, Blocks.GLASS);
                setBlockSafe(x, yBase + 3, z, Blocks.GLASS);
            }
        }
    }

    // эвристика: если слева/справа на y+2 стоят блоки стены — это север/юг; иначе — запад/восток
    private boolean isPerimeterNorthSouth(int x, int z) {
        int y = terrainYFromCoordsOrWorld(x, z);
        if (y == Integer.MIN_VALUE) return true;
        BlockState ls = safeState(x-1, y+2, z);
        BlockState rs = safeState(x+1, y+2, z);
        return (ls != null && !ls.isAir()) || (rs != null && !rs.isAir());
    }
    private BlockState safeState(int x, int y, int z){
        try { return level.getBlockState(new BlockPos(x,y,z)); } catch (Throwable t){ return null; }
    }

    // ---- Крыши ----
    private int maxPerimeterTopY(int x0, int z0, int x1, int z1) {
        int m = Integer.MIN_VALUE;
        for (int x=x0; x<=x1; x++) {
            m = Math.max(m, terrainYFromCoordsOrWorld(x, z0) + WALL_H);
            m = Math.max(m, terrainYFromCoordsOrWorld(x, z1) + WALL_H);
        }
        for (int z=z0; z<=z1; z++) {
            m = Math.max(m, terrainYFromCoordsOrWorld(x0, z) + WALL_H);
            m = Math.max(m, terrainYFromCoordsOrWorld(x1, z) + WALL_H);
        }
        return m==Integer.MIN_VALUE ? 0 : m;
    }

    private void buildRoofFlat(int x0, int z0, int x1, int z1, int yIgnored, Block mat) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                int y = yBase + WALL_H;
                setBlockSafe(x, y, z, mat);
            }
        }
    }

    // --- HIP (пирамидка) по рельефу
    private void buildRoofHip(int x0, int z0, int x1, int z1, int yIgnored, Block mat) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                int dx = Math.min(x - x0, x1 - x);
                int dz = Math.min(z - z0, z1 - z);
                int h  = Math.max(0, Math.min(dx, dz));
                int y  = yBase + WALL_H + h;
                setBlockSafe(x, y, z, mat);
            }
        }
    }

    // Прибавка высоты для двускатки
    private int gableIncrement(boolean ridgeAlongX, int x, int z, int x0, int z0, int x1, int z1) {
        if (ridgeAlongX) {
            int half = (z1 - z0 + 1) / 2;
            int dz   = Math.min(z - z0, z1 - z);
            return Math.min(half, Math.max(0, dz));
        } else {
            int half = (x1 - x0 + 1) / 2;
            int dx   = Math.min(x - x0, x1 - x);
            return Math.min(half, Math.max(0, dx));
        }
    }

    // --- GABLE (двускатная) по рельефу + заделка ровных торцов
    private void buildRoofGable(int x0, int z0, int x1, int z1, int yIgnored, Block mat, boolean ridgeAlongX) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                int h = gableIncrement(ridgeAlongX, x, z, x0, z0, x1, z1);
                int y = yBase + WALL_H + h;
                setBlockSafe(x, y, z, mat);
            }
        }

        if (ridgeAlongX) {
            for (int z = z0; z <= z1; z++) {
                int baseEdge = terrainYFromCoordsOrWorld(x0, z);
                int baseIn   = terrainYFromCoordsOrWorld(Math.min(x0+1, x1), z);
                if (baseEdge != Integer.MIN_VALUE && baseIn != Integer.MIN_VALUE) {
                    int hIn   = gableIncrement(true, Math.min(x0+1, x1), z, x0, z0, x1, z1);
                    int yTop  = baseIn + WALL_H + hIn;
                    int yFrom = baseEdge + WALL_H;
                    if (yTop >= yFrom) for (int y = yFrom; y <= yTop; y++) setBlockSafe(x0, y, z, mat);
                }
                baseEdge = terrainYFromCoordsOrWorld(x1, z);
                baseIn   = terrainYFromCoordsOrWorld(Math.max(x1-1, x0), z);
                if (baseEdge != Integer.MIN_VALUE && baseIn != Integer.MIN_VALUE) {
                    int hIn   = gableIncrement(true, Math.max(x1-1, x0), z, x0, z0, x1, z1);
                    int yTop  = baseIn + WALL_H + hIn;
                    int yFrom = baseEdge + WALL_H;
                    if (yTop >= yFrom) for (int y = yFrom; y <= yTop; y++) setBlockSafe(x1, y, z, mat);
                }
            }
        } else {
            for (int x = x0; x <= x1; x++) {
                int baseEdge = terrainYFromCoordsOrWorld(x, z0);
                int baseIn   = terrainYFromCoordsOrWorld(x, Math.min(z0+1, z1));
                if (baseEdge != Integer.MIN_VALUE && baseIn != Integer.MIN_VALUE) {
                    int hIn   = gableIncrement(false, x, Math.min(z0+1, z1), x0, z0, x1, z1);
                    int yTop  = baseIn + WALL_H + hIn;
                    int yFrom = baseEdge + WALL_H;
                    if (yTop >= yFrom) for (int y = yFrom; y <= yTop; y++) setBlockSafe(x, y, z0, mat);
                }
                baseEdge = terrainYFromCoordsOrWorld(x, z1);
                baseIn   = terrainYFromCoordsOrWorld(x, Math.max(z1-1, z0));
                if (baseEdge != Integer.MIN_VALUE && baseIn != Integer.MIN_VALUE) {
                    int hIn   = gableIncrement(false, x, Math.max(z1-1, z0), x0, z0, x1, z1);
                    int yTop  = baseIn + WALL_H + hIn;
                    int yFrom = baseEdge + WALL_H;
                    if (yTop >= yFrom) for (int y = yFrom; y <= yTop; y++) setBlockSafe(x, y, z1, mat);
                }
            }
        }
    }

    // ---- Примитивы ----
    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }
    private void setAir(int x, int y, int z) {
        try { level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 3); } catch (Throwable ignore) {}
    }

    // ---- Рельеф ----
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

    // ---- Утилиты/парсинг ----
    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Long optLong(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsLong() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Double optDouble(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsDouble() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static int clamp(int v, int a, int b){ return Math.max(a, Math.min(b, v)); }

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

    // мелкие заглушки/эвристики
    @SuppressWarnings("unused")
    private boolean isOn(int a, int b){ return a==b; }
    @SuppressWarnings("unused")
    private int nearest(int v){ return v; }


    // === Случайный план дома: дверь + два окна на каждую стену (если влезают) ===
    private HousePlan makePlan(int x0, int z0, int x1, int z1, RoofType roof, Random rng) {
        // дверь
        Side doorSide = Side.values()[rng.nextInt(Side.values().length)];
        int span = doorSide.isHorizontal() ? (x1 - x0 + 1) : (z1 - z0 + 1);
        int minOff = 2, maxOff = Math.max(minOff, span - 3);
        int off = clamp(rng.nextInt(Math.max(1, maxOff - minOff + 1)) + minOff, minOff, maxOff);

        int doorX=0, doorZ=0;
        switch (doorSide) {
            case NORTH -> { doorZ = z0; doorX = x0 + off; }
            case SOUTH -> { doorZ = z1; doorX = x0 + off; }
            case WEST  -> { doorX = x0; doorZ = z0 + off; }
            case EAST  -> { doorX = x1; doorZ = z0 + off; }
        }

        boolean ridgeAlongX = (x1 - x0) >= (z1 - z0);
        HousePlan p = new HousePlan(doorSide, doorX, doorZ, ridgeAlongX);

        // окна
        chooseWindowOffsetsForSide(p, Side.NORTH, x0, z0, x1, z1, rng);
        chooseWindowOffsetsForSide(p, Side.SOUTH, x0, z0, x1, z1, rng);
        chooseWindowOffsetsForSide(p, Side.WEST,  x0, z0, x1, z1, rng);
        chooseWindowOffsetsForSide(p, Side.EAST,  x0, z0, x1, z1, rng);

        return p;
    }

    // Выбор двух отступов для окон на конкретной стене
    private void chooseWindowOffsetsForSide(HousePlan p, Side side, int x0, int z0, int x1, int z1, Random rng) {
        int span = side.isHorizontal() ? (x1 - x0 + 1) : (z1 - z0 + 1);
        int minOff = 2, maxOff = Math.max(minOff, span - 3);
        List<Integer> candidates = new ArrayList<>();

        for (int off = minOff; off <= maxOff - 1; off++) {
            int wx = (side==Side.NORTH||side==Side.SOUTH) ? (x0 + off) : (side==Side.WEST ? x0 : x1);
            int wz = (side==Side.NORTH? z0 : (side==Side.SOUTH? z1 : (z0+off)));

            boolean overlapsDoor =
                    (p.doorSide == side) &&
                    (side.isHorizontal() ?
                            ( (p.doorX == wx || p.doorX == wx+1) && p.doorZ == (side==Side.NORTH? z0 : z1) )
                            :
                            ( (p.doorZ == wz || p.doorZ == wz+1) && p.doorX == (side==Side.WEST? x0 : x1) )
                    );
            if (!overlapsDoor) candidates.add(off);
        }

        Collections.shuffle(candidates, rng);
        List<Integer> chosen = p.win.get(side);
        for (int off : candidates) {
            boolean ok = true;
            for (int c : chosen) if (Math.abs(c - off) < 3) { ok=false; break; }
            if (ok) {
                chosen.add(off);
                if (chosen.size() >= 2) break;
            }
        }
    }

    // Проверка: блок свободен? (считаем коллизией любой НЕ-воздух)
    private boolean isFree(int x, int y, int z) {
        try {
            BlockState st = level.getBlockState(new BlockPos(x,y,z));
            return st == null || st.isAir();
        } catch (Throwable t) { return false; }
    }

    // Вычисление Y крыши над (x,z)
    private int roofYAt(int x, int z, int x0, int z0, int x1, int z1, RoofType roof, boolean ridgeAlongX) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        int y = yBase + WALL_H;
        switch (roof) {
            case FLAT -> { /* y как есть */ }
            case HIP -> {
                int dx = Math.min(x - x0, x1 - x);
                int dz = Math.min(z - z0, z1 - z);
                int h  = Math.max(0, Math.min(dx, dz));
                y += h;
            }
            case GABLE -> {
                int h = gableIncrement(ridgeAlongX, x, z, x0, z0, x1, z1);
                y += h;
            }
        }
        return y;
    }

    // true → всё свободно; false → коллизия, строить нельзя
    private boolean canPlaceAll(int x0, int z0, int x1, int z1, RoofType roof, HousePlan p) {
        // Стены (периметр)
        for (int x = x0; x <= x1; x++) {
            if (!checkWallColumnFree(x, z0, p)) return false;
            if (!checkWallColumnFree(x, z1, p)) return false;
        }
        for (int z = z0; z <= z1; z++) {
            if (!checkWallColumnFree(x0, z, p)) return false;
            if (!checkWallColumnFree(x1, z, p)) return false;
        }

        // Окна: 2×2 на yBase+2..+3
        if (!checkWindowsFree(Side.NORTH, x0, z0, x1, z1, p)) return false;
        if (!checkWindowsFree(Side.SOUTH, x0, z0, x1, z1, p)) return false;
        if (!checkWindowsFree(Side.WEST , x0, z0, x1, z1, p)) return false;
        if (!checkWindowsFree(Side.EAST , x0, z0, x1, z1, p)) return false;

        // Дверь: две клетки по высоте
        int yDoorBase = terrainYFromCoordsOrWorld(p.doorX, p.doorZ);
        if (yDoorBase == Integer.MIN_VALUE) return false;
        if (!isFree(p.doorX, yDoorBase + 1, p.doorZ)) return false;
        if (!isFree(p.doorX, yDoorBase + 2, p.doorZ)) return false;

        // Крыша (каждая клетка прямоугольника)
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int y = roofYAt(x, z, x0, z0, x1, z1, roof, p.ridgeAlongX);
                if (y == Integer.MIN_VALUE) return false;
                if (!isFree(x, y, z)) return false;
            }
        }
        return true;
    }
    
    @SuppressWarnings("unused")
    private boolean checkWallColumnFree(int x, int z, HousePlan p) {
        int yBase = terrainYFromCoordsOrWorld(x, z);
        if (yBase == Integer.MIN_VALUE) return false;
        boolean isDoorCell = (x == p.doorX && z == p.doorZ);
        for (int dy = 1; dy <= WALL_H; dy++) {
            // На месте двери проверяем тоже: там станет дверь (2 блока), выше — стена
            if (!isFree(x, yBase + dy, z)) return false;
        }
        return true;
    }

    private boolean checkWindowsFree(Side side, int x0, int z0, int x1, int z1, HousePlan p) {
        for (int off : p.win.get(side)) {
            if (side == Side.NORTH || side == Side.SOUTH) {
                int z = (side == Side.NORTH ? z0 : z1);
                for (int dx = 0; dx < 2; dx++) {
                    int x = x0 + off + dx;
                    int yBase = terrainYFromCoordsOrWorld(x, z);
                    if (yBase == Integer.MIN_VALUE) return false;
                    if (!isFree(x, yBase + 2, z)) return false;
                    if (!isFree(x, yBase + 3, z)) return false;
                }
            } else {
                int x = (side == Side.WEST ? x0 : x1);
                for (int dz = 0; dz < 2; dz++) {
                    int z = z0 + off + dz;
                    int yBase = terrainYFromCoordsOrWorld(x, z);
                    if (yBase == Integer.MIN_VALUE) return false;
                    if (!isFree(x, yBase + 2, z)) return false;
                    if (!isFree(x, yBase + 3, z)) return false;
                }
            }
        }
        return true;
    }

    private void placeWindowsPlanned(Side side, int x0, int z0, int x1, int z1, HousePlan p) {
        for (int off : p.win.get(side)) {
            if (side==Side.NORTH || side==Side.SOUTH) {
                int z = (side==Side.NORTH ? z0 : z1);
                for (int dx=0; dx<2; dx++) {
                    int x = x0 + off + dx;
                    int yBase = terrainYFromCoordsOrWorld(x, z);
                    if (yBase == Integer.MIN_VALUE) continue;
                    setBlockSafe(x, yBase + 2, z, Blocks.GLASS);
                    setBlockSafe(x, yBase + 3, z, Blocks.GLASS);
                }
            } else {
                int x = (side==Side.WEST ? x0 : x1);
                for (int dz=0; dz<2; dz++) {
                    int z = z0 + off + dz;
                    int yBase = terrainYFromCoordsOrWorld(x, z);
                    if (yBase == Integer.MIN_VALUE) continue;
                    setBlockSafe(x, yBase + 2, z, Blocks.GLASS);
                    setBlockSafe(x, yBase + 3, z, Blocks.GLASS);
                }
            }
        }
    }

}