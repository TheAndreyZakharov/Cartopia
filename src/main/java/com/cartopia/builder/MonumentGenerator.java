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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MonumentGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Постамент
    private static final net.minecraft.world.level.block.Block BASE_BLOCK = Blocks.CHISELED_QUARTZ_BLOCK;

    public MonumentGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
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

    // Тип оставим для совместимости, но использовать будем один стиль
    private enum Kind { ARMOR_STAND, TWO_BLOCKS }

    @SuppressWarnings("unused")
    private static final class Obj {
        final int x, z;
        final Kind kind;
        Obj(int x, int z, Kind kind) { this.x = x; this.z = z; this.kind = kind; }
    }

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "MonumentGenerator: coords == null — пропускаю.");
            return;
        }
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "MonumentGenerator: нет center/bbox — пропускаю.");
            return;
        }

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

        List<Obj> list = new ArrayList<>();

        // ===== Сбор признаков со стримингом =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collect(e, list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "MonumentGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "MonumentGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collect(el.getAsJsonObject(), list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "MonumentGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (list.isEmpty()) {
            broadcast(level, "MonumentGenerator: подходящих объектов нет — готово.");
            return;
        }

        // ===== Построение =====
        int done = 0, total = list.size();
        broadcast(level, "Памятники: построение…");
        for (Obj o : list) {
            if (o.x < minX || o.x > maxX || o.z < minZ || o.z > maxZ) continue;
            try {
                // ЕДИНЫЙ стиль для всех
                placeArmorStandSculpture(o.x, o.z);
            } catch (Throwable t) {
                broadcast(level, "MonumentGenerator: ошибка на ("+o.x+","+o.z+"): " + t.getMessage());
            }
            done++;
            if (done % Math.max(1, total/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, total));
                broadcast(level, "Памятники: ~" + pct + "%");
            }
        }
        broadcast(level, "Памятники: готово.");
    }

    // ===== Разбор признаков =====
    private void collect(JsonObject e, List<Obj> out,
                         double centerLat, double centerLng,
                         double east, double west, double north, double south,
                         int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        Kind kind = classify(tags);
        if (kind == null) return;

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

        out.add(new Obj(ax, az, kind));
    }

    /**
     * Теперь ВСЁ, что попадает под арт/памятники, трактуем как ARMOR_STAND.
     */
    private Kind classify(JsonObject t) {
        String tourism = low(optString(t, "tourism"));
        String amenity = low(optString(t, "amenity"));
        String artwork = "artwork".equals(tourism) || "artwork".equals(amenity) ? "artwork" : null;
        String artworkType = low(optString(t, "artwork_type"));
        String manMade = low(optString(t, "man_made"));
        String historic = low(optString(t, "historic"));
        String memorial = low(optString(t, "memorial"));
        String memorialType = low(optString(t, "memorial:type"));
        String streetArt = low(optString(t, "street_art"));

        // Любой artwork → ставим стойку
        if ("artwork".equals(artwork)) return Kind.ARMOR_STAND;

        // Чёткие скульптурные признаки
        if (isAny(manMade, "statue","sculpture","memorial","monument","obelisk")) return Kind.ARMOR_STAND;

        // Исторические памятники/монументы/обелиски
        if (isAny(historic, "memorial","monument","obelisk")) return Kind.ARMOR_STAND;

        // Мемориальные уточнения
        if (isAny(memorial, "yes","statue","bust","plaque","stele","stela","war_memorial")) return Kind.ARMOR_STAND;
        if (isAny(memorialType, "statue","bust","obelisk","stele","plaque")) return Kind.ARMOR_STAND;

        // Стрит-арт и плоские работы — всё равно один стиль
        if ("yes".equals(streetArt) || isAny(artworkType, "mural","street_art","graffiti","relief","fresco"))
            return Kind.ARMOR_STAND;

        // Ничего профильного — пропускаем
        return null;
    }

    private static boolean isAny(String v, String... opts) {
        if (v == null) return false;
        for (String o : opts) if (v.equals(o)) return true;
        return false;
    }
    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

    // ===== Постройка =====

    /** Универсальный стиль: постамент + armor stand в железной броне. Ставим прямо «на землю». */
    private void placeArmorStandSculpture(int x, int z) {
        int gy = groundY(x, z);
        if (gy == Integer.MIN_VALUE) return;

        int baseY = gy + 1;
        setBlock(x, baseY, z, BASE_BLOCK);

        double sx = x + 0.5;
        double sy = baseY + 1.0;
        double sz = z + 0.5;

        ArmorStand stand = new ArmorStand(level, sx, sy, sz);
        try {
            stand.setNoGravity(false);
            stand.setInvisible(false);
            stand.setShowArms(true);
            stand.setInvulnerable(true); // чтобы случайно не сносило

            stand.setItemSlot(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
            stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            stand.setItemSlot(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
            stand.setItemSlot(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
        } catch (Throwable ignore) {}
        level.addFreshEntity(stand);
    }

    // Оставлено для совместимости; больше не вызывается
    @SuppressWarnings("unused")
    private void placeTwoBlocksMonument(int x, int z) {
        int gy = groundY(x, z);
        if (gy == Integer.MIN_VALUE) return;
        int y1 = gy + 1;
        int y2 = gy + 2;
        setBlock(x, y1, z, BASE_BLOCK);
        setBlock(x, y2, z, BASE_BLOCK);
    }

    // ===== Блоки / рельеф =====
    private void setBlock(int x, int y, int z, net.minecraft.world.level.block.Block block) {
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

    // ===== Утилиты координат =====
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
        double slat = 0, slon = 0;
        int n = g.size();
        for (JsonElement el : g) {
            JsonObject p = el.getAsJsonObject();
            slat += p.get("lat").getAsDouble();
            slon += p.get("lon").getAsDouble();
        }
        return new double[]{ slat / Math.max(1,n), slon / Math.max(1,n) };
    }
}