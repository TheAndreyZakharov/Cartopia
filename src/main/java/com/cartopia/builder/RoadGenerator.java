package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.cartopia.store.TerrainGridStore;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class RoadGenerator {

    private final ServerLevel level;
    private final JsonObject coords;           // как и раньше
    private final GenerationStore store;       // НОВОЕ: стрим фич и грид рельефа (может быть null)
    // === runway centerline lights ===
    private static final int RUNWAY_LAMP_EVERY = 30; // каждые N блоков
    private int runwayLampStep = 0;                  // счётчик вдоль текущей ВПП
    private boolean runwayMode = false;              // true, когда обрабатываем aeroway=runway

    // СТАРЫЙ конструктор — оставляем для совместимости (fallback на coords.features.elements).
    public RoadGenerator(ServerLevel level, JsonObject coords) {
        this(level, coords, null);
    }

    // НОВЫЙ конструктор — предпочтительно использовать его (из Pipeline передаём store).
    public RoadGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
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

    // === Материалы дорог ===
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
        ROAD_MATERIALS.put("unclassified", new RoadStyle("gray_concrete", 6));
        ROAD_MATERIALS.put("service",      new RoadStyle("gray_concrete", 5));
        ROAD_MATERIALS.put("footway",      new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("path",         new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("cycleway",     new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("pedestrian",   new RoadStyle("stone", 4));
        ROAD_MATERIALS.put("track",        new RoadStyle("cobblestone", 4));
        ROAD_MATERIALS.put("aeroway:runway",   new RoadStyle("gray_concrete", 45));
        ROAD_MATERIALS.put("aeroway:taxiway",  new RoadStyle("gray_concrete", 15));
        ROAD_MATERIALS.put("aeroway:taxilane", new RoadStyle("gray_concrete", 8));

        // rail не используем, т.к. railway исключаем.
        ROAD_MATERIALS.put("rail",         new RoadStyle("rail", 1));
    }

    // ==== публичный запуск ====
    public void generate() {
        broadcast(level, "Generating roads (no bridges/tunnels/rail)...");

        if (coords == null) {
            broadcast(level, "coords == null — skipping RoadGenerator.");
            return;
        }

        // Параметры геопривязки
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "No center/bbox in coords — skipping roads.");
            return;
        }

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

        // Точные границы области генерации (как раньше)
        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        // === Два режима чтения фич:
        // 1) Предпочтительно: поток из store.featureStream() (NDJSON, без загрузки в ОЗУ).
        // 2) Fallback: старый массив coords.features.elements (если store отсутствует).
        boolean streaming = (store != null);
        if (!streaming) {
            if (!coords.has("features")) {
                broadcast(level, "No features in coords — skipping RoadGenerator.");
                return;
            }
            JsonObject features = coords.getAsJsonObject("features");
            JsonArray elements = features.getAsJsonArray("elements");
            if (elements == null || elements.size() == 0) {
                broadcast(level, "OSM elements are empty — skipping roads.");
                return;
            }
            runWithJsonArray(elements, minX, maxX, minZ, maxZ,
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            return;
        }

        // === STREAM режим (двойной проход: быстрый подсчёт, затем рендер) ===
        int totalWays = 0;
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject e : fs) {
                JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                if (tags == null) continue;
                if (!isRoadCandidate(tags)) continue;
                if (!"way".equals(optString(e,"type"))) continue;
                if (!e.has("geometry") || !e.get("geometry").isJsonArray()) continue;
                if (e.getAsJsonArray("geometry").size() < 2) continue;
                totalWays++;
            }
        } catch (Exception ex) {  // <— ЛОВИМ Exception, а не IOException
            broadcast(level, "Error reading NDJSON features (count): " + ex.getMessage() + " — trying fallback to coords.features.");
            JsonArray elements = coords.has("features") && coords.getAsJsonObject("features").has("elements")
                    ? coords.getAsJsonObject("features").getAsJsonArray("elements") : null;
            if (elements == null || elements.size() == 0) return;
            runWithJsonArray(elements, minX, maxX, minZ, maxZ,
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            return;
        }

        int processed = 0;
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject e : fs) {
                JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                if (tags == null) continue;

                if (!isRoadCandidate(tags)) continue;    // только дороги
                if (isBridgeOrTunnel(tags)) continue;    // исключаем мосты/тоннели/слои
                if (!"way".equals(optString(e,"type"))) continue;

                JsonArray geom = e.getAsJsonArray("geometry");
                if (geom == null || geom.size() < 2) continue;

                String highway = optString(tags, "highway");
                String aeroway = optString(tags, "aeroway");
                String styleKey = (highway != null) ? highway
                        : (aeroway != null ? "aeroway:" + aeroway : "");
                RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));


                int widthBlocks = widthFromTagsOrDefault(tags, style.width);
                Block roadBlock = resolveBlock(style.blockId);
                roadBlock = pickRoadBlockFromSurface(tags, roadBlock);

                // RUNWAY LAMPS: активируем только для аэродромной ВПП
                runwayMode = "runway".equals(aeroway);
                if (runwayMode) runwayLampStep = 0;

                // Переводим lat/lon в блоки и красим сегменты Брезенхэмом
                int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;
                Integer lastYHint = null;
                for (int i=0; i<geom.size(); i++) {
                    JsonObject p = geom.get(i).getAsJsonObject();
                    double lat = p.get("lat").getAsDouble();
                    double lon = p.get("lon").getAsDouble();
                    int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    int x = xz[0], z = xz[1];

                    if (prevX != Integer.MIN_VALUE) {
                        paintSegment(prevX, prevZ, x, z, widthBlocks, roadBlock, lastYHint,
                                minX, maxX, minZ, maxZ);
                    }

                    prevX = x; prevZ = z;
                }

                runwayMode = false; // выключаем режим ВПП после завершения way

                processed++;
                if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                    int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                    broadcast(level, "Roads: ~" + pct + "%");
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error reading NDJSON features (render): " + ex.getMessage());
        }

        broadcast(level, "Roads are ready.");
    }

    // === режим старого массива (fallback) ===
    private void runWithJsonArray(JsonArray elements,
                                  int minX, int maxX, int minZ, int maxZ,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRoadCandidate(tags)) continue;
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

            if (!isRoadCandidate(tags)) continue;            // только дороги
            if (isBridgeOrTunnel(tags)) continue;            // исключаем мосты/тоннели/слои
            if (!"way".equals(optString(e,"type"))) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            String highway = optString(tags, "highway");
            String aeroway = optString(tags, "aeroway");
            String styleKey = (highway != null) ? highway : (aeroway != null ? "aeroway:" + aeroway : "");
            RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));

            int widthBlocks = widthFromTagsOrDefault(tags, style.width);
            Block roadBlock = resolveBlock(style.blockId);
            roadBlock = pickRoadBlockFromSurface(tags, roadBlock);

            // RUNWAY LAMPS: активируем только для аэродромной ВПП
            runwayMode = "runway".equals(aeroway);
            if (runwayMode) runwayLampStep = 0;

            int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;
            Integer lastYHint = null;
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                double lat = p.get("lat").getAsDouble();
                double lon = p.get("lon").getAsDouble();
                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                int x = xz[0], z = xz[1];

                if (prevX != Integer.MIN_VALUE) {
                    paintSegment(prevX, prevZ, x, z, widthBlocks, roadBlock, lastYHint,
                            minX, maxX, minZ, maxZ);
                }

                prevX = x; prevZ = z;
            }

            runwayMode = false; // выключаем режим ВПП после завершения way

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Roads: ~" + pct + "%");
            }
        }
    }

    // === логика отбора ===

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

    // === основной рендер сегмента ===

    private void paintSegment(int x1, int z1, int x2, int z2, int width, Block roadBlock, Integer yHintStart,
                              int minX, int maxX, int minZ, int maxZ) {
        List<int[]> line = bresenhamLine(x1, z1, x2, z2);
        boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        int half = width / 2;

        Integer yHint = yHintStart;
        for (int[] pt : line) {
            int x = pt[0], z = pt[1];

            for (int w = -half; w <= half; w++) {
                int xx = horizontalMajor ? x : x + w;
                int zz = horizontalMajor ? z + w : z;

                // жёсткая отсечка области
                if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                int y = findTopYSmart(xx, zz, yHint); // НОВОЕ: сначала грид, потом fallback-скан
                if (y == Integer.MIN_VALUE) continue;

                // Можно дополнительно избегать воды по гриду:
                // Integer wY = (store != null && store.grid != null) ? store.grid.waterY(xx, zz) : null;
                // if (wY != null && wY >= y) continue;

                @SuppressWarnings("unused")
                BlockState top = level.getBlockState(new BlockPos(xx, y, zz));
                // if (top.getBlock() == Blocks.WATER) continue;

                // --- runway centerline light каждые RUNWAY_LAMP_EVERY блоков по осевой (w==0) ---
                if (runwayMode && w == 0) {
                    if (runwayLampStep % RUNWAY_LAMP_EVERY == 0) {
                        level.setBlock(new BlockPos(xx, y, zz), Blocks.SEA_LANTERN.defaultBlockState(), 3);
                    } else {
                        level.setBlock(new BlockPos(xx, y, zz), roadBlock.defaultBlockState(), 3);
                    }
                } else {
                    level.setBlock(new BlockPos(xx, y, zz), roadBlock.defaultBlockState(), 3);
                }

                // счёт шага только по осевой точки линии, чтобы «каждые 10 блоков» было по центру
                if (runwayMode && w == 0) runwayLampStep++;

                yHint = y;
            }
        }
    }

    // === поиск высоты ===

    /** Сначала берём высоту поверхности из TerrainGridStore, иначе старый скан мира. */
    private int findTopYSmart(int x, int z, Integer hintY) {
        if (store != null) {
            TerrainGridStore g = store.grid;
            if (g != null && g.inBounds(x, z)) {
                int gy = g.groundY(x, z);
                if (gy != Integer.MIN_VALUE) return gy;
            }
        }
        return findTopNonAirNear(x, z, hintY);
    }

    /** быстрый поиск поверхности рядом с предполагаемой высотой; иначе фулл-скан сверху вниз */
    private int findTopNonAirNear(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    // === утилиты ===

    private static Block resolveBlock(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        return (b != null ? b : Blocks.STONE);
    }

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
                int blocks = (int)Math.round(meters); // 1 м ≈ 1 блок по нашей проекции
                if (blocks >= 1) return blocks;
            } catch (Exception ignore) { }
        }
        return Math.max(1, def);
    }

    private Block pickRoadBlockFromSurface(JsonObject tags, Block fallback) {
        // смотрим явные материалы/поверхности дороги
        String[] keys = new String[] { "surface", "material" };
        String val = null;
        for (String k : keys) {
            String v = optString(tags, k);
            if (v != null && !v.isBlank()) { val = v.trim().toLowerCase(Locale.ROOT); break; }
        }
        if (val == null) return fallback;

        // дерево -> еловые доски (как в мостах)
        Set<String> WOODY = Set.of("wood","wooden","boards","board","boardwalk","planks","timber");
        for (String tok : val.split("[;,/\\s]+")) {
            if (WOODY.contains(tok)) return Blocks.SPRUCE_PLANKS;
        }

        // металл -> chiseled_stone_bricks
        Set<String> METALLIC = Set.of(
            "metal","metallic","steel","iron","metal_grid","metal_grate","grate","grating","grid",
            "chequer_plate","tread_plate",
            "металл","сталь","железо","решётка","решетка"
        );
        for (String tok : val.split("[;,/\\s]+")) {
            if (METALLIC.contains(tok)) return Blocks.CHISELED_STONE_BRICKS;
        }

        // брусчатка  -> булыжник
        Set<String> BRUSCHATKA = Set.of(
            // OSM-классика
            "sett","setts","stone_setts","granite_setts","basalt_setts","sandstone_setts",
            "paving_stones","paving-stones","paving_stone","paving-stone",
            "cobblestone","cobblestones","cobbled","cobbles","cobble",
            "cobblestone:flattened","unhewn_cobblestone",
            // русские варианты
            "брусчатка","булыжник","булыжная","булыжное","булыжной","гранитная_брусчатка"
        );
        for (String tok : val.split("[;,/\\s]+")) {
            if (BRUSCHATKA.contains(tok)) return Blocks.COBBLESTONE;
        }

        return fallback; // иначе — как сказал стиль дороги
    }

    // === вспомогательные методы для других генераторов ===
    public static boolean hasRoadMaterial(String key) {
        return ROAD_MATERIALS.containsKey(key);
    }

    public static int getRoadWidth(String key) {
        RoadStyle style = ROAD_MATERIALS.get(key);
        return (style != null ? style.width : 12);
    }

}
