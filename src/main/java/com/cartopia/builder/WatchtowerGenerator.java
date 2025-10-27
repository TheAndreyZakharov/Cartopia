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
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WatchtowerGenerator {

    // ===== Конфиг визуала =====
    private static final Block MATERIAL_BODY   = Blocks.IRON_BLOCK; // площадка, ножки, домик и крыша
    private static final Block MATERIAL_GLASS  = Blocks.GLASS;        // окна домика
    private static final int   PLATFORM_HALF   = 2;                   // 5×5 ⇒ смещения -2..+2
    private static final int   LEG_HEIGHT      = 4;                   // высота ножек
    private static final int   HOUSE_LEN_Z     = 3;                   // «длина» домика (вглубь площадки)
    @SuppressWarnings("unused")
    private static final int   HOUSE_WID_X     = 5;                   // ширина домика по X (вся платформа)
    // Домик ставим на северной кромке платформы (zOffset = -2..0), дверь — с юга (в сторону свободного места)
    // Лестницы — с внешней южной стороны (к ножкам, где нет домика).

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public WatchtowerGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
            broadcast(level, "WatchtowerGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "WatchtowerGenerator: нет center/bbox — пропускаю.");
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
                    broadcast(level, "WatchtowerGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "WatchtowerGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), towers,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "WatchtowerGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (towers.isEmpty()) {
            broadcast(level, "WatchtowerGenerator: подходящих вышек не найдено — готово.");
            return;
        }

        int done = 0;
        for (Tower t : towers) {
            try {
                if (t.x<minX||t.x>maxX||t.z<minZ||t.z>maxZ) continue;
                renderTower(t);
            } catch (Exception ex) {
                broadcast(level, "WatchtowerGenerator: ошибка на ("+t.x+","+t.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, towers.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, towers.size()));
                broadcast(level, "Вышки: ~" + pct + "%");
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

        if (!isWatchtowerLike(tags)) return;

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

    /** lifeguard_tower или наблюдательные вышки (watch / observation / lookout). */
    private static boolean isWatchtowerLike(JsonObject t) {
        String mm = optString(t, "man_made");
        String em = optString(t, "emergency");

        // явные спасательные вышки
        if ("lifeguard_tower".equalsIgnoreCase(mm)) return true;
        if ("lifeguard_tower".equalsIgnoreCase(em)) return true;

        // наблюдательные варианты
        String towerType = optString(t, "tower:type");
        if (towerType != null) {
            String v = towerType.toLowerCase(Locale.ROOT);
            if (v.contains("watch") || v.contains("observation") || v.contains("lookout")) return true;
        }

        // альтернативные man_made
        if ("watchtower".equalsIgnoreCase(mm)) return true;
        if ("observation_tower".equalsIgnoreCase(mm)) return true; // встречается в дикой природе

        // иногда ещё как building
        String building = optString(t, "building");
        if ("lifeguard_tower".equalsIgnoreCase(building)) return true;

        return false;
    }

    // ===== Рендер одной вышки =====
    private void renderTower(Tower t) {
        // 1) Платформа 5×5: каждая клетка «сидит» на своём рельефе (ножки = 4 блока, сверху — настил)
        int[][] corners = new int[][]{
                {-PLATFORM_HALF, -PLATFORM_HALF},
                {-PLATFORM_HALF,  PLATFORM_HALF},
                { PLATFORM_HALF, -PLATFORM_HALF},
                { PLATFORM_HALF,  PLATFORM_HALF}
        };

        // сами 4 ножки на углах
        for (int[] c : corners) {
            int x = t.x + c[0], z = t.z + c[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            for (int dy = 1; dy <= LEG_HEIGHT; dy++) {
                setBlockSafe(x, yBase + dy, z, MATERIAL_BODY);
            }
        }

        // настил (верх площадки) — на высоте terrain+1+LEG_HEIGHT в каждой клетке 5×5
        for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) {
            for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
                int x = t.x + dx, z = t.z + dz;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                int yDeck = yBase + 1 + LEG_HEIGHT;
                setBlockSafe(x, yDeck, z, MATERIAL_BODY);
            }
        }

        // 2) Домик 5 (по X) × 3 (по Z) на северной кромке (z=-2..0).
        // Кладём «кольцами»: 1-й уровень — красный бетон, ещё 2 уровня — стекло, затем плоская крыша.
        for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) {
            for (int dz = -PLATFORM_HALF; dz <= -PLATFORM_HALF + (HOUSE_LEN_Z - 1); dz++) { // -2..0
                int x = t.x + dx, z = t.z + dz;
                int yDeck = terrainYFromCoordsOrWorld(x, z, null);
                if (yDeck == Integer.MIN_VALUE) continue;
                yDeck = yDeck + 1 + LEG_HEIGHT;

                boolean isPerimeter =
                        (dx == -PLATFORM_HALF || dx == PLATFORM_HALF || dz == -PLATFORM_HALF || dz == -PLATFORM_HALF + (HOUSE_LEN_Z - 1));

                if (isPerimeter) {
                    // нижний «пояс» (бетон)
                    setBlockSafe(x, yDeck + 1, z, MATERIAL_BODY);
                    // два пояса стекла
                    setBlockSafe(x, yDeck + 2, z, MATERIAL_GLASS);
                    setBlockSafe(x, yDeck + 3, z, MATERIAL_GLASS);
                } else {
                    // внутри домик пустой — ничего не ставим (оставляем воздух над настилом)
                }
                // крыша — плоская, закрывает всю площадь домика
                setBlockSafe(x, yDeck + 4, z, MATERIAL_BODY);
            }
        }

        // 3) Дверь из дуба — на южной стороне домика (в центр, z=0), смотрит на юг (к свободной части платформы)
        int doorX = t.x;      // центр по X
        int doorZ = t.z + 0;  // южная стенка домика — z=0 в локальных смещениях
        int doorBaseYDeck = terrainYFromCoordsOrWorld(doorX, doorZ, null);
        if (doorBaseYDeck != Integer.MIN_VALUE) {
            int yDeck = doorBaseYDeck + 1 + LEG_HEIGHT;
            // прорезаем проём в нижнем «блоке стены»
            setAir(doorX, yDeck + 1, doorZ);
            setAir(doorX, yDeck + 2, doorZ);

            // ставим дверь на настил (направление SOUTH — внутрь свободной зоны)
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, Direction.SOUTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.OPEN, Boolean.FALSE)
                    .setValue(DoorBlock.POWERED, Boolean.FALSE);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

            level.setBlock(new BlockPos(doorX, yDeck + 1, doorZ), lower, 3);
            level.setBlock(new BlockPos(doorX, yDeck + 2, doorZ), upper, 3);
        }

        // 4) Лестницы с внешней южной стороны, «к ножкам» (южные ножки — на z=+2).
        // Ставим лестницы на южных ножках (x=-2 и +2), на их южной стороне.
        int[][] southLegs = new int[][]{
                {t.x - PLATFORM_HALF, t.z + PLATFORM_HALF},
                {t.x + PLATFORM_HALF, t.z + PLATFORM_HALF}
        };
        for (int[] leg : southLegs) {
            int lx = leg[0], lz = leg[1];
            int yBase = terrainYFromCoordsOrWorld(lx, lz, null);
            if (yBase == Integer.MIN_VALUE) continue;
            int topY = yBase + LEG_HEIGHT; // верх ножки
            // Лестница ставится в блок К ЮГУ от ножки (lz+1), крепится к северной стороне (FACING = NORTH).
            for (int y = yBase + 1; y <= topY; y++) {
                placeLadder(lx, y, lz + 1, Direction.SOUTH);
            }
        }
    }

    // ===== Низкоуровневые сеттеры =====
    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }
    private void setAir(int x, int y, int z) {
        level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 3);
    }
    private void placeLadder(int x, int y, int z, Direction facing) {
        BlockPos pos = new BlockPos(x,y,z);
        BlockState st = Blocks.LADDER.defaultBlockState();
        try {
            st = st.setValue(LadderBlock.FACING, facing);
        } catch (Exception ignore) {}
        level.setBlock(pos, st, 3);
    }

    // ===== Рельеф =====
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
}