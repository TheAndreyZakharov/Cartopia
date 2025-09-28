package com.cartopia.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class RoadLampGenerator {

    // === ПАРАМЕТРЫ ЛАМП ===
    private static final int ROAD_LAMP_PERIOD = 20;          // шаг по длине дороги
    private static final int ROAD_LAMP_COLUMN_WALLS = 5;     // высота колонны из стен
    private static final class Counter { int v = 0; }

    // Чтобы лампы не дублировались/не «садились» друг на друга
    private final Set<Long> roadLampBases = new HashSet<>();
    private final Set<Long> placedByRoadLamps = new HashSet<>();

    private final ServerLevel level;
    private final JsonObject coords;

    public RoadLampGenerator(ServerLevel level, JsonObject coords) {
        this.level = level;
        this.coords = coords;
    }

    // --- широковещалка ---
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

    // === Материалы дорог (ТОЧНО как в RoadGenerator) ===
    private static final class RoadStyle {
        final String blockId;
        final int width;
        RoadStyle(String vanillaName, int width) {
            this.blockId = "minecraft:" + vanillaName;
            this.width = Math.max(1, width);
        }
    }

    private static final Map<String, RoadStyle> ROAD_MATERIALS = new HashMap<>();
    static {
        ROAD_MATERIALS.put("motorway",     new RoadStyle("gray_concrete", 20));
        ROAD_MATERIALS.put("trunk",        new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("primary",      new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("secondary",    new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("tertiary",     new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("residential",  new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("unclassified", new RoadStyle("stone", 6));
        ROAD_MATERIALS.put("service",      new RoadStyle("stone", 5));
        ROAD_MATERIALS.put("footway",      new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("path",         new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("cycleway",     new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("pedestrian",   new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("track",        new RoadStyle("cobblestone", 4));
        ROAD_MATERIALS.put("aeroway:runway",   new RoadStyle("gray_concrete", 45));
        ROAD_MATERIALS.put("aeroway:taxiway",  new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("aeroway:taxilane", new RoadStyle("gray_concrete", 8));
        ROAD_MATERIALS.put("rail",         new RoadStyle("rail", 1)); // совместимость
    }

    // Все блок-id полотна дорог (на них лампы СТРОГО запрещены)
    private static final Set<String> ROAD_BLOCK_IDS = new HashSet<>();
    static {
        for (RoadStyle s : ROAD_MATERIALS.values()) {
            ROAD_BLOCK_IDS.add(s.blockId);
        }
    }

    private static boolean isRoadLikeBlock(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        return key != null && ROAD_BLOCK_IDS.contains(key.toString());
    }

    // ==== ПУБЛИЧНЫЙ ЗАПУСК: ТОЛЬКО ФОНАРИ ====
    public void generate() {
        broadcast(level, "💡 Расставляю дорожные фонари вокруг уже построенных дорог…");

        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю RoadLampGenerator.");
            return;
        }

        // Геопривязка и границы
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble())
                : 0;
        final int centerZ = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble())
                : 0;

        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements пуст — пропускаю фонари.");
            return;
        }

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRoadCandidate(tags)) continue;            // только линии-дороги
            if (isBridgeOrTunnel(tags)) continue;            // мосты/тоннели пропускаем
            if (!"way".equals(optString(e,"type"))) continue;
            if (!e.has("geometry") || !e.get("geometry").isJsonArray()) continue;
            if (e.getAsJsonArray("geometry").size() < 2) continue;
            totalWays++;
        }

        int processed = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;

            if (!isRoadCandidate(tags)) continue;
            if (isBridgeOrTunnel(tags)) continue;
            if (!"way".equals(optString(e,"type"))) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            String highway = optString(tags, "highway");
            String aeroway = optString(tags, "aeroway");
            String styleKey = (highway != null) ? highway
                            : (aeroway != null ? "aeroway:" + aeroway : "");
            RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));

            int widthBlocks = widthFromTagsOrDefault(tags, style.width);

            // счётчик для шага ламп по этой дороге
            Counter lamp = new Counter();

            int prevLX = Integer.MIN_VALUE, prevLZ = Integer.MIN_VALUE;
            Integer hint = null;
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                double lat = p.get("lat").getAsDouble();
                double lon = p.get("lon").getAsDouble();
                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                int x = xz[0], z = xz[1];

                if (prevLX != Integer.MIN_VALUE) {
                    placeLampsAlongSegment(prevLX, prevLZ, x, z, widthBlocks, hint,
                            minX, maxX, minZ, maxZ, lamp);
                }
                prevLX = x; prevLZ = z;
            }

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Фонари вдоль дорог: ~" + pct + "%");
            }
        }

        broadcast(level, "Фонари для дорог готовы.");
    }

    // === ОТБОР ===

    /** Только дороги; НЕ брать ж/д; НЕ брать waterway и т.п. */
    private static boolean isRoadCandidate(JsonObject tags) {
        boolean isHighway = tags.has("highway");
        String aeroway = optString(tags, "aeroway");
        boolean isAerowayLine = "runway".equals(aeroway)
                             || "taxiway".equals(aeroway)
                             || "taxilane".equals(aeroway);

        if (!(isHighway || isAerowayLine)) return false;
        if (tags.has("railway")) return false;
        if (tags.has("waterway") || tags.has("barrier")) return false;
        return true;
    }

    /** Мосты/тоннели/ненулевой layer — исключаем полностью. */
    private static boolean isBridgeOrTunnel(JsonObject tags) {
        if (truthy(optString(tags, "bridge"))) return true;
        if (truthy(optString(tags, "tunnel"))) return true;
        try {
            String ls = optString(tags, "layer");
            if (ls != null && !ls.isBlank()) {
                int layer = Integer.parseInt(ls.trim());
                if (layer != 0) return true;
            }
        } catch (Exception ignore) {}
        return false;
    }

    // === РАССТАНОВКА ЛАМП ВДОЛЬ СЕГМЕНТА (РОВНО КАК У ТЕБЯ) ===
    private void placeLampsAlongSegment(int x1, int z1, int x2, int z2, int width, Integer yHintStart,
                                        int minX, int maxX, int minZ, int maxZ, Counter lamp) {
        List<int[]> line = bresenhamLine(x1, z1, x2, z2);
        boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        int half = Math.max(0, width / 2);

        Integer yHint = yHintStart;

        for (int[] pt : line) {
            int x = pt[0], z = pt[1];

            if (lamp.v % ROAD_LAMP_PERIOD == 0) {
                int off = Math.max(1, half + 1); // строго за кромкой полотна
                if (horizontalMajor) {
                    int lx1 = x,     lz1 = z - off; // toward +1 к центру
                    int lx2 = x,     lz2 = z + off; // toward -1 к центру
                    placeRoadLamp(lx1, lz1, yHint, true,  +1, minX, maxX, minZ, maxZ);
                    placeRoadLamp(lx2, lz2, yHint, true,  -1, minX, maxX, minZ, maxZ);
                } else {
                    int lx1 = x - off, lz1 = z;     // toward +1 к центру
                    int lx2 = x + off, lz2 = z;     // toward -1 к центру
                    placeRoadLamp(lx1, lz1, yHint, false, +1, minX, maxX, minZ, maxZ);
                    placeRoadLamp(lx2, lz2, yHint, false, -1, minX, maxX, minZ, maxZ);
                }
            }

            lamp.v++;
        }
    }

    // === ФОНАРЬ: КОЛОННА + 3 ПОЛУБЛОКА + GLOWSTONE (ТОЧНО КАК СЕЙЧАС) ===
    /** Нельзя ставить фонарь на белый/серый/жёлтый бетон. */
    private static boolean isForbiddenConcrete(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        if (key == null) return false;
        String id = key.toString();
        return "minecraft:gray_concrete".equals(id)
            || "minecraft:white_concrete".equals(id)
            || "minecraft:yellow_concrete".equals(id);
    }

    /** Поставить полублок в нижней половине блока. */
    private void placeBottomSlab(int x, int y, int z, Block slabBlock) {
        BlockState st = slabBlock.defaultBlockState();
        if (st.hasProperty(SlabBlock.TYPE)) {
            st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        level.setBlock(new BlockPos(x, y, z), st, 3);
        placedByRoadLamps.add(BlockPos.asLong(x, y, z));
    }

    /** Верхний не-air, считая все блоки наших ламп как «воздух», чтобы не ставить лампы на лампы. */
    private int findTopNonAirNearSkippingRoadLamps(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                long key = BlockPos.asLong(x, y, z);
                if (placedByRoadLamps.contains(key)) continue; // лампа = воздух
                if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            long key = BlockPos.asLong(x, y, z);
            if (placedByRoadLamps.contains(key)) continue; // лампа = воздух
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private void placeRoadLamp(int edgeX, int edgeZ, Integer hintY,
                               boolean horizontalMajor, int towardCenterSign,
                               int minX, int maxX, int minZ, int maxZ) {
        if (edgeX < minX || edgeX > maxX || edgeZ < minZ || edgeZ > maxZ) return;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        // Берём фактический рельеф, игнорируя ранее поставленные лампы
        int ySurfEdge = findTopNonAirNearSkippingRoadLamps(edgeX, edgeZ, hintY);
        if (ySurfEdge == Integer.MIN_VALUE) return;

        // НИКОГДА не ставим на дорожное полотно и запрещённые бетоны
        Block under = level.getBlockState(new BlockPos(edgeX, ySurfEdge, edgeZ)).getBlock();
        if (isRoadLikeBlock(under) || isForbiddenConcrete(under)) return;

        // База колонны
        int y0 = ySurfEdge + 1;
        if (y0 > worldMax) return;

        // Если в базе уже что-то поставлено лампой раньше — пропускаем
        long baseKey = BlockPos.asLong(edgeX, y0, edgeZ);
        if (roadLampBases.contains(baseKey)) return;

        // Если база НЕ воздух — не ставим, чтобы не «садиться» сверху
        if (!level.getBlockState(new BlockPos(edgeX, y0, edgeZ)).isAir()) return;

        // Колонна из ANDESITE_WALL (высота 5)
        int yTop = Math.min(y0 + ROAD_LAMP_COLUMN_WALLS - 1, worldMax);
        for (int y = y0; y <= yTop; y++) {
            BlockPos pos = new BlockPos(edgeX, y, edgeZ);
            level.setBlock(pos, Blocks.ANDESITE_WALL.defaultBlockState(), 3);
            placedByRoadLamps.add(BlockPos.asLong(edgeX, y, edgeZ));
        }

        // Три нижних полублока к центру дороги
        int ySlab = yTop + 1;
        if (ySlab > worldMax) return;

        int sx = horizontalMajor ? 0 : towardCenterSign;
        int sz = horizontalMajor ? towardCenterSign : 0;

        placeBottomSlab(edgeX,              ySlab, edgeZ,              Blocks.SMOOTH_STONE_SLAB);
        placeBottomSlab(edgeX + 1 * sx,     ySlab, edgeZ + 1 * sz,     Blocks.SMOOTH_STONE_SLAB);
        placeBottomSlab(edgeX + 2 * sx,     ySlab, edgeZ + 2 * sz,     Blocks.SMOOTH_STONE_SLAB);

        // Светокамень под крайним полублоком
        int gx = edgeX + 2 * sx;
        int gz = edgeZ + 2 * sz;
        int gy = ySlab - 1;
        if (gy >= worldMin && gy <= worldMax && gx >= minX && gx <= maxX && gz >= minZ && gz <= maxZ) {
            BlockPos gpos = new BlockPos(gx, gy, gz);
            level.setBlock(gpos, Blocks.GLOWSTONE.defaultBlockState(), 3);
            placedByRoadLamps.add(BlockPos.asLong(gx, gy, gz));
        }

        // помечаем базу, чтобы второй раз в этом месте лампу не ставить
        roadLampBases.add(baseKey);
    }

    // === УТИЛИТЫ ===

    private static boolean truthy(String v) {
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("yes") || v.equals("true") || v.equals("1");
    }

    private static String optString(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
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

    private static List<int[]> bresenhamLine(int x0, int z0, int x1, int z1) {
        List<int[]> pts = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int x = x0, z = z0;

        if (dx >= dz) {
            int err = dx / 2;
            while (x != x1) {
                pts.add(new int[]{x, z});
                err -= dz;
                if (err < 0) { z += sz; err += dx; }
                x += sx;
            }
        } else {
            int err = dz / 2;
            while (z != z1) {
                pts.add(new int[]{x, z});
                err -= dx;
                if (err < 0) { x += sx; err += dz; }
                z += sz;
            }
        }
        pts.add(new int[]{x1, z1});
        return pts;
    }

    /** width из тегов, если есть; иначе дефолт. Значение в метрах ≈ блокам при текущем масштабе. */
    private static int widthFromTagsOrDefault(JsonObject tags, int def) {
        String[] keys = new String[] { "width:carriageway", "width", "est_width", "runway:width", "taxiway:width" };
        for (String k : keys) {
            String v = optString(tags, k);
            if (v == null) continue;
            v = v.trim().toLowerCase(Locale.ROOT).replace(',', '.');
            StringBuilder num = new StringBuilder();
            boolean dotSeen = false;
            for (char c : v.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c=='.' && !dotSeen) { num.append('.'); dotSeen = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters); // 1 м ≈ 1 блок
                if (blocks >= 1) return blocks;
            } catch (Exception ignore) { }
        }
        return Math.max(1, def);
    }
}