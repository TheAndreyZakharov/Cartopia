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
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class WasteGenerator {

    // Материалы
    private static final Block BIN_BLOCK          = Blocks.CAULDRON;        // урна
    private static final Block RECYCLING_CAULDRON = Blocks.CAULDRON;        // для линии переработки
    private static final Block RECYCLING_BARREL   = Blocks.BARREL;
    private static final Block RECYCLING_COMPOST  = Blocks.COMPOSTER;
    private static final Block SHELTER_BLOCK      = Blocks.GREEN_CONCRETE;  // навес и стены "П" из зелёного бетона

    // Внутренние типы
    private static final class Pt { final int x, z; Pt(int x,int z){this.x=x; this.z=z;} }
    private static final class Polyline { final List<Pt> pts = new ArrayList<>(); }

    // Коллекции позиций
    private final List<Pt> wasteBaskets   = new ArrayList<>(); // amenity=waste_basket
    private final List<Pt> recyclingSites = new ArrayList<>(); // amenity=recycling и recycling:*
    private final List<Pt> wasteDisposals = new ArrayList<>(); // amenity=waste_disposal
    private final List<Pt> otherWasteLike = new ArrayList<>(); // прочее мусорное -> как переработка
    private final List<Polyline> roads    = new ArrayList<>();

    // Контекст
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public WasteGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
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
        if (sourceIndex == null) { broadcast(level, "WasteGenerator: нет исходных данных — пропускаю."); return; }
        JsonObject center = sourceIndex.getAsJsonObject("center");
        JsonObject bbox   = sourceIndex.getAsJsonObject("bbox");
        if (center == null || bbox == null) { broadcast(level, "WasteGenerator: нет center/bbox — пропускаю."); return; }

        final double centerLat  = center.get("lat").getAsDouble();
        final double centerLng  = center.get("lng").getAsDouble();
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

        // === 1) Сбор (стримом или фолбэком)
        try {
            if (store != null) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) collect(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            } else {
                JsonArray elements = safeElementsArray(coords);
                if (elements == null) { broadcast(level, "WasteGenerator: features.elements пуст — пропускаю."); return; }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "WasteGenerator: ошибка чтения features: " + ex.getMessage());
        }

        // === 2) Постройка (каждый блок — по локальному рельефу)
        int total = wasteBaskets.size() + recyclingSites.size() + wasteDisposals.size() + otherWasteLike.size();
        int done = 0;

        for (Pt p : wasteBaskets)   { if (inBounds(p, minX, maxX, minZ, maxZ)) buildWasteBasket(p.x, p.z);     done++; progress(done, total); }
        for (Pt p : recyclingSites) { if (inBounds(p, minX, maxX, minZ, maxZ)) buildRecyclingModule(p.x, p.z); done++; progress(done, total); }
        for (Pt p : otherWasteLike) { if (inBounds(p, minX, maxX, minZ, maxZ)) buildRecyclingModule(p.x, p.z); done++; progress(done, total); }
        for (Pt p : wasteDisposals) { if (inBounds(p, minX, maxX, minZ, maxZ)) buildTrashShelter(p.x, p.z);    done++; progress(done, total); }

        broadcast(level, "WasteGenerator: готово.");
    }

    private void progress(int done, int total) {
        if (total <= 0) return;
        if (done % Math.max(1, total/5) == 0) {
            int pct = (int)Math.round(100.0 * done / Math.max(1, total));
            broadcast(level, "Мусорная инфраструктура: ~" + pct + "%");
        }
    }

    // === Сбор одной фичи ===
    private void collect(JsonObject e,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject t = tags(e);
        if (t == null) return;

        // дороги складываем отдельно — для ориентации
        if (isRoadWay(t) && hasGeometry(e)) {
            Polyline pl = new Polyline();
            JsonArray g = geometry(e);
            for (int i=0; i<g.size(); i++) {
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                pl.pts.add(new Pt(xz[0], xz[1]));
            }
            if (pl.pts.size() >= 2) roads.add(pl);
            return;
        }

        // Точка/центр
        Pt pos = toPoint(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        if (pos == null) return;

        String amenity = low(opt(t, "amenity"));

        // 1) УРНЫ
        if ("waste_basket".equals(amenity)) {
            wasteBaskets.add(pos);
            return;
        }

        // 2) ПЕРЕРАБОТКА (recycling)
        if (isRecyclingLike(t)) {
            recyclingSites.add(pos);
            return;
        }

        // 3) МУСОРКА / СБОР МУСОРА с навесом
        if (isWasteDisposalLike(t)) {
            wasteDisposals.add(pos);
            return;
        }

        // 4) Прочие "мусорные" — делаем как переработку (3x2)
        if (isOtherWasteLike(t)) {
            otherWasteLike.add(pos);
        }
    }

    // === Постройка ===

    /** 1x1 урна (каулдрон) строго по рельефу. */
    private void buildWasteBasket(int x, int z) {
        placeBlockOnTerrain(x, z, BIN_BLOCK);
    }


    private void buildRecyclingModule(int x, int z) {
        Direction axisDir = roadAxisAt(x, z);
        int[] axis = dirToStep(axisDir);           // вдоль линии (3)
        int[] nor  = new int[]{-axis[1], axis[0]}; // поперёк (2)

        // две строки рядом: offset 0 и +1 по нормали
        for (int off = 0; off <= 1; off++) {
            // три позиции вдоль оси: 0..2
            for (int i = 0; i <= 2; i++) {
                int bx = x + axis[0]*i + nor[0]*off;
                int bz = z + axis[1]*i + nor[1]*off;
                Block block = (i == 0) ? RECYCLING_CAULDRON : (i == 1) ? RECYCLING_BARREL : RECYCLING_COMPOST;
                placeBlockOnTerrain(bx, bz, block);
            }
        }
    }


    private void buildTrashShelter(int x, int z) {
        Direction axisDir = roadAxisAt(x, z);
        int[] axis = dirToStep(axisDir);           // длина 2
        int[] nor  = new int[]{-axis[1], axis[0]}; // ширина 4 итого: стена(1) + просвет(2) + стена(1)

        // Позиции стен: на оффсетах -2 и +1 (между ними остаются оффсеты -1 и 0 — это просвет 2)
        int[] wallOffsets = new int[]{-2, +1};

        // --- Стены (высота 2)
        for (int off : wallOffsets) {
            for (int i = 0; i < 2; i++) { // длина 2
                int bx = x + axis[0]*i + nor[0]*off;
                int bz = z + axis[1]*i + nor[1]*off;
                int gy = groundY(bx, bz);
                if (gy == Integer.MIN_VALUE) continue;
                // два блока вверх
                setBlock(bx, gy + 1, bz, SHELTER_BLOCK);
                setBlock(bx, gy + 2, bz, SHELTER_BLOCK);
            }
        }

        // --- Крыша (ширина 4: -2,-1,0,+1; длина 2: i=0..1) на +3 от ground локально
        for (int w = -2; w <= 1; w++) {
            for (int i = 0; i < 2; i++) {
                int rx = x + axis[0]*i + nor[0]*w;
                int rz = z + axis[1]*i + nor[1]*w;
                int gy = groundY(rx, rz);
                if (gy == Integer.MIN_VALUE) continue;
                setBlock(rx, gy + 3, rz, SHELTER_BLOCK);
            }
        }

        // --- "Ребро" по центру крыши (2x2 над оффсетами -1..0; i=0..1) на +4
        for (int w = -1; w <= 0; w++) {
            for (int i = 0; i < 2; i++) {
                int rx = x + axis[0]*i + nor[0]*w;
                int rz = z + axis[1]*i + nor[1]*w;
                int gy = groundY(rx, rz);
                if (gy == Integer.MIN_VALUE) continue;
                setBlock(rx, gy + 4, rz, SHELTER_BLOCK);
            }
        }

        // --- Наполнение просвета 2x2 каулдронами на земле: оффсеты -1 и 0; i=0..1
        for (int w = -1; w <= 0; w++) {
            for (int i = 0; i < 2; i++) {
                int gx = x + axis[0]*i + nor[0]*w;
                int gz = z + axis[1]*i + nor[1]*w;
                placeBlockOnTerrain(gx, gz, BIN_BLOCK);
            }
        }
    }

    // === Вспомогательные низкоуровневые примитивы ===

    private void placeBlockOnTerrain(int x, int z, Block b) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;
        level.setBlock(new BlockPos(x, y + 1, z), b.defaultBlockState(), 3);
    }

    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }

    // === Ориентация по ближайшей дороге ===

    private boolean isRoadWay(JsonObject t) { return t.has("highway") && !t.has("railway") && !t.has("waterway"); }

    /** Возвращает ориентир оси дороги в этой точке (E/W/N/S). */
    private Direction roadAxisAt(int x, int z) {
        if (roads.isEmpty()) return Direction.EAST;
        double bestD2 = Double.MAX_VALUE;
        int vx = 1, vz = 0;

        for (Polyline pl : roads) {
            for (int i=0; i<pl.pts.size()-1; i++) {
                Pt a = pl.pts.get(i);
                Pt b = pl.pts.get(i+1);
                int dx = b.x - a.x, dz = b.z - a.z;
                double len2 = (double)dx*dx + (double)dz*dz;
                if (len2 <= 1e-6) continue;

                double t = ((x - a.x)*dx + (z - a.z)*dz) / len2;
                if (t < 0) t = 0; else if (t > 1) t = 1;

                double px = a.x + t*dx, pz = a.z + t*dz;
                double ddx = x - px, ddz = z - pz;
                double d2 = ddx*ddx + ddz*ddz;
                if (d2 < bestD2) { bestD2 = d2; vx = dx; vz = dz; }
            }
        }
        if (Math.abs(vx) >= Math.abs(vz)) return (vx >= 0) ? Direction.EAST : Direction.WEST;
        return (vz >= 0) ? Direction.SOUTH : Direction.NORTH;
    }

    private static int[] dirToStep(Direction d) {
        switch (d) {
            case EAST:  return new int[]{+1, 0};
            case WEST:  return new int[]{-1, 0};
            case SOUTH: return new int[]{0, +1};
            case NORTH: return new int[]{0, -1};
            default:    return new int[]{1, 0};
        }
    }

    // === преобразования/утилиты данных ===

    private static boolean inBounds(Pt p, int minX, int maxX, int minZ, int maxZ) {
        return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ;
    }

    private static JsonObject tags(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }

    private static boolean hasKeyStartsWith(JsonObject t, String prefix) {
        for (String k : t.keySet()) if (k.toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
        return false;
    }

    private static boolean hasKeyContains(JsonObject t, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
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

    private static String opt(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private static boolean hasGeometry(JsonObject e) {
        return e.has("geometry") && e.get("geometry").isJsonArray() && e.getAsJsonArray("geometry").size() >= 2;
    }
    private static JsonArray geometry(JsonObject e) {
        return (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
    }
    private static JsonArray safeElementsArray(JsonObject coords) {
        if (coords == null) return null;
        if (!coords.has("features")) return null;
        JsonObject f = coords.getAsJsonObject("features");
        if (f == null || !f.has("elements")) return null;
        return f.getAsJsonArray("elements");
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
            for (int i=0; i<g.size(); i++) {
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

    // === определители тегов ===

    /** recycling-подобные: amenity=recycling, recycling_type=*, любые ключи recycling:* или значения 'recycling'. */
    private static boolean isRecyclingLike(JsonObject t) {
        String amenity = low(opt(t, "amenity"));
        if ("recycling".equals(amenity)) return true;
        if (hasKeyStartsWith(t, "recycling")) return true;        // recycling:scrap_metal=yes и т.п.
        if (hasKeyContains(t, "recycling")) return true;
        if (containsValueLike(t, "recycling")) return true;
        return false;
    }

    /** мусорки/сбор мусора под навес: amenity=waste_disposal (и явные синонимы). */
    private static boolean isWasteDisposalLike(JsonObject t) {
        String amenity = low(opt(t, "amenity"));
        if ("waste_disposal".equals(amenity)) return true;
        // если захотим расширить:
        // if ("waste_bunker".equals(amenity)) return true;
        return false;
    }

    /** прочие "мусорные" — сваливаем в переработку-формат 3x2 */
    private static boolean isOtherWasteLike(JsonObject t) {
        String amenity = low(opt(t, "amenity"));
        if ("sanitary_dump_station".equals(amenity)) return true; // приём стоков из кемперов и т.п.
        // любые упоминания мусора/отходов/garbage/refuse/trash
        if (containsValueLike(t, "waste"))   return true;
        if (containsValueLike(t, "garbage")) return true;
        if (containsValueLike(t, "trash"))   return true;
        if (containsValueLike(t, "refuse"))  return true;
        return false;
    }
}