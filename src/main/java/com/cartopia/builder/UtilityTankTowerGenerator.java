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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class UtilityTankTowerGenerator {

    // ===== Конфиг визуала =====
    private static final Block MATERIAL_BODY       = Blocks.IRON_BLOCK;  // металл: ножки, стенки, купола
    private static final int   RADIUS              = 3;                  // диаметр 6
    private static final int   LEG_HEIGHT          = 4;                  // высота ножек
    private static final int   CYLINDER_HEIGHT     = 10;                 // «основная часть» над ножками
    private static final int   DOME_HEIGHT         = 3;                  // верх/низ куполов (слоёв)
    // высота нижней точки: локальный groundY + 1 + LEG_HEIGHT

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public UtilityTankTowerGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
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
    private static final class PolyXZ {
        @SuppressWarnings("unused")
        final int[] xs, zs; final int n; final int cx, cz;
        PolyXZ(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            long sx = 0, sz = 0;
            for (int i=0;i<n;i++){ sx+=xs[i]; sz+=zs[i]; }
            cx = (int)Math.round(sx/(double)n);
            cz = (int)Math.round(sz/(double)n);
        }
    }
    private static final class Tower {
        final int x,z;
        Tower(int x, int z) { this.x=x; this.z=z; }
    }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "UtilityTankTowerGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "UtilityTankTowerGenerator: нет center/bbox — пропускаю.");
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

        List<Tower> towers = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, towers,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "UtilityTankTowerGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "UtilityTankTowerGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), towers,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "UtilityTankTowerGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (towers.isEmpty()) {
            broadcast(level, "UtilityTankTowerGenerator: подходящих объектов не найдено — готово.");
            return;
        }

        int done = 0;
        for (Tower t : towers) {
            try {
                if (t.x<minX||t.x>maxX||t.z<minZ||t.z>maxZ) continue;
                renderTower(t);
            } catch (Exception ex) {
                broadcast(level, "UtilityTankTowerGenerator: ошибка на ("+t.x+","+t.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, towers.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, towers.size()));
                broadcast(level, "Башни-резервуары: ~" + pct + "%");
            }
        }
    }

    // ===== Разбор фич =====
    private void collectFeature(JsonObject e, List<Tower> out,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        if (!isTankLikeEligible(tags)) return;

        String type = optString(e,"type");
        int tx, tz;

        if ("node".equals(type)) {
            double lat, lon;
            if (e.has("lat") && e.has("lon")) {
                lat = e.get("lat").getAsDouble();
                lon = e.get("lon").getAsDouble();
            } else {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble();
                lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            tx = xz[0]; tz = xz[1];
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
            tx = poly.cx; tz = poly.cz;
        } else return;

        out.add(new Tower(tx, tz));
    }

    //Белый список башен на ножках
    private static boolean isTankLikeEligible(JsonObject t) {
        // 1) Должно отсутствовать любое "building"
        if (t.has("building")) return false;

        // Жёсткие отсеки — если видим эти ключи/значения, сразу мимо
        if (t.has("power")) return false;
        if (t.has("telecom")) return false;
        String manMade = low(optString(t, "man_made"));
        String towerType = low(optString(t, "tower:type"));
        String comms    = low(optString(t, "communication"));
        if (comms != null) return false;

        if (manMade != null) {
            if (manMade.equals("mast") || manMade.equals("flagpole") || manMade.equals("lighthouse")
                    || manMade.equals("windmill") || manMade.equals("cooling_tower")
                    || manMade.equals("chimney") || manMade.equals("smokestack")) return false;

            // Смотровые/наблюдательные/коммуникационные башни мимо
            if (manMade.equals("tower")) {
                if (towerType != null) {
                    if (towerType.contains("communication") || towerType.contains("telecom")
                            || towerType.contains("observation") || towerType.contains("watch")
                            || towerType.contains("lookout") || towerType.contains("lighting")) {
                        return false;
                    }
                    if (towerType.contains("water")) return true;
                }
                return false;
            }
        }

        // Белый список «резервуароподобных»
        if ("water_tower".equals(manMade))   return true;
        if ("storage_tank".equals(manMade))  return true;
        if ("silo".equals(manMade))          return true;   // зерно/семена — просили добавить
        if ("tank".equals(manMade))          return true;
        if ("water_tank".equals(manMade))    return true;

        return false;
    }

    // ===== Рендер одной башни =====
    private void renderTower(Tower t) {
        // 1) Четыре ножки в позициях (±R,0), (0,±R)
        int[][] legs = new int[][]{
                { t.x + RADIUS, t.z },
                { t.x - RADIUS, t.z },
                { t.x,          t.z + RADIUS },
                { t.x,          t.z - RADIUS }
        };
        for (int[] p : legs) {
            int x = p[0], z = p[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            for (int dy = 1; dy <= LEG_HEIGHT; dy++) {
                setBlockSafe(x, yBase + dy, z, MATERIAL_BODY);
            }
        }

        // 2) Корпус: круглый цилиндр радиуса 3, «сидящий» на локальном (x,z)-настиле (groundY+1+LEG_HEIGHT)
        //    Для каждой ячейки цилиндра свой yDeck — как в примере с площадкой.
        int r = RADIUS;
        int r2 = r * r;
        int min = -r - 2, max = r + 2; // чуть больше бокс для куполов

        for (int dx = min; dx <= max; dx++) {
            for (int dz = min; dz <= max; dz++) {
                int x = t.x + dx, z = t.z + dz;
                int dist2 = dx*dx + dz*dz;

                // стенка цилиндра — «тонкое кольцо»
                boolean onWall = (dist2 >= (r2 - 1)) && (dist2 <= (r2 + 1));
                if (onWall) {
                    int yDeck = terrainYFromCoordsOrWorld(x, z, null);
                    if (yDeck == Integer.MIN_VALUE) continue;
                    yDeck = yDeck + 1 + LEG_HEIGHT;
                    for (int h = 0; h < CYLINDER_HEIGHT; h++) {
                        setBlockSafe(x, yDeck + h, z, MATERIAL_BODY);
                    }
                }
            }
        }

        // 3) Верхний купол (3 слоя + центральная кнопка)
        // Слой 0: радиус 3, слой 1: 2, слой 2: 1, слой 3: центр.
        for (int dx = min; dx <= max; dx++) {
            for (int dz = min; dz <= max; dz++) {
                int x = t.x + dx, z = t.z + dz;
                int dist2 = dx*dx + dz*dz;
                int yDeck = terrainYFromCoordsOrWorld(x, z, null);
                if (yDeck == Integer.MIN_VALUE) continue;
                yDeck = yDeck + 1 + LEG_HEIGHT;

                for (int layer = 0; layer < DOME_HEIGHT; layer++) {
                    int rr = Math.max(0, RADIUS - layer); // 3,2,1
                    if (rr == 0) break; // центральную кнопку поставим отдельно
                    int rr2 = rr*rr;
                    boolean onRing = (dist2 >= (rr2 - 1)) && (dist2 <= (rr2 + 1));
                    if (onRing) {
                        setBlockSafe(x, yDeck + CYLINDER_HEIGHT + layer, z, MATERIAL_BODY);
                    }
                }
            }
        }
        // центральная «кнопка» купола
        int centerYTop = terrainYFromCoordsOrWorld(t.x, t.z, null);
        if (centerYTop != Integer.MIN_VALUE) {
            centerYTop = centerYTop + 1 + LEG_HEIGHT + CYLINDER_HEIGHT + DOME_HEIGHT;
            setBlockSafe(t.x, centerYTop, t.z, MATERIAL_BODY);
        }

        // 4) Нижний купол (инвертированный): 3 слоя вниз от «дек»-уровня
        for (int dx = min; dx <= max; dx++) {
            for (int dz = min; dz <= max; dz++) {
                int x = t.x + dx, z = t.z + dz;
                int dist2 = dx*dx + dz*dz;
                int yDeck = terrainYFromCoordsOrWorld(x, z, null);
                if (yDeck == Integer.MIN_VALUE) continue;
                yDeck = yDeck + 1 + LEG_HEIGHT;

                for (int layer = 1; layer <= DOME_HEIGHT; layer++) { // вниз
                    int rr = Math.max(0, RADIUS - (layer - 1)); // 3,2,1
                    if (rr == 0) break;
                    int rr2 = rr*rr;
                    boolean onRing = (dist2 >= (rr2 - 1)) && (dist2 <= (rr2 + 1));
                    if (onRing) {
                        setBlockSafe(x, yDeck - layer, z, MATERIAL_BODY);
                    }
                }
            }
        }
    }

    // ===== Низкоуровневые сеттеры/утилиты =====
    private void setBlockSafe(int x, int y, int z, Block block) {
        BlockPos pos = new BlockPos(x,y,z);
        BlockState st = block.defaultBlockState();
        level.setBlock(pos, st, 3);
    }

    // Рельеф — сначала пробуем store.grid, потом coords.terrainGrid, потом мир
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

    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

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