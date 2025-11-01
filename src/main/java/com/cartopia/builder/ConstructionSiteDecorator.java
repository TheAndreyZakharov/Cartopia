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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;


public class ConstructionSiteDecorator {

    // ---- Материалы / размеры ----
    private static final Block SAND_BLOCK       = Blocks.SMOOTH_SANDSTONE;
    private static final Block BRICK_BLOCK      = Blocks.BRICKS;      // стопки кирпичей
    private static final Block PLANK_BLOCK      = Blocks.OAK_PLANKS;  // поддоны и стопки досок

    // Песчаные «пирамиды»
    private static final int   SAND_DIAMETER    = 10; // диаметр основания
    private static final int   SAND_RADIUS      = SAND_DIAMETER / 2; // 5
    // три кучки «впритык» в линию: центры через диаметр
    @SuppressWarnings("unused")
    private static final int   SAND_CLUSTER_COUNT = 1; // один кластер на зону

    // Стопки кирпичей
    private static final int   BRICK_PALLET_W   = 4;
    private static final int   BRICK_PALLET_L   = 4;
    private static final int   BRICK_STACK_H    = 3;  // высота кирпичей над поддоном
    private static final int   BRICK_STACK_COUNT= 2;

    // Стопки досок
    private static final int   PLANK_STACK_W    = 6;
    private static final int   PLANK_STACK_L    = 3;
    private static final int   PLANK_STACK_H    = 3;
    private static final int   PLANK_STACK_COUNT= 2;

    // Попытки подобрать позицию внутри зоны (чтоб не затирать и помещалось в полигон)
    private static final int   MAX_TRIES_SAND   = 200;
    private static final int   MAX_TRIES_RECT   = 200;

    private final ServerLevel level;
    private final JsonObject  coords;
    private final GenerationStore store;

    public ConstructionSiteDecorator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ---- Геометрия полигона ----
    private static final class Ring {
        final int[] xs, zs; final int n;
        final int minX, maxX, minZ, maxZ;
        Ring(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE, mnz = Integer.MAX_VALUE, mxz = Integer.MIN_VALUE;
            for (int i=0;i<n;i++){
                int x = xs[i], z = zs[i];
                if (x<mnx) mnx=x; if (x>mxx) mxx=x;
                if (z<mnz) mnz=z; if (z>mxz) mxz=z;
            }
            minX = mnx; maxX = mxx; minZ = mnz; maxZ = mxz;
        }
        boolean containsPoint(int x, int z){
            boolean inside = false;
            for (int i=0, j=n-1; i<n; j=i++){
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
        private void grow(Ring r){ minX=Math.min(minX,r.minX); maxX=Math.max(maxX,r.maxX); minZ=Math.min(minZ,r.minZ); maxZ=Math.max(maxZ,r.maxZ); }
        boolean contains(int x, int z){
            boolean in=false; for (Ring r:outers){ if (r.containsPoint(x,z)){ in=true; break; } }
            if (!in) return false;
            for (Ring r:inners){ if (r.containsPoint(x,z)) return false; }
            return true;
        }
    }

    // простая AABB в XZ, чтобы не пересекать друг друга
    private static final class Box2D {
        final int minX,minZ,maxX,maxZ;
        Box2D(int minX,int minZ,int maxX,int maxZ){ this.minX=minX; this.minZ=minZ; this.maxX=maxX; this.maxZ=maxZ; }
        boolean intersects(Box2D o){
            return !(o.maxX < this.minX || o.minX > this.maxX || o.maxZ < this.minZ || o.minZ > this.maxZ);
        }
    }

    // ---- Запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "ConstructionSiteDecorator: coords == null — пропуск."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "ConstructionSiteDecorator: нет center/bbox — пропуск."); return; }

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

        // ---- читать OSM (стрим/батч) ----
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
                if (!coords.has("features")) { broadcast(level, "ConstructionSiteDecorator: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "ConstructionSiteDecorator: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) {
                    collectArea(el.getAsJsonObject(), areas,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "ConstructionSiteDecorator: ошибка чтения features: " + ex.getMessage());
        }

        if (areas.isEmpty()) { broadcast(level, "ConstructionSiteDecorator: зон строек не найдено — готово."); return; }

        // ---- генерация по зонам ----
        int idx = 0, total = areas.size();
        for (Area area : areas) {
            idx++;
            decorateArea(area, worldMinX, worldMaxX, worldMinZ, worldMaxZ, idx, total);
        }

        broadcast(level, "ConstructionSiteDecorator: готово.");
    }

    // ---- Сбор зон landuse=construction ----
    private void collectArea(JsonObject e, List<Area> out,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isConstructionArea(tags)) return;

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
            String rtype = optString(tags, "type"); // ожидаем multipolygon
            JsonArray members = (e.has("members") && e.get("members").isJsonArray())
                    ? e.getAsJsonArray("members") : null;

            if ("multipolygon".equalsIgnoreCase(String.valueOf(rtype)) && members != null && members.size() > 0) {
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
                return;
            }

            // fallback: как у way — один внешний контур
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
        }
    }

