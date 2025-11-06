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
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class ElectricChargerGenerator {

    private static final int SEARCH_RADIUS_BLOCKS = 120;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public ElectricChargerGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store  = store;
    }

    // --- широковещалка
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

    // ===== типы =====
    private static final class RoadSeg {
        final int x1,z1,x2,z2;
        RoadSeg(int x1,int z1,int x2,int z2){this.x1=x1;this.z1=z1;this.x2=x2;this.z2=z2;}
    }
    private static final class Charger {
        final int x,z;
        Direction lengthDir;  // вдоль дороги
        int ux, uz;           // ед. вектор вдоль lengthDir
        Direction sideA, sideB; // широкие стороны (перпендикуляр)
        int sxA, szA, sxB, szB;
        Charger(int x,int z){this.x=x; this.z=z;}
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "ElectricChargerGenerator: coords == null — skipping.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "ElectricChargerGenerator: no center/bbox — skipping.");
            return;
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
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        List<Charger> chargers = new ArrayList<>();
        List<RoadSeg> roads = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, chargers, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "ElectricChargerGenerator: no coords.features — skipping.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "ElectricChargerGenerator: features.elements is empty — skipping.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), chargers, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "ElectricChargerGenerator: error reading features: " + ex.getMessage());
        }

        if (chargers.isEmpty()) {
            broadcast(level, "ElectricChargerGenerator: amenity=charging_station not found — done.");
            return;
        }

        // Ориентация вдоль ближайшей дороги
        for (Charger ch : chargers) {
            Direction dir = nearestRoadDirection(roads, ch.x, ch.z, SEARCH_RADIUS_BLOCKS);
            if (dir == null) dir = Direction.EAST;
            ch.lengthDir = dir;
            if (dir == Direction.EAST || dir == Direction.WEST) {
                ch.ux = (dir == Direction.EAST ? 1 : -1); ch.uz = 0;
                ch.sideA = Direction.NORTH; ch.sideB = Direction.SOUTH;
                ch.sxA = 0; ch.szA = -1; ch.sxB = 0; ch.szB = 1;
            } else {
                ch.ux = 0; ch.uz = (dir == Direction.SOUTH ? 1 : -1);
                ch.sideA = Direction.EAST; ch.sideB = Direction.WEST;
                ch.sxA = 1; ch.szA = 0; ch.sxB = -1; ch.szB = 0;
            }
        }

        int done = 0;
        for (Charger ch : chargers) {
            try {
                if (ch.x<minX||ch.x>maxX||ch.z<minZ||ch.z>maxZ) continue;
                placeCharger(ch);
            } catch (Exception ex) {
                broadcast(level, "ElectricChargerGenerator: error at ("+ch.x+","+ch.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, chargers.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, chargers.size()));
                broadcast(level, "EV chargers: ~" + pct + "%");
            }
        }
    }

    // ===== сбор =====
    private void collectFeature(JsonObject e,
                                List<Charger> chargers, List<RoadSeg> roads,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags != null) {
            String type = optString(e, "type");
            if ("charging_station".equals(optString(tags,"amenity"))) {
                int cx, cz;
                if ("node".equals(type)) {
                    if (e.has("lat") && e.has("lon")) {
                        int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        cx = xz[0]; cz = xz[1];
                    } else {
                        JsonArray g = e.has("geometry") && e.get("geometry").isJsonArray()
                                ? e.getAsJsonArray("geometry") : null;
                        if (g == null || g.size() == 0) return;
                        JsonObject p = g.get(0).getAsJsonObject();
                        int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        cx = xz[0]; cz = xz[1];
                    }
                } else if ("way".equals(type) || "relation".equals(type)) {
                    // для площадей ставим одну конструкцию в центре
                    JsonArray g = e.has("geometry") && e.get("geometry").isJsonArray()
                            ? e.getAsJsonArray("geometry") : null;
                    if (g == null || g.size() < 1) return;
                    long sx = 0, sz = 0;
                    int n = g.size();
                    for (int i=0;i<n;i++){
                        JsonObject p = g.get(i).getAsJsonObject();
                        int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        sx += xz[0]; sz += xz[1];
                    }
                    cx = (int)Math.round(sx/(double)n);
                    cz = (int)Math.round(sz/(double)n);
                } else return;

                chargers.add(new Charger(cx, cz));
            }

            // дороги для ориентации
            if (isCarRoad(tags) && "way".equals(optString(e,"type"))) {
                JsonArray g = e.has("geometry") && e.get("geometry").isJsonArray()
                        ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() >= 2) {
                    JsonObject p0 = g.get(0).getAsJsonObject();
                    int[] prev = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    for (int i=1;i<g.size();i++) {
                        JsonObject pi = g.get(i).getAsJsonObject();
                        int[] cur = latlngToBlock(pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        roads.add(new RoadSeg(prev[0], prev[1], cur[0], cur[1]));
                        prev = cur;
                    }
                }
            }
        }
    }

    private static boolean isCarRoad(JsonObject t) {
        String hw = optString(t,"highway");
        if (hw == null) return false;
        if (hw.equals("footway") || hw.equals("path") || hw.equals("cycleway") ||
            hw.equals("bridleway") || hw.equals("steps") || hw.equals("pedestrian")) return false;
        return true;
    }

    // ===== ориентация по дороге =====
    private Direction nearestRoadDirection(List<RoadSeg> segs, int x, int z, int radius) {
        long bestD2 = Long.MAX_VALUE; int vx = 0, vz = 0; int r2 = radius*radius;
        for (RoadSeg s : segs) {
            int[] proj = projectPointOnSegment(x, z, s.x1, s.z1, s.x2, s.z2);
            int dx = proj[0]-x, dz = proj[1]-z; int d2 = dx*dx + dz*dz;
            if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; vx = s.x2 - s.x1; vz = s.z2 - s.z1; }
        }
        if (bestD2 == Long.MAX_VALUE) return null;
        return (Math.abs(vx) >= Math.abs(vz)) ? (vx >= 0 ? Direction.EAST : Direction.WEST)
                                              : (vz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }
    private static int[] projectPointOnSegment(int px, int pz, int x1, int z1, int x2, int z2) {
        double vx = x2 - x1, vz = z2 - z1;
        double wx = px - x1, wz = pz - z1;
        double c1 = vx*wx + vz*wz; if (c1 <= 0) return new int[]{x1,z1};
        double c2 = vx*vx + vz*vz; if (c2 <= c1) return new int[]{x2,z2};
        double t = c1 / c2; int x = (int)Math.round(x1 + t*vx); int z = (int)Math.round(z1 + t*vz);
        return new int[]{x,z};
    }

    // ===== рендер одной зарядки =====
    private void placeCharger(Charger ch) {
        // три железных блока по длине, 2 по высоте, повторяют рельеф
        int[][] ofs = new int[][]{
                {-ch.ux, -ch.uz}, // левый торец
                {0, 0},           // центр
                { ch.ux,  ch.uz}  // правый торец
        };

        int[] yBottom = new int[3];
        int[] xCol = new int[3];
        int[] zCol = new int[3];

        for (int i=0; i<3; i++) {
            int x = ch.x + ofs[i][0];
            int z = ch.z + ofs[i][1];
            int y = terrainYFromCoordsOrWorld(x, z, null);
            if (y == Integer.MIN_VALUE) return;
            y += 1;

            xCol[i] = x; zCol[i] = z; yBottom[i] = y;
            setBlockSafe(x, y,   z, Blocks.IRON_BLOCK);
            setBlockSafe(x, y+1, z, Blocks.IRON_BLOCK);
        }

        // фонарь на центральной верхней железке
        placeLanternIfAir(xCol[1], yBottom[1] + 2, zCol[1]);

        // таблички только на торцах (по одному знаку на каждом конце)
        // левый торец — наружу против направления длины
        Direction leftOut  = opposite(ch.lengthDir);
        Direction rightOut = ch.lengthDir;
        placeWallSignIfAir(xCol[0] + leftOut.getStepX(),  yBottom[0], zCol[0] + leftOut.getStepZ(),  leftOut);
        placeWallSignIfAir(xCol[2] + rightOut.getStepX(), yBottom[2], zCol[2] + rightOut.getStepZ(), rightOut);

        // широкие стороны: верхний ряд кнопок, нижний — рычагов, по всем трём секциям
        placeSideControlsRow(xCol, yBottom, zCol, ch.sideA, ch.sxA, ch.szA);
        placeSideControlsRow(xCol, yBottom, zCol, ch.sideB, ch.sxB, ch.szB);
    }

    private Direction opposite(Direction d) {
        switch (d) {
            case NORTH: return Direction.SOUTH;
            case SOUTH: return Direction.NORTH;
            case WEST:  return Direction.EAST;
            case EAST:  return Direction.WEST;
            default:    return d;
        }
    }

    private void placeSideControlsRow(int[] xCol, int[] yBottom, int[] zCol,
                                      Direction sideDir, int sx, int sz) {
        for (int i=0; i<3; i++) {
            int bx = xCol[i] + sx, bz = zCol[i] + sz;
            // верхний ряд — кнопки (на уровне верхнего железного блока)
            placeWallButton(bx, yBottom[i] + 1, bz, sideDir);
            // нижний ряд — рычаги (на уровне нижнего железного блока)
            placeWallLever(bx, yBottom[i], bz, sideDir, false);
        }
    }

    // ===== навесные блоки =====
    private void placeWallSignIfAir(int x, int y, int z, Direction facingOutward) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.OAK_WALL_SIGN.defaultBlockState();
        try {
            st = st.setValue(WallSignBlock.FACING, facingOutward);
            if (st.hasProperty(BlockStateProperties.WATERLOGGED)) {
                st = st.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeWallButton(int x, int y, int z, Direction facingOutward) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState();
        try {
            st = st.setValue(ButtonBlock.FACE, AttachFace.WALL)
                   .setValue(ButtonBlock.POWERED, Boolean.FALSE)
                   .setValue(BlockStateProperties.HORIZONTAL_FACING, facingOutward);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeWallLever(int x, int y, int z, Direction facingOutward, boolean powered) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.LEVER.defaultBlockState();
        try {
            st = st.setValue(LeverBlock.FACE, AttachFace.WALL)
                   .setValue(LeverBlock.FACING, facingOutward)
                   .setValue(LeverBlock.POWERED, powered);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeLanternIfAir(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.LANTERN.defaultBlockState();
        try {
            if (st.hasProperty(BlockStateProperties.HANGING)) {
                st = st.setValue(BlockStateProperties.HANGING, Boolean.FALSE);
            }
            if (st.hasProperty(BlockStateProperties.WATERLOGGED)) {
                st = st.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    // ===== низкоуровневые set'ы =====
    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }

    // ===== высота рельефа =====
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

    // ===== утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
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