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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class UtilityBoxGenerator {

    // ===== конфиг =====
    private static final int SEARCH_RADIUS_BLOCKS = 120;
    private static final int MAX_UNITS_PER_AREA   = 20; // на всякий, чтобы не заспамить
    private static final int UNIT_SPACING         = 10; // шаг, если вдруг попадём в полигон (рынок/укрытие)
    private static final int EDGE_MARGIN          = 2;

    // Куб 4×4×4 из энд-стоун-кирпичей + «шишка» 2×2 сверху
    private static final Block BODY_BLOCK      = Blocks.END_STONE_BRICKS;
    private static final Block ROOF_BUMP_BLOCK = Blocks.END_STONE_BRICKS;
    private static final Block DOOR_BLOCK      = Blocks.BIRCH_DOOR; // берёзовые двойные двери

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public UtilityBoxGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
    private static final class PolyXZ {
        final int[] xs,zs; final int n;
        final int minX,maxX,minZ,maxZ; final int cx,cz;
        PolyXZ(int[] xs,int[] zs){
            this.xs=xs; this.zs=zs; this.n=xs.length;
            int minx=Integer.MAX_VALUE,maxx=Integer.MIN_VALUE,minz=Integer.MAX_VALUE,maxz=Integer.MIN_VALUE;
            long sx=0,sz=0;
            for(int i=0;i<n;i++){int x=xs[i],z=zs[i];
                if(x<minx)minx=x; if(x>maxx)maxx=x; if(z<minz)minz=z; if(z>maxz)maxz=z; sx+=x; sz+=z;}
            minX=minx; maxX=maxx; minZ=minz; maxZ=maxz;
            cx=(int)Math.round(sx/(double)n); cz=(int)Math.round(sz/(double)n);
        }
        boolean contains(int x,int z){
            boolean inside=false;
            for(int i=0,j=n-1;i<n;j=i++){
                int xi=xs[i], zi=zs[i], xj=xs[j], zj=zs[j];
                boolean inter=((zi>z)!=(zj>z)) && (x < (double)(xj-xi)*(z-zi)/(double)(zj-zi+1e-9)+xi);
                if(inter) inside=!inside;
            }
            return inside;
        }
    }
    private static final class Unit {
        final int x,z;
        Direction face; // куда «смотрит» стенка с дверями (к дороге)
        Unit(int x,int z){this.x=x; this.z=z;}
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "UtilityBoxGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "UtilityBoxGenerator: нет center/bbox — пропускаю.");
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

        List<Unit> units = new ArrayList<>();
        List<PolyXZ> polys = new ArrayList<>();
        List<RoadSeg> roads = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, units, polys, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "UtilityBoxGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "UtilityBoxGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), units, polys, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "UtilityBoxGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (units.isEmpty()) {
            broadcast(level, "UtilityBoxGenerator: подходящих объектов нет — готово.");
            return;
        }

        // определить «лицо» к ближайшей дороге
        for (Unit u : units) {
            Direction dir = nearestRoadDirection(roads, u.x, u.z, SEARCH_RADIUS_BLOCKS);
            u.face = (dir == null ? Direction.EAST : dir);
        }

        int done = 0;
        for (Unit u : units) {
            try {
                renderUnit(u, minX, maxX, minZ, maxZ);
            } catch (Exception ex) {
                broadcast(level, "UtilityBoxGenerator: ошибка на ("+u.x+","+u.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, units.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, units.size()));
                broadcast(level, "Утилитарные боксы: ~" + pct + "%");
            }
        }
    }

    // ===== сбор признаков =====
    private void collectFeature(JsonObject e,
                                List<Unit> units, List<PolyXZ> polys, List<RoadSeg> roads,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        // берём только БЕЗ building
        if (tags.has("building")) return;

        String type = optString(e,"type");

        // 1) точечные объекты — ставим куб в координату
        if (isUtilityBox(tags)) {
            if ("node".equals(type)) {
                // Узел может прийти БЕЗ geometry — используем lat/lon как fallback
                if (e.has("lat") && e.has("lon")) {
                    int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    units.add(new Unit(xz[0], xz[1]));
                } else {
                    JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                    if (g != null && g.size() == 1) {
                        JsonObject p = g.get(0).getAsJsonObject();
                        int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        units.add(new Unit(xz[0], xz[1]));
                    }
                }
            } else if ("way".equals(type) || "relation".equals(type)) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() >= 4) {
                    int[] xs = new int[g.size()], zs = new int[g.size()];
                    for (int i=0;i<g.size();i++){
                        JsonObject p=g.get(i).getAsJsonObject();
                        int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        xs[i]=xz[0]; zs[i]=xz[1];
                    }
                    PolyXZ poly = new PolyXZ(xs, zs);
                    polys.add(poly);
                    // расставим по линии центра с шагом
                    for (int k = poly.minX + EDGE_MARGIN; k <= poly.maxX - EDGE_MARGIN; k += UNIT_SPACING) {
                        int z0 = clampToInsideZ(poly, poly.cz);
                        if (poly.contains(k, z0)) units.add(new Unit(k, z0));
                        if (units.size() >= MAX_UNITS_PER_AREA) break;
                    }
                }
            }
        }

        // 2) автомобильные дороги — для ориентации дверей
        if (isCarRoad(tags) && "way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
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

    // что считаем «утилитарным боксом» без building
    private static boolean isUtilityBox(JsonObject t) {
        String amenity = optString(t,"amenity");
        String manmade = optString(t,"man_made");
        String telecom = optString(t,"telecom");
        String leisure  = optString(t,"leisure");
        String emergency= optString(t,"emergency");
        String military = optString(t,"military");

        if ("toilets".equals(amenity)) return true;
        if ("telephone".equals(amenity)) return true;
        if ("shelter".equals(amenity)) return true;
        if ("marketplace".equals(amenity)) return true;

        if ("changing_rooms".equals(leisure)) return true; // dressing/changing

        if ("street_cabinet".equals(manmade)) return true;
        if ("bunker_silo".equals(manmade)) return true;

        if ("exchange".equals(telecom)) return true;

        if ("phone".equals(emergency)) return true;

        if ("bunker".equals(military)) return true;

        return false;
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
        double t = c1 / c2;
        int x = (int)Math.round(x1 + t*vx);
        int z = (int)Math.round(z1 + t*vz);
        return new int[]{x,z};
    }

    private int clampToInsideZ(PolyXZ area, int zPref) {
        for (int d=0; d<=8; d++) { if (area.contains(area.cx, zPref+d)) return zPref+d; if (area.contains(area.cx, zPref-d)) return zPref-d; }
        return Math.max(area.minZ+1, Math.min(area.maxZ-1, zPref));
    }

    // ===== рендер =====
    private void renderUnit(Unit u, int minX, int maxX, int minZ, int maxZ) {
        if (u.x<minX||u.x>maxX||u.z<minZ||u.z>maxZ) return;
        placeUtilityBoxAt(u.x, u.z, u.face);
    }

    private void placeUtilityBoxAt(int ax, int az, Direction face) {
        // Куб 4×4×4, «повторяет рельеф»: для КАЖДОЙ клетки берём свою высоту (terrain+1)
        int minX = ax - 1, maxX = ax + 2;
        int minZ = az - 1, maxZ = az + 2;

        // Строим столбиками
        for (int xx = minX; xx <= maxX; xx++) {
            for (int zz = minZ; zz <= maxZ; zz++) {
                int baseY = terrainYFromCoordsOrWorld(xx, zz, null);
                if (baseY == Integer.MIN_VALUE) continue;
                int yCol = baseY + 1;
                for (int yy = yCol; yy <= yCol + 3; yy++) {
                    setBlockSafe(xx, yy, zz, BODY_BLOCK);
                }
            }
        }

        // «Шишка» 2×2 по центру — тоже от локальной вершины каждой из четырёх клеток
        placeRoofBump(minX+1, minZ+1);
        placeRoofBump(minX+2, minZ+1);
        placeRoofBump(minX+1, minZ+2);
        placeRoofBump(minX+2, minZ+2);

        // Двойные двери по центру стены, «смотрят» в сторону ближайшей дороги
        placeCenteredDoubleDoor(minX, maxX, minZ, maxZ, face);
    }

    private void placeRoofBump(int x, int z) {
        int baseY = terrainYFromCoordsOrWorld(x, z, null);
        if (baseY == Integer.MIN_VALUE) return;
        int topY = baseY + 1 + 3; // верх крыши для этой клетки
        setBlockIfAir(x, topY + 1, z, ROOF_BUMP_BLOCK);
    }

    // ===== двери =====
    private void placeCenteredDoubleDoor(int minX, int maxX, int minZ, int maxZ, Direction face) {
        if (face == Direction.NORTH || face == Direction.SOUTH) {
            int z = (face == Direction.NORTH ? minZ : maxZ);
            int xLeft = minX + 1;
            int xRight= minX + 2;
            placeDoorPair(xLeft, z, xRight, z, face);
        } else {
            int x = (face == Direction.WEST ? minX : maxX);
            int zLeft = minZ + 1;
            int zRight= minZ + 2;
            placeDoorPair(x, zLeft, x, zRight, face);
        }
    }

    private void placeDoorPair(int xA, int zA, int xB, int zB, Direction face) {
        // под каждую половинку берём локальный рельеф и ставим двери так, чтобы bottoms были СИНХРОННО на более высокой стороне
        int baseA = terrainYFromCoordsOrWorld(xA, zA, null);
        int baseB = terrainYFromCoordsOrWorld(xB, zB, null);
        if (baseA == Integer.MIN_VALUE || baseB == Integer.MIN_VALUE) return;

        int yA = baseA + 1;
        int yB = baseB + 1;
        int yDoor = Math.max(yA, yB); // чтобы не врезаться в склон

        // гарантия опоры под дверью
        setBlockIfAir(xA, yDoor - 1, zA, BODY_BLOCK);
        setBlockIfAir(xB, yDoor - 1, zB, BODY_BLOCK);

        // прорезаем проём и ставим створки (ориентированы наружу — к дороге)
        placeSingleDoor(xA, yDoor, zA, face, DoorHingeSide.RIGHT); // «левая» створка с правой петлёй
        placeSingleDoor(xB, yDoor, zB, face, DoorHingeSide.LEFT);  // «правая» — с левой петлёй
    }

    private void placeSingleDoor(int x, int y, int z, Direction face, DoorHingeSide hinge) {
        // очистка проёма на 2 блока высотой
        setAir(x, y, z);
        setAir(x, y+1, z);

        BlockState lower = DOOR_BLOCK.defaultBlockState()
                .setValue(DoorBlock.FACING, face)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, Boolean.FALSE)
                .setValue(DoorBlock.POWERED, Boolean.FALSE);

        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        level.setBlock(new BlockPos(x, y,   z), lower, 3);
        level.setBlock(new BlockPos(x, y+1, z), upper, 3);
    }

    // ===== низкоуровневые set'ы =====
    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }
    private void setBlockIfAir(int x, int y, int z, Block block) {
        BlockPos p = new BlockPos(x,y,z);
        if (level.getBlockState(p).isAir()) level.setBlock(p, block.defaultBlockState(), 3);
    }
    private void setAir(int x, int y, int z) {
        level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 3);
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