    private static boolean isConstructionArea(JsonObject t) {
        String landuse = low(optString(t, "landuse"));
        return "construction".equals(landuse);
    }

    // ---- Украшение одной зоны ----
    private void decorateArea(Area area, int wMinX, int wMaxX, int wMinZ, int wMaxZ, int idx, int total) {
        final int minX = Math.max(area.minX, wMinX);
        final int maxX = Math.min(area.maxX, wMaxX);
        final int minZ = Math.max(area.minZ, wMinZ);
        final int maxZ = Math.min(area.maxZ, wMaxZ);

        if (minX > maxX || minZ > maxZ) {
            broadcast(level, String.format(Locale.ROOT, "Стройплощадка %d/%d: вне мира — пропуск.", idx, total));
            return;
        }

        Random rng = new Random((((long)minX)<<32) ^ (maxX & 0xffffffffL) ^ (((long)minZ)<<16) ^ maxZ);
        List<Box2D> reserved = new ArrayList<>();

        // ---- Кластер из 3 песчаных пирамид (1 раз) ----
        boolean sandOk = false;
        for (int t=0; t<MAX_TRIES_SAND && !sandOk; t++) {
            boolean horiz = rng.nextBoolean(); // true: вдоль X, false: вдоль Z
            int span = SAND_DIAMETER;         // центры через диаметр
            int cx = randInRange(rng, minX + SAND_RADIUS + span, maxX - SAND_RADIUS - span);
            int cz = randInRange(rng, minZ + SAND_RADIUS + span, maxZ - SAND_RADIUS - span);

            // координаты трёх центров
            int[][] centers = horiz
                    ? new int[][] { {cx - span, cz}, {cx, cz}, {cx + span, cz} }
                    : new int[][] { {cx, cz - span}, {cx, cz}, {cx, cz + span} };

            // AABB кластера
            int cminX = Integer.MAX_VALUE, cmaxX = Integer.MIN_VALUE, cminZ = Integer.MAX_VALUE, cmaxZ = Integer.MIN_VALUE;
            for (int[] p: centers) {
                cminX = Math.min(cminX, p[0]-SAND_RADIUS); cmaxX = Math.max(cmaxX, p[0]+SAND_RADIUS);
                cminZ = Math.min(cminZ, p[1]-SAND_RADIUS); cmaxZ = Math.max(cmaxZ, p[1]+SAND_RADIUS);
            }
            Box2D box = new Box2D(cminX, cminZ, cmaxX, cmaxZ);

            // проверка границ, полигона и занятости
            if (intersectsReserved(reserved, box)) continue;
            if (!aabbInsideArea(area, box)) continue;
            if (!volConeClusterFree(centers, SAND_RADIUS, /*maxExtraH*/ SAND_RADIUS+2)) continue;

            // ставим 3 пирамиды
            for (int[] p: centers) buildSandPile(p[0], p[1], SAND_RADIUS);
            reserved.add(box);
            sandOk = true;
            broadcast(level, String.format(Locale.ROOT, "Стройплощадка %d/%d: кучи песка готовы.", idx, total));
        }

        // ---- Две стопки кирпичей 4×4×3 на поддоне 4×4 ----
        int bricksPlaced = 0;
        for (int t=0; t<MAX_TRIES_RECT && bricksPlaced < BRICK_STACK_COUNT; t++) {
            int w = BRICK_PALLET_W, l = BRICK_PALLET_L, h = BRICK_STACK_H + 1; // +1 слой поддона
            int x0 = randInRange(rng, minX, maxX - w);
            int z0 = randInRange(rng, minZ, maxZ - l);
            Box2D box = new Box2D(x0, z0, x0 + w - 1, z0 + l - 1);
            if (intersectsReserved(reserved, box)) continue;
            if (!aabbInsideArea(area, box)) continue;
            if (!volumeRectFree(x0, z0, w, l, h)) continue;

            buildBrickStack(x0, z0);
            reserved.add(box);
            bricksPlaced++;
            if (bricksPlaced == 1 || bricksPlaced == BRICK_STACK_COUNT) {
                int pct = bricksPlaced == BRICK_STACK_COUNT ? 66 : 33;
                broadcast(level, String.format(Locale.ROOT, "Стройплощадка %d/%d: стопки кирпичей %d/%d (~%d%%).",
                        idx, total, bricksPlaced, BRICK_STACK_COUNT, pct));
            }
        }

        // ---- Две стопки досок 6×3×3 ----
        int planksPlaced = 0;
        for (int t=0; t<MAX_TRIES_RECT && planksPlaced < PLANK_STACK_COUNT; t++) {
            // случайно разворачиваем 6×3 ↔ 3×6
            boolean rotate = rng.nextBoolean();
            int w = rotate ? PLANK_STACK_L : PLANK_STACK_W;
            int l = rotate ? PLANK_STACK_W : PLANK_STACK_L;
            int h = PLANK_STACK_H;

            int x0 = randInRange(rng, minX, maxX - w);
            int z0 = randInRange(rng, minZ, maxZ - l);
            Box2D box = new Box2D(x0, z0, x0 + w - 1, z0 + l - 1);
            if (intersectsReserved(reserved, box)) continue;
            if (!aabbInsideArea(area, box)) continue;
            if (!volumeRectFree(x0, z0, w, l, h)) continue;

            buildPlankStack(x0, z0, w, l, h);
            reserved.add(box);
            planksPlaced++;
            if (planksPlaced == PLANK_STACK_COUNT) {
                broadcast(level, String.format(Locale.ROOT, "Стройплощадка %d/%d: стопки досок готовы (~100%%).", idx, total));
            }
        }
    }

    // ---- Построители ----

    /** Песчаная «пирамида» (на самом деле коническая куча): каждый слой круг радиуса (r - dy). */
    private void buildSandPile(int cx, int cz, int r) {
        // максимальная высота ~ r
        for (int dr = 0; dr <= r; dr++) {
            int curR = Math.max(0, r - dr);
            // пробегаем круг
            int rr = curR * curR;
            for (int ox = -curR; ox <= curR; ox++) {
                for (int oz = -curR; oz <= curR; oz++) {
                    if (ox*ox + oz*oz > rr) continue;
                    int x = cx + ox;
                    int z = cz + oz;
                    int yBase = terrainYFromCoordsOrWorld(x, z);
                    if (yBase == Integer.MIN_VALUE) continue;

                    int y = yBase + 1 + dr;
                    // не затираем, если уже что-то стоит выше
                    if (!isAirAt(x, y, z)) continue;
                    setBlockSafe(x, y, z, SAND_BLOCK);
                }
            }
        }
    }

    /** Поддон 4×4 из досок + стопка кирпичей 4×4×3. Основание — на рельефе для каждой колонки. */
    private void buildBrickStack(int x0, int z0) {
        int w = BRICK_PALLET_W, l = BRICK_PALLET_L;

        // поддон
        for (int dx=0; dx<w; dx++) for (int dz=0; dz<l; dz++) {
            int x = x0 + dx, z = z0 + dz;
            int yBase = terrainYFromCoordsOrWorld(x, z);
            if (yBase == Integer.MIN_VALUE) continue;
            setBlockSafe(x, yBase + 1, z, PLANK_BLOCK);
            // три слоя кирпичей над поддоном
            for (int h=1; h<=BRICK_STACK_H; h++) {
                if (!isAirAt(x, yBase + 1 + h, z)) break;
                setBlockSafe(x, yBase + 1 + h, z, BRICK_BLOCK);
            }
        }
    }

    /** Стопка досок w×l×h (каждая колонка стоит на местном рельефе). */
    private void buildPlankStack(int x0, int z0, int w, int l, int h) {
        for (int dx=0; dx<w; dx++) for (int dz=0; dz<l; dz++) {
            int x = x0 + dx, z = z0 + dz;
            int yBase = terrainYFromCoordsOrWorld(x, z);
            if (yBase == Integer.MIN_VALUE) continue;
            for (int yy=1; yy<=h; yy++) {
                if (!isAirAt(x, yBase + yy, z)) break;
                setBlockSafe(x, yBase + yy, z, PLANK_BLOCK);
            }
        }
    }

    // ---- Проверки «можно ли ставить» ----

    /** Проверить, что объём под прямоугольную стопку (w×l×h) свободен (не затираем). */
    private boolean volumeRectFree(int x0, int z0, int w, int l, int h) {
        for (int dx=0; dx<w; dx++) for (int dz=0; dz<l; dz++) {
            int x = x0 + dx, z = z0 + dz;
            int yBase = terrainYFromCoordsOrWorld(x, z);
            if (yBase == Integer.MIN_VALUE) return false;
            for (int yy=1; yy<=h; yy++) {
                if (!isAirAt(x, yBase + yy, z)) return false; // занято — не ставим
            }
        }
        return true;
    }

    /** Проверить, что три конические кучи с центрами centers и радиусом r поместятся и объём свободен. */
    private boolean volConeClusterFree(int[][] centers, int r, int maxExtraH) {
        for (int[] c : centers) {
            int cx = c[0], cz = c[1];
            // грубая проверка по AABB и по высоте
            for (int ox=-r; ox<=r; ox++) for (int oz=-r; oz<=r; oz++) {
                if (ox*ox + oz*oz > r*r) continue;
                int x = cx + ox, z = cz + oz;
                int yBase = terrainYFromCoordsOrWorld(x, z);
                if (yBase == Integer.MIN_VALUE) return false;
                // максимум h слоёв вверх
                for (int dy=1; dy<=r+1 && dy<=maxExtraH; dy++) {
                    if (!isAirAt(x, yBase + dy, z)) return false;
                }
            }
        }
        return true;
    }

    private boolean aabbInsideArea(Area area, Box2D b) {
        // проверим все углы и несколько внутренних точек
        int[] xs = new int[]{b.minX, (b.minX+b.maxX)/2, b.maxX};
        int[] zs = new int[]{b.minZ, (b.minZ+b.maxZ)/2, b.maxZ};
        for (int x: xs) for (int z: zs) if (!area.contains(x, z)) return false;
        return true;
    }

    private boolean intersectsReserved(List<Box2D> rs, Box2D b) {
        for (Box2D o: rs) if (b.intersects(o)) return true;
        return false;
    }

    private boolean isAirAt(int x, int y, int z) {
        try {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            return st.isAir();
        } catch (Throwable t) {
            return true; // безопасно считать пустым, чтобы не фейлить генерацию
        }
    }

    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
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

    // ---- Утилиты ----
    private static int randInRange(Random r, int a, int b) {
        if (b < a) return a;
        return a + r.nextInt(b - a + 1);
    }
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

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