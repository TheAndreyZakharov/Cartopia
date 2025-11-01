package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("unused")
public class AdvertisingGenerator {

    // Материал стендов / щитов
    private static final Block MAT = Blocks.POLISHED_DIORITE;

    // Геометрия «billboard на ножке»
    private static final int BILL_POST_W = 2;   // ширина ножки
    private static final int BILL_POST_H = 3;   // высота ножки
    private static final int BILL_FACE_W = 4;   // ширина панели (блоков)
    private static final int BILL_FACE_H = 4;   // высота панели (блоков)

    // «стенд 2×2×5»
    private static final int STAND_H  = 5;

    // Радиус для поиска стен
    private static final int WALL_SEARCH_R = 50;

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;
    private final RandomSource rng;

    public AdvertisingGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
        this.rng    = level.getRandom();
    }

    // ===== Вещалка =====
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

    // ===== Типы =====
    private static final class Ad {
        final int x, z; String kind;
        Ad(int x, int z, String kind){ this.x=x; this.z=z; this.kind=kind; }
    }
    private static final class WallSpot {
        final BlockPos wall, front; final Direction face;
        WallSpot(BlockPos wall, BlockPos front, Direction face){ this.wall=wall; this.front=front; this.face=face; }
    }

    // ===== Запуск =====
    public void generate() {
        if (coords == null) { broadcast(level, "Advertising: coords == null — пропуск."); return; }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "Advertising: нет center/bbox — пропуск."); return; }

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
        final int minX = Math.min(a[0], b[0]), maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]), maxZ = Math.max(a[1], b[1]);

        List<Ad> list = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (var fs = store.featureStream()) {
                    for (JsonObject e : fs) collect(e, list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "Advertising: нет coords.features — пропуск."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "Advertising: features.elements пуст — пропуск."); return; }
                for (JsonElement el : elements) collect(el.getAsJsonObject(), list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            }
        } catch (Exception ex) { broadcast(level, "Advertising: ошибка чтения features: " + ex.getMessage()); }

        if (list.isEmpty()) { broadcast(level, "Advertising: подходящих объектов нет — готово."); return; }

        broadcast(level, "Advertising: построение…");
        int done = 0, total = list.size();
        for (Ad ad : list) {
            if (ad.x<minX||ad.x>maxX||ad.z<minZ||ad.z>maxZ) continue;
            try {
                switch (ad.kind) {
                    case "billboard" -> buildBillboardOnPost(ad.x, ad.z);
                    case "board", "screen", "tarp", "wall_painting", "video_wall" -> attachToNearestWall(ad.x, ad.z);
                    default -> buildStandAndWrapWithPaintings(ad.x, ad.z);
                }
            } catch (Throwable t) {
                broadcast(level, "Advertising: ошибка у ("+ad.x+","+ad.z+"): " + t.getMessage());
            }
            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                broadcast(level, "Advertising: ~" + pct + "%");
            }
        }
        broadcast(level, "Advertising: готово.");
    }

    // ===== Разбор фич =====
    private void collect(JsonObject e, List<Ad> out,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        String adv = low(optString(tags, "advertising"));
        String mm  = low(optString(tags, "man_made"));
        if (adv == null && (mm == null || !mm.contains("video_wall"))) return;

        String type = optString(e, "type");
        int ax, az;

        if ("node".equals(type)) {
            Double lat = e.has("lat") ? e.get("lat").getAsDouble() : null;
            Double lon = e.has("lon") ? e.get("lon").getAsDouble() : null;
            if (lat == null || lon == null) {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble(); lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            ax = xz[0]; az = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            double[] ll = centroidLatLon(g);
            int[] xz = latlngToBlock(ll[0], ll[1], centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            ax = xz[0]; az = xz[1];
        } else return;

        String kind = mapKind(adv, mm);
        if (kind != null) out.add(new Ad(ax, az, kind));
    }

    private String mapKind(String adv, String mm) {
        if (adv == null) adv = "";
        if (mm  == null) mm  = "";
        if (adv.contains("billboard")) return "billboard";
        if (adv.contains("board") || adv.contains("screen") || adv.contains("tarp") || adv.contains("wall_painting")
                || mm.contains("video_wall")) return "screen";
        if (adv.contains("column")) return "column";
        if (adv.contains("flag")) return "flag";
        if (adv.contains("poster_box")) return "poster_box";
        if (adv.contains("sculpture")) return "sculpture";
        if (adv.contains("sign")) return "sign";
        if (adv.contains("totem")) return "totem";
        return "other";
    }

    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

    // ===== Постройка: billboard на ножке + картины на 4 стороны панели =====
    private void buildBillboardOnPost(int cx, int cz) {
        int[] tops = new int[BILL_POST_W];
        int maxTop = Integer.MIN_VALUE;

        // ножка 2×? — каждая колонка «сидит» на своём рельефе
        for (int i = 0; i < BILL_POST_W; i++) {
            int x = cx + i, z = cz;
            int yBase = groundY(x, z);
            if (yBase == Integer.MIN_VALUE) continue;
            int yStart = yBase + 1;
            int yEnd   = yStart + BILL_POST_H - 1;
            for (int y = yStart; y <= yEnd; y++) set(x, y, z, MAT);
            tops[i] = yEnd; maxTop = Math.max(maxTop, yEnd);
        }
        if (maxTop == Integer.MIN_VALUE) return;

        // панель 4×4 (плоскость Z = cz)
        int bottom = maxTop + 1;
        int leftX  = cx - (BILL_FACE_W/2) + (BILL_POST_W/2); // центрируем над ножкой
        int zFace  = cz;
        for (int x = leftX; x < leftX + BILL_FACE_W; x++) {
            for (int y = bottom; y < bottom + BILL_FACE_H; y++) set(x, y, zFace, MAT);
        }

        // ► Южная грань (основная)
        placeOnPanel(leftX, bottom, zFace, Direction.SOUTH, BILL_FACE_W, BILL_FACE_H);
        // ► Северная грань (обратная сторона)
        placeOnPanel(leftX, bottom, zFace, Direction.NORTH, BILL_FACE_W, BILL_FACE_H);
        // ► Восточная узкая грань (ширина 1 по Z)
        placeOnStripX(leftX + BILL_FACE_W - 1, bottom, zFace, Direction.EAST, 1, BILL_FACE_H);
        // ► Западная узкая грань (ширина 1 по Z)
        placeOnStripX(leftX,                     bottom, zFace, Direction.WEST, 1, BILL_FACE_H);
    }

    // ===== Настенные форматы =====
    private void attachToNearestWall(int cx, int cz) {
        WallSpot spot = findNearestWall(cx, cz, WALL_SEARCH_R);
        if (spot != null) {
            int[] dY = new int[]{0, 1, -1, 2, -2};
            int[] sizes = new int[]{4, 3, 2, 1};
            for (int dy : dY) for (int s : sizes)
                if (tryPlaceFittingVariant(spot.front.getX(), spot.front.getY()+dy, spot.front.getZ(), spot.face, s, s)) return;
            tryPlacePainting1x1Front(spot.front.getX(), spot.front.getY(), spot.front.getZ(), spot.face);
            return;
        }

        // если стены нет — мини-стойка + экран 2×2 (на юг)
        int yBase = groundY(cx, cz);
        if (yBase == Integer.MIN_VALUE) return;
        int y = yBase + 1;
        for (int k=0;k<3;k++) set(cx, y+k, cz, MAT);
        for (int ox=0;ox<2;ox++) for (int oy=0;oy<2;oy++) set(cx-1+ox, y+3+oy, cz, MAT);
        tryPlaceFittingVariant(cx, y+4, cz+1, Direction.SOUTH, 2, 2);
    }

    private WallSpot findNearestWall(int cx, int cz, int radius) {
        Direction[] faces = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (int r=1; r<=radius; r++) {
            for (Direction face : faces) {
                int x = cx + (face.getAxis()==Direction.Axis.X ? (face==Direction.EAST? r : -r) : 0);
                int z = cz + (face.getAxis()==Direction.Axis.Z ? (face==Direction.SOUTH? r : -r) : 0);
                int gy = groundY(x, z); if (gy == Integer.MIN_VALUE) continue;
                int y = gy + 3;
                BlockPos wall = new BlockPos(x, y, z);
                BlockPos front = wall.relative(face);
                BlockState wallState = level.getBlockState(wall);
                if (!wallState.isAir() && wallState.isFaceSturdy(level, wall, face) && level.isEmptyBlock(front))
                    return new WallSpot(wall, front, face);
            }
        }
        return null;
    }

    // ===== Стенд 2×2×5 — обклейка 1×1 =====
    private void buildStandAndWrapWithPaintings(int cx, int cz) {
        int[][] offs = new int[][]{{0,0},{1,0},{0,1},{1,1}};
        int maxTop = Integer.MIN_VALUE;
        for (int[] o : offs) {
            int x = cx + o[0], z = cz + o[1];
            int yBase = groundY(x, z); if (yBase == Integer.MIN_VALUE) continue;
            int yStart = yBase + 1, yEnd = yStart + STAND_H - 1;
            for (int y = yStart; y <= yEnd; y++) set(x, y, z, MAT);
            maxTop = Math.max(maxTop, yEnd);
        }
        if (maxTop == Integer.MIN_VALUE) return;

        for (int oy=0; oy<STAND_H; oy++) {
            int yL = (groundY(cx, cz)+1) + oy;
            tryPlacePainting1x1Front(cx-1, yL, cz,   Direction.WEST);
            tryPlacePainting1x1Front(cx-1, yL, cz+1, Direction.WEST);

            int yR = (groundY(cx+1, cz)+1) + oy;
            tryPlacePainting1x1Front(cx+2, yR, cz,   Direction.EAST);
            tryPlacePainting1x1Front(cx+2, yR, cz+1, Direction.EAST);

            int yN0 = (groundY(cx,   cz)+1) + oy;
            int yN1 = (groundY(cx+1, cz)+1) + oy;
            tryPlacePainting1x1Front(cx,   yN0, cz-1, Direction.NORTH);
            tryPlacePainting1x1Front(cx+1, yN1, cz-1, Direction.NORTH);

            int yS0 = (groundY(cx,   cz+1)+1) + oy;
            int yS1 = (groundY(cx+1, cz+1)+1) + oy;
            tryPlacePainting1x1Front(cx,   yS0, cz+2, Direction.SOUTH);
            tryPlacePainting1x1Front(cx+1, yS1, cz+2, Direction.SOUTH);
        }
    }

    // ===== Картинки: ядро подбора/установки =====

    /** Панель в плоскости Z=fixedZ (направления NORTH/SOUTH); размер panelW×panelH (в блоках). */
    private void placeOnPanel(int minX, int minY, int fixedZ, Direction face, int panelW, int panelH) {
        List<Holder<PaintingVariant>> cand = collectVariantsUpTo(panelW, panelH);
        cand.sort(Comparator.comparingInt((Holder<PaintingVariant> h) ->
                (h.value().getWidth()/16) * (h.value().getHeight()/16)).reversed());

        for (Holder<PaintingVariant> h : cand) {
            for (int y = minY; y < minY + panelH; y++) {
                for (int x = minX; x < minX + panelW; x++) {
                    BlockPos front = new BlockPos(x, y, fixedZ).relative(face);
                    Painting p = new Painting(level, front, face, h);
                    if (p.survives()) { level.addFreshEntity(p); return; }
                }
            }
        }
        int midX = minX + panelW/2, midY = minY + panelH/2;
        tryPlacePainting1x1Front(new BlockPos(midX, midY, fixedZ).relative(face), face);
    }

    /** Узкая полоса в плоскости X=fixedX (направления EAST/WEST); ширина по Z = panelWz, высота = panelH. */
    private void placeOnStripX(int fixedX, int minY, int minZ, Direction face, int panelWz, int panelH) {
        List<Holder<PaintingVariant>> cand = collectVariantsUpTo(panelWz, panelH);
        cand.sort(Comparator.comparingInt((Holder<PaintingVariant> h) ->
                (h.value().getWidth()/16) * (h.value().getHeight()/16)).reversed());
        for (Holder<PaintingVariant> h : cand) {
            for (int y = minY; y < minY + panelH; y++) {
                for (int z = minZ; z < minZ + panelWz; z++) {
                    BlockPos front = new BlockPos(fixedX, y, z).relative(face);
                    Painting p = new Painting(level, front, face, h);
                    if (p.survives()) { level.addFreshEntity(p); return; }
                }
            }
        }
        tryPlacePainting1x1Front(new BlockPos(fixedX, minY + panelH/2, minZ).relative(face), face);
    }

    /** Подобрать вариант не больше maxBw×maxBh (в блоках) и поставить по якорю. */
    private boolean tryPlaceFittingVariant(int anchorX, int anchorY, int anchorZ, Direction face, int maxBw, int maxBh) {
        List<Holder<PaintingVariant>> cand = collectVariantsUpTo(maxBw, maxBh);
        cand.sort(Comparator.comparingInt((Holder<PaintingVariant> h) ->
                (h.value().getWidth()/16) * (h.value().getHeight()/16)).reversed());
        for (Holder<PaintingVariant> h : cand) {
            Painting p = new Painting(level, new BlockPos(anchorX, anchorY, anchorZ), face, h);
            if (p.survives()) { level.addFreshEntity(p); return true; }
        }
        return false;
    }

    /** Все варианты, помещающиеся в указанный размер (в блоках). */
    private List<Holder<PaintingVariant>> collectVariantsUpTo(int maxBw, int maxBh) {
        List<Holder<PaintingVariant>> out = new ArrayList<>();
        for (PaintingVariant v : ForgeRegistries.PAINTING_VARIANTS.getValues()) {
            int bw = Math.max(1, v.getWidth()  / 16);
            int bh = Math.max(1, v.getHeight() / 16);
            if (bw <= maxBw && bh <= maxBh) out.add(Holder.direct(v));
        }
        return out;
    }

    /** Строгая 1×1 (якорь — уже в воздухе перед гранью). */
    private boolean tryPlacePainting1x1Front(int xFront, int yFront, int zFront, Direction face) {
        for (PaintingVariant v : ForgeRegistries.PAINTING_VARIANTS.getValues()) {
            if (v.getWidth() == 16 && v.getHeight() == 16) {
                Painting p = new Painting(level, new BlockPos(xFront, yFront, zFront), face, Holder.direct(v));
                if (p.survives()) { level.addFreshEntity(p); return true; }
            }
        }
        return false;
    }
    private boolean tryPlacePainting1x1Front(BlockPos front, Direction face) {
        return tryPlacePainting1x1Front(front.getX(), front.getY(), front.getZ(), face);
    }

    // ===== Блоки / рельеф =====
    private void set(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
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

    // ===== Утилиты =====
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

    private static double[] centroidLatLon(JsonArray g) {
        double slat = 0, slon = 0; int n = g.size();
        for (JsonElement el : g) {
            JsonObject p = el.getAsJsonObject();
            slat += p.get("lat").getAsDouble();
            slon += p.get("lon").getAsDouble();
        }
        return new double[]{ slat / Math.max(1,n), slon / Math.max(1,n) };
    }
}