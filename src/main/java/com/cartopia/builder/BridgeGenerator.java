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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class BridgeGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;       // может быть null
    private final TerrainGridStore grid;       // может быть null

    // диапазоны занятых нами Y по колонке (x,z), чтобы при фоллбэке не «видеть» свой же мост
    private static final class YRange {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        void add(int y) { if (y < min) min = y; if (y > max) max = y; }
        boolean valid() { return min != Integer.MAX_VALUE; }
    }
    private final Map<Long, YRange> placedColumnRanges = new HashMap<>();
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

    public BridgeGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
        this.grid = (store != null) ? store.grid : null;
    }
    public BridgeGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

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

    // === Материалы (как в дорогах) ===
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
        ROAD_MATERIALS.put("rail", new RoadStyle("rail", 1));
    }
    private static final Set<String> ROAD_BLOCK_IDS = new HashSet<>();
    static { for (RoadStyle s : ROAD_MATERIALS.values()) ROAD_BLOCK_IDS.add(s.blockId); }
    private static boolean isRoadLikeBlock(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        return key != null && ROAD_BLOCK_IDS.contains(key.toString());
    }

    // === оффсеты / параметры ===
    private static final int DEFAULT_OFFSET = 7;
    private static final int SHORT_OFFSET   = 1;
    private static final int SHORT_MAX_LEN  = 32;
    private static final int RAMP_STEPS     = 7;
    private static final int LAYER_STEP     = 7;
    private static final int SUPPORT_PERIOD = 50;

    // фонари
    private static final int LAMP_PERIOD = 15;
    private static final int LAMP_COLUMN_WALLS = 4;

    // ==== публичный запуск ====
    public void generate() {
        broadcast(level, "🌉 Генерация мостов…");

        if (coords == null) {
            broadcast(level, "coords == null — пропускаю BridgeGenerator.");
            return;
        }
        if (!coords.has("center") || !coords.has("bbox")) {
            broadcast(level, "Нет center/bbox — пропускаю BridgeGenerator.");
            return;
        }

        // Геопривязка и границы
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");

        final double centerLat   = center.get("lat").getAsDouble();
        final double centerLng   = center.get("lng").getAsDouble();
        final int    sizeMeters  = coords.get("sizeMeters").getAsInt();

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

        Block cobble = resolveBlock("minecraft:cobblestone");
        Block rail   = resolveBlock("minecraft:rail");
        Block stoneBricks    = resolveBlock("minecraft:stone_bricks");
        Block stoneBrickWall = resolveBlock("minecraft:stone_brick_wall");

        // === РЕЖИМ 1: стор доступен → стримим NDJSON и используем mmap grid
        if (store != null) {
            long approxTotal = 0;
            try {
                JsonObject idx = store.indexJsonObject();
                if (idx != null && idx.has("features_count")) approxTotal = Math.max(0, idx.get("features_count").getAsLong());
            } catch (Throwable ignore) {}

            // 1) Area-мосты (multipolygon и замкнутые way с man_made=bridge)
            generateAreaBridgesStreaming(centerLat, centerLng, east, west, north, south,
                    sizeMeters, centerX, centerZ, minX, maxX, minZ, maxZ, approxTotal);

            // 2) Линейные мосты: highway/railway
            long scanned = 0;
            int nextPctMark = 5;

            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    scanned++;

                    if (!"way".equals(optString(e, "type"))) continue;

                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                            ? e.getAsJsonObject("tags") : null;
                    if (tags == null) continue;

                    if (!isBridgeLike(tags)) continue; // игнор не-мостов и тоннелей

                    JsonArray geom = e.has("geometry") && e.get("geometry").isJsonArray()
                            ? e.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;

                    // преобразуем в блок-координаты
                    List<int[]> pts = new ArrayList<>(geom.size());
                    for (int i = 0; i < geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                    }

                    String highway = optString(tags, "highway");
                    String aeroway = optString(tags, "aeroway");
                    String r = optString(tags, "railway");

                    if (highway != null || aeroway != null) {
                        // Дорожной мост (может содержать railway — тогда положим рельсы поверх)
                        String styleKey = (highway != null) ? highway : ("aeroway:" + aeroway);
                        RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));

                        int lengthBlocks = approxPathLengthBlocks(pts);
                        int mainOffset   = computeMainOffset(tags, lengthBlocks);

                        Block deckBlock = resolveBlock(style.blockId);
                        if (mainOffset >= DEFAULT_OFFSET) {
                            paintBridgeDeckWithRamps(pts, style.width, deckBlock, mainOffset, RAMP_STEPS,
                                    minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                            if (hasRailway(tags)) {
                                paintRailsWithRamps(pts, mainOffset, RAMP_STEPS,
                                        minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                            }
                        } else {
                            paintBridgeDeckFollowRelief(pts, style.width, deckBlock, mainOffset,
                                    minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                            if (hasRailway(tags)) {
                                paintRailsFollowRelief(pts, mainOffset,
                                        minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                            }
                        }
                    } else if (r != null && isRailCore(r)) {
                        // Чисто железнодорожный мост
                        int lengthBlocks = approxPathLengthBlocks(pts);
                        int mainOffset   = computeMainOffset(tags, lengthBlocks);

                        if (mainOffset >= DEFAULT_OFFSET) {
                            paintRailsWithRamps(pts, mainOffset, RAMP_STEPS,
                                    minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                        } else {
                            paintRailsFollowRelief(pts, mainOffset,
                                    minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                        }
                    }

                    if (approxTotal > 0) {
                        int pct = (int)Math.round(100.0 * Math.min(scanned, approxTotal) / (double)approxTotal);
                        if (pct >= nextPctMark) {
                            broadcast(level, "Мосты (линейные): ~" + pct + "%");
                            nextPctMark = Math.min(100, nextPctMark + 5);
                        }
                    } else if (scanned % 10000 == 0) {
                        broadcast(level, "Мосты (линейные): обработано элементов ≈ " + scanned);
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Ошибка при чтении features NDJSON (линейные): " + ex.getMessage());
            }

            broadcast(level, "Мосты готовы.");
            return;
        }

        // === РЕЖИМ 2: фоллбэк — работаем по старому JSON в памяти ===
        if (!coords.has("features") || !coords.get("features").isJsonObject()) {
            broadcast(level, "В coords нет features — пропускаю BridgeGenerator.");
            return;
        }
        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements пуст — пропускаю мосты.");
            return;
        }

        // area-мосты
        generateAreaBridges(elements, centerLat, centerLng, east, west, north, south,
                sizeMeters, centerX, centerZ, minX, maxX, minZ, maxZ);

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (t == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (isHighwayBridge(t) || isRailBridge(t)) totalWays++;
        }

        int processed = 0;

        // 1) дорожные мосты
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!isHighwayBridge(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            String highway = optString(tags, "highway");
            String aeroway = optString(tags, "aeroway");
            String styleKey = (highway != null) ? highway : (aeroway != null ? "aeroway:" + aeroway : "");
            RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));

            List<int[]> pts = new ArrayList<>();
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
            }

            int lengthBlocks = approxPathLengthBlocks(pts);
            int mainOffset = computeMainOffset(tags, lengthBlocks);

            Block deckBlock = resolveBlock(style.blockId);
            if (mainOffset >= DEFAULT_OFFSET) {
                paintBridgeDeckWithRamps(pts, style.width, deckBlock, mainOffset, RAMP_STEPS,
                        minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                if (hasRailway(tags)) {
                    paintRailsWithRamps(pts, mainOffset, RAMP_STEPS,
                            minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                }
            } else {
                paintBridgeDeckFollowRelief(pts, style.width, deckBlock, mainOffset,
                        minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                if (hasRailway(tags)) {
                    paintRailsFollowRelief(pts, mainOffset,
                            minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                }
            }

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Мосты: ~" + pct + "%");
            }
        }

        // 2) ж/д мосты
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!isRailBridge(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            List<int[]> pts = new ArrayList<>();
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
            }

            int lengthBlocks = approxPathLengthBlocks(pts);
            int mainOffset = computeMainOffset(tags, lengthBlocks);

            if (mainOffset >= DEFAULT_OFFSET) {
                paintRailsWithRamps(pts, mainOffset, RAMP_STEPS,
                        minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
            } else {
                paintRailsFollowRelief(pts, mainOffset,
                        minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
            }

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Мосты: ~" + pct + "%");
            }
        }

        broadcast(level, "Мосты готовы.");
    }

    // ====== ОТБОР / ПАРАМЕТРЫ ======

    private static Integer optInt(JsonObject o, String k) {
        try {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String s = o.get(k).getAsString().trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignore) {}
        return null;
    }
    private static boolean isBridgeLike(JsonObject tags) {
        if (truthyOrText(tags, "tunnel")) return false;
        if (truthyOrText(tags, "bridge")) return true;
        if (truthyOrText(tags, "bridge:structure")) return true;
        Integer layer = optInt(tags, "layer");
        return layer != null && layer > 0;
    }
    private int computeMainOffset(JsonObject tags, int lengthBlocks) {
        Integer L = optInt(tags, "layer");
        if (L != null) return Math.max(0, L * LAYER_STEP);
        return (lengthBlocks <= SHORT_MAX_LEN) ? SHORT_OFFSET : DEFAULT_OFFSET;
    }
    private static boolean isHighwayBridge(JsonObject tags) {
        boolean isLine = tags.has("highway") || tags.has("aeroway");
        if (!isLine) return false;
        return isBridgeLike(tags);
    }
    private static boolean isRailBridge(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        if (!isRailCore(r)) return false;
        return isBridgeLike(tags);
    }
    private static boolean isRailCore(String r) {
        String x = r.trim().toLowerCase(Locale.ROOT);
        return x.equals("rail") || x.equals("tram") || x.equals("light_rail");
    }
    private static boolean hasRailway(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        return isRailCore(r);
    }
    private static boolean truthyOrText(JsonObject tags, String key) {
        String v = optString(tags, key);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

    // ====== РЕНДЕР ПО РЕЛЬЕФУ ======

    private void paintBridgeDeckFollowRelief(List<int[]> pts, int width, Block deckBlock, int offset,
                                             int minX, int maxX, int minZ, int maxZ,
                                             Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int half = Math.max(0, width / 2);
        int idx = 0;
        Integer yHint = null;
        int prevDeckY = Integer.MIN_VALUE;

        for (int i = 1; i < pts.size(); i++) {
            int x1 = pts.get(i - 1)[0], z1 = pts.get(i - 1)[1];
            int x2 = pts.get(i)[0],     z2 = pts.get(i)[1];
            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int pi = 0; pi < seg.size(); pi++) {
                int x = seg.get(pi)[0], z = seg.get(pi)[1];

                int ySurfCenter = surfaceY(x, z, yHint);
                if (ySurfCenter == Integer.MIN_VALUE) { idx++; continue; }

                int targetDeckY = clampInt(ySurfCenter + offset, worldMin, worldMax);
                int yDeck = stepClamp(prevDeckY, targetDeckY, 1);
                int effectiveOffset = yDeck - ySurfCenter;

                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    setBridgeBlock(xx, yDeck, zz, deckBlock);

                    if (w == -half || w == half) {
                        int wOut = (w < 0) ? -half - 1 : half + 1;
                        int ox = horizontalMajor ? x : x + wOut;
                        int oz = horizontalMajor ? z + wOut : z;
                        if (!(ox < minX || ox > maxX || oz < minZ || oz > maxZ)) {
                            setBridgeBlock(ox, yDeck, oz, curbBlock);
                            if (yDeck + 1 <= worldMax) setBridgeBlock(ox, yDeck + 1, oz, wallBlock);

                            if (idx % LAMP_PERIOD == 0) {
                                int toward = (w < 0) ? +1 : -1;
                                placeBridgeLamp(ox, oz, yDeck, horizontalMajor, toward, minX, maxX, minZ, maxZ);
                            }
                        }
                    }
                }

                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, half,
                        effectiveOffset, minX, maxX, minZ, maxZ, ySurfCenter, supportBlock);

                yHint = ySurfCenter;
                prevDeckY = yDeck;
                idx++;
            }
        }
    }

    private void paintBridgeDeckWithRamps(List<int[]> pts, int width, Block deckBlock,
                                          int mainOffset, int rampSteps,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int half = Math.max(0, width / 2);
        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;
        Integer yHint = null;
        int prevDeckY = Integer.MIN_VALUE;

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];
            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                int localOffset = rampOffsetForIndex(idx, totalLen, mainOffset, rampSteps);

                int ySurfCenter = surfaceY(x, z, yHint);
                if (ySurfCenter == Integer.MIN_VALUE) { idx++; continue; }

                int targetDeckY = clampInt(ySurfCenter + localOffset, worldMin, worldMax);
                int yDeck = stepClamp(prevDeckY, targetDeckY, 1);
                int effectiveOffset = yDeck - ySurfCenter;

                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    setBridgeBlock(xx, yDeck, zz, deckBlock);

                    if (w == -half || w == half) {
                        int wOut = (w < 0) ? -half - 1 : half + 1;
                        int ox = horizontalMajor ? x : x + wOut;
                        int oz = horizontalMajor ? z + wOut : z;
                        if (!(ox < minX || ox > maxX || oz < minZ || oz > maxZ)) {
                            setBridgeBlock(ox, yDeck, oz, curbBlock);
                            if (yDeck + 1 <= worldMax) setBridgeBlock(ox, yDeck + 1, oz, wallBlock);

                            if (idx % LAMP_PERIOD == 0) {
                                int toward = (w < 0) ? +1 : -1;
                                placeBridgeLamp(ox, oz, yDeck, horizontalMajor, toward, minX, maxX, minZ, maxZ);
                            }
                        }
                    }
                }

                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, half,
                        effectiveOffset, minX, maxX, minZ, maxZ, ySurfCenter, supportBlock);

                yHint = ySurfCenter;
                prevDeckY = yDeck;
                idx++;
            }
        }
    }

    private void paintRailsFollowRelief(List<int[]> pts, int offset,
                                        int minX, int maxX, int minZ, int maxZ,
                                        Block cobble, Block railBlock,
                                        Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int idx = 0;
        Integer yHint = null;
        int prevRailY = Integer.MIN_VALUE;

        for (int i = 1; i < pts.size(); i++) {
            int x1 = pts.get(i - 1)[0], z1 = pts.get(i - 1)[1];
            int x2 = pts.get(i)[0],     z2 = pts.get(i)[1];

            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
            int[][] sides = horizontalMajor ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int[] p : seg) {
                int x = p[0], z = p[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) { idx++; continue; }

                int ySurf = surfaceY(x, z, yHint);
                if (ySurf == Integer.MIN_VALUE) { idx++; continue; }

                int targetBaseY = clampInt(ySurf + offset, worldMin, worldMax - 1);
                int yBase = stepClamp(prevRailY, targetBaseY, 1);
                int effectiveOffset = yBase - ySurf;

                setBridgeBlock(x, yBase, z, cobble);
                setBridgeBlock(x, yBase + 1, z, railBlock);

                for (int[] s : sides) {
                    int sx = x + s[0], sz = z + s[1];
                    if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                    setBridgeBlock(sx, yBase,     sz, curbBlock);
                    if (yBase + 1 <= worldMax) setBridgeBlock(sx, yBase + 1, sz, wallBlock);
                }

                if (idx % LAMP_PERIOD == 0) {
                    for (int[] s : sides) {
                        int sx = x + s[0], sz = z + s[1];
                        int toward = horizontalMajor ? -s[1] : -s[0];
                        placeBridgeLamp(sx, sz, yBase, horizontalMajor, toward, minX, maxX, minZ, maxZ);
                    }
                }

                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, 0,
                        effectiveOffset, minX, maxX, minZ, maxZ, ySurf, supportBlock);

                yHint = ySurf;
                prevRailY = yBase;
                idx++;
            }
        }
    }

    private void paintRailsWithRamps(List<int[]> pts, int mainOffset, int rampSteps,
                                     int minX, int maxX, int minZ, int maxZ,
                                     Block cobble, Block railBlock,
                                     Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;
        Integer yHint = null;
        int prevRailY = Integer.MIN_VALUE;

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];

            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
            int[][] sides = horizontalMajor ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) { idx++; continue; }

                int localOffset = rampOffsetForIndex(idx, totalLen, mainOffset, rampSteps);

                int ySurf = surfaceY(x, z, yHint);
                if (ySurf != Integer.MIN_VALUE) {
                    int targetBaseY = clampInt(ySurf + localOffset, worldMin, worldMax - 1);
                    int yBase = stepClamp(prevRailY, targetBaseY, 1);
                    int effectiveOffset = yBase - ySurf;

                    setBridgeBlock(x, yBase, z, cobble);
                    setBridgeBlock(x, yBase + 1, z, railBlock);

                    for (int[] s : sides) {
                        int sx = x + s[0], sz = z + s[1];
                        if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                        setBridgeBlock(sx, yBase,     sz, curbBlock);
                        if (yBase + 1 <= worldMax) setBridgeBlock(sx, yBase + 1, sz, wallBlock);
                    }

                    if (idx % LAMP_PERIOD == 0) {
                        for (int[] s : sides) {
                            int sx = x + s[0], sz = z + s[1];
                            int toward = horizontalMajor ? -s[1] : -s[0];
                            placeBridgeLamp(sx, sz, yBase, horizontalMajor, toward, minX, maxX, minZ, maxZ);
                        }
                    }

                    placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, 0,
                            effectiveOffset, minX, maxX, minZ, maxZ, ySurf, supportBlock);

                    yHint = ySurf;
                    prevRailY = yBase;
                }
                idx++;
            }
        }
    }

    // ====== AREA BRIDGES ======

    private void generateAreaBridgesStreaming(double centerLat, double centerLng,
                                              double east, double west, double north, double south,
                                              int sizeMeters, int centerX, int centerZ,
                                              int minX, int maxX, int minZ, int maxZ,
                                              long approxTotal) {
        int made = 0;
        int nextPctMark = 5;
        long scanned = 0;

        @SuppressWarnings("unused")
        Block deckBlock = resolveBlock("minecraft:gray_concrete");
        @SuppressWarnings("unused")
        final int offset = SHORT_OFFSET;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject e : fs) {
                scanned++;

                JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                if (tags == null) continue;
                if (truthyOrText(tags, "tunnel")) continue;

                boolean bridgeTagged = "bridge".equals(optString(tags, "man_made")) || truthyOrText(tags, "bridge");
                if (!bridgeTagged) continue;

                String etype = optString(e, "type");

                // relation multipolygon
                if ("relation".equals(etype) && "multipolygon".equals(optString(tags, "type"))) {
                    if (e.has("members") && e.get("members").isJsonArray()) {
                        JsonArray members = e.getAsJsonArray("members");
                        List<List<int[]>> outers = new ArrayList<>();
                        List<List<int[]>> inners = new ArrayList<>();
                        for (JsonElement memEl : members) {
                            JsonObject m = memEl.getAsJsonObject();
                            String role = optString(m, "role");
                            if (role == null) continue;
                            if (!m.has("geometry") || !m.get("geometry").isJsonArray()) continue;
                            JsonArray geom = m.getAsJsonArray("geometry");
                            if (geom.size() < 3) continue;

                            List<int[]> ring = toBlockRing(geom, centerLat, centerLng, east, west, north, south,
                                    sizeMeters, centerX, centerZ);
                            if (ring.size() < 3) continue;
                            if ("outer".equals(role)) outers.add(ring);
                            else if ("inner".equals(role)) inners.add(ring);
                        }
                        if (!outers.isEmpty()) {
                            //paintAreaBridgeFill(outers, inners, deckBlock, offset, minX, maxX, minZ, maxZ);
                            made++;
                        }
                    }
                    continue;
                }

                // замкнутый way/area
                if ("way".equals(etype)) {
                    JsonArray geom = e.has("geometry") ? e.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 3) continue;

                    boolean areaYes = "yes".equalsIgnoreCase(String.valueOf(optString(tags, "area")));
                    boolean closed = isClosedRing(geom);

                    if (areaYes || closed) {
                        List<int[]> outer = toBlockRing(geom, centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                        if (outer.size() >= 3) {
                            @SuppressWarnings("unused")
                            List<List<int[]>> outers = Collections.singletonList(outer);
                            @SuppressWarnings("unused")
                            List<List<int[]>> inners = Collections.emptyList();
                            //paintAreaBridgeFill(outers, inners, deckBlock, offset, minX, maxX, minZ, maxZ);
                            made++;
                        }
                    }
                }

                if (approxTotal > 0) {
                    int pct = (int)Math.round(100.0 * Math.min(scanned, approxTotal) / (double)approxTotal);
                    if (pct >= nextPctMark) {
                        broadcast(level, "Мосты (area): ~" + pct + "%");
                        nextPctMark = Math.min(100, nextPctMark + 5);
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Ошибка при чтении features NDJSON (area): " + ex.getMessage());
        }

        if (made > 0) broadcast(level, "Зонные мосты: построено " + made + " шт. (+1 над рельефом).");
    }

    private void generateAreaBridges(JsonArray elements,
                                     double centerLat, double centerLng,
                                     double east, double west, double north, double south,
                                     int sizeMeters, int centerX, int centerZ,
                                     int minX, int maxX, int minZ, int maxZ) {
        int made = 0;
        @SuppressWarnings("unused")
        Block deckBlock = resolveBlock("minecraft:gray_concrete");
        @SuppressWarnings("unused")
        final int offset = SHORT_OFFSET;

        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (truthyOrText(tags, "tunnel")) continue;

            boolean bridgeTagged = "bridge".equals(optString(tags, "man_made")) || truthyOrText(tags, "bridge");
            if (!bridgeTagged) continue;

            String etype = optString(e, "type");

            if ("relation".equals(etype) && "multipolygon".equals(optString(tags, "type"))) {
                List<List<int[]>> outers = new ArrayList<>();
                List<List<int[]>> inners = new ArrayList<>();

                if (e.has("members") && e.get("members").isJsonArray()) {
                    JsonArray members = e.getAsJsonArray("members");
                    for (JsonElement memEl : members) {
                        JsonObject m = memEl.getAsJsonObject();
                        String role = optString(m, "role");
                        if (role == null) continue;
                        if (!m.has("geometry") || !m.get("geometry").isJsonArray()) continue;
                        JsonArray geom = m.getAsJsonArray("geometry");
                        if (geom.size() < 3) continue;

                        List<int[]> ring = toBlockRing(geom, centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                        if (ring.size() < 3) continue;

                        if ("outer".equals(role)) outers.add(ring);
                        else if ("inner".equals(role)) inners.add(ring);
                    }
                }
                if (!outers.isEmpty()) {
                    //paintAreaBridgeFill(outers, inners, deckBlock, offset, minX, maxX, minZ, maxZ);
                    made++;
                }
                continue;
            }

            if ("way".equals(etype)) {
                JsonArray geom = e.has("geometry") ? e.getAsJsonArray("geometry") : null;
                if (geom == null || geom.size() < 3) continue;

                boolean areaYes = "yes".equalsIgnoreCase(String.valueOf(optString(tags, "area")));
                boolean closed = isClosedRing(geom);

                if (areaYes || closed) {
                    List<int[]> outer = toBlockRing(geom, centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                    if (outer.size() >= 3) {
                        @SuppressWarnings("unused")
                        List<List<int[]>> outers = Collections.singletonList(outer);
                        @SuppressWarnings("unused")
                        List<List<int[]>> inners = Collections.emptyList();
                        //paintAreaBridgeFill(outers, inners, deckBlock, offset, minX, maxX, minZ, maxZ);
                        made++;
                    }
                }
            }
        }

        if (made > 0) broadcast(level, "Зонные мосты: построено " + made + " шт. (+1 над рельефом).");
    }

    // ====== ПОМОЩНИКИ ДЛИНЫ / OFFSET ======

    private static int approxPathLengthBlocks(List<int[]> pts) {
        int L = 0;
        for (int i = 1; i < pts.size(); i++) {
            int dx = Math.abs(pts.get(i)[0] - pts.get(i-1)[0]);
            int dz = Math.abs(pts.get(i)[1] - pts.get(i-1)[1]);
            L += Math.max(dx, dz);
        }
        return L;
    }
    private static int rampOffsetForIndex(int idx, int totalLen, int mainOffset, int rampSteps) {
        if (mainOffset <= 0) return 0;
        final int SPAN = 2; // длина ступени
        final int rampHeight = Math.min(rampSteps, mainOffset);
        final int baseOffset = Math.max(0, mainOffset - rampHeight);
        int fromStart = (idx / SPAN) + 1;
        int fromEnd   = ((totalLen - 1 - idx) / SPAN) + 1;
        int step = Math.min(rampHeight, Math.min(fromStart, fromEnd));
        return baseOffset + step;
    }

    private void placeSupportPairIfNeeded(int idx, int period,
                                          int cx, int cz, boolean horizontalMajor, int half,
                                          int offset,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Integer yHint,
                                          Block supportBlock) {
        if (offset <= 0) return;
        if (idx % period != 0) return;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int ex1 = horizontalMajor ? cx : cx - half;
        int ez1 = horizontalMajor ? cz - half : cz;
        int ex2 = horizontalMajor ? cx : cx + half;
        int ez2 = horizontalMajor ? cz + half : cz;

        int[][] edges = new int[][] { {ex1, ez1}, {ex2, ez2} };

        for (int[] e : edges) {
            int ex = e[0], ez = e[1];
            if (ex < minX || ex > maxX || ez < minZ || ez > maxZ) continue;

            int ySurf = surfaceY(ex, ez, yHint);
            if (ySurf == Integer.MIN_VALUE) continue;

            Block surfBlock = level.getBlockState(mpos.set(ex, ySurf, ez)).getBlock();
            if (isRoadLikeBlock(surfBlock)) continue;

            int yTop = ySurf + offset;
            if (yTop <= ySurf) continue;
            if (yTop > worldMax) yTop = worldMax;

            for (int y = yTop - 1; y >= Math.max(worldMin, ySurf + 1); y--) {
                setBridgeBlock(ex, y, ez, supportBlock);
            }
        }
    }

    // === высота поверхности: сперва из grid, иначе — поиск в мире c «пропуском» нашего моста ===
    private int surfaceY(int x, int z, Integer hintY) {
        try {
            if (grid != null && grid.inBounds(x, z)) {
                int y = grid.groundY(x, z);
                if (y != Integer.MIN_VALUE) return y;
            }
        } catch (Throwable ignore) {}
        return findTopNonAirNearIgnoringBridge(x, z, hintY);
    }

    private int findTopNonAirNearIgnoringBridge(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            int y = from;
            while (y >= to) {
                y = skipOurColumnIfInside(x, z, y);
                if (y < to) break;
                if (!level.getBlockState(mpos.set(x, y, z)).isAir()) return y;
                y--;
            }
        }
        int y = worldMax;
        while (y >= worldMin) {
            y = skipOurColumnIfInside(x, z, y);
            if (y < worldMin) break;
            if (!level.getBlockState(mpos.set(x, y, z)).isAir()) return y;
            y--;
        }
        return Integer.MIN_VALUE;
    }
    private int skipOurColumnIfInside(int x, int z, int y) {
        YRange r = placedColumnRanges.get(packXZ(x, z));
        if (r != null && r.valid() && y <= r.max && y >= r.min) return r.min - 1;
        return y;
    }

    private void setBridgeBlock(int x, int y, int z, Block block) {
        level.setBlock(mpos.set(x, y, z), block.defaultBlockState(), 3);
        registerPlaced(x, y, z);
    }

    // ====== УТИЛИТЫ ======

    private static Block resolveBlock(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        return (b != null ? b : Blocks.STONE);
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
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int stepClamp(int prevY, int targetY, int maxStep) {
        if (prevY == Integer.MIN_VALUE) return targetY;
        if (targetY > prevY + maxStep) return prevY + maxStep;
        if (targetY < prevY - maxStep) return prevY - maxStep;
        return targetY;
    }

    private void placeBridgeLamp(int edgeX, int edgeZ, int deckY,
                                 boolean horizontalMajor, int towardCenterSign,
                                 int minX, int maxX, int minZ, int maxZ) {
        if (edgeX < minX || edgeX > maxX || edgeZ < minZ || edgeZ > maxZ) return;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int y0 = deckY + 2;
        int yTop = y0 + LAMP_COLUMN_WALLS - 1;
        if (y0 > worldMax) return;
        yTop = Math.min(yTop, worldMax);

        Block andesiteWall = Blocks.ANDESITE_WALL;
        for (int y = y0; y <= yTop; y++) {
            setBridgeBlock(edgeX, y, edgeZ, andesiteWall);
        }

        int ySlab = yTop + 1;
        if (ySlab > worldMax) return;

        int sx = horizontalMajor ? 0 : towardCenterSign;
        int sz = horizontalMajor ? towardCenterSign : 0;

        placeBottomSlab(edgeX,            ySlab, edgeZ,            Blocks.SMOOTH_STONE_SLAB);
        placeBottomSlab(edgeX + sx,       ySlab, edgeZ + sz,       Blocks.SMOOTH_STONE_SLAB);
        placeBottomSlab(edgeX + 2 * sx,   ySlab, edgeZ + 2 * sz,   Blocks.SMOOTH_STONE_SLAB);

        int gx = edgeX + 2 * sx;
        int gz = edgeZ + 2 * sz;
        int gy = ySlab - 1;
        if (gy >= worldMin && gy <= worldMax && gx >= minX && gx <= maxX && gz >= minZ && gz <= maxZ) {
            setBridgeBlock(gx, gy, gz, Blocks.GLOWSTONE);
        }
    }
    private void placeBottomSlab(int x, int y, int z, Block slabBlock) {
        BlockState st = slabBlock.defaultBlockState();
        if (st.hasProperty(SlabBlock.TYPE)) st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        level.setBlock(mpos.set(x, y, z), st, 3);
        registerPlaced(x, y, z);
    }

    // учёт занятого диапазона по колонке (x,z)
    private void registerPlaced(int x, int y, int z) {
        long k = packXZ(x, z);
        YRange r = placedColumnRanges.get(k);
        if (r == null) { r = new YRange(); placedColumnRanges.put(k, r); }
        r.add(y);
    }
    private static long packXZ(int x, int z) { return (((long) x) << 32) ^ (z & 0xffffffffL); }

    // === area-помощники ===
    private List<int[]> toBlockRing(JsonArray geom,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ) {
        List<int[]> ring = new ArrayList<>(geom.size());
        for (int i = 0; i < geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            ring.add(latlngToBlock(
                    p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ
            ));
        }
        if (ring.size() >= 2) {
            int[] f = ring.get(0);
            int[] l = ring.get(ring.size() - 1);
            if (f[0] != l[0] || f[1] != l[1]) ring.add(new int[]{f[0], f[1]});
        }
        return ring;
    }
    private boolean isClosedRing(JsonArray geom) {
        if (geom.size() < 4) return false;
        JsonObject f = geom.get(0).getAsJsonObject();
        JsonObject l = geom.get(geom.size() - 1).getAsJsonObject();
        try {
            double lat0 = f.get("lat").getAsDouble(), lon0 = f.get("lon").getAsDouble();
            double lat1 = l.get("lat").getAsDouble(), lon1 = l.get("lon").getAsDouble();
            return Math.abs(lat0 - lat1) < 1e-9 && Math.abs(lon0 - lon1) < 1e-9;
        } catch (Throwable ignore) { return false; }
    }
    private static boolean pointInPolygon(int x, int z, List<int[]> ring) {
        double px = x + 0.5, pz = z + 0.5;
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int[] pi = ring.get(i), pj = ring.get(j);
            double xi = pi[0], zi = pi[1];
            double xj = pj[0], zj = pj[1];
            boolean intersect = ((zi > pz) != (zj > pz)) &&
                    (px < (xj - xi) * (pz - zi) / ((zj - zi) == 0 ? 1e-9 : (zj - zi)) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    @SuppressWarnings("unused")
    private void paintAreaBridgeFill(List<List<int[]>> outers, List<List<int[]>> inners,
                                     Block deckBlock, int offset,
                                     int minX, int maxX, int minZ, int maxZ) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;
        if (outers == null || outers.isEmpty()) return;

        int bx0 = Integer.MAX_VALUE, bz0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE, bz1 = Integer.MIN_VALUE;
        for (List<int[]> r : outers) {
            for (int[] p : r) {
                if (p[0] < bx0) bx0 = p[0];
                if (p[0] > bx1) bx1 = p[0];
                if (p[1] < bz0) bz0 = p[1];
                if (p[1] > bz1) bz1 = p[1];
            }
        }
        bx0 = Math.max(bx0, minX); bx1 = Math.min(bx1, maxX);
        bz0 = Math.max(bz0, minZ); bz1 = Math.min(bz1, maxZ);
        if (bx0 > bx1 || bz0 > bz1) return;

        int placed = 0;
        for (int x = bx0; x <= bx1; x++) {
            Integer yHint = null;
            for (int z = bz0; z <= bz1; z++) {
                boolean inside = false;
                for (List<int[]> outer : outers) { if (pointInPolygon(x, z, outer)) { inside = true; break; } }
                if (!inside) continue;
                boolean inHole = false;
                for (List<int[]> inner : inners) { if (pointInPolygon(x, z, inner)) { inHole = true; break; } }
                if (inHole) continue;

                int ySurf = surfaceY(x, z, yHint);
                if (ySurf == Integer.MIN_VALUE) continue;

                int yDeck = clampInt(ySurf + offset, worldMin, worldMax);
                setBridgeBlock(x, yDeck, z, deckBlock);
                yHint = ySurf;
                placed++;
            }
        }
        if (placed > 0) broadcast(level, "Area-мост: залито " + placed + " блоков настила.");
    }
}
