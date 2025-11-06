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
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class ClassicWindmillGenerator {

    // ---- Материалы/размеры ----
    private static final Block TOWER_BLOCK = Blocks.OAK_PLANKS;
    private static final Block ROOF_BLOCK  = Blocks.DARK_OAK_PLANKS;
    private static final Block BLADE_BLOCK = Blocks.BIRCH_PLANKS;
    private static final Block BLADE_FENCE = Blocks.BIRCH_FENCE;

    private static final int TOWER_H     = 20; // высота «стен»
    private static final int R_BASE      = 5;  // радиус у основания (диаметр 10)
    private static final int R_TOP       = 4;  // радиус у верха (диаметр 8)
    private static final int ROOF_HALF   = 5;  // половина ширины крыши (10x10)
    private static final int BRACKET_LEN = 4;  // длина кронштейна 2x2 на юг
    private static final int HUB_OFFSET  = 3;  // лопасти на 3 блока от поверхности башни
    private static final int BLADE_LEN   = 10; // длина одной лопасти (от оси до конца)
    private static final int BLADE_W     = 2;  // ширина лопасти

    private static final int FENCE_ROWS = 2;             // сколько параллельных рядов ставим
    private static final int FENCE_SKIP_NEAR_HUB_H = 2;  // пропуск у оси для горизонтальных заборов (убирает пересечение)
    private static final int FENCE_SKIP_NEAR_HUB_V = 0;  // пропуск у оси для вертикальных заборов

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public ClassicWindmillGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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

    // ---- Тип ----
    private static final class Windmill {
        final int x, z;
        Windmill(int x, int z) { this.x=x; this.z=z; }
    }

    // ---- Запуск ----
    public void generate() {
        if (coords == null) { broadcast(level, "ClassicWindmill: coords == null — skipping."); return; }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "ClassicWindmill: no center/bbox — skipping."); return; }

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
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        List<Windmill> list = new ArrayList<>();

        // ---- Сбор признаков со стримингом ----
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectWindmill(e, list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) { broadcast(level, "ClassicWindmill: no coords.features — skipping."); return; }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) { broadcast(level, "ClassicWindmill: features.elements is empty — skipping."); return; }
                for (JsonElement el : elements) {
                    collectWindmill(el.getAsJsonObject(), list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "ClassicWindmill: error reading features: " + ex.getMessage());
        }

        if (list.isEmpty()) { broadcast(level, "ClassicWindmill: no suitable windmills found — done."); return; }

        // ---- Построение ----
        int done = 0;
        for (Windmill w : list) {
            if (w.x<minX||w.x>maxX||w.z<minZ||w.z>maxZ) continue;
            try {
                buildWindmill(w.x, w.z);
            } catch (Throwable t) {
                broadcast(level, "ClassicWindmill: error at ("+w.x+","+w.z+"): " + t.getMessage());
            }
            done++;
            if (done % Math.max(1, list.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, list.size()));
                broadcast(level, "Windmills: ~" + pct + "%");
            }
        }

        broadcast(level, "ClassicWindmill: done.");
    }

    // ---- Фильтр OSM ----
    private void collectWindmill(JsonObject e, List<Windmill> out,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isWindmill(tags)) return;

        String type = optString(e, "type");
        int tx, tz;

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
            tx = xz[0]; tz = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() == 0) return;
            long sx = 0, sz = 0;
            for (JsonElement el : g) {
                JsonObject p = el.getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                sx += xz[0]; sz += xz[1];
            }
            tx = (int)Math.round(sx / (double)g.size());
            tz = (int)Math.round(sz / (double)g.size());
        } else return;

        out.add(new Windmill(tx, tz));
    }

    private boolean isWindmill(JsonObject t) {
        String m = optString(t, "man_made");
        if (m != null && m.equalsIgnoreCase("windmill")) return true;
        String b = optString(t, "building");
        if (b != null && b.equalsIgnoreCase("windmill")) return true;
        String hist = optString(t, "historic");
        return hist != null && hist.equalsIgnoreCase("windmill");
    }

    // ---- Постройка одной мельницы ----
    private void buildWindmill(int cx, int cz) {
        // 1) Башня: «по рельефу», послойно, с плавным сужением к верху
        for (int dy = 0; dy < TOWER_H; dy++) {
            int r = radiusAtLayer(dy); // 5 -> 4
            int rr = r * r;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx*dx + dz*dz > rr) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    int yBase = groundY(x, z);
                    if (yBase == Integer.MIN_VALUE) continue;
                    int y = yBase + 1 + dy;
                    set(x, y, z, TOWER_BLOCK);
                }
            }
        }

        // 2) Крыша-«пирамидка» 10x10 (hip), тоже «по рельефу»
        int x0 = cx - ROOF_HALF, x1 = cx + ROOF_HALF - 1; // 10 клеток
        int z0 = cz - ROOF_HALF, z1 = cz + ROOF_HALF - 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int yBase = groundY(x, z);
                if (yBase == Integer.MIN_VALUE) continue;
                int yRoofBase = yBase + TOWER_H;
                int dxEdge = Math.min(x - x0, x1 - x);
                int dzEdge = Math.min(z - z0, z1 - z);
                int h = Math.max(0, Math.min(dxEdge, dzEdge)); // 0..4
                set(x, yRoofBase + h, z, ROOF_BLOCK);
            }
        }

        // 3) Узел/хаб и кронштейн, который соединён с самой крышей
        int yRoofCenter = groundY(cx, cz) + TOWER_H + 1; // «основание» крыши
        int zRoofSouth  = cz + ROOF_HALF - 1;            // южная кромка квадрата крыши
        int faceSouthZ  = cz + R_BASE;                   // южная грань башни
        int hubZ        = cz + R_BASE + HUB_OFFSET;      // ось лопастей на 3 блока южнее башни

        // Кронштейн 2×2: тянем от самой крыши (zRoofSouth) до узла (hubZ) включительно
        for (int z = Math.min(zRoofSouth, hubZ); z <= Math.max(zRoofSouth, hubZ); z++) {
            for (int ox = -1; ox <= 0; ox++) {
                for (int oy = 0; oy <= 1; oy++) {
                    set(cx + ox, yRoofCenter + oy, z, ROOF_BLOCK);
                }
            }
        }

        // Небольшой «узел» 2×2 в плоскости X–Y на уровне hubZ
        for (int ox = -1; ox <= 0; ox++) {
            for (int oy = 0; oy <= 1; oy++) {
                set(cx + ox, yRoofCenter + 1 + oy, hubZ, ROOF_BLOCK);
            }
        }
        int hubY = yRoofCenter + 1; // центр хаба по Y

        // 5) Лопасти в плоскости X–Y при фиксированном Z=hubZ

        // ВЕРХНЯЯ (dirY=+1), ширина по X: fence СПРАВА от неё
        drawBladeVertical(cx-1, cx, hubY, hubZ, +1, BLADE_LEN, /*fenceOnRight=*/true);

        // НИЖНЯЯ (dirY=-1), fence СЛЕВА от неё
        drawBladeVertical(cx-1, cx, hubY-1, hubZ, -1, BLADE_LEN, /*fenceOnRight=*/false);

        // ПРАВАЯ (dirX=+1), ширина по Y: fence СНИЗУ от неё
        drawBladeHorizontal(cx, hubY,   hubZ, +1, BLADE_LEN, FenceVerticalPos.BELOW);

        // ЛЕВАЯ (dirX=-1), fence СВЕРХУ от неё
        drawBladeHorizontal(cx, hubY-1, hubZ, -1, BLADE_LEN, FenceVerticalPos.ABOVE);

    }

    // dy=0..TOWER_H-1 → радиус 5 → 4
    private int radiusAtLayer(int dy) {
        // линейное сужение 5 → 4 вдоль высоты (минимум 4)
        double t = (TOWER_H <= 1) ? 1.0 : (dy / (double)(TOWER_H - 1));
        double rf = R_BASE + (R_TOP - R_BASE) * t; // 5 -> 4
        int r = (int)Math.round(rf);
        if (r < R_TOP) r = R_TOP;
        if (r > R_BASE) r = R_BASE;
        return r;
    }

    private enum FenceVerticalPos { ABOVE, BELOW }

    // Вертикальная лопасть: X ∈ [xMin..xMax], идёт по Y.
    // fenceOnRight = true → забор справа (xMax+1, xMax+2); false → слева (xMin-1, xMin-2).
    private void drawBladeVertical(int xMin, int xMax, int y0, int z, int dirY, int len, boolean fenceOnRight) {
        int baseFenceX = fenceOnRight ? (xMax + 1) : (xMin - 1);
        int stepX = fenceOnRight ? +1 : -1; // второй ряд уходит дальше наружу
        int y = y0;
        for (int s = 0; s < len; s++) {
            // сама лопасть (ширина 2 по X)
            for (int x = xMin; x <= xMax; x++) {
                set(x, y, z, BLADE_BLOCK);
            }
            // заборы — без пропуска у оси (тут конфликтов нет), но оставил параметр на будущее
            if (s >= FENCE_SKIP_NEAR_HUB_V) {
                for (int r = 0; r < FENCE_ROWS; r++) {
                    set(baseFenceX + r * stepX, y, z, BLADE_FENCE);
                }
            }
            y += dirY;
        }
    }

    // Горизонтальная лопасть: идёт по X, ширина 2 по Y (y0 и y0+1).
    // FenceVerticalPos.ABOVE → забор сверху (y0+2, y0+3), BELOW → снизу (y0-1, y0-2).
    private void drawBladeHorizontal(int x0, int y0, int z, int dirX, int len, FenceVerticalPos fencePos) {
        int baseFenceY = (fencePos == FenceVerticalPos.ABOVE) ? (y0 + BLADE_W) : (y0 - 1);
        int stepY = (fencePos == FenceVerticalPos.ABOVE) ? +1 : -1; // второй ряд уходит дальше от лопасти
        int x = x0;
        for (int s = 0; s < len; s++) {
            // сама лопасть (2 по Y)
            for (int oy = 0; oy < BLADE_W; oy++) {
                set(x, y0 + oy, z, BLADE_BLOCK);
            }
            // ключевой момент: у оси (s=0,1) ПРопускаем забор, чтобы не перетирать перпендикулярную лопасть
            if (s >= FENCE_SKIP_NEAR_HUB_H) {
                for (int r = 0; r < FENCE_ROWS; r++) {
                    set(x, baseFenceY + r * stepY, z, BLADE_FENCE);
                }
            }
            x += dirX;
        }
    }
    // ---- Блоки/рельеф ----
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

    // ---- Парсинг/утилиты ----
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