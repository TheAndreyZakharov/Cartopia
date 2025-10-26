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


public class CarWashGenerator {

    // ===== конфиг =====
    private static final int SEARCH_RADIUS_BLOCKS = 120;
    private static final int MAX_WASH_UNITS_PER_AREA = 6;
    private static final int WASH_SPACING = 6;
    private static final int EDGE_MARGIN = 2;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public CarWashGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
    private static final class Station {
        final int x,z;
        PolyXZ canopyOrNull;
        PolyXZ buildingOrNull;
        Direction lengthDir; // вдоль дороги
        int ux,uz;           // ед. вектор вдоль
        @SuppressWarnings("unused")
        Direction sideA, sideB; // перпендикулярные «длинные» стороны
        @SuppressWarnings("unused")
        int sxA,szA,sxB,szB;
        Station(int x,int z){this.x=x;this.z=z;}
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "CarWashGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "CarWashGenerator: нет center/bbox — пропускаю.");
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

        List<Station> stations = new ArrayList<>();
        List<PolyXZ> roofs  = new ArrayList<>();
        List<PolyXZ> builds = new ArrayList<>();
        List<RoadSeg> roads = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, stations, roofs, builds, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "CarWashGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "CarWashGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), stations, roofs, builds, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "CarWashGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (stations.isEmpty()) {
            broadcast(level, "CarWashGenerator: автомоек нет в области — готово.");
            return;
        }

        // подобрать навес/здание и направление
        for (Station st : stations) {
            st.canopyOrNull   = nearestPoly(roofs,  st.x, st.z, SEARCH_RADIUS_BLOCKS);
            st.buildingOrNull = nearestPoly(builds, st.x, st.z, SEARCH_RADIUS_BLOCKS);

            Direction dir = nearestRoadDirection(roads, st.x, st.z, SEARCH_RADIUS_BLOCKS);
            if (dir == null) {
                PolyXZ p = (st.canopyOrNull != null ? st.canopyOrNull : st.buildingOrNull);
                if (p != null) {
                    int spanX = p.maxX - p.minX, spanZ = p.maxZ - p.minZ;
                    dir = (spanX >= spanZ) ? Direction.EAST : Direction.SOUTH;
                } else dir = Direction.EAST;
            }
            st.lengthDir = dir;
            if (dir == Direction.EAST || dir == Direction.WEST) {
                st.ux = (dir == Direction.EAST ? 1 : -1); st.uz = 0;
                st.sideA = Direction.NORTH; st.sideB = Direction.SOUTH;
                st.sxA = 0; st.szA = -1; st.sxB = 0; st.szB = 1;
            } else {
                st.ux = 0; st.uz = (dir == Direction.SOUTH ? 1 : -1);
                st.sideA = Direction.EAST; st.sideB = Direction.WEST;
                st.sxA = 1; st.szA = 0; st.sxB = -1; st.szB = 0;
            }
        }

        // рендер
        int done = 0;
        for (Station st : stations) {
            try {
                renderStation(st, minX, maxX, minZ, maxZ);
            } catch (Exception ex) {
                broadcast(level, "CarWashGenerator: ошибка на станции ("+st.x+","+st.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, stations.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, stations.size()));
                broadcast(level, "Автомойки: ~" + pct + "%");
            }
        }
        broadcast(level, "Автомойки готовы.");
    }

    // ===== сбор признаков =====
    private void collectFeature(JsonObject e,
                                List<Station> stations, List<PolyXZ> roofs, List<PolyXZ> builds, List<RoadSeg> roads,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String type = optString(e, "type");

        // 1) автомойка
        if (isCarWash(tags)) {
            if ("node".equals(type)) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g != null && g.size() == 1) {
                    JsonObject p = g.get(0).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    stations.add(new Station(xz[0], xz[1]));
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
                    stations.add(new Station(poly.cx, poly.cz));

                    // Если сама автомойка помечена как roof/canopy — это и есть навес.
                    if (isRoofLike(tags)) roofs.add(poly);
                    else if (isBuilding(tags)) builds.add(poly);
                }
            }
            return;
        }

        // 2) навес
        if (isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 4) {
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                roofs.add(new PolyXZ(xs, zs));
            }
            return;
        }

        // 3) обычное здание
        if (isBuilding(tags) && !isRoofLike(tags) && ("way".equals(type) || "relation".equals(type))) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 4) {
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                builds.add(new PolyXZ(xs, zs));
            }
            return;
        }

        // 4) автомобильные дороги
        if (isCarRoad(tags) && "way".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g != null && g.size() >= 2) {
                JsonObject p0 = g.get(0).getAsJsonObject();
                int[] prev = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                for (int i=1;i<g.size();i++){
                    JsonObject pi = g.get(i).getAsJsonObject();
                    int[] cur = latlngToBlock(pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    roads.add(new RoadSeg(prev[0], prev[1], cur[0], cur[1]));
                    prev = cur;
                }
            }
        }
    }

    // ===== классификация тегов =====
    private static boolean isCarWash(JsonObject t) {
        String amenity = optString(t,"amenity");
        if ("car_wash".equals(amenity)) return true;
        // редкие альтернативы — на всякий
        String service = optString(t,"service");
        if ("car_wash".equals(service)) return true;
        return false;
    }
    private static boolean isRoofLike(JsonObject t) {
        String b = optString(t,"building");
        String mm= optString(t,"man_made");
        String bp= optString(t,"building:part");
        return "roof".equals(b) || "roof".equals(bp) || "canopy".equals(mm) || "carport".equals(b);
    }
    private static boolean isBuilding(JsonObject t) { return t.has("building"); }
    private static boolean isCarRoad(JsonObject t) {
        String hw = optString(t,"highway");
        if (hw == null) return false;
        if (hw.equals("footway")||hw.equals("path")||hw.equals("cycleway")||hw.equals("bridleway")||hw.equals("steps")||hw.equals("pedestrian")) return false;
        return true;
    }

    // ===== выбор ближайших сущностей =====
    private PolyXZ nearestPoly(List<PolyXZ> polys, int x, int z, int radius) {
        PolyXZ best=null; long bestD2=Long.MAX_VALUE; int r2=radius*radius;
        for (PolyXZ p: polys) {
            int dx=p.cx-x, dz=p.cz-z; int d2=dx*dx+dz*dz;
            if (d2<=r2 && d2<bestD2){ bestD2=d2; best=p; }
        }
        return best;
    }

    private Direction nearestRoadDirection(List<RoadSeg> segs, int x, int z, int radius) {
        long bestD2=Long.MAX_VALUE; int vx=0,vz=0; int r2=radius*radius;
        for (RoadSeg s: segs) {
            int[] proj = projectPointOnSegment(x,z,s.x1,s.z1,s.x2,s.z2);
            int dx=proj[0]-x, dz=proj[1]-z; int d2=dx*dx+dz*dz;
            if (d2<=r2 && d2<bestD2){ bestD2=d2; vx=s.x2-s.x1; vz=s.z2-s.z1; }
        }
        if (bestD2==Long.MAX_VALUE) return null;
        return (Math.abs(vx)>=Math.abs(vz)) ? (vx>=0?Direction.EAST:Direction.WEST)
                                            : (vz>=0?Direction.SOUTH:Direction.NORTH);
    }
    private static int[] projectPointOnSegment(int px,int pz,int x1,int z1,int x2,int z2){
        double vx=x2-x1, vz=z2-z1; double wx=px-x1, wz=pz-z1;
        double c1=vx*wx+vz*wz; if(c1<=0) return new int[]{x1,z1};
        double c2=vx*vx+vz*vz; if(c2<=c1) return new int[]{x2,z2};
        double t=c1/c2; int x=(int)Math.round(x1+t*vx); int z=(int)Math.round(z1+t*vz);
        return new int[]{x,z};
    }

    // ===== рендер станции =====
    private void renderStation(Station st, int minX, int maxX, int minZ, int maxZ) {
        PolyXZ area = (st.canopyOrNull != null ? st.canopyOrNull : st.buildingOrNull);
        if (area == null) {
            placeSimplePair(st, minX, maxX, minZ, maxZ);
            return;
        }
        List<int[]> anchors = anchorsAlongArea(area, st, MAX_WASH_UNITS_PER_AREA);
        if (anchors.isEmpty()) anchors.add(new int[]{area.cx, area.cz});
        for (int[] a : anchors) {
            int ax=a[0], az=a[1];
            if (ax<minX||ax>maxX||az<minZ||az>maxZ) continue;
            placeWashUnitAt(ax, az, st);
        }
    }

    private List<int[]> anchorsAlongArea(PolyXZ area, Station st, int limit) {
        ArrayList<int[]> out = new ArrayList<>();
        if (st.lengthDir == Direction.EAST || st.lengthDir == Direction.WEST) {
            int z0 = clampToInsideZ(area, area.cz);
            int minX = area.minX + EDGE_MARGIN, maxX = area.maxX - EDGE_MARGIN;
            if (minX > maxX) { out.add(new int[]{area.cx, z0}); return out; }
            int start = minX + (WASH_SPACING/2);
            for (int x=start; x<=maxX; x+=WASH_SPACING) {
                if (area.contains(x, z0)) { out.add(new int[]{x, z0}); if(out.size()>=limit) break; }
            }
        } else {
            int x0 = clampToInsideX(area, area.cx);
            int minZ = area.minZ + EDGE_MARGIN, maxZ = area.maxZ - EDGE_MARGIN;
            if (minZ > maxZ) { out.add(new int[]{x0, area.cz}); return out; }
            int start = minZ + (WASH_SPACING/2);
            for (int z=start; z<=maxZ; z+=WASH_SPACING) {
                if (area.contains(x0, z)) { out.add(new int[]{x0, z}); if(out.size()>=limit) break; }
            }
        }
        return out;
    }

    private int clampToInsideZ(PolyXZ area, int zPref) {
        for (int d=0; d<=8; d++) { if (area.contains(area.cx, zPref+d)) return zPref+d; if (area.contains(area.cx, zPref-d)) return zPref-d; }
        return Math.max(area.minZ+1, Math.min(area.maxZ-1, zPref));
    }
    private int clampToInsideX(PolyXZ area, int xPref) {
        for (int d=0; d<=8; d++) { if (area.contains(xPref+d, area.cz)) return xPref+d; if (area.contains(xPref-d, area.cz)) return xPref-d; }
        return Math.max(area.minX+1, Math.min(area.maxX-1, xPref));
    }

    private void placeSimplePair(Station st, int minX, int maxX, int minZ, int maxZ) {
        int gap = WASH_SPACING;
        int ax1 = st.x - st.ux * gap, az1 = st.z - st.uz * gap;
        int ax2 = st.x + st.ux * gap, az2 = st.z + st.uz * gap;
        if (ax1>=minX&&ax1<=maxX&&az1>=minZ&&az1<=maxZ) placeWashUnitAt(ax1, az1, st);
        if (ax2>=minX&&ax2<=maxX&&az2>=minZ&&az2<=maxZ) placeWashUnitAt(ax2, az2, st);
    }

    // ===== установка одного поста автомойки =====
    private void placeWashUnitAt(int ax, int az, Station st) {
        // базовая высота — рельеф + 1
        int yGround = terrainYFromCoordsOrWorld(ax, az, null);
        if (yGround == Integer.MIN_VALUE) return;
        int y = yGround + 1;

        // железо (стек 2 блока)
        setBlockSafe(ax, y,   az, Blocks.IRON_BLOCK);
        setBlockSafe(ax, y+1, az, Blocks.IRON_BLOCK);

        // --- нижний уровень: 3 таблички + «фронтальная» грань без таблички ---
        Direction front = st.sideA;               // «фронт» – одна из длинных сторон
        Direction left  = rotateLeft(front);
        Direction right = rotateRight(front);
        Direction back  = front.getOpposite();

        // таблички на трёх сторонах (кроме front)
        placeWallSign(ax + dx(back),  y, az + dz(back),  back);
        placeWallSign(ax + dx(left),  y, az + dz(left),  left);
        placeWallSign(ax + dx(right), y, az + dz(right), right);

        // --- ДВА НАПОЛЬНЫХ РЫЧАГА ПЕРЕД КОЛОННОЙ (на «земле» = локальный рельеф + 1) ---
        // позиции: фронт-лево и фронт-право (диагонально перед колонной)
        int flx = ax + dx(front) + dx(left);
        int flz = az + dz(front) + dz(left);
        int frx = ax + dx(front) + dx(right);
        int frz = az + dz(front) + dz(right);

        int yFL = terrainYFromCoordsOrWorld(flx, flz, null);
        if (yFL != Integer.MIN_VALUE) {
            placeFloorLever(flx, yFL + 1, flz, front, false);
        }
        int yFR = terrainYFromCoordsOrWorld(frx, frz, null);
        if (yFR != Integer.MIN_VALUE) {
            placeFloorLever(frx, yFR + 1, frz, front, false);
        }

        // --- верхний уровень: фонарь + кнопки со всех четырёх сторон ---
        placeLanternIfAir(ax, y+2, az); // на «крышу» верхнего железа

        placeWallButton(ax + dx(Direction.NORTH), y+1, az + dz(Direction.NORTH), Direction.NORTH);
        placeWallButton(ax + dx(Direction.SOUTH), y+1, az + dz(Direction.SOUTH), Direction.SOUTH);
        placeWallButton(ax + dx(Direction.EAST),  y+1, az + dz(Direction.EAST),  Direction.EAST);
        placeWallButton(ax + dx(Direction.WEST),  y+1, az + dz(Direction.WEST),  Direction.WEST);
    }

    // ===== утилиты направлений =====
    private static int dx(Direction d){ return d.getStepX(); }
    private static int dz(Direction d){ return d.getStepZ(); }
    private static Direction rotateLeft(Direction d){
        return switch (d){
            case NORTH -> Direction.WEST;
            case WEST  -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case EAST  -> Direction.NORTH;
            default -> d;
        };
    }
    private static Direction rotateRight(Direction d){
        return switch (d){
            case NORTH -> Direction.EAST;
            case EAST  -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST  -> Direction.NORTH;
            default -> d;
        };
    }

    // ===== навесные/напольные блоки =====
    private void placeWallSign(int x, int y, int z, Direction facingOutward) {
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

    @SuppressWarnings("unused")
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

    private void placeFloorLever(int x, int y, int z, Direction facing, boolean powered) {
        // Напольный рычаг на верхней поверхности блока (AttachFace.FLOOR)
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = Blocks.LEVER.defaultBlockState();
        try {
            st = st.setValue(LeverBlock.FACE, AttachFace.FLOOR)
                   .setValue(LeverBlock.FACING, facing)
                   .setValue(LeverBlock.POWERED, powered);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    private void placeLanternIfAir(int x, int y, int z) {
        BlockPos pos = new BlockPos(x,y,z);
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
                    if (ix>=0 && ix<w && iz>=0 && iz<h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz*w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
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