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

public class LighthouseGenerator {

    // ===== конфиг =====
    private static final int SEARCH_RADIUS_BLOCKS = 160;
    @SuppressWarnings("unused")
    private static final int EDGE_MARGIN = 2;

    private static final int DEFAULT_HEIGHT = 20;
    private static final String DEFAULT_COLOR = "white";
    private static final int STRIPE_HEIGHT = 2; // высота одной цветной полосы

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public LighthouseGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
        @SuppressWarnings("unused")
        final int[] xs,zs; final int n;
        @SuppressWarnings("unused")
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
    }
    private static final class Light {
        final int x,z;
        int height;                 // в блоках
        List<Block> palette;        // бетон по цветам
        Direction face;             // к ближайшей дороге (для двери)
        Light(int x,int z){this.x=x; this.z=z;}
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "LighthouseGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "LighthouseGenerator: нет center/bbox — пропускаю.");
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

        List<Light> lights = new ArrayList<>();
        List<RoadSeg> roads = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, lights, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "LighthouseGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "LighthouseGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), lights, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "LighthouseGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (lights.isEmpty()) {
            broadcast(level, "LighthouseGenerator: маяки не найдены — готово.");
            return;
        }

        // ориентация к дороге
        for (Light l : lights) {
            Direction dir = nearestRoadDirection(roads, l.x, l.z, SEARCH_RADIUS_BLOCKS);
            l.face = (dir == null ? Direction.EAST : dir);
        }

        // рендер
        int done = 0;
        for (Light l : lights) {
            try {
                if (l.x<minX||l.x>maxX||l.z<minZ||l.z>maxZ) continue;
                renderLighthouse(l);
            } catch (Exception ex) {
                broadcast(level, "LighthouseGenerator: ошибка на ("+l.x+","+l.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, lights.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, lights.size()));
                broadcast(level, "Маяки: ~" + pct + "%");
            }
        }
    }

    // ===== сбор признаков =====
    private void collectFeature(JsonObject e,
                                List<Light> lights, List<RoadSeg> roads,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String type = optString(e,"type");

        if (isLighthouseLike(tags)) {
            int lx, lz;
            if ("node".equals(type)) {
                if (e.has("lat") && e.has("lon")) {
                    int[] xz = latlngToBlock(e.get("lat").getAsDouble(), e.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    lx = xz[0]; lz = xz[1];
                } else {
                    JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                    if (g == null || g.size() == 0) return;
                    JsonObject p = g.get(0).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    lx = xz[0]; lz = xz[1];
                }
            } else if ("way".equals(type) || "relation".equals(type)) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() < 1) return;
                int[] xs = new int[g.size()], zs = new int[g.size()];
                for (int i=0;i<g.size();i++){
                    JsonObject p=g.get(i).getAsJsonObject();
                    int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                PolyXZ poly = new PolyXZ(xs, zs);
                lx = poly.cx; lz = poly.cz;
            } else {
                return;
            }

            Light L = new Light(lx, lz);
            L.height  = extractHeightBlocks(tags);
            L.palette = extractPalette(tags);
            lights.add(L);
        }

        // дороги для ориентации двери
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

    private static boolean isLighthouseLike(JsonObject t) {
        String mm = optString(t,"man_made");
        if ("lighthouse".equals(mm)) return true;
        if ("beacon".equals(mm)) return true;

        String smType = optString(t,"seamark:type");
        if (smType != null && (smType.startsWith("beacon") || smType.contains("lighthouse"))) return true;

        for (String k : t.keySet()) {
            if (k.startsWith("seamark:beacon_")) return true;
        }
        return false;
    }

    private static boolean isCarRoad(JsonObject t) {
        String hw = optString(t,"highway");
        if (hw == null) return false;
        if (hw.equals("footway")||hw.equals("path")||hw.equals("cycleway")||
            hw.equals("bridleway")||hw.equals("steps")||hw.equals("pedestrian")) return false;
        return true;
    }

    // ===== извлечение высоты/цвета =====
    private int extractHeightBlocks(JsonObject t) {
        String[] keys = new String[]{
                "height",
                "seamark:beacon_lateral:height",
                "seamark:beacon_cardinal:height",
                "seamark:beacon_safe_water:height",
                "seamark:beacon_special_purpose:height",
                "seamark:light:height",
                "seamark:light:1:height"
        };
        for (String k: keys) {
            String v = optString(t, k);
            if (v != null) {
                Integer num = parseFirstInt(v);
                if (num != null && num > 0) return num;
            }
        }
        return DEFAULT_HEIGHT;
    }

    private List<Block> extractPalette(JsonObject t) {
        String[] keys = new String[]{
                "seamark:beacon_lateral:colour",
                "seamark:beacon_cardinal:colour",
                "seamark:beacon_safe_water:colour",
                "seamark:beacon_special_purpose:colour",
                "seamark:light:colour",
                "seamark:light:1:colour",
                "colour",
                "color"
        };
        String raw = null;
        for (String k: keys) {
            String v = optString(t, k);
            if (v != null) { raw = v; break; }
        }
        if (raw == null || raw.isEmpty()) raw = DEFAULT_COLOR;

        String[] parts = raw.toLowerCase(Locale.ROOT).split("[; ,/]+");
        List<Block> out = new ArrayList<>();
        for (String c : parts) {
            Block b = colorToConcrete(c);
            if (b != null) out.add(b);
        }
        if (out.isEmpty()) out.add(Blocks.WHITE_CONCRETE);
        return out;
    }

    private static Integer parseFirstInt(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && !Character.isDigit(s.charAt(i))) i++;
        if (i == len) return null;
        int j = i;
        while (j < len && Character.isDigit(s.charAt(j))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    private static Block colorToConcrete(String c) {
        switch (c) {
            case "white":   return Blocks.WHITE_CONCRETE;
            case "red":     return Blocks.RED_CONCRETE;
            case "green":   return Blocks.GREEN_CONCRETE;
            case "black":   return Blocks.BLACK_CONCRETE;
            case "yellow":  return Blocks.YELLOW_CONCRETE;
            case "blue":    return Blocks.BLUE_CONCRETE;
            case "orange":  return Blocks.ORANGE_CONCRETE;
            case "brown":   return Blocks.BROWN_CONCRETE;
            case "purple":
            case "violet":  return Blocks.PURPLE_CONCRETE;
            case "magenta": return Blocks.MAGENTA_CONCRETE;
            case "pink":    return Blocks.PINK_CONCRETE;
            case "cyan":    return Blocks.CYAN_CONCRETE;
            case "gray":
            case "grey":    return Blocks.LIGHT_GRAY_CONCRETE;
            default:        return null;
        }
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
    private static int[] projectPointOnSegment(int px,int pz,int x1,int z1,int x2,int z2){
        double vx=x2-x1, vz=z2-z1; double wx=px-x1, wz=pz-z1;
        double c1=vx*wx+vz*wz; if(c1<=0) return new int[]{x1,z1};
        double c2=vx*vx+vz*vz; if(c2<=c1) return new int[]{x2,z2};
        double t=c1/c2; int x=(int)Math.round(x1+t*vx); int z=(int)Math.round(z1+t*vz);
        return new int[]{x,z};
    }

    // ===== рендер маяка =====
    private void renderLighthouse(Light L) {
        final int H = Math.max(2, L.height);
        final int radius = (H < 10 ? 1 : 2);   // d=3 или d=5
        final int roofRadius = radius + 1;     // «на пару блоков больше»

        // 1) Ствол маяка — «по рельефу», у каждой точки своя база
        List<int[]> circle = diskOffsets(radius);
        for (int[] d : circle) {
            int x = L.x + d[0], z = L.z + d[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            yBase += 1;
            for (int dy = 0; dy < H; dy++) {
                int stripe = (dy / STRIPE_HEIGHT) % L.palette.size();
                Block block = L.palette.get(stripe);
                setBlockSafe(x, yBase + dy, z, block);
            }
        }

        // 2) Круглая каменная площадка крыши — ТОЖЕ по местной высоте (terrain+1+H)
        List<int[]> roofDisk = diskOffsets(roofRadius);
        for (int[] d : roofDisk) {
            int x = L.x + d[0], z = L.z + d[1];
            int yRoof = localRoofY(x, z, H);
            if (yRoof != Integer.MIN_VALUE) setBlockSafe(x, yRoof, z, Blocks.STONE_BRICKS);
        }

        // 3) Стеклянный «купол» — кольца по местной высоте
        placeGlassRingDynamic(L.x, L.z, roofRadius,     H, 1);
        if (roofRadius - 1 >= 1) placeGlassRingDynamic(L.x, L.z, roofRadius - 1, H, 2);
        if (roofRadius - 2 >= 1) placeGlassRingDynamic(L.x, L.z, roofRadius - 2, H, 3);
        // центральный «шпиль»
        //int centerRoof = localRoofY(L.x, L.z, H);
        //if (centerRoof != Integer.MIN_VALUE) setBlockIfAir(L.x, centerRoof + 4, L.z, Blocks.GLASS);

        // 4) Внутри купола — слой блоков маяка (BEACON) по местной высоте
        List<int[]> inner = diskOffsets(Math.max(roofRadius - 1, 0));
        for (int[] d : inner) {
            int x = L.x + d[0], z = L.z + d[1];
            int yRoof = localRoofY(x, z, H);
            if (yRoof != Integer.MIN_VALUE) setBlockIfAir(x, yRoof + 1, z, Blocks.BEACON);
        }

        // 5) Дверь со стороны ближайшей дороги
        placeDoorAtSide(L.x, L.z, radius, L.face);
    }

    /** yRoof = (terrain + 1 + H) в конкретной клетке (x,z). */
    private int localRoofY(int x, int z, int H) {
        int yBase = terrainYFromCoordsOrWorld(x, z, null);
        if (yBase == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return yBase + 1 + H;
    }

    /** Стеклянное кольцо на слое (roof + layer), где roof — локальная высота по рельефу. */
    private void placeGlassRingDynamic(int cx, int cz, int r, int H, int layer) {
        if (r <= 0) {
            int yRoof = localRoofY(cx, cz, H);
            if (yRoof != Integer.MIN_VALUE) setBlockIfAir(cx, yRoof + layer, cz, Blocks.GLASS);
            return;
        }
        int rr = r*r;
        for (int dx = -r-1; dx <= r+1; dx++) {
            for (int dz = -r-1; dz <= r+1; dz++) {
                int d2 = dx*dx + dz*dz;
                boolean on = (d2 <= rr && d2 >= rr - r); // тонкое кольцо
                if (!on) continue;
                int x = cx + dx, z = cz + dz;
                int yRoof = localRoofY(x, z, H);
                if (yRoof != Integer.MIN_VALUE) setBlockIfAir(x, yRoof + layer, z, Blocks.GLASS);
            }
        }
    }

    // ===== геометрия круга =====
    private List<int[]> diskOffsets(int r) {
        ArrayList<int[]> out = new ArrayList<>();
        int rr = r*r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx*dx + dz*dz <= rr + 1) out.add(new int[]{dx, dz});
            }
        }
        return out;
    }

    // ===== дверь =====
    private void placeDoorAtSide(int cx, int cz, int radius, Direction face) {
        int dx = face.getStepX();
        int dz = face.getStepZ();
        int x = cx + dx * radius;
        int z = cz + dz * radius;

        int baseY = terrainYFromCoordsOrWorld(x, z, null);
        if (baseY == Integer.MIN_VALUE) return;
        int y = baseY + 1;

        setAir(x, y, z);
        setAir(x, y+1, z);

        if (level.getBlockState(new BlockPos(x, y-1, z)).isAir())
            setBlockSafe(x, y-1, z, Blocks.STONE_BRICKS);

        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, face)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
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