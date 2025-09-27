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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TunnelGenerator {

    private final ServerLevel level;
    private final JsonObject coords;

    // Все позиции блоков, поставленных ЭТИМ генератором в текущем запуске.
    private final Set<Long> placedByTunnel = new HashSet<>();

    public TunnelGenerator(ServerLevel level, JsonObject coords) {
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

    // === Материалы (те же, что в дорогах/мостах) ===
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
    }

    // === Параметры глубины/лесенок ===
    private static final int DEFAULT_DEPTH      = 7;  // фиксированная глубина тоннеля относительно поверхности
    private static final int RAMP_STEPS         = 7;  // ступеней спуска/подъёма
    private static final int EDGE_CLEAR_STEPS   = 4;  // сколько крайних ступеней чистим от рельефа
    private static final int RAMP_SPAN          = 1;  // длина одной «ступени» по оси пути (в блоках)
    private static final int RIM_BUILD_STEPS    = 3;  // на скольких крайних ступенях строим короб
    private static final int RIM_LENGTH         = 3;  // длина короба в блоках внутрь тоннеля

    // ==== публичный запуск ====
    public void generate() {
        broadcast(level, "🚇 Тоннели: глубина всегда 7 ниже рельефа, ступень=1 блок, порталы x4 + каменные ограждения…");

        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю TunnelGenerator.");
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
            broadcast(level, "OSM elements пуст — пропускаю тоннели.");
            return;
        }

        Block railBlock = resolveBlock("minecraft:rail");
        Block stoneBricks = resolveBlock("minecraft:stone_bricks");

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (isHighwayTunnel(tags) || isRailTunnel(tags)) totalWays++;
        }

        int processed = 0;

        // === 1) Дорожные/аэродромные подземные участки (ПОЛОТНО дороги под землёй) ===
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!isHighwayTunnel(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            String highway = optString(tags, "highway");
            String aeroway = optString(tags, "aeroway");
            String styleKey = (highway != null) ? highway : (aeroway != null ? "aeroway:" + aeroway : "");
            RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));
            Block deckBlock = resolveBlock(style.blockId);

            List<int[]> pts = new ArrayList<>();
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
            }

            int mainDepth = computeMainDepth(tags); // всегда 7

            paintTunnelDeckWithRamps(pts, style.width, deckBlock, mainDepth, RAMP_STEPS,
                    minX, maxX, minZ, maxZ, stoneBricks);

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Тоннели: ~" + pct + "%");
            }
        }

        // === 2) Чисто железнодорожные подземные участки (только если ЖД сами подземные) ===
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!isRailTunnel(tags)) continue; // строго по своим подземным тегам

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            List<int[]> pts = new ArrayList<>();
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
            }

            int mainDepth = computeMainDepth(tags); // всегда 7

            Block baseBlock = resolveBlock("minecraft:gray_concrete");
            paintRailsInTunnelWithRamps(pts, mainDepth, RAMP_STEPS,
                    minX, maxX, minZ, maxZ, baseBlock, railBlock, stoneBricks);

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Тоннели: ~" + pct + "%");
            }
        }

        broadcast(level, "Тоннели готовы.");
    }

    // ====== ОТБОР ======

    // значения covered, характерные для проходов/аркад под крышей здания
    private static final Set<String> COVERED_VALUES = new HashSet<>(Arrays.asList(
            "yes","arcade","colonnade","gallery","veranda","canopy","roof"
    ));

    private static boolean hasAnyCovered(JsonObject tags) {
        String raw = optString(tags, "covered");
        if (raw == null) return false;
        for (String token : raw.toLowerCase(Locale.ROOT).split("[;|,]")) {
            if (COVERED_VALUES.contains(token.trim())) return true;
        }
        return false;
    }

    /** Любой «тоннель», связанный со зданиями/интерьерами — пропускаем в этом генераторе. */
    private static boolean isBuildingLinkedPassage(JsonObject tags) {
        String t = optString(tags, "tunnel");
        if (t != null) {
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.contains("building_passage") || tl.equals("building")) return true;
        }
        if (truthyOrText(tags, "indoor")) return true; // indoor=yes
        String loc = optString(tags, "location");
        if (loc != null && loc.toLowerCase(Locale.ROOT).contains("indoor")) return true; // location=indoor(s)
        if (hasAnyCovered(tags)) return true; // covered=yes/arcade/…
        return false;
    }

    private static boolean truthyOrText(JsonObject tags, String key) {
        String v = optString(tags, key);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

    // Парсинг любых чисел внутри значения тега (учитывает "-1;-2", " -1 ; 0 " и т.п.)
    private static final Pattern INT_PATTERN = Pattern.compile("[-+]?\\d+");

    private static Integer mostNegativeIntFromTag(JsonObject tags, String key) {
        String raw = optString(tags, key);
        if (raw == null) return null;
        Matcher m = INT_PATTERN.matcher(raw);
        Integer min = null;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group());
                if (min == null || v < min) min = v;
            } catch (Exception ignore) {}
        }
        return min;
    }

    // «тоннелеподобность»: tunnel=*, layer<0, level<0, location=underground/below_ground. bridge исключаем.
    private static boolean isTunnelLike(JsonObject tags) {
        if (truthyOrText(tags, "bridge")) return false;           // мосты исключаем
        if (truthyOrText(tags, "tunnel")) return true;
        Integer layerNeg = mostNegativeIntFromTag(tags, "layer");
        if (layerNeg != null && layerNeg < 0) return true;
        Integer levelNeg = mostNegativeIntFromTag(tags, "level");
        if (levelNeg != null && levelNeg < 0) return true;
        String location = optString(tags, "location");
        if (location != null) {
            String loc = location.trim().toLowerCase(Locale.ROOT);
            if (loc.contains("underground") || loc.contains("below_ground")) return true;
        }
        return false;
    }

    private static boolean isHighwayTunnel(JsonObject tags) {
        boolean isLine = tags.has("highway") || tags.has("aeroway");
        if (!isLine) return false;
        if (isBuildingLinkedPassage(tags)) return false; // игнорим «зданийные»
        return isTunnelLike(tags);
    }

    private static boolean isRailTunnel(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        r = r.trim().toLowerCase(Locale.ROOT);
        if (!(r.equals("rail") || r.equals("tram") || r.equals("light_rail"))) return false;
        if (isBuildingLinkedPassage(tags)) return false; //  игнорим «зданийные»
        return isTunnelLike(tags);
    }

    /** Главная глубина тоннеля — ВСЕГДА 7 блоков ниже поверхности, независимо от тегов. */
    private int computeMainDepth(JsonObject tags) {
        return DEFAULT_DEPTH;
    }

    // ====== РЕНДЕР ТОННЕЛЕЙ (НИЖЕ рельефа) ======

    /** Полотно тоннеля: 7-ступенчатые спуски/подъёмы, БЕЗ бортиков, с очисткой порталов и каменным «ободком». */
    private void paintTunnelDeckWithRamps(List<int[]> pts, int width, Block deckBlock,
                                          int mainDepth, int rampSteps,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Block rimBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int half = Math.max(0, width / 2);
        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;
        Integer yHint = null;
        int prevDeckY = Integer.MIN_VALUE;
        ArrayDeque<Integer> lastStepTops = new ArrayDeque<>(3);

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];

            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
            int dirX = Integer.signum(x2 - x1);
            int dirZ = Integer.signum(z2 - z1);

            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                int fromStart = (idx / RAMP_SPAN) + 1;
                int fromEnd   = ((totalLen - 1 - idx) / RAMP_SPAN) + 1;
                boolean nearStart = fromStart <= EDGE_CLEAR_STEPS;
                boolean nearEnd   = fromEnd   <= EDGE_CLEAR_STEPS;

                int localDepth = rampOffsetForIndex(idx, totalLen, mainDepth, rampSteps);

                int ySurfCenter = findTopNonAirNearIgnoringTunnel(x, z, yHint);
                if (ySurfCenter == Integer.MIN_VALUE) { idx++; continue; }

                int targetDeckY = clampInt(ySurfCenter - localDepth, worldMin, worldMax);
                int yDeck = stepClamp(prevDeckY, targetDeckY, 1);

                if (lastStepTops.size() == 3) lastStepTops.removeFirst();
                lastStepTops.addLast(yDeck);

                boolean clearEdge = nearStart || nearEnd;


                // ==== РУКАВ вдоль полотна (каменный кирпич) ====
                final int wallHeight = 5;            // итого 5 в высоту
                final int yRoof = yDeck + wallHeight - 1;

                // боковые стены на 1 клетку шире полотна (только слева и справа)
                if (horizontalMajor) {
                    int zL = z - (half + 1);
                    int zR = z + (half + 1);
                    placeWallColumnCapped(x, zL, yDeck, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                    placeWallColumnCapped(x, zR, yDeck, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                } else {
                    int xL = x - (half + 1);
                    int xR = x + (half + 1);
                    placeWallColumnCapped(xL, z, yDeck, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                    placeWallColumnCapped(xR, z, yDeck, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                }

                // крыша: сплошняком над областью дорожного полотна (без «носов» спереди/сзади)
                for (int w = -half; w <= half; w++) {
                    int rx = horizontalMajor ? x : x + w;
                    int rz = horizontalMajor ? z + w : z;
                    placeBlockCapped(rx, rz, yRoof, yHint, minX, maxX, minZ, maxZ, rimBlock);
                }

                // поперечник полотна
                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    setTunnelBlock(xx, yDeck, zz, deckBlock);

                    if (clearEdge) {
                        clearTerrainAboveColumn(xx, zz, yDeck, yHint); // портал
                    }
                }

                // каменный «ободок» по периметру портала (кроме стороны захода)
                // строим короб только на крайних 3 ступенях
                boolean rimZone = (fromStart <= RIM_BUILD_STEPS) || (fromEnd <= RIM_BUILD_STEPS);
                if (rimZone) {
                    // «внутрь тоннеля»: от начала идём по +dir, от конца — по -dir
                    int inDirX = (fromStart <= RIM_BUILD_STEPS) ? dirX : -dirX;
                    int inDirZ = (fromStart <= RIM_BUILD_STEPS) ? dirZ : -dirZ;

                    // высота самой высокой из последних/текущей ступени
                    int stepTopMax = lastStepTops.stream().mapToInt(v -> v).max().orElse(yDeck);

                    buildPerimeterWallsAndRoof(
                            x, z, half, horizontalMajor,
                            inDirX, inDirZ,
                            /*stepTopMax=*/stepTopMax,
                            minX, maxX, minZ, maxZ,
                            rimBlock
                    );
                }

                // свет в крыше каждые 10 блоков (после короба, чтобы нас не перезаписало)
                if (idx % 10 == 0) {
                    placeRoofLightNearCenter(
                            x, z, horizontalMajor, half, yRoof, yHint,
                            minX, maxX, minZ, maxZ,
                            Blocks.GLOWSTONE
                    );
                }

                yHint = ySurfCenter;
                prevDeckY = yDeck;
                idx++;
            }
        }
    }

    /** ЖД в тоннеле: база + rail на +1; те же спуски. С порталами и «ободком». */
    private void paintRailsInTunnelWithRamps(List<int[]> pts, int mainDepth, int rampSteps,
                                             int minX, int maxX, int minZ, int maxZ,
                                             Block baseBlock, Block railBlock,
                                             Block rimBlock) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int totalLen = approxPathLengthBlocks(pts);
        int idx = 0;
        Integer yHint = null;
        int prevRailBaseY = Integer.MIN_VALUE;

        ArrayDeque<Integer> lastRailTops = new ArrayDeque<>(3);

        for (int si = 1; si < pts.size(); si++) {
            int x1 = pts.get(si - 1)[0], z1 = pts.get(si - 1)[1];
            int x2 = pts.get(si)[0],     z2 = pts.get(si)[1];

            int dirX = Integer.signum(x2 - x1);
            int dirZ = Integer.signum(z2 - z1);
            boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);


            List<int[]> seg = bresenhamLine(x1, z1, x2, z2);

            for (int pi = 0; pi < seg.size(); pi++) {
                if (si > 1 && pi == 0) continue;

                int x = seg.get(pi)[0], z = seg.get(pi)[1];
                if (x < minX || x > maxX || z < minZ || z > maxZ) { idx++; continue; }

                int fromStart = (idx / RAMP_SPAN) + 1;
                int fromEnd   = ((totalLen - 1 - idx) / RAMP_SPAN) + 1;
                boolean nearStart = fromStart <= EDGE_CLEAR_STEPS;
                boolean nearEnd   = fromEnd   <= EDGE_CLEAR_STEPS;

                int localDepth = rampOffsetForIndex(idx, totalLen, mainDepth, rampSteps);

                int ySurf = findTopNonAirNearIgnoringTunnel(x, z, yHint);
                if (ySurf == Integer.MIN_VALUE) { idx++; continue; }

                int targetBaseY = clampInt(ySurf - localDepth, worldMin, worldMax - 1);
                int yBase = stepClamp(prevRailBaseY, targetBaseY, 1);

                int stepTopHere = yBase + 1;
                if (lastRailTops.size() == 3) lastRailTops.removeFirst();
                lastRailTops.addLast(stepTopHere);


                // ==== РУКАВ вдоль ЖД (каменный кирпич) ====
                final int wallHeight = 5;
                final int yRoof = yBase + wallHeight - 1;

                // боковые стены на 1 клетку от пути
                if (horizontalMajor) {
                    int zL = z - 1;
                    int zR = z + 1;
                    placeWallColumnCapped(x, zL, yBase, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                    placeWallColumnCapped(x, zR, yBase, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                } else {
                    int xL = x - 1;
                    int xR = x + 1;
                    placeWallColumnCapped(xL, z, yBase, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                    placeWallColumnCapped(xR, z, yBase, wallHeight, yHint, minX, maxX, minZ, maxZ, rimBlock);
                }

                // крыша: над самим путём (ширина 1)
                placeBlockCapped(x, z, yRoof, yHint, minX, maxX, minZ, maxZ, rimBlock);

                setTunnelBlock(x, yBase,     z, baseBlock);
                setTunnelBlock(x, yBase + 1, z, railBlock);

                if (nearStart || nearEnd) {
                    clearTerrainAboveColumn(x, z, yBase + 1, yHint);
                }

                boolean rimZone = (fromStart <= RIM_BUILD_STEPS) || (fromEnd <= RIM_BUILD_STEPS);
                if (rimZone) {
                    int inDirX = (fromStart <= RIM_BUILD_STEPS) ? dirX : -dirX;
                    int inDirZ = (fromStart <= RIM_BUILD_STEPS) ? dirZ : -dirZ;

                    int stepTopMax = lastRailTops.stream().mapToInt(v -> v).max().orElse(stepTopHere);

                    buildPerimeterWallsAndRoof(
                            x, z, /*half=*/0, horizontalMajor,
                            inDirX, inDirZ,
                            /*stepTopMax=*/stepTopMax,
                            minX, maxX, minZ, maxZ,
                            rimBlock
                    );
                }
                
                // свет в крыше каждые 10 блоков (ширина крыши = 1, потому half=0)
                if (idx % 10 == 0) {
                    placeRoofLightNearCenter(
                            x, z, horizontalMajor, /*half=*/0, yRoof, yHint,
                            minX, maxX, minZ, maxZ,
                            Blocks.GLOWSTONE
                    );
                }

                yHint = ySurf;
                prevRailBaseY = yBase;
                idx++;
            }
        }
    }

    // ====== КАМЕННЫЙ ОБОДОК ВОКРУГ ПОРТАЛА ======

    private void buildPerimeterWallsAndRoof(
            int x, int z, int half, boolean horizontalMajor,
            int inDirX, int inDirZ,
            int stepTopMax,
            int minX, int maxX, int minZ, int maxZ,
            Block rimBlock
    ) {
        final int worldMax = level.getMaxBuildHeight() - 1;
        int h1 = Math.min(worldMax, stepTopMax + 1); // 1-й ярус стены
        int h2 = Math.min(worldMax, stepTopMax + 2); // 2-й ярус стены
        int h3 = Math.min(worldMax, stepTopMax + 3); // 3-й ярус стены
        int h4 = h3; // крыша

        // Боковые стены: по обе стороны ширины, вдоль длины 0..RIM_LENGTH-1
        for (int i = 0; i < RIM_LENGTH; i++) {
            int cx = x + inDirX * i;
            int cz = z + inDirZ * i;

            if (horizontalMajor) {
                int zl = cz - (half + 1);
                int zr = cz + (half + 1);
                placeRimBlock(cx, zl, h1, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(cx, zl, h2, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(cx, zl, h3, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(cx, zr, h1, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(cx, zr, h2, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(cx, zr, h3, minX, maxX, minZ, maxZ, rimBlock);
            } else {
                int xl = cx - (half + 1);
                int xr = cx + (half + 1);
                placeRimBlock(xl, cz, h1, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(xl, cz, h2, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(xl, cz, h3, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(xr, cz, h1, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(xr, cz, h2, minX, maxX, minZ, maxZ, rimBlock);
                placeRimBlock(xr, cz, h3, minX, maxX, minZ, maxZ, rimBlock);

            }
        }

        // Задняя (внутренняя) стена на отступе i = RIM_LENGTH (поперёк ширины)
        int bx = x + inDirX * RIM_LENGTH;
        int bz = z + inDirZ * RIM_LENGTH;
        for (int w = -half; w <= half; w++) {
            int wx = horizontalMajor ? bx : bx + w;
            int wz = horizontalMajor ? bz + w : bz;
            placeRimBlock(wx, wz, h1, minX, maxX, minZ, maxZ, rimBlock);
            placeRimBlock(wx, wz, h2, minX, maxX, minZ, maxZ, rimBlock);
            placeRimBlock(wx, wz, h3, minX, maxX, minZ, maxZ, rimBlock);
        }

        // Крыша: над прямоугольником ширина×RIM_LENGTH на высоте h3
        for (int i = 0; i < RIM_LENGTH; i++) {
            int cx = x + inDirX * i;
            int cz = z + inDirZ * i;
            for (int w = -half; w <= half; w++) {
                int wx = horizontalMajor ? cx : cx + w;
                int wz = horizontalMajor ? cz + w : cz;
                placeRimBlock(wx, wz, h4, minX, maxX, minZ, maxZ, rimBlock);
            }
        }
    }

    /** Ставит rimBlock на точной высоте (x,y,z) и помечает как "наш". */
    private void placeRimBlock(int x, int z, int y,
                            int minX, int maxX, int minZ, int maxZ, Block rimBlock) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        level.setBlock(new BlockPos(x, y, z), rimBlock.defaultBlockState(), 3);
        placedByTunnel.add(BlockPos.asLong(x, y, z));
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

    /** Симметричные спуски/подъёмы: каждые RAMP_SPAN блоков увеличиваем/уменьшаем ступень. */
    private int rampOffsetForIndex(int idx, int totalLen, int mainOffset, int rampSteps) {
        if (mainOffset <= 0) return 0;

        final int rampHeight = Math.min(rampSteps, mainOffset);
        final int baseOffset = Math.max(0, mainOffset - rampHeight);

        int fromStart = (idx / RAMP_SPAN) + 1;
        int fromEnd   = ((totalLen - 1 - idx) / RAMP_SPAN) + 1;

        int step = Math.min(rampHeight, Math.min(fromStart, fromEnd));
        return baseOffset + step;
    }

    // ====== ПОИСК РЕЛЬЕФА / УСТАНОВКА БЛОКОВ / ОЧИСТКА ======

    private int findTopNonAirNearIgnoringTunnel(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                long key = BlockPos.asLong(x, y, z);
                if (placedByTunnel.contains(key)) continue; // наши блоки считаем воздухом
                if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            long key = BlockPos.asLong(x, y, z);
            if (placedByTunnel.contains(key)) continue;
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Ставит блок и запоминает как «наш». */
    private void setTunnelBlock(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
        placedByTunnel.add(BlockPos.asLong(x, y, z));
    }

    /** Очищает рельеф над (x,z) от поверхности до (yBottomInclusive+1), НЕ трогая rail-блоки (останавливаемся). */
    private void clearTerrainAboveColumn(int x, int z, int yBottomInclusive, Integer yHint) {
        final int worldMax = level.getMaxBuildHeight() - 1;
        int ySurf = findTopNonAirNearIgnoringTunnel(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        for (int y = Math.min(worldMax, ySurf); y > yBottomInclusive; y--) {
            Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
            if (b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL) {
                break; // не удаляем рельсы и всё, что ниже них
            }
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
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

    // --- helpers для «лесенки» ---
    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    /** Ограничиваем изменение высоты: не более чем на ±maxStep от prevY. */
    private static int stepClamp(int prevY, int targetY, int maxStep) {
        if (prevY == Integer.MIN_VALUE) return targetY; // первый шаг — без ограничений
        if (targetY > prevY + maxStep) return prevY + maxStep;
        if (targetY < prevY - maxStep) return prevY - maxStep;
        return targetY;
    }

    /** Вертикальная колонна стены, но не выше (ySurf-1). Ставим всё, что помещается. */
    private void placeWallColumnCapped(int x, int z, int yStart, int height,
                                    Integer yHint,
                                    int minX, int maxX, int minZ, int maxZ,
                                    Block wallBlock) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int ySurf = findTopNonAirNearIgnoringTunnel(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        int yTopWanted  = yStart + height - 1;
        int yTopAllowed = Math.min(worldMax, Math.min(yTopWanted, ySurf - 1));
        if (yTopAllowed < yStart) return;

        for (int y = Math.max(worldMin, yStart); y <= yTopAllowed; y++) {
            setTunnelBlock(x, y, z, wallBlock);
        }
    }

    /** Ставим одиночный блок только если целевая высота ≤ (ySurf-1). Иначе пропускаем. */
    private void placeBlockCapped(int x, int z, int y,
                                Integer yHint,
                                int minX, int maxX, int minZ, int maxZ,
                                Block block) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;
        if (y < worldMin || y > worldMax) return;

        int ySurf = findTopNonAirNearIgnoringTunnel(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        if (y <= ySurf - 1) {
            setTunnelBlock(x, y, z, block);
        }
    }

    /** Ставим свет в крыше по центру; если нельзя — ищем ближайшеe место к центру на той же высоте. */
    private void placeRoofLightNearCenter(int x, int z, boolean horizontalMajor, int half, int yRoof,
                                        Integer yHint, int minX, int maxX, int minZ, int maxZ,
                                        Block lightBlock) {
        // порядок смещений поперёк полотна: 0, +1, -1, +2, -2, ...
        for (int d = 0; d <= half; d++) {
            int[] offs = (d == 0) ? new int[]{0} : new int[]{d, -d};
            for (int o : offs) {
                int rx = horizontalMajor ? x : x + o;
                int rz = horizontalMajor ? z + o : z;
                if (rx < minX || rx > maxX || rz < minZ || rz > maxZ) continue;

                int ySurf = findTopNonAirNearIgnoringTunnel(rx, rz, yHint);
                if (ySurf == Integer.MIN_VALUE) continue;
                if (yRoof <= ySurf - 1) {
                    setTunnelBlock(rx, yRoof, rz, lightBlock);
                    return; // поставили — выходим
                }
            }
        }
        // места нет — ничего не ставим
    }

}