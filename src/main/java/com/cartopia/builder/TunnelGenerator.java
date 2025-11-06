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
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TunnelGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;       // может быть null
    private final TerrainGridStore grid;       // может быть null

    // Все позиции блоков, поставленных ЭТИМ генератором в текущем запуске.
    private final Set<Long> placedByTunnel = new HashSet<>();

    // для быстрого доступа к состояниям — переиспользуемый mpos
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

    public TunnelGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
        this.grid = (store != null) ? store.grid : null;
    }
    public TunnelGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

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
        broadcast(level, "Tunnels: depth 7 below terrain, step = 1 block, portals with a rim...");

        if (coords == null) {
            broadcast(level, "coords == null — skipping TunnelGenerator.");
            return;
        }
        if (!coords.has("center") || !coords.has("bbox")) {
            broadcast(level, "No center/bbox — skipping TunnelGenerator.");
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

        Block railBlock   = resolveBlock("minecraft:rail");
        Block stoneBricks = resolveBlock("minecraft:stone_bricks");

        // ===== РЕЖИМ 1: есть store → стримим NDJSON =====
        if (store != null) {
            long approxTotal = 0;
            try {
                JsonObject idx = store.indexJsonObject();
                if (idx != null && idx.has("features_count")) approxTotal = Math.max(0, idx.get("features_count").getAsLong());
            } catch (Throwable ignore) {}

            long scanned = 0;
            int nextPctMark = 5;

            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    scanned++;

                    if (!"way".equals(optString(e, "type"))) continue;

                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject())
                            ? e.getAsJsonObject("tags") : null;
                    if (tags == null) continue;

                    if (isHighwayTunnel(tags)) {
                        JsonArray geom = e.getAsJsonArray("geometry");
                        if (geom == null || geom.size() < 2) continue;

                        String highway = optString(tags, "highway");
                        String aeroway = optString(tags, "aeroway");
                        String styleKey = (highway != null) ? highway : (aeroway != null ? "aeroway:" + aeroway : "");
                        RoadStyle style = ROAD_MATERIALS.getOrDefault(styleKey, new RoadStyle("stone", 4));
                        Block deckBlock = resolveBlock(style.blockId);

                        List<int[]> pts = new ArrayList<>(geom.size());
                        for (int i = 0; i < geom.size(); i++) {
                            JsonObject p = geom.get(i).getAsJsonObject();
                            pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                        }

                        int mainDepth = computeMainDepth(tags);
                        paintTunnelDeckWithRamps(pts, style.width, deckBlock, mainDepth, RAMP_STEPS,
                                minX, maxX, minZ, maxZ, stoneBricks);

                    } else if (isRailTunnel(tags)) {
                        JsonArray geom = e.getAsJsonArray("geometry");
                        if (geom == null || geom.size() < 2) continue;

                        List<int[]> pts = new ArrayList<>(geom.size());
                        for (int i = 0; i < geom.size(); i++) {
                            JsonObject p = geom.get(i).getAsJsonObject();
                            pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                        }

                        int mainDepth = computeMainDepth(tags);
                        Block baseBlock = resolveBlock("minecraft:gray_concrete");
                        paintRailsInTunnelWithRamps(pts, mainDepth, RAMP_STEPS,
                                minX, maxX, minZ, maxZ, baseBlock, railBlock, stoneBricks);
                    }

                    if (approxTotal > 0) {
                        int pct = (int)Math.round(100.0 * Math.min(scanned, approxTotal) / (double)approxTotal);
                        if (pct >= nextPctMark) {
                            broadcast(level, "Tunnels: ~" + pct + "%");
                            nextPctMark = Math.min(100, nextPctMark + 5);
                        }
                    } else if (scanned % 10000 == 0) {
                        broadcast(level, "Tunnels: processed ≈ " + scanned);
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Error reading features NDJSON: " + ex.getMessage());
            }

            broadcast(level, "Tunnels done.");
            return;
        }

        // ===== РЕЖИМ 2: фоллбэк на старый JSON в памяти =====
        if (!coords.has("features") || !coords.get("features").isJsonObject()) {
            broadcast(level, "No features in coords — skipping TunnelGenerator.");
            return;
        }

        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements are empty — skipping tunnels.");
            return;
        }

        int totalWays = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (isHighwayTunnel(tags) || isRailTunnel(tags)) totalWays++;
        }

        int processed = 0;

        // 1) дорожные/аэродромные тоннели
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

            int mainDepth = computeMainDepth(tags);
            paintTunnelDeckWithRamps(pts, style.width, deckBlock, mainDepth, RAMP_STEPS,
                    minX, maxX, minZ, maxZ, stoneBricks);

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Tunnels: ~" + pct + "%");
            }
        }

        // 2) чисто железнодорожные тоннели
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!isRailTunnel(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            List<int[]> pts = new ArrayList<>();
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
            }

            int mainDepth = computeMainDepth(tags);
            Block baseBlock = resolveBlock("minecraft:gray_concrete");
            paintRailsInTunnelWithRamps(pts, mainDepth, RAMP_STEPS,
                    minX, maxX, minZ, maxZ, baseBlock, railBlock, stoneBricks);

            processed++;
            if (totalWays > 0 && processed % Math.max(1, totalWays/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalWays));
                broadcast(level, "Tunnels: ~" + pct + "%");
            }
        }

        broadcast(level, "Tunnels done.");
    }

    // ====== ОТБОР ======

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

    private static boolean isBuildingLinkedPassage(JsonObject tags) {
        String t = optString(tags, "tunnel");
        if (t != null) {
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.contains("building_passage") || tl.equals("building")) return true;
        }
        if (truthyOrText(tags, "indoor")) return true;
        String loc = optString(tags, "location");
        if (loc != null && loc.toLowerCase(Locale.ROOT).contains("indoor")) return true;
        if (hasAnyCovered(tags)) return true;
        return false;
    }

    private static boolean truthyOrText(JsonObject tags, String key) {
        String v = optString(tags, key);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

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

    private static boolean isTunnelLike(JsonObject tags) {
        if (truthyOrText(tags, "bridge")) return false;
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
        if (isBuildingLinkedPassage(tags)) return false;
        return isTunnelLike(tags);
    }

    private static boolean isRailTunnel(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        r = r.trim().toLowerCase(Locale.ROOT);
        if (!(r.equals("rail") || r.equals("tram") || r.equals("light_rail"))) return false;
        if (isBuildingLinkedPassage(tags)) return false;
        return isTunnelLike(tags);
    }

    /** Главная глубина тоннеля — ВСЕГДА 7 блоков ниже поверхности. */
    private int computeMainDepth(JsonObject tags) {
        return DEFAULT_DEPTH;
    }

    // ====== РЕНДЕР ТОННЕЛЕЙ ======

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

                int ySurfCenter = surfaceY(x, z, yHint);
                if (ySurfCenter == Integer.MIN_VALUE) { idx++; continue; }

                int targetDeckY = clampInt(ySurfCenter - localDepth, worldMin, worldMax);
                int yDeck = stepClamp(prevDeckY, targetDeckY, 1);

                if (lastStepTops.size() == 3) lastStepTops.removeFirst();
                lastStepTops.addLast(yDeck);

                final int wallHeight = 5;
                final int yRoof = yDeck + wallHeight - 1;

                // боковые стены
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

                // крыша над полотном
                for (int w = -half; w <= half; w++) {
                    int rx = horizontalMajor ? x : x + w;
                    int rz = horizontalMajor ? z + w : z;
                    placeBlockCapped(rx, rz, yRoof, yHint, minX, maxX, minZ, maxZ, rimBlock);
                }

                // полотно и очистка порталов
                boolean clearEdge = nearStart || nearEnd;
                for (int w = -half; w <= half; w++) {
                    int xx = horizontalMajor ? x : x + w;
                    int zz = horizontalMajor ? z + w : z;
                    if (xx < minX || xx > maxX || zz < minZ || zz > maxZ) continue;

                    setTunnelBlock(xx, yDeck, zz, deckBlock);

                    if (clearEdge) {
                        clearTerrainAboveColumn(xx, zz, yDeck, yHint);
                    }
                }

                // каменный «ободок» в крайних трёх ступенях
                boolean rimZone = (fromStart <= RIM_BUILD_STEPS) || (fromEnd <= RIM_BUILD_STEPS);
                if (rimZone) {
                    int inDirX = (fromStart <= RIM_BUILD_STEPS) ? dirX : -dirX;
                    int inDirZ = (fromStart <= RIM_BUILD_STEPS) ? dirZ : -dirZ;

                    int stepTopMax = lastStepTops.stream().mapToInt(v -> v).max().orElse(yDeck);

                    buildPerimeterWallsAndRoof(
                            x, z, half, horizontalMajor,
                            inDirX, inDirZ,
                            stepTopMax,
                            minX, maxX, minZ, maxZ,
                            rimBlock
                    );
                }

                // свет в крыше
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

                int ySurf = surfaceY(x, z, yHint);
                if (ySurf == Integer.MIN_VALUE) { idx++; continue; }

                int targetBaseY = clampInt(ySurf - localDepth, worldMin, worldMax - 1);
                int yBase = stepClamp(prevRailBaseY, targetBaseY, 1);

                int stepTopHere = yBase + 1;
                if (lastRailTops.size() == 3) lastRailTops.removeFirst();
                lastRailTops.addLast(stepTopHere);

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

                // крыша (ширина 1)
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
                            x, z, 0, horizontalMajor,
                            inDirX, inDirZ,
                            stepTopMax,
                            minX, maxX, minZ, maxZ,
                            rimBlock
                    );
                }

                if (idx % 10 == 0) {
                    placeRoofLightNearCenter(
                            x, z, horizontalMajor, 0, yRoof, yHint,
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
        int h1 = Math.min(worldMax, stepTopMax + 1);
        int h2 = Math.min(worldMax, stepTopMax + 2);
        int h3 = Math.min(worldMax, stepTopMax + 3);
        int h4 = h3;

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

        int bx = x + inDirX * RIM_LENGTH;
        int bz = z + inDirZ * RIM_LENGTH;
        for (int w = -half; w <= half; w++) {
            int wx = horizontalMajor ? bx : bx + w;
            int wz = horizontalMajor ? bz + w : bz;
            placeRimBlock(wx, wz, h1, minX, maxX, minZ, maxZ, rimBlock);
            placeRimBlock(wx, wz, h2, minX, maxX, minZ, maxZ, rimBlock);
            placeRimBlock(wx, wz, h3, minX, maxX, minZ, maxZ, rimBlock);
        }

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

    private void placeRimBlock(int x, int z, int y,
                               int minX, int maxX, int minZ, int maxZ, Block rimBlock) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        level.setBlock(mpos.set(x, y, z), rimBlock.defaultBlockState(), 3);
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

    private int rampOffsetForIndex(int idx, int totalLen, int mainOffset, int rampSteps) {
        if (mainOffset <= 0) return 0;

        final int rampHeight = Math.min(rampSteps, mainOffset);
        final int baseOffset = Math.max(0, mainOffset - rampHeight);

        int fromStart = (idx / RAMP_SPAN) + 1;
        int fromEnd   = ((totalLen - 1 - idx) / RAMP_SPAN) + 1;

        int step = Math.min(rampHeight, Math.min(fromStart, fromEnd));
        return baseOffset + step;
    }

    // ====== РЕЛЬЕФ / УСТАНОВКА / ОЧИСТКА ======

    /** Высота поверхности: сперва из mmap-гряда, иначе — поиск сверху вниз, игнорируя наши же блоки. */
    private int surfaceY(int x, int z, Integer hintY) {
        try {
            if (grid != null && grid.inBounds(x, z)) {
                int y = grid.groundY(x, z);
                if (y != Integer.MIN_VALUE) return y;
            }
        } catch (Throwable ignore) {}
        return findTopNonAirNearIgnoringTunnel(x, z, hintY);
    }

    private int findTopNonAirNearIgnoringTunnel(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                long key = BlockPos.asLong(x, y, z);
                if (placedByTunnel.contains(key)) continue;
                if (!level.getBlockState(mpos.set(x, y, z)).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            long key = BlockPos.asLong(x, y, z);
            if (placedByTunnel.contains(key)) continue;
            if (!level.getBlockState(mpos.set(x, y, z)).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Ставит блок и запоминает как «наш». */
    private void setTunnelBlock(int x, int y, int z, Block block) {
        level.setBlock(mpos.set(x, y, z), block.defaultBlockState(), 3);
        placedByTunnel.add(BlockPos.asLong(x, y, z));
    }

    /** Чистим столбец над (x,z) от поверхности до (yBottomInclusive+1), не трогая rail-блоки. */
    private void clearTerrainAboveColumn(int x, int z, int yBottomInclusive, Integer yHint) {
        final int worldMax = level.getMaxBuildHeight() - 1;
        int ySurf = surfaceY(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        for (int y = Math.min(worldMax, ySurf); y > yBottomInclusive; y--) {
            Block b = level.getBlockState(mpos.set(x, y, z)).getBlock();
            if (b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL) {
                break; // не удаляем рельсы и всё, что ниже них
            }
            level.setBlock(mpos.set(x, y, z), Blocks.AIR.defaultBlockState(), 3);
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

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    private static int stepClamp(int prevY, int targetY, int maxStep) {
        if (prevY == Integer.MIN_VALUE) return targetY;
        if (targetY > prevY + maxStep) return prevY + maxStep;
        if (targetY < prevY - maxStep) return prevY - maxStep;
        return targetY;
    }

    private void placeWallColumnCapped(int x, int z, int yStart, int height,
                                       Integer yHint,
                                       int minX, int maxX, int minZ, int maxZ,
                                       Block wallBlock) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        int ySurf = surfaceY(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        int yTopWanted  = yStart + height - 1;
        int yTopAllowed = Math.min(worldMax, Math.min(yTopWanted, ySurf - 1));
        if (yTopAllowed < yStart) return;

        for (int y = Math.max(worldMin, yStart); y <= yTopAllowed; y++) {
            setTunnelBlock(x, y, z, wallBlock);
        }
    }

    private void placeBlockCapped(int x, int z, int y,
                                  Integer yHint,
                                  int minX, int maxX, int minZ, int maxZ,
                                  Block block) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;
        if (y < worldMin || y > worldMax) return;

        int ySurf = surfaceY(x, z, yHint);
        if (ySurf == Integer.MIN_VALUE) return;

        if (y <= ySurf - 1) {
            setTunnelBlock(x, y, z, block);
        }
    }

    private void placeRoofLightNearCenter(int x, int z, boolean horizontalMajor, int half, int yRoof,
                                          Integer yHint, int minX, int maxX, int minZ, int maxZ,
                                          Block lightBlock) {
        for (int d = 0; d <= half; d++) {
            int[] offs = (d == 0) ? new int[]{0} : new int[]{d, -d};
            for (int o : offs) {
                int rx = horizontalMajor ? x : x + o;
                int rz = horizontalMajor ? z + o : z;
                if (rx < minX || rx > maxX || rz < minZ || rz > maxZ) continue;

                int ySurf = surfaceY(rx, rz, yHint);
                if (ySurf == Integer.MIN_VALUE) continue;
                if (yRoof <= ySurf - 1) {
                    setTunnelBlock(rx, yRoof, rz, lightBlock);
                    return;
                }
            }
        }
    }
}
