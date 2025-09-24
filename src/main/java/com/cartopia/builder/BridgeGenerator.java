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
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class BridgeGenerator {

    private final ServerLevel level;
    private final JsonObject coords;

    // Все позиции блоков, поставленных ЭТИМ генератором моста в текущем запуске.
    // Любой поиск «высоты рельефа» будет их ПРОПУСКАТЬ (считать как воздух).
    private final Set<Long> placedByBridge = new HashSet<>();

    public BridgeGenerator(ServerLevel level, JsonObject coords) {
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

    // === Материалы (те же, что в дорогах) ===
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
        ROAD_MATERIALS.put("rail", new RoadStyle("rail", 1)); // не используется как полотно, просто совместимость
    }

    // Все блок-ид дорожных полотен (нельзя ставить опоры на них)
    private static final Set<String> ROAD_BLOCK_IDS = new HashSet<>();
    static {
        for (RoadStyle s : ROAD_MATERIALS.values()) {
            ROAD_BLOCK_IDS.add(s.blockId); // например minecraft:gray_concrete, minecraft:stone, minecraft:cobblestone, minecraft:rail ...
        }
    }

    private static boolean isRoadLikeBlock(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        return key != null && ROAD_BLOCK_IDS.contains(key.toString());
    }

    // === Высотные оффсеты и параметры пандусов / слоёв ===
    private static final int DEFAULT_OFFSET = 7;   // базовый оффсет «большого» моста
    private static final int SHORT_OFFSET   = 1;   // оффсет коротких мостов
    private static final int SHORT_MAX_LEN  = 32;  // порог короткого моста в блоках
    private static final int RAMP_STEPS     = 7;   // число «ступеней» у въездов
    private static final int LAYER_STEP     = 7;   // шаг высоты между layer'ами
    private static final int SUPPORT_PERIOD = 50; // опоры каждые 50 блоков

    // ==== публичный запуск ====
    public void generate() {
        broadcast(level, "🌉 Генерация мостов: +offset от рельефа (игнорим сам мост) + ступени на концах больших мостов…");

        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю BridgeGenerator.");
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

        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements пуст — пропускаю мосты.");
            return;
        }


        Block cobble = resolveBlock("minecraft:cobblestone");
        Block rail   = resolveBlock("minecraft:rail");
        Block stoneBricks    = resolveBlock("minecraft:stone_bricks");
        Block stoneBrickWall = resolveBlock("minecraft:stone_brick_wall");

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (isHighwayBridge(tags) || isRailBridge(tags)) totalWays++;
        }

        int processed = 0;

        // === 1) Дорожные мосты (highway=* + bridge) ===
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
                // большие/слойные — со ступенями до mainOffset (в т.ч. 14, 21 и т.д.)
                paintBridgeDeckWithRamps(pts, style.width, deckBlock, mainOffset, RAMP_STEPS, minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                if (hasRailway(tags)) {
                    paintRailsWithRamps(pts, mainOffset, RAMP_STEPS, minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                }
            } else {
                // короткие без layer — «+1 над рельефом»
                paintBridgeDeckFollowRelief(pts, style.width, deckBlock, mainOffset, minX, maxX, minZ, maxZ, stoneBricks, stoneBrickWall, stoneBricks);
                if (hasRailway(tags)) {
                    paintRailsFollowRelief(pts, mainOffset, minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
                }
            }

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Мосты: ~" + pct + "%");
            }
        }

        // === 2) Чисто железнодорожные мосты (railway in {rail,tram,light_rail} + bridge) ===
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
                paintRailsWithRamps(pts, mainOffset, RAMP_STEPS, minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
            } else {
                paintRailsFollowRelief(pts, mainOffset, minX, maxX, minZ, maxZ, cobble, rail, stoneBricks, stoneBrickWall, stoneBricks);
            }

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Мосты: ~" + pct + "%");
            }
        }

        broadcast(level, "Мосты готовы.");
    }

    // ====== ОТБОР ======

    //  парсим layer как число
    private static Integer optInt(JsonObject o, String k) {
        try {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String s = o.get(k).getAsString().trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignore) {}
        return null;
    }

    // единая проверка «мостоподобности»
    private static boolean isBridgeLike(JsonObject tags) {
        if (truthyOrText(tags, "tunnel")) return false;            // тоннели исключаем
        if (truthyOrText(tags, "bridge")) return true;             // bridge=yes/viaduct/… — ок
        if (truthyOrText(tags, "bridge:structure")) return true;   // встречается без bridge=*
        Integer layer = optInt(tags, "layer");
        return layer != null && layer > 0;                         // layer>0 — считаем «над землей»
    }

    /** Главный оффсет: если указан layer — используем layer*7; иначе: короткий=1, длинный=7. */
    private int computeMainOffset(JsonObject tags, int lengthBlocks) {
        Integer L = optInt(tags, "layer");
        if (L != null) {
            // Явный layer: разводим слои на L*7
            return Math.max(0, L * LAYER_STEP);
        }
        // layer не указан — старое поведение: короткий/длинный мост
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
        r = r.trim().toLowerCase(Locale.ROOT);
        if (!(r.equals("rail") || r.equals("tram") || r.equals("light_rail"))) return false;
        return isBridgeLike(tags);
    }

    private static boolean hasRailway(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        r = r.trim().toLowerCase(Locale.ROOT);
        return r.equals("rail") || r.equals("tram") || r.equals("light_rail");
    }

    private static boolean truthyOrText(JsonObject tags, String key) {
        String v = optString(tags, key);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

    // ====== РЕНДЕР ПО РЕЛЬЕФУ (+offset для КАЖДОГО БЛОКА) ======

    /** Полотно моста: для каждой колонки берем рельеф и ставим блок на (ySurface + offset). */
    private void paintBridgeDeckFollowRelief(List<int[]> pts, int width, Block deckBlock, int offset,
                                            int minX, int maxX, int minZ, int maxZ,
                                            Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int half = Math.max(0, width / 2);
        int idx = 0; // индекс вдоль центр-линии для периодичности опор

        for (int i = 1; i < pts.size(); i++) {
            int x1 = pts.get(i - 1)[0], z1 = pts.get(i - 1)[1];
            int x2 = pts.get(i)[0],     z2 = pts.get(i)[1];
            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            Integer yHint = null;
            for (int pi = 0; pi < seg.size(); pi++) {
                int x = seg.get(pi)[0], z = seg.get(pi)[1];

                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    int ySurf = findTopNonAirNearIgnoringBridge(xx, zz, yHint);
                    if (ySurf == Integer.MIN_VALUE) continue;

                    int yDeck = Math.max(worldMin, Math.min(worldMax, ySurf + offset));

                    // полотно
                    setBridgeBlock(xx, yDeck, zz, deckBlock);

                    // бордюр СНАРУЖИ (±(half+1)) + стенка
                    if (w == -half || w == half) {
                        int wOut = (w < 0) ? -half - 1 : half + 1;
                        int ox = horizontalMajor ? x : x + wOut;
                        int oz = horizontalMajor ? z + wOut : z;
                        if (!(ox < minX || ox > maxX || oz < minZ || oz > maxZ)) {
                            setBridgeBlock(ox, yDeck, oz, curbBlock);
                            if (yDeck + 1 <= worldMax) setBridgeBlock(ox, yDeck + 1, oz, wallBlock);
                        }
                    }

                    yHint = ySurf;
                }

                // после прорисовки поперечного сечения — пробуем поставить опоры
                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, half,
                                        offset, minX, maxX, minZ, maxZ, yHint, supportBlock);
                idx++;
            }
        }
    }

    /** Полотно моста с 7-ступенчатыми заездами на концах (только для больших мостов). */
    private void paintBridgeDeckWithRamps(List<int[]> pts, int width, Block deckBlock,
                                        int mainOffset, int rampSteps,
                                        int minX, int maxX, int minZ, int maxZ,
                                        Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int half = Math.max(0, width / 2);
        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];
            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);
            Integer yHint = null;

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                int localOffset = rampOffsetForIndex(idx, totalLen, mainOffset, rampSteps);

                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    int ySurf = findTopNonAirNearIgnoringBridge(xx, zz, yHint);
                    if (ySurf == Integer.MIN_VALUE) continue;

                    int yDeck = Math.max(worldMin, Math.min(worldMax, ySurf + localOffset));

                    // полотно
                    setBridgeBlock(xx, yDeck, zz, deckBlock);

                    // бордюр СНАРУЖИ (±(half+1)) + стенка
                    if (w == -half || w == half) {
                        int wOut = (w < 0) ? -half - 1 : half + 1;
                        int ox = horizontalMajor ? x : x + wOut;
                        int oz = horizontalMajor ? z + wOut : z;
                        if (!(ox < minX || ox > maxX || oz < minZ || oz > maxZ)) {
                            setBridgeBlock(ox, yDeck, oz, curbBlock);
                            if (yDeck + 1 <= worldMax) setBridgeBlock(ox, yDeck + 1, oz, wallBlock);
                        }
                    }

                    yHint = ySurf;
                }

                // опоры по периодичности
                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, half,
                                        localOffset, minX, maxX, minZ, maxZ, yHint, supportBlock);
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

        int idx = 0; // индекс вдоль центр-линии для периодичности опор

        for (int i = 1; i < pts.size(); i++) {
            int x1 = pts.get(i - 1)[0], z1 = pts.get(i - 1)[1];
            int x2 = pts.get(i)[0],     z2 = pts.get(i)[1];

            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
            int[][] sides = horizontalMajor ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);
            Integer yHint = null;

            for (int[] p : seg) {
                int x = p[0], z = p[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) { idx++; continue; }

                int ySurf = findTopNonAirNearIgnoringBridge(x, z, yHint);
                if (ySurf == Integer.MIN_VALUE) { idx++; continue; }

                int yBase = Math.max(worldMin, Math.min(worldMax, ySurf + offset));
                if (yBase == worldMax && yBase - 1 >= worldMin) yBase--;

                // путь
                setBridgeBlock(x, yBase, z, cobble);
                if (yBase + 1 <= worldMax) setBridgeBlock(x, yBase + 1, z, railBlock);

                // бортики слева/справа
                for (int[] s : sides) {
                    int sx = x + s[0], sz = z + s[1];
                    if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                    setBridgeBlock(sx, yBase,     sz, curbBlock);
                    if (yBase + 1 <= worldMax) setBridgeBlock(sx, yBase + 1, sz, wallBlock);
                }

                yHint = ySurf;

                // опоры (для ЖД — от края полотна шириной 1: half = 0)
                placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, 0,
                                        offset, minX, maxX, minZ, maxZ, yHint, supportBlock);
                idx++;
            }
        }
    }

    /** Рельсы на мосту со ступенями (только для больших мостов). */
    private void paintRailsWithRamps(List<int[]> pts, int mainOffset, int rampSteps,
                                    int minX, int maxX, int minZ, int maxZ,
                                    Block cobble, Block railBlock,
                                    Block curbBlock, Block wallBlock, Block supportBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];

            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
            int[][] sides = horizontalMajor ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);
            Integer yHint = null;

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) { idx++; continue; }

                int localOffset = rampOffsetForIndex(idx, totalLen, mainOffset, rampSteps);

                int ySurf = findTopNonAirNearIgnoringBridge(x, z, yHint);
                if (ySurf != Integer.MIN_VALUE) {
                    int yBase = Math.max(worldMin, Math.min(worldMax, ySurf + localOffset));
                    if (yBase == worldMax && yBase - 1 >= worldMin) yBase--;

                    // путь
                    setBridgeBlock(x, yBase, z, cobble);
                    if (yBase + 1 <= worldMax) setBridgeBlock(x, yBase + 1, z, railBlock);

                    // бортики
                    for (int[] s : sides) {
                        int sx = x + s[0], sz = z + s[1];
                        if (sx < minX || sx > maxX || sz < minZ || sz > maxZ) continue;
                        setBridgeBlock(sx, yBase,     sz, curbBlock);
                        if (yBase + 1 <= worldMax) setBridgeBlock(sx, yBase + 1, sz, wallBlock);
                    }

                    yHint = ySurf;

                    // опоры по периодичности (half = 0 для ЖД полотна)
                    placeSupportPairIfNeeded(idx, SUPPORT_PERIOD, x, z, horizontalMajor, 0,
                                            localOffset, minX, maxX, minZ, maxZ, yHint, supportBlock);
                }
                idx++;
            }
        }
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

        // ширина одной "ступени" по длине (в блоках)
        final int SPAN = 2;

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

        // координаты левого/правого края полотна (без внешних бортиков)
        int ex1 = horizontalMajor ? cx : cx - half;
        int ez1 = horizontalMajor ? cz - half : cz;
        int ex2 = horizontalMajor ? cx : cx + half;
        int ez2 = horizontalMajor ? cz + half : cz;

        // две точки-края
        int[][] edges = new int[][] { {ex1, ez1}, {ex2, ez2} };

        for (int[] e : edges) {
            int ex = e[0], ez = e[1];
            if (ex < minX || ex > maxX || ez < minZ || ez > maxZ) continue;

            int ySurf = findTopNonAirNearIgnoringBridge(ex, ez, yHint);
            if (ySurf == Integer.MIN_VALUE) continue;

            // если наверху рельефа стоит "дорога" — опору пропускаем
            Block surfBlock = level.getBlockState(new BlockPos(ex, ySurf, ez)).getBlock();
            if (isRoadLikeBlock(surfBlock)) continue;

            int yTop = ySurf + offset; // высота полотна в этой колонке
            if (yTop <= ySurf) continue; // некуда ставить

            if (yTop > worldMax) yTop = worldMax;

            // тянем колонну stone_bricks от (yTop-1) до (ySurf+1), не трогая сам рельеф
            for (int y = yTop - 1; y >= Math.max(worldMin, ySurf + 1); y--) {
                setBridgeBlock(ex, y, ez, supportBlock);
            }
        }
    }

    private int findTopNonAirNearIgnoringBridge(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (placedByBridge.contains(BlockPos.asLong(x, y, z))) continue; // считаем как воздух
                if (!level.getBlockState(pos).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (placedByBridge.contains(BlockPos.asLong(x, y, z))) continue; // считаем как воздух
            if (!level.getBlockState(pos).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private void setBridgeBlock(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
        placedByBridge.add(BlockPos.asLong(x, y, z));
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
}