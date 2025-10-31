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
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class LeisureRestGenerator {

    // Материалы
    private static final Block BENCH_STAIRS      = Blocks.OAK_STAIRS;
    private static final Block BENCH_END_SIGN    = Blocks.OAK_WALL_SIGN; // "подлокотники" на боках
    private static final Block TABLE_BLOCK       = Blocks.SCAFFOLDING;
    private static final Block SHELTER_POST      = Blocks.BIRCH_FENCE;
    private static final Block SHELTER_ROOF_BLOCK= Blocks.BIRCH_PLANKS; 
    private static final Block BBQ_FURNACE       = Blocks.FURNACE;
    private static final Block BBQ_CHEST         = Blocks.CHEST;
    private static final Block BBQ_WORKBENCH     = Blocks.CRAFTING_TABLE;
    private static final Block FIRE_PIT_FLOOR    = Blocks.COBBLESTONE;
    private static final Block FIRE_PIT_CENTER   = Blocks.CAMPFIRE;
    private static final Block TENT_BLOCK         = Blocks.ORANGE_CONCRETE;

    // Внутренние коллекции
    private static final class Pt { final int x,z; Pt(int x,int z){this.x=x; this.z=z;} }
    private static final class Polyline { final List<Pt> pts = new ArrayList<>(); }

    private final List<Pt> benches  = new ArrayList<>();
    private final List<Pt> tables   = new ArrayList<>();
    private final List<Pt> bbqSites = new ArrayList<>();
    private final List<Pt> shelters = new ArrayList<>();
    private final List<Pt> theatres = new ArrayList<>();
    private final List<Pt> firepits = new ArrayList<>();
    private final List<Pt> tents    = new ArrayList<>();
    private final List<Polyline> roads = new ArrayList<>();

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public LeisureRestGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ==== вещалка
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

    // ==== запуск
    public void generate() {
        JsonObject sourceIndex = (store != null && store.indexJsonObject() != null) ? store.indexJsonObject() : coords;
        if (sourceIndex == null) { broadcast(level, "LeisureRestGenerator: нет исходных данных — пропускаю."); return; }
        JsonObject center = sourceIndex.getAsJsonObject("center");
        JsonObject bbox   = sourceIndex.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "LeisureRestGenerator: нет center/bbox — пропускаю."); return; }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = sourceIndex.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = sourceIndex.has("player")
                ? (int)Math.round(sourceIndex.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = sourceIndex.has("player")
                ? (int)Math.round(sourceIndex.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        // === 1) Сбор (стримом или фолбэком)
        try {
            if (store != null) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) collect(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            } else {
                JsonArray elements = safeElementsArray(coords);
                if (elements == null) { broadcast(level, "LeisureRestGenerator: features.elements пуст — пропускаю."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "LeisureRestGenerator: ошибка чтения features: " + ex.getMessage());
        }

        // === 2) Постройка (каждый блок — по локальному рельефу)
        int total = benches.size() + tables.size() + bbqSites.size() + shelters.size() + theatres.size() + firepits.size() + tents.size();
        int done = 0;

        for (Pt p : benches)   { if (inBounds(p, minX, maxX, minZ, maxZ)) buildBench(p.x, p.z);      done++; progress(done, total); }
        for (Pt p : tables)    { if (inBounds(p, minX, maxX, minZ, maxZ)) buildTable2x2(p.x, p.z);   done++; progress(done, total); }
        for (Pt p : bbqSites)  { if (inBounds(p, minX, maxX, minZ, maxZ)) buildBBQArea(p.x, p.z);    done++; progress(done, total); }
        for (Pt p : shelters)  { if (inBounds(p, minX, maxX, minZ, maxZ)) buildShelter(p.x, p.z);    done++; progress(done, total); }
        for (Pt p : theatres)  { if (inBounds(p, minX, maxX, minZ, maxZ)) buildOpenAirTheatre(p.x, p.z); done++; progress(done, total); }
        for (Pt p : firepits)  { if (inBounds(p, minX, maxX, minZ, maxZ)) buildFirepit(p.x, p.z);    done++; progress(done, total); }
        for (Pt p : tents)     { if (inBounds(p, minX, maxX, minZ, maxZ)) buildTent(p.x, p.z);       done++; progress(done, total); }

        broadcast(level, "LeisureRestGenerator: готово.");
    }

    private void progress(int done, int total) {
        if (total <= 0) return;
        if (done % Math.max(1, total/5) == 0) {
            int pct = (int)Math.round(100.0 * done / Math.max(1, total));
            broadcast(level, "Места отдыха: ~" + pct + "%");
        }
    }

    // === Сбор одной фичи
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = tags(e);
        if (t == null) return;

        if (hasKeyStartsWith(t, "building")) return; // игнор: building*
        String leisure = low(opt(t, "leisure"));
        if ("pitch".equals(leisure) || "playground".equals(leisure)
                || "sports_centre".equals(leisure) || "stadium".equals(leisure) || "track".equals(leisure)) return;

        // дороги для ориентации
        if (isRoadWay(t) && hasGeometry(e)) {
            Polyline pl = new Polyline();
            JsonArray g = geometry(e);
            for (int i=0;i<g.size();i++) {
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                pl.pts.add(new Pt(xz[0], xz[1]));
            }
            if (pl.pts.size() >= 2) roads.add(pl);
            return;
        }

        // узлы/полигоны — приводим к точке (центр/узел)
        Pt pos = toPoint(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        if (pos == null) return;

        String amenity  = low(opt(t, "amenity"));
        String tourism  = low(opt(t, "tourism"));
        String shelterT = low(opt(t, "shelter_type"));
        String outdoor  = low(opt(t, "outdoor_seating"));

        boolean isBench      = "bench".equals(amenity) || "outdoor_seating".equals(leisure) || "yes".equals(outdoor);
        boolean isTable      = "picnic_table".equals(leisure) || "picnic_table".equals(amenity);
        boolean isBBQ        = "bbq".equals(amenity) || "picnic_site".equals(tourism) || "picnic_site".equals(leisure);
        boolean isShelter    = "shelter".equals(amenity) || "picnic_shelter".equals(shelterT) || "shelter".equals(leisure) || "gazebo".equals(low(opt(t,"building")));
        boolean isTheatre    = ("theatre".equals(amenity) && ( "yes".equals(low(opt(t,"open_air"))) || "yes".equals(low(opt(t,"outdoor")))))
                               || "amphitheatre".equals(leisure) || "amphitheater".equals(leisure);
        boolean isFirepit    = "firepit".equals(amenity) || "firepit".equals(leisure) || containsValueLike(t, "campfire") || containsValueLike(t, "firepit");
        boolean isTent       = isTentTag(t);

        if (isBench) benches.add(pos);
        else if (isTable) tables.add(pos);
        else if (isBBQ) bbqSites.add(pos);
        else if (isShelter) shelters.add(pos);
        else if (isTheatre) theatres.add(pos);
        else if (isFirepit) firepits.add(pos);
        else if (isTent) tents.add(pos);
        else if (isAnyLeisureResty(t)) benches.add(pos);
    }

    private static boolean isAnyLeisureResty(JsonObject t) {
        if (t.has("amenity") || t.has("tourism") || t.has("leisure")) {
            String a = low(opt(t,"amenity"));
            String l = low(opt(t,"leisure"));
            String r = low(opt(t,"tourism"));
            if (a != null && (a.contains("rest") || a.contains("bench") || a.contains("bbq") || a.contains("picnic"))) return true;
            if (l != null && (l.contains("picnic") || l.contains("outdoor") || l.contains("amphi"))) return true;
            if (r != null && r.contains("picnic")) return true;
        }
        return false;
    }

    private static boolean isTentTag(JsonObject t) {
        if (t == null) return false;

        String tourism = low(opt(t, "tourism"));
        if ("camp_site".equals(tourism) || "camp_pitch".equals(tourism)) return true;

        String tents = low(opt(t, "tents"));
        if ("yes".equals(tents)) return true;

        // Иногда встречаются уточнения в нестандартных ключах
        String campSite = low(opt(t, "camp_site"));
        if (campSite != null && (campSite.contains("tent") || campSite.contains("pitch"))) return true;

        String campPitch = low(opt(t, "camp_pitch"));
        if (campPitch != null && (campPitch.contains("tent") || campPitch.contains("pitch"))) return true;

        // Мягкий запасной вариант — если в значениях вообще встречается 'tent'
        // (например, source/description с пометкой tent). Оставляем в конце, чтобы не ловить лишнего.
        if (containsValueLike(t, "tent")) return true;

        return false;
    }

    private boolean isRoadWay(JsonObject t) { return t.has("highway") && !t.has("railway") && !t.has("waterway"); }

    // === Постройка элементов ===

    /** Скамейка: две ступени ВПЛОТНУЮ (по перпендикуляру к дороге) + таблички, прикреплённые к бокам ступеней. */
    private void buildBench(int x, int z) {
        Direction roadDir = roadAxisAt(x, z);           // ось дороги
        Direction facing  = roadDir;                    // ступени "смотрят" к дороге
        int[] axis = dirToStep(roadDir);                // (ax, az)
        int[] left = new int[]{-axis[1], axis[0]};      // влево относительно facing

        // Две ступени: в центре и сразу слева (впритык)
        int sx1 = x,            sz1 = z;
        int sx2 = x + left[0],  sz2 = z + left[1];

        placeStairsOnTerrain(sx1, sz1, facing);
        placeStairsOnTerrain(sx2, sz2, facing);

        // Боковые таблички: ставим в соседние клетки С НАРУЖНЫХ сторон и задаём facing так, чтобы опорой была ступень
        // Левый "подлокотник": позиция снаружи слева (x - left). Чтобы опорой была ступень в (x,z), facing должен быть ПРОТИВ left.
        int signLx = x - left[0], signLz = z - left[1];
        placeWallSignOnTerrain(signLx, signLz, stepToDir(-left[0], -left[1]));

        // Правый "подлокотник": позиция снаружи справа (x + 2*left). Опора — ступень в (x+left,z+left). facing = ПО left.
        int signRx = x + left[0] * 2, signRz = z + left[1] * 2;
        placeWallSignOnTerrain(signRx, signRz, stepToDir(left[0], left[1]));
    }
        
    /** Скамейка с явным направлением ступеней  */
    private void buildBenchFacing(int x, int z, Direction facing) {
        // ось поперёк направления сиденья
        int[] axis = dirToStep(facing);
        int[] left = new int[]{-axis[1], axis[0]};

        // две ступени ВПЛОТНУЮ
        int sx1 = x,           sz1 = z;
        int sx2 = x + left[0], sz2 = z + left[1];

        placeStairsOnTerrain(sx1, sz1, facing);
        placeStairsOnTerrain(sx2, sz2, facing);

        // таблички «приклеены» к внешним бокам и смотрят ВНУТРЬ
        int signLx = sx1 - left[0], signLz = sz1 - left[1];                 // слева наружу
        int signRx = sx2 + left[0], signRz = sz2 + left[1];                 // справа наружу
        placeWallSignOnTerrain(signLx, signLz, stepToDir(-left[0], -left[1])); // лицом к центру
        placeWallSignOnTerrain(signRx, signRz, stepToDir( left[0],  left[1])); // лицом к центру
    }

    /** Стол 2×2 из scaffolding (каждый блок — по своему groundY). */
    private void buildTable2x2(int x, int z) {
        placeBlockOnTerrain(x,     z,     TABLE_BLOCK);
        placeBlockOnTerrain(x + 1, z,     TABLE_BLOCK);
        placeBlockOnTerrain(x,     z + 1, TABLE_BLOCK);
        placeBlockOnTerrain(x + 1, z + 1, TABLE_BLOCK);
    }

    /** Беседка: 4 столба h=3 (каждая колонна — от своего ground), крыша из берёзовых досок пирамидой; внутри — скамейка. */
    private void buildShelter(int x, int z) {
        // Опоры на смещениях ±3 от центра, каждая колонна идёт на 3 блока выше локального ground
        int[] offs = new int[]{-3, 3};
        for (int ox : offs) for (int oz : offs) {
            int cx = x + ox, cz = z + oz;
            int base = groundY(cx, cz) + 1;
            for (int h = 0; h < 3; h++) setBlock(cx, base + h, cz, SHELTER_POST);
        }

        // Крыша: четыре слоя (радиусы 3,2,1,0) из полных блоков досок, каждый блок — на (groundY(x,z)+1+offset)
        placeBlockSquareFollowTerrain(x, z, 3, 3, SHELTER_ROOF_BLOCK);
        placeBlockSquareFollowTerrain(x, z, 2, 4, SHELTER_ROOF_BLOCK);
        placeBlockSquareFollowTerrain(x, z, 1, 5, SHELTER_ROOF_BLOCK);
        placeBlockSquareFollowTerrain(x, z, 0, 6, SHELTER_ROOF_BLOCK);

        // Две скамейки в центре под навесом, друг напротив друга, между ними 1 блок.
        // Ось берём по ближайшей дороге, чтобы компоновка выглядела аккуратно.
        Direction axisDir = roadAxisAt(x, z);
        int[] axis = dirToStep(axisDir);

        int ax = axis[0], az = axis[1];
        // Для ступенек чтобы визуально смотреть К центру — даём обратный facing
        Direction faceA = stepToDir(-ax, -az); // лавка A (слева) развёрнута к центру
        buildBenchFacing(x - ax, z - az, faceA);

        Direction faceB = stepToDir(+ax, +az); // лавка B (справа) развёрнута к центру
        buildBenchFacing(x + ax, z + az, faceB);
    }

    /** BBQ/пикник-уголок. Все блоки — по локальному ground. */
    private void buildBBQArea(int x, int z) {
        Direction axisDir = roadAxisAt(x, z);
        int[] axis = dirToStep(axisDir);
        int[] nor  = new int[]{-axis[1], axis[0]};

        // Две лавки напротив (между ними ~3 блока по оси), ОБЕ «смотрят» В ЦЕНТР
        int bx1 = x - (axis[0] * 2), bz1 = z - (axis[1] * 2);
        int bx2 = x + (axis[0] * 2), bz2 = z + (axis[1] * 2);

        // Разворачиваем ступени К центру (для stairs это обратное направление)
        Direction f1 = stepToDir(bx1 - x, bz1 - z);
        Direction f2 = stepToDir(bx2 - x, bz2 - z);

        buildBenchFacing(bx1, bz1, f1);
        buildBenchFacing(bx2, bz2, f2);

        // Два scaffolding между лавками (по центру)
        placeBlockOnTerrain(x,           z,           TABLE_BLOCK);
        placeBlockOnTerrain(x + nor[0],  z + nor[1],  TABLE_BLOCK);

        // Печь, сундук, верстак — в ряд перпендикулярно scaffolding, в 1 блоке сбоку
        int rx = x + (nor[0] * 2), rz = z + (nor[1] * 2);
        placeBlockOnTerrain(rx,                       rz,                       BBQ_FURNACE);
        placeBlockOnTerrain(rx + axis[0],             rz + axis[1],             BBQ_CHEST);
        placeBlockOnTerrain(rx + axis[0] * 2,         rz + axis[1] * 2,         BBQ_WORKBENCH);
    }

    /** Open-air театр: две параллельные “линии” по 2 лавки; лавки ориентированы к дороге. */
    private void buildOpenAirTheatre(int x, int z) {
        Direction axisDir = roadAxisAt(x, z);
        int[] axis = dirToStep(axisDir);
        int[] nor  = new int[]{-axis[1], axis[0]};

        // Ряд 1
        buildBench(x - axis[0], z - axis[1]);
        buildBench(x - axis[0] + nor[0]*2, z - axis[1] + nor[1]*2);

        // Ряд 2
        buildBench(x + axis[0], z + axis[1]);
        buildBench(x + axis[0] - nor[0]*2, z + axis[1] - nor[1]*2);
    }

    /** Кострище 10×10 + CAMPFIRE по центру. Каждая плитка — по своему ground. */
    private void buildFirepit(int x, int z) {
        int half = 5; // 10×10
        for (int dx = -half; dx < half; dx++) {
            for (int dz = -half; dz < half; dz++) {
                placeBlockOnTerrain(x + dx, z + dz, FIRE_PIT_FLOOR);
            }
        }
        placeBlockOnTerrain(x, z, FIRE_PIT_CENTER);
    }


    private void buildTent(int x, int z) {
        // Ориентация по дороге
        Direction axisDir = roadAxisAt(x, z);      // вдоль дороги
        int[] axis = dirToStep(axisDir);           // (ax, az)
        int[] nor  = new int[]{-axis[1], axis[0]}; // нормаль к дороге

        // --- 1) Снимаем СЛЕПОК исходного рельефа для всех клеток палатки ДО установки блоков
        int[] offsetsAll = new int[]{-2, +2, -1, +1, 0}; // все потенциальные линии
        java.util.HashMap<Long, Integer> base = new java.util.HashMap<>();

        for (int off : offsetsAll) {
            for (int i = -1; i <= 2; i++) { // 4 блока вдоль оси: -1,0,1,2
                int bx = x + axis[0] * i + nor[0] * off;
                int bz = z + axis[1] * i + nor[1] * off;
                int gy = groundY(bx, bz);                 // читаем ТОЛЬКО исходный рельеф на этот тик
                if (gy == Integer.MIN_VALUE) continue;
                base.put(pack(bx, bz), gy);
            }
        }

        // --- 2) Вспомогалка: ставим линию из 4 блоков, пользуясь базовой высотой из "base"
        java.util.function.BiConsumer<Integer, Integer> placeLineAtOffsetAndLayer = (off, layer) -> {
            for (int i = -1; i <= 2; i++) {
                int bx = x + axis[0] * i + nor[0] * off;
                int bz = z + axis[1] * i + nor[1] * off;
                Integer gy = base.get(pack(bx, bz));
                if (gy == null) continue; // вне рельефной сетки — пропускаем
                setBlock(bx, gy + 1 + layer, bz, TENT_BLOCK); // НЕ звоним в groundY второй раз
            }
        };

        // --- 3) «Объёмная Л»: 2 линии (нижний слой), 2 линии (средний), 1 центральная (верхний)
        placeLineAtOffsetAndLayer.accept(-2, 0);
        placeLineAtOffsetAndLayer.accept(+2, 0);

        placeLineAtOffsetAndLayer.accept(-1, 1);
        placeLineAtOffsetAndLayer.accept(+1, 1);

        placeLineAtOffsetAndLayer.accept(0, 2);
    }

    @SuppressWarnings("unused")
    /** Поставить блок на (groundY(x,z) + 1 + layerOffset). */
    private void placeBlockOnTerrainLayer(int x, int z, Block b, int layerOffset) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        setBlock(x, y + 1 + layerOffset, z, b);
    }

    // === низкоуровневые строительные примитивы ===

    private void placeStairsOnTerrain(int x, int z, Direction facing) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        BlockState st = BENCH_STAIRS.defaultBlockState();
        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        // нижняя половина (не перевёрнутые ступени)
        if (st.hasProperty(BlockStateProperties.HALF)) {
            st = st.setValue(BlockStateProperties.HALF, Half.BOTTOM);
        }
        level.setBlock(new BlockPos(x, y + 1, z), st, 3);
    }

    private void placeWallSignOnTerrain(int x, int z, Direction facing) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        BlockState st = BENCH_END_SIGN.defaultBlockState();
        if (st.getBlock() instanceof WallSignBlock && st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        level.setBlock(new BlockPos(x, y + 1, z), st, 3);
    }

    private void placeBlockSquareFollowTerrain(int cx, int cz, int r, int offsetAboveGround, Block block) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                int y = groundY(x, z);
                if (y == Integer.MIN_VALUE) continue;
                level.setBlock(new BlockPos(x, y + 1 + offsetAboveGround, z), block.defaultBlockState(), 3);
            }
        }
    }

    private void placeBlockOnTerrain(int x, int z, Block b) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, y + 1, z), b.defaultBlockState(), 3);
    }

    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }

    // === ориентация по ближайшей дороге ===

    private Direction roadAxisAt(int x, int z) {
        if (roads.isEmpty()) return Direction.EAST;

        double bestD2 = Double.MAX_VALUE;
        int vx = 1, vz = 0;

        for (Polyline pl : roads) {
            for (int i=0;i<pl.pts.size()-1;i++) {
                Pt a = pl.pts.get(i);
                Pt b = pl.pts.get(i+1);
                int dx = b.x - a.x, dz = b.z - a.z;
                double len2 = (double)dx*dx + (double)dz*dz;
                if (len2 <= 1e-6) continue;
                double t = ((x - a.x)*dx + (z - a.z)*dz) / len2;
                if (t < 0) t = 0; else if (t > 1) t = 1;
                double px = a.x + t*dx;
                double pz = a.z + t*dz;
                double ddx = x - px;
                double ddz = z - pz;
                double d2 = ddx*ddx + ddz*ddz;
                if (d2 < bestD2) { bestD2 = d2; vx = dx; vz = dz; }
            }
        }
        if (Math.abs(vx) >= Math.abs(vz)) return (vx >= 0) ? Direction.EAST : Direction.WEST;
        return (vz >= 0) ? Direction.SOUTH : Direction.NORTH;
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

    private static Direction stepToDir(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return (dx >= 0) ? Direction.EAST : Direction.WEST;
        return (dz >= 0) ? Direction.SOUTH : Direction.NORTH;
    }

    /** Упаковка (x,z) в long-ключ для HashMap. */
    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    // === преобразования/утилиты данных ===

    private Pt toPoint(JsonObject e,
                       double centerLat, double centerLng,
                       double east, double west, double north, double south,
                       int sizeMeters, int centerX, int centerZ) {
        String type = opt(e, "type");
        if ("node".equals(type)) {
            if (e.has("lat") && e.has("lon")) {
                int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                return new Pt(xz[0], xz[1]);
            }
            JsonArray g = geometry(e);
            if (g != null && g.size() > 0) {
                JsonObject p = g.get(0).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                return new Pt(xz[0], xz[1]);
            }
            return null;
        }
        if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = geometry(e);
            if (g == null || g.size() == 0) return null;
            long sx = 0, sz = 0;
            for (int i=0;i<g.size();i++) {
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                sx += xz[0]; sz += xz[1];
            }
            int cx = (int)Math.round(sx / (double)g.size());
            int cz = (int)Math.round(sz / (double)g.size());
            return new Pt(cx, cz);
        }
        return null;
    }

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

    private static boolean inBounds(Pt p, int minX, int maxX, int minZ, int maxZ) {
        return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ;
    }

    private static JsonObject tags(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }

    private static boolean hasKeyStartsWith(JsonObject t, String prefix) {
        for (String k : t.keySet()) if (k.toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
        return false;
    }

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

    private static String opt(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private static boolean hasGeometry(JsonObject e) {
        return e.has("geometry") && e.get("geometry").isJsonArray() && e.getAsJsonArray("geometry").size() >= 2;
    }

    private static JsonArray geometry(JsonObject e) {
        return (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
    }

    private static JsonArray safeElementsArray(JsonObject coords) {
        if (coords == null) return null;
        if (!coords.has("features")) return null;
        JsonObject f = coords.getAsJsonObject("features");
        if (f == null || !f.has("elements")) return null;
        return f.getAsJsonArray("elements");
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