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
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class SportsFacilitiesGenerator {

    // Материалы
    private static final Block WALL_DIORITE   = Blocks.DIORITE_WALL;
    private static final Block NET_COBWEB     = Blocks.COBWEB;
    private static final Block QUARTZ_BLOCK   = Blocks.QUARTZ_BLOCK;
    private static final Block FENCE_BIRCH    = Blocks.BIRCH_FENCE;
    private static final Block TARGET_BLOCK   = Blocks.TARGET;
    private static final Block IRON_BLOCK     = Blocks.IRON_BLOCK;
    private static final Block OAK_FENCE      = Blocks.OAK_FENCE;
    private static final Block RED_BANNER     = Blocks.RED_BANNER;

    // Геометрия
    private static final class Pt { final int x,z; Pt(int x,int z){this.x=x; this.z=z;} }
    private static final class Rect {
        final int minX,maxX,minZ,maxZ, cx,cz, wX,wZ;
        final List<Pt> poly;
        Rect(int minX,int maxX,int minZ,int maxZ,List<Pt> poly){
            this.minX=minX; this.maxX=maxX; this.minZ=minZ; this.maxZ=maxZ; this.poly = poly;
            this.cx = (int)Math.round((minX+maxX)/2.0);
            this.cz = (int)Math.round((minZ+maxZ)/2.0);
            this.wX = Math.max(0, maxX-minX+1);
            this.wZ = Math.max(0, maxZ-minZ+1);
        }
        boolean longAxisIsX(){ return wX >= wZ; }
        boolean valid(){ return wX>=3 && wZ>=3; }
        boolean contains(int x, int z){
            if (poly != null && poly.size() >= 3) return pointInPolygon(poly, x, z);
            return x>=minX && x<=maxX && z>=minZ && z<=maxZ;
        }
        private static boolean pointInPolygon(List<Pt> poly, int x, int z){
            boolean inside = false;
            for (int i=0, j=poly.size()-1; i<poly.size(); j=i++){
                Pt pi = poly.get(i), pj = poly.get(j);
                if (((pi.z>z) != (pj.z>z)) &&
                    (x < (long)(pj.x - pi.x) * (z - pi.z) / (double)(pj.z - pi.z) + pi.x)) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    private final List<Rect> soccerPitches      = new ArrayList<>();
    private final List<Rect> basketballPitches  = new ArrayList<>();
    private final List<Rect> tennisPitches      = new ArrayList<>();
    private final List<Rect> volleyPitches      = new ArrayList<>();
    private final List<Rect> shootingRanges     = new ArrayList<>();
    private final List<Rect> fitnessZones       = new ArrayList<>();
    private final List<Rect> multiPitches       = new ArrayList<>();
    private final List<Rect> fallbackPitches    = new ArrayList<>();
    private final List<Pt>   golfPins           = new ArrayList<>();

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public SportsFacilitiesGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
        if (sourceIndex == null) { broadcast(level, "SportsFacilities: no source data — skipping."); return; }

        JsonObject center = sourceIndex.getAsJsonObject("center");
        JsonObject bbox   = sourceIndex.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "SportsFacilities: no center/bbox — skipping."); return; }

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

        // === 1) Сбор
        try {
            if (store != null) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) collect(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            } else {
                JsonArray elements = safeElementsArray(coords);
                if (elements == null) { broadcast(level, "SportsFacilities: features.elements are empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "SportsFacilities: error reading features: " + ex.getMessage());
        }

        // === 2) Постройка
        int total = soccerPitches.size() + basketballPitches.size() + tennisPitches.size() + volleyPitches.size()
                  + shootingRanges.size() + fitnessZones.size() + multiPitches.size() + fallbackPitches.size()
                  + golfPins.size();
        int done = 0;

        for (Rect r : soccerPitches)     { if (inBounds(r, minX, maxX, minZ, maxZ)) buildSoccerGoals(r);         progress(++done, total); }
        for (Rect r : basketballPitches) { if (inBounds(r, minX, maxX, minZ, maxZ)) buildBasketballHoops(r);     progress(++done, total); }
        for (Rect r : tennisPitches)     { if (inBounds(r, minX, maxX, minZ, maxZ)) buildTennisNet(r);           progress(++done, total); }
        for (Rect r : volleyPitches)     { if (inBounds(r, minX, maxX, minZ, maxZ)) buildVolleyballNet(r);       progress(++done, total); }
        for (Rect r : shootingRanges)    { if (inBounds(r, minX, maxX, minZ, maxZ)) buildShootingRange(r);       progress(++done, total); }
        for (Rect r : fitnessZones)      { if (inBounds(r, minX, maxX, minZ, maxZ)) buildFitnessZone(r);         progress(++done, total); }
        for (Rect r : multiPitches)      { if (inBounds(r, minX, maxX, minZ, maxZ)) { buildSoccerGoals(r); buildVolleyballNet(r); } progress(++done, total); }
        for (Rect r : fallbackPitches)   { if (inBounds(r, minX, maxX, minZ, maxZ)) buildVolleyballNet(r);       progress(++done, total); }
        for (Pt pin : golfPins)          { if (inBounds(pin, minX, maxX, minZ, maxZ)) buildGolfFlag(pin.x, pin.z); progress(++done, total); }

        broadcast(level, "SportsFacilities: done.");
    }

    private void progress(int done, int total) {
        if (total <= 0) return;
        if (done % Math.max(1, total/5) == 0) {
            int pct = (int)Math.round(100.0 * done / Math.max(1, total));
            broadcast(level, "Sports facilities: ~" + pct + "%");
        }
    }

    // === Сбор
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = tags(e);
        if (t == null) return;

        String leisure = low(opt(t,"leisure"));
        if ("playground".equals(leisure)) return;

        // Гольф лунки (узлы)
        String golf = low(opt(t,"golf"));
        if (golf != null && (golf.contains("pin") || golf.contains("hole") || golf.contains("flag"))) {
            Pt p = toPointNodeOnly(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (p != null) golfPins.add(p);
            return;
        }

        // Полигоны/линии спортивных площадок
        if (("pitch".equals(leisure) || "sports_centre".equals(leisure) || "track".equals(leisure) || "stadium".equals(leisure))
                && hasGeometry(e)) {

            Rect r = toRectWithPoly(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (r == null || !r.valid()) return;

            String sport = low(opt(t,"sport"));
            if (sport == null) sport = "";

            if (sport.contains("soccer") || sport.contains("football") || sport.contains("futsal")) { soccerPitches.add(r); return; }
            if (sport.contains("basketball") || sport.contains("streetball")) { basketballPitches.add(r); return; }
            if (sport.contains("tennis")) { tennisPitches.add(r); return; }
            if (sport.contains("volleyball") || sport.contains("beachvolleyball") || sport.contains("badminton")) { volleyPitches.add(r); return; }
            if (sport.contains("shoot") || sport.contains("shooting") || containsKeyLike(t, "shoot")) { shootingRanges.add(r); return; }
            if ("fitness_station".equals(leisure) || sport.contains("fitness") || containsValueLike(t,"fitness")) { fitnessZones.add(r); return; }
            if (sport.contains("multi")) { multiPitches.add(r); return; }

            fallbackPitches.add(r);
        } else if ("fitness_station".equals(leisure)) {
            Pt p = toPoint(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (p != null) fitnessZones.add(new Rect(p.x-3, p.x+3, p.z-3, p.z+3, null));
        }
    }

    // === Постройка

    /** Футбол: ворота строго у узких сторон и В ЦЕНТРЕ соответствующей короткой линии. */
    private void buildSoccerGoals(Rect r) {
        boolean longX = r.longAxisIsX();
        if (longX) {
            // Узкие стороны = x=minX/maxX, центр берём по реальной кромке полигона (а не по сечению «+1»).
            int czMinEdge = centerZOnXEdge(r, r.minX);
            int czMaxEdge = centerZOnXEdge(r, r.maxX);
            buildOneGoalAtLineZSpan(czMinEdge, r.minX + 1, +1); // смотреть к +X (в центр)
            buildOneGoalAtLineZSpan(czMaxEdge, r.maxX - 1, -1); // смотреть к -X
        } else {
            // Узкие стороны = z=minZ/maxZ
            int cxMinEdge = centerXOnZEdge(r, r.minZ);
            int cxMaxEdge = centerXOnZEdge(r, r.maxZ);
            buildOneGoalAtLineXSpan(cxMinEdge, r.minZ + 1, +1); // смотреть к +Z
            buildOneGoalAtLineXSpan(cxMaxEdge, r.maxZ - 1, -1); // смотреть к -Z
        }
    }

    // Ворота, перекладина вдоль X (линия по X). depthDirToCenter по Z: +1 если центр на +Z, -1 если на -Z.
    private void buildOneGoalAtLineXSpan(int cX, int zGoal, int depthDirToCenter) {
        int px1 = cX - 2, px2 = cX + 2;
        placeColumn(px1, zGoal, WALL_DIORITE, 3);
        placeColumn(px2, zGoal, WALL_DIORITE, 3);
        for (int dx = -1; dx <= 1; dx++) placeAtHeightOffset(cX + dx, zGoal, WALL_DIORITE, 2);
        int zNet = zGoal - depthDirToCenter;
        for (int dx = -2; dx <= 2; dx++) for (int h = 0; h < 3; h++) placeAtHeightOffset(cX + dx, zNet, NET_COBWEB, h);
    }

    // Ворота, перекладина вдоль Z (линия по Z). depthDirToCenter по X: +1 если центр на +X, -1 если на -X.
    private void buildOneGoalAtLineZSpan(int cZ, int xGoal, int depthDirToCenter) {
        int pz1 = cZ - 2, pz2 = cZ + 2;
        placeColumn(xGoal, pz1, WALL_DIORITE, 3);
        placeColumn(xGoal, pz2, WALL_DIORITE, 3);
        for (int dz = -1; dz <= 1; dz++) placeAtHeightOffset(xGoal, cZ + dz, WALL_DIORITE, 2);
        int xNet = xGoal - depthDirToCenter;
        for (int dz = -2; dz <= 2; dz++) for (int h = 0; h < 3; h++) placeAtHeightOffset(xNet, cZ + dz, NET_COBWEB, h);
    }

    /** Баскетбол: кольца у коротких сторон, центрированные по линии, паутиной к центру. */
    private void buildBasketballHoops(Rect r) {
        boolean longX = r.longAxisIsX();
        if (longX) {
            // Короткие стороны по X: x=minX/maxX, центр вдоль Z по кромке
            int cZMin = centerZOnXEdge(r, r.minX);
            int cZMax = centerZOnXEdge(r, r.maxX);
            buildOneHoopOnXEdge(cZMin, r.minX + 1, +1); // смотреть к +X (в центр)
            buildOneHoopOnXEdge(cZMax, r.maxX - 1, -1); // смотреть к -X
        } else {
            // Короткие стороны по Z: z=minZ/maxZ, центр вдоль X по кромке
            int cXMin = centerXOnZEdge(r, r.minZ);
            int cXMax = centerXOnZEdge(r, r.maxZ);
            buildOneHoopOnZEdge(cXMin, r.minZ + 1, +1); // смотреть к +Z
            buildOneHoopOnZEdge(cXMax, r.maxZ - 1, -1); // смотреть к -Z
        }
    }

    // Кольцо на стороне X=const, ориентировано в сторону центра по X.
    private void buildOneHoopOnXEdge(int cZ, int xHoop, int dirXToCenter) {
        placeColumn(xHoop, cZ, WALL_DIORITE, 3);                 // стойка/стоймоста
        placeAtHeightOffset(xHoop, cZ, QUARTZ_BLOCK, 3);         // щит
        placeAtHeightOffset(xHoop + dirXToCenter, cZ, NET_COBWEB, 3); // "кольцо/сетка" на блок ближе к центру
    }

    // Кольцо на стороне Z=const, ориентировано в сторону центра по Z.
    private void buildOneHoopOnZEdge(int cX, int zHoop, int dirZToCenter) {
        placeColumn(cX, zHoop, WALL_DIORITE, 3);
        placeAtHeightOffset(cX, zHoop, QUARTZ_BLOCK, 3);
        placeAtHeightOffset(cX, zHoop + dirZToCenter, NET_COBWEB, 3);
    }

    /** Теннис (по полу) */
    private void buildTennisNet(Rect r) {
        boolean longX = r.longAxisIsX();
        if (longX) {
            int z = clampCenterLine(r.cz, r.minZ+1, r.maxZ-1);
            placeAtHeightOffsetInside(r, r.cx, z - 3, WALL_DIORITE, 0);
            placeAtHeightOffsetInside(r, r.cx, z + 3, WALL_DIORITE, 0);
            for (int dz = -2; dz <= 2; dz++) placeAtHeightOffsetInside(r, r.cx, z + dz, NET_COBWEB, 0);
        } else {
            int x = clampCenterLine(r.cx, r.minX+1, r.maxX-1);
            placeAtHeightOffsetInside(r, x - 3, r.cz, WALL_DIORITE, 0);
            placeAtHeightOffsetInside(r, x + 3, r.cz, WALL_DIORITE, 0);
            for (int dx = -2; dx <= 2; dx++) placeAtHeightOffsetInside(r, x + dx, r.cz, NET_COBWEB, 0);
        }
    }

    /** Волейбол/бадминтон (поднятая сеть) */
    private void buildVolleyballNet(Rect r) {
        boolean longX = r.longAxisIsX();
        if (longX) {
            int z = clampCenterLine(r.cz, r.minZ+1, r.maxZ-1);
            placeColumnInside(r, r.cx, z - 3, WALL_DIORITE, 2);
            placeColumnInside(r, r.cx, z + 3, WALL_DIORITE, 2);
            for (int dz = -2; dz <= 2; dz++) placeAtHeightOffsetInside(r, r.cx, z + dz, NET_COBWEB, 1);
        } else {
            int x = clampCenterLine(r.cx, r.minX+1, r.maxX-1);
            placeColumnInside(r, x - 3, r.cz, WALL_DIORITE, 2);
            placeColumnInside(r, x + 3, r.cz, WALL_DIORITE, 2);
            for (int dx = -2; dx <= 2; dx++) placeAtHeightOffsetInside(r, x + dx, r.cz, NET_COBWEB, 1);
        }
    }

    /** Гольф: красный баннер */
    private void buildGolfFlag(int x, int z) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, y + 1, z), RED_BANNER.defaultBlockState(), 3);
    }

    /** Стрельбище */
    private void buildShootingRange(Rect r) {
        boolean longX = r.longAxisIsX();
        if (longX) {
            int z = clampCenterLine(r.cz, r.minZ+1, r.maxZ-1);
            for (int i = -2; i <= 2; i++) {
                int x = clampInside(r, r.cx + i, z)[0];
                placeColumnInside(r, x, z, FENCE_BIRCH, 1);
                placeAtHeightOffsetInside(r, x, z, TARGET_BLOCK, 1);
            }
        } else {
            int x = clampCenterLine(r.cx, r.minX+1, r.maxX-1);
            for (int i = -2; i <= 2; i++) {
                int z = clampInside(r, x, r.cz + i)[1];
                placeColumnInside(r, x, z, FENCE_BIRCH, 1);
                placeAtHeightOffsetInside(r, x, z, TARGET_BLOCK, 1);
            }
        }
    }

    /** Фитнес: две конструкции, лестницы/рычаги — инвертированные направления */
    private void buildFitnessZone(Rect r) {
        int cx = r.cx, cz = r.cz;

        // Куб 2x2x2
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            placeColumn(cx + dx, cz + dz, IRON_BLOCK, 2);
        }

        // Рычаги — инверт
        placeLeverWall(cx,   cz-1, Direction.NORTH);
        placeLeverWall(cx+1, cz-1, Direction.NORTH);
        placeLeverWall(cx,   cz+2, Direction.SOUTH);
        placeLeverWall(cx+1, cz+2, Direction.SOUTH);
        placeLeverWall(cx-1, cz,   Direction.WEST);
        placeLeverWall(cx-1, cz+1, Direction.WEST);
        placeLeverWall(cx+2, cz,   Direction.EAST);
        placeLeverWall(cx+2, cz+1, Direction.EAST);

        boolean longX = r.longAxisIsX();
        if (longX) {
            int z1 = cz - 1, z2 = cz + 1;
            for (int dx = -2; dx <= 2; dx++) {
                placeColumn(cx + dx, z1, IRON_BLOCK, 3);
                placeColumn(cx + dx, z2, IRON_BLOCK, 3);
            }
            // Лестницы — инверт
            for (int dx = -2; dx <= 2; dx++) placeLadderWall(cx + dx, z1 - 1, Direction.NORTH, 3);
            for (int dx = -2; dx <= 2; dx++) placeLadderWall(cx + dx, z2 + 1, Direction.SOUTH, 3);
            placeLadderWall(cx - 3, z1, Direction.WEST, 3);
            placeLadderWall(cx + 3, z1, Direction.EAST, 3);
            placeLadderWall(cx - 3, z2, Direction.WEST, 3);
            placeLadderWall(cx + 3, z2, Direction.EAST, 3);

            for (int dx = -2; dx <= 2; dx++) {
                placeFenceRoof(cx + dx, z1, 3);
                placeFenceRoof(cx + dx, cz,  3);
                placeFenceRoof(cx + dx, z2,  3);
            }
        } else {
            int x1 = cx - 1, x2 = cx + 1;
            for (int dz = -2; dz <= 2; dz++) {
                placeColumn(x1, cz + dz, IRON_BLOCK, 3);
                placeColumn(x2, cz + dz, IRON_BLOCK, 3);
            }
            // Лестницы — инверт
            for (int dz = -2; dz <= 2; dz++) placeLadderWall(x1 - 1, cz + dz, Direction.WEST, 3);
            for (int dz = -2; dz <= 2; dz++) placeLadderWall(x2 + 1, cz + dz, Direction.EAST, 3);
            placeLadderWall(x1, cz - 3, Direction.NORTH, 3);
            placeLadderWall(x1, cz + 3, Direction.SOUTH, 3);
            placeLadderWall(x2, cz - 3, Direction.NORTH, 3);
            placeLadderWall(x2, cz + 3, Direction.SOUTH, 3);

            for (int dz = -2; dz <= 2; dz++) {
                placeFenceRoof(x1, cz + dz, 3);
                placeFenceRoof(cx,  cz + dz, 3);
                placeFenceRoof(x2, cz + dz, 3);
            }
        }
    }

    // === низкоуровневые примитивы + кламп внутрь ===

    /** Поставить блок на высоте ground+1+offset. */
    private void placeAtHeightOffset(int x, int z, Block b, int offsetAboveGround) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, y + 1 + offsetAboveGround, z), b.defaultBlockState(), 3);
    }
    /** Вертикальная колонна h блоков. */
    private void placeColumn(int x, int z, Block b, int height) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        for (int i = 0; i < height; i++) {
            level.setBlock(new BlockPos(x, y + 1 + i, z), b.defaultBlockState(), 3);
        }
    }

    // версии с контролем «внутри полигона»
    private void placeAtHeightOffsetInside(Rect r, int x, int z, Block b, int offset) {
        int[] p = clampInside(r, x, z);
        placeAtHeightOffset(p[0], p[1], b, offset);
    }
    private void placeColumnInside(Rect r, int x, int z, Block b, int height) {
        int[] p = clampInside(r, x, z);
        placeColumn(p[0], p[1], b, height);
    }

    /** Лестница: ставим в клетку СНАРУЖИ, HORIZONTAL_FACING = куда «смотрит». */
    private void placeLadderWall(int x, int z, Direction facing, int height) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        for (int i = 0; i < height; i++) {
            BlockState st = Blocks.LADDER.defaultBlockState();
            if (st.getBlock() instanceof LadderBlock && st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
            level.setBlock(new BlockPos(x, y + 1 + i, z), st, 3);
        }
    }

    /** Рычаг: ATTACH_FACE=WALL, HORIZONTAL_FACING = куда «смотрит». */
    private void placeLeverWall(int x, int z, Direction facing) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        BlockState st = Blocks.LEVER.defaultBlockState();
        if (st.getBlock() instanceof LeverBlock) {
            if (st.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                st = st.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL);
            }
            if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        level.setBlock(new BlockPos(x, y + 1, z), st, 3);
    }

    /** Крыша из забора. */
    private void placeFenceRoof(int x, int z, int offsetAboveGround) {
        placeAtHeightOffset(x, z, OAK_FENCE, offsetAboveGround);
    }

    // «втягивание внутрь» полигона: шагами к центру
    private int[] clampInside(Rect r, int x, int z) {
        if (r.contains(x, z)) return new int[]{x, z};
        int nx = x, nz = z;
        for (int i=0;i<8;i++){
            if (nx < r.cx) nx++; else if (nx > r.cx) nx--;
            if (nz < r.cz) nz++; else if (nz > r.cz) nz--;
            if (r.contains(nx, nz)) break;
        }
        nx = Math.max(r.minX+1, Math.min(r.maxX-1, nx));
        nz = Math.max(r.minZ+1, Math.min(r.maxZ-1, nz));
        return new int[]{nx, nz};
    }
    private int clampCenterLine(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    // === точное центрирование по КОРОТКОЙ КРОМКЕ полигона ===

    /** Центр Z на кромке полигона x = sideX (вертикальная короткая сторона). */
    private int centerZOnXEdge(Rect r, int sideX) {
        if (r.poly == null || r.poly.size() < 3) return r.cz;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        List<Pt> poly = r.poly;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            Pt a = poly.get(j), b = poly.get(i);
            int ax = a.x, bx = b.x;
            int az = a.z, bz = b.z;

            if (ax == sideX && bx == sideX) {
                // вертикальный отрезок по самой кромке
                if (az < minZ) minZ = az; if (az > maxZ) maxZ = az;
                if (bz < minZ) minZ = bz; if (bz > maxZ) maxZ = bz;
            } else if ((ax <= sideX && bx >= sideX) || (ax >= sideX && bx <= sideX)) {
                if (ax == bx) continue; // параллельный случай обработан выше
                double t = (sideX - ax) / (double)(bx - ax);
                double z = az + t * (bz - az);
                int zi = (int)Math.round(z);
                if (zi < minZ) minZ = zi;
                if (zi > maxZ) maxZ = zi;
            }
        }
        if (minZ == Integer.MAX_VALUE) return r.cz;
        return (int)Math.round((minZ + maxZ) / 2.0);
    }

    /** Центр X на кромке полигона z = sideZ (горизонтальная короткая сторона). */
    private int centerXOnZEdge(Rect r, int sideZ) {
        if (r.poly == null || r.poly.size() < 3) return r.cx;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        List<Pt> poly = r.poly;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            Pt a = poly.get(j), b = poly.get(i);
            int ax = a.x, bx = b.x;
            int az = a.z, bz = b.z;

            if (az == sideZ && bz == sideZ) {
                // горизонтальный отрезок по самой кромке
                if (ax < minX) minX = ax; if (ax > maxX) maxX = ax;
                if (bx < minX) minX = bx; if (bx > maxX) maxX = bx;
            } else if ((az <= sideZ && bz >= sideZ) || (az >= sideZ && bz <= sideZ)) {
                if (az == bz) continue; // параллельный случай обработан выше
                double t = (sideZ - az) / (double)(bz - az);
                double x = ax + t * (bx - ax);
                int xi = (int)Math.round(x);
                if (xi < minX) minX = xi;
                if (xi > maxX) maxX = xi;
            }
        }
        if (minX == Integer.MAX_VALUE) return r.cx;
        return (int)Math.round((minX + maxX) / 2.0);
    }

    // === утилиты JSON/OSM ===

    private static boolean inBounds(Rect r, int minX, int maxX, int minZ, int maxZ) {
        return r.maxX >= minX && r.minX <= maxX && r.maxZ >= minZ && r.minZ <= maxZ;
    }
    private static boolean inBounds(Pt p, int minX, int maxX, int minZ, int maxZ) {
        return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ;
    }

    private Rect toRectWithPoly(JsonObject e,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {
        JsonArray g = geometry(e);
        if (g == null || g.size() == 0) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        List<Pt> poly = new ArrayList<>();
        for (int i=0;i<g.size();i++) {
            JsonObject p = g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            minX = Math.min(minX, xz[0]); minZ = Math.min(minZ, xz[1]);
            maxX = Math.max(maxX, xz[0]); maxZ = Math.max(maxZ, xz[1]);
            poly.add(new Pt(xz[0], xz[1]));
        }
        return new Rect(minX, maxX, minZ, maxZ, poly);
    }

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

    private Pt toPointNodeOnly(JsonObject e,
                               double centerLat, double centerLng,
                               double east, double west, double north, double south,
                               int sizeMeters, int centerX, int centerZ) {
        if (!"node".equals(opt(e,"type"))) return null;
        if (e.has("lat") && e.has("lon")) {
            int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            return new Pt(xz[0], xz[1]);
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

    // === JSON/OSM утилиты ===

    private static JsonObject tags(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }
    private static boolean hasGeometry(JsonObject e) {
        return e.has("geometry") && e.get("geometry").isJsonArray() && e.getAsJsonArray("geometry").size() >= 2;
    }
    private static JsonArray geometry(JsonObject e) {
        return (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
    }
    private static String opt(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private static boolean containsKeyLike(JsonObject t, String kPart) {
        String n = kPart.toLowerCase(Locale.ROOT);
        for (String k : t.keySet()) if (k.toLowerCase(Locale.ROOT).contains(n)) return true;
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