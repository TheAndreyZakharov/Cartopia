package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.*;


public class RoadButtonMarkingGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Настройки
    private static final boolean ENABLE_SEAM_LINE = false; // без шва

    // Шаги/константы
    private static final int APPROACH_DENSE = 20;   // уплотнение за 20 блоков до пересечения
    private static final int SKIP_AT_NODE = 2;      // не ставим в пределах ±2 блоков от узла по длине
    private static final int DEFAULT_WIDTH = 12;    // fallback ширины
    private static final int MAX_SEG_STEP = 1;      // дискретизация вдоль сегмента
    private static final int INTERSECTION_RADIUS = 4; // радиус маски пересечения (в блоках)
    // тоннели
    private static final int TUNNEL_MAIN_DEPTH = 7;
    private static final int TUNNEL_RAMP_STEPS = 7;
    private static final int TUNNEL_RAMP_SPAN  = 1;

    // bbox в блоках
    private int minX, maxX, minZ, maxZ;
    private int gridW, gridH;

    private BitSet roadMask;                // маска «здесь дорога» (xz → бит)
    private BitSet intersectionMask;        // маска «зона пересечения» (xz → бит)
    private int maskOriginX, maskOriginZ;   // смещение индексации масок

    public RoadButtonMarkingGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }
    public RoadButtonMarkingGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

    // ===== Публичный запуск =====
    public void generate() {
        broadcast(level, "Generating road button markings...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping RoadButtonMarkingGenerator.");
            return;
        }

        // Гео - блоки, подготовка bbox/масок
        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        double centerLat  = center.get("lat").getAsDouble();
        double centerLng  = center.get("lng").getAsDouble();
        int sizeMeters    = coords.get("sizeMeters").getAsInt();

        double south = bbox.get("south").getAsDouble();
        double north = bbox.get("north").getAsDouble();
        double west  = bbox.get("west").getAsDouble();
        double east  = bbox.get("east").getAsDouble();

        int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]); maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]); maxZ = Math.max(a[1], b[1]);

        gridW = (maxX - minX + 1);
        gridH = (maxZ - minZ + 1);
        maskOriginX = minX;
        maskOriginZ = minZ;
        roadMask = new BitSet(gridW * gridH);
        intersectionMask = new BitSet(gridW * gridH);

        // PASS1: считаем пересечения и строим маску дорожного покрытия
        Map<Long, Integer> nodeTouch = new HashMap<>();
        Set<Long> crossingNodeIds = new HashSet<>();
        int waysCount = 0;
        int maskedCells = 0;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;

                JsonObject tags = tagsOf(way);
                if (tags == null || !isCarRoad(tags)) continue;

                waysCount++;

                int width = Math.max(1, widthFromWayTagsOrDefault(tags));

                // Узлы для выявления пересечений
                JsonArray nds = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
                if (nds != null) {
                    for (int i = 0; i < nds.size(); i++) {
                        long nid = nds.get(i).getAsLong();
                        nodeTouch.put(nid, nodeTouch.getOrDefault(nid, 0) + 1);
                    }
                }

                // Растр дорожного тела в маску
                int painted = rasterizeWayIntoMask(way, width,
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                maskedCells += painted;
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1: " + ex.getMessage());
            return;
        }

        for (Map.Entry<Long, Integer> e : nodeTouch.entrySet()) {
            if (e.getValue() >= 2) crossingNodeIds.add(e.getKey());
        }

        // PASS1b: маска «зона пересечения»
        int markedIntersections = 0;
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject elem : fs) {
                if (!"node".equals(opt(elem, "type"))) continue;
                Long id = asLong(elem, "id");
                if (id == null || !crossingNodeIds.contains(id)) continue;

                Double lat = asDouble(elem, "lat"), lon = asDouble(elem, "lon");
                if (lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                markIntersectionDisk(xz[0], xz[1], INTERSECTION_RADIUS);
                markedIntersections++;
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1b (nodes): " + ex.getMessage());
        }

        broadcast(level, "PASS1: vehicular roads: " + waysCount
                + ", road cells: " + maskedCells
                + ", intersection nodes: " + crossingNodeIds.size()
                + ", intersection zones marked: " + markedIntersections);

        // PASS2: установка кнопок по правилам
        int placedRegular = 0;
        int placedApproach = 0;
        int placedSeam = 0;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;

                JsonObject tags = tagsOf(way);
                if (tags == null || !isCarRoad(tags)) continue;

                int width = Math.max(1, widthFromWayTagsOrDefault(tags));
                List<Integer> sIntersections = collectIntersectionPositionsS(way, nodeTouch,
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

                // линии по внутренним разделителям
                int[] added = placeButtonsOnWay(way, width, tags, sIntersections,
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                placedRegular  += added[0];
                placedApproach += added[1];

                if (ENABLE_SEAM_LINE) {
                    placedSeam += placeSeamLineIfTouching(way, width, tags, sIntersections,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS2: " + ex.getMessage());
        }

        broadcast(level, "Buttons placed. Regularх: " + placedRegular
                + ", approach before intersections: " + placedApproach
                + ", seam: " + placedSeam);
    }

    // ===== Основная логика PASS2 — расстановка кнопок на дороге =====

    /** Возвращает [placedRegular, placedApproach]. */
    private int[] placeButtonsOnWay(JsonObject way, int width, JsonObject wayTags, List<Integer> sIntersections,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ) {

        JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                ? way.getAsJsonArray("geometry") : null;
        if (geom == null || geom.size() < 2) return new int[]{0,0};

        List<Integer> widthOffsets = innerLaneOffsets(width); // только внутренние разделители
        if (widthOffsets.isEmpty()) {
            return new int[]{0,0};
        }

        // Индекс следующего узла-пересечения по длине
        int nextIx = 0;
        sIntersections.sort(Integer::compareTo);

        int placedRegular = 0;
        int placedApproach = 0;

        int S = 0; // пройденная длина в блоках вдоль пути
        int totalLen = approxPathLengthBlocks(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

        boolean isBridge = isBridgeLike(wayTags);
        boolean isTunnel = !isBridge && isTunnelLike(wayTags);

        for (int gi = 0; gi < geom.size() - 1; gi++) {
            JsonObject p0 = geom.get(gi).getAsJsonObject();
            JsonObject p1 = geom.get(gi + 1).getAsJsonObject();
            int[] A = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            int[] B = latlngToBlock(p1.get("lat").getAsDouble(), p1.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

            int dx = B[0] - A[0];
            int dz = B[1] - A[1];
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps <= 0) continue;

            double ux = dx / (double) steps;
            double uz = dz / (double) steps;
            // перпендикуляр (вправо относительно (ux,uz))
            double cx = -uz;
            double cz =  ux;

            // FACING ⟂ дороге, чтобы кнопка визуально была вдоль
            Direction facing = cardinalPerp(ux, uz);

            for (int t = 0; t <= steps; t += MAX_SEG_STEP) {
                double fx = A[0] + ux * t;
                double fz = A[1] + uz * t;

                int bx = (int) Math.round(fx);
                int bz = (int) Math.round(fz);
                if (!inBounds(bx, bz)) { S += MAX_SEG_STEP; continue; }
                if (isIntersection(bx, bz)) { S += MAX_SEG_STEP; continue; } // зона пересечения — пропуск

                // ближайший узел-перекрёсток вперёд
                while (nextIx < sIntersections.size() && sIntersections.get(nextIx) < S - SKIP_AT_NODE)
                    nextIx++;

                boolean skipHere = false;
                boolean dense = false;
                if (nextIx < sIntersections.size()) {
                    int d = sIntersections.get(nextIx) - S;
                    if (Math.abs(d) <= SKIP_AT_NODE) {
                        skipHere = true; // прямо на узле — пропуск
                    } else if (d > 0 && d <= APPROACH_DENSE) {
                        dense = true; // последние 20 блоков перед узлом — уплотняем
                    }
                }

                if (skipHere) { S += MAX_SEG_STEP; continue; }
                // если не уплотнение — ставим через блок (каждый второй)
                if (!dense && ((S & 1) == 1)) { S += MAX_SEG_STEP; continue; }

                // дедуп координат в этом t, чтобы по ширине не было «заливки»
                HashSet<Long> usedAtThisT = new HashSet<>();

                for (int w : widthOffsets) {
                    double xw = fx + cx * w;
                    double zw = fz + cz * w;
                    int x = (int) Math.round(xw);
                    int z = (int) Math.round(zw);

                    long key = (((long)x) << 32) ^ (z & 0xffffffffL);
                    if (!usedAtThisT.add(key)) continue;

                    if (!inBounds(x, z)) continue;
                    if (!isRoad(x, z)) continue;
                    if (isIntersection(x, z)) continue;

                    Integer yHere = computePlacementY(x, z, S, totalLen, isBridge, isTunnel);
                    if (yHere == null) continue;

                    if (placeButtonAt(x, yHere, z, facing)) {
                        if (dense) placedApproach++;
                        else       placedRegular++;
                    }
                }

                S += MAX_SEG_STEP;
            }
        }
        return new int[]{placedRegular, placedApproach};
    }

    /**
     * «Шов» между дорогами, идущими вплотную: выключено флагом ENABLE_SEAM_LINE.
     * Оставлено для совместимости.
     */
    private int placeSeamLineIfTouching(JsonObject way, int width, JsonObject wayTags, List<Integer> sIntersections,
                                        double centerLat, double centerLng,
                                        double east, double west, double north, double south,
                                        int sizeMeters, int centerX, int centerZ) {
        return 0;
    }

    // ===== Высота установки =====

    /** Высота для кнопки (блок-координата Y для самой кнопки), либо null если нельзя поставить. */
    private Integer computePlacementY(int x, int z, int S, int totalLen, boolean isBridge, boolean isTunnel) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (isTunnel) {
            Integer ySurf = terrainGroundY(x, z);
            if (ySurf == null) ySurf = scanTopY(x, z);
            if (ySurf == null) return null;

            int localDepth = rampDepthForIndex(S, totalLen, TUNNEL_MAIN_DEPTH, TUNNEL_RAMP_STEPS);
            int yDeck = ySurf - localDepth;
            int yPlace = yDeck + 1;
            if (yPlace < worldMin || yPlace > worldMax) return null;
            return yPlace;
        }

        if (isBridge) {
            Integer top = scanTopY(x, z);
            if (top == null) top = terrainGroundY(x, z);
            if (top == null) return null;
            int yPlace = top + 1;
            if (yPlace < worldMin || yPlace > worldMax) return null;
            return yPlace;
        }

        Integer gy = terrainGroundY(x, z);
        if (gy == null) gy = scanTopY(x, z);
        if (gy == null) return null;
        int yPlace = gy + 1;
        if (yPlace < worldMin || yPlace > worldMax) return null;
        return yPlace;
    }

    private static int rampDepthForIndex(int idx, int totalLen, int mainDepth, int rampSteps) {
        if (mainDepth <= 0) return 0;
        int rampHeight = Math.min(rampSteps, mainDepth);
        int base = Math.max(0, mainDepth - rampHeight);
        int fromStart = (idx / TUNNEL_RAMP_SPAN) + 1;
        int fromEnd   = ((totalLen - 1 - idx) / TUNNEL_RAMP_SPAN) + 1;
        int step = Math.min(rampHeight, Math.min(fromStart, fromEnd));
        return base + step;
    }

    // ===== PASS1 — маска покрытия дороги =====

    private int rasterizeWayIntoMask(JsonObject way, int width,
                                     double centerLat, double centerLng,
                                     double east, double west, double north, double south,
                                     int sizeMeters, int centerX, int centerZ) {

        JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                ? way.getAsJsonArray("geometry") : null;
        if (geom == null || geom.size() < 2) return 0;

        int half = Math.max(0, width / 2);
        int added = 0;

        for (int gi = 0; gi < geom.size() - 1; gi++) {
            JsonObject p0 = geom.get(gi).getAsJsonObject();
            JsonObject p1 = geom.get(gi + 1).getAsJsonObject();
            int[] A = latlngToBlock(p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            int[] B = latlngToBlock(p1.get("lat").getAsDouble(), p1.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

            int dx = B[0] - A[0];
            int dz = B[1] - A[1];
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps <= 0) continue;

            double ux = dx / (double) steps;
            double uz = dz / (double) steps;
            double cx = -uz;
            double cz =  ux;

            for (int t = 0; t <= steps; t += MAX_SEG_STEP) {
                double fx = A[0] + ux * t;
                double fz = A[1] + uz * t;

                for (int w = -half; w <= half; w++) {
                    int x = (int) Math.round(fx + cx * w);
                    int z = (int) Math.round(fz + cz * w);
                    if (!inBounds(x, z)) continue;
                    int idx = maskIndex(x, z);
                    if (!roadMask.get(idx)) {
                        roadMask.set(idx);
                        added++;
                    }
                }
            }
        }
        return added;
    }

    private void markIntersectionDisk(int cx, int cz, int R) {
        int R2 = R * R;
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                if (dx*dx + dz*dz > R2) continue;
                int x = cx + dx;
                int z = cz + dz;
                if (!inBounds(x, z)) continue;
                intersectionMask.set(maskIndex(x, z));
            }
        }
    }

    private List<Integer> collectIntersectionPositionsS(JsonObject way, Map<Long, Integer> nodeTouch,
                                                        double centerLat, double centerLng,
                                                        double east, double west, double north, double south,
                                                        int sizeMeters, int centerX, int centerZ) {
        List<Integer> sList = new ArrayList<>();

        JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                ? way.getAsJsonArray("geometry") : null;
        JsonArray nds  = way.has("nodes") ? way.getAsJsonArray("nodes") : null;
        if (geom == null || geom.size() == 0 || nds == null || nds.size() == 0) return sList;

        Set<Integer> crossingIdx = new HashSet<>();
        for (int i = 0; i < nds.size(); i++) {
            long nid = safeLong(nds.get(i));
            if (nid != Long.MIN_VALUE) {
                int c = nodeTouch.getOrDefault(nid, 0);
                if (c >= 2) crossingIdx.add(i);
            }
        }
        if (crossingIdx.isEmpty()) return sList;

        int S = 0;
        for (int gi = 0; gi < geom.size(); gi++) {
            if (gi > 0) {
                int[] P = latlngToBlock(geom.get(gi - 1).getAsJsonObject().get("lat").getAsDouble(),
                        geom.get(gi - 1).getAsJsonObject().get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                int[] Q = latlngToBlock(geom.get(gi).getAsJsonObject().get("lat").getAsDouble(),
                        geom.get(gi).getAsJsonObject().get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                S += Math.max(Math.abs(Q[0] - P[0]), Math.abs(Q[1] - P[1]));
            }
            if (gi < nds.size() && crossingIdx.contains(gi)) sList.add(S);
        }
        return sList;
    }

    // ===== Низкоуровневые утилиты =====

    private boolean placeButtonAt(int x, int y, int z, Direction facing) {
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return false;

        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return false;

        BlockState state = Blocks.BIRCH_BUTTON.defaultBlockState()
                .setValue(ButtonBlock.FACE, AttachFace.FLOOR)
                .setValue(ButtonBlock.FACING, facing);

        level.setBlock(pos, state, 3);
        return true;
    }

    private static Direction cardinalAlong(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    /** Перпендикулярный к оси дороги (для кнопки «вдоль» дороги на полу). */
    private static Direction cardinalPerp(double dx, double dz) {
        double px = -dz;
        double pz =  dx;
        return cardinalAlong(px, pz);
    }

    /** Внутренние разделители: делим span на n интервалов 3/4/5, максимально ровно. */
    private static List<Integer> innerLaneOffsets(int width) {
        List<Integer> out = new ArrayList<>();
        int half = Math.max(0, width / 2);
        int span = half * 2;                 // расстояние между краями

        // слишком узко для внутренних разделителей
        if (span < 6) return out;            // нужен хотя бы один интервал внутри (минимум 2 интервала по 3)

        // число интервалов n: около span/4, но в [2, span/3]
        int minN = 2;
        int maxN = Math.max(2, span / 3);    // при шаге 3
        int n = (int)Math.round(span / 4.0);
        if (n < minN) n = minN;
        if (n > maxN) n = maxN;

        // шаг (вещественный), гарантированно в [3..5]
        double step = span / (double) n;
        int floorGap = (int)Math.floor(step);   // 3/4/5
        int ceilGap  = (int)Math.ceil(step);    // 3/4/5 (не больше 5, т.к. step ≤ 5)
        int numCeil  = span - floorGap * n;     // сколько интервалов должны быть чуток больше

        int cur = -half;
        for (int i = 1; i <= n - 1; i++) {
            int gap = (i <= numCeil) ? ceilGap : floorGap; // первые numCeil интервалов — на 1 больше
            cur += gap;
            out.add(cur);
        }
        return out;
    }

    private boolean inBounds(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private int maskIndex(int x, int z) {
        return (z - maskOriginZ) * gridW + (x - maskOriginX);
    }

    private boolean isRoad(int x, int z) {
        int idx = maskIndex(x, z);
        return idx >= 0 && idx < gridW * gridH && roadMask.get(idx);
    }

    private boolean isIntersection(int x, int z) {
        int idx = maskIndex(x, z);
        return idx >= 0 && idx < gridW * gridH && intersectionMask.get(idx);
    }

    // ===== Классификация дорог / мост / тоннель =====

    private static boolean isCarRoad(JsonObject tags) {
        if (tags == null) return false;
        String h = opt(tags, "highway");
        if (h == null) return false;

        if (eqAny(h, "footway", "path", "pedestrian", "cycleway", "steps", "bridleway")) return false;

        if (!eqAny(h, "motorway", "motorway_link",
                "trunk", "trunk_link",
                "primary", "primary_link",
                "secondary", "secondary_link",
                "tertiary", "tertiary_link",
                "unclassified", "residential",
                "living_street", "service")) {
            if (!"track".equalsIgnoreCase(h)) return false;
        }

        String access = low(tags, "access");
        if ("no".equals(access)) return false;
        String mv = low(tags, "motor_vehicle");
        if ("no".equals(mv)) return false;
        String mc = low(tags, "motorcar");
        if ("no".equals(mc)) return false;
        String veh = low(tags, "vehicle");
        if ("no".equals(veh)) return false;

        return true;
    }

    private static boolean isBridgeLike(JsonObject tags) {
        if (tags == null) return false;
        if (truthyOrText(tags, "bridge")) return true;
        if (truthyOrText(tags, "bridge:structure")) return true;
        Integer layer = optInt(tags, "layer");
        return layer != null && layer > 0;
    }

    private static boolean isTunnelLike(JsonObject tags) {
        if (tags == null) return false;
        if (truthyOrText(tags, "bridge")) return false;
        if (truthyOrText(tags, "tunnel")) return true;
        Integer layerNeg = mostNegativeIntFromTag(tags, "layer");
        if (layerNeg != null && layerNeg < 0) return true;
        Integer levelNeg = mostNegativeIntFromTag(tags, "level");
        if (levelNeg != null && levelNeg < 0) return true;
        String location = opt(tags, "location");
        if (location != null) {
            String loc = location.trim().toLowerCase(Locale.ROOT);
            if (loc.contains("underground") || loc.contains("below_ground")) return true;
        }
        return false;
    }

    private static boolean eqAny(String v, String... options) {
        for (String o : options) if (o.equalsIgnoreCase(v)) return true;
        return false;
    }

    private static String low(JsonObject o, String k) {
        String v = opt(o, k);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static boolean truthyOrText(JsonObject tags, String key) {
        String v = opt(tags, key);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return false;
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

    private static Integer optInt(JsonObject o, String k) {
        try {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String s = o.get(k).getAsString().trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static final java.util.regex.Pattern INT_PATTERN = java.util.regex.Pattern.compile("[-+]?\\d+");
    private static Integer mostNegativeIntFromTag(JsonObject tags, String key) {
        String raw = opt(tags, key);
        if (raw == null) return null;
        java.util.regex.Matcher m = INT_PATTERN.matcher(raw);
        Integer min = null;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group());
                if (min == null || v < min) min = v;
            } catch (Exception ignore) {}
        }
        return min;
    }

    // ===== Рельеф / высота =====

    /** Поверхность по grid или верхнему твёрдому блоку. */
    private Integer terrainGroundY(int x, int z) {
        try {
            if (store != null && store.grid != null && store.grid.inBounds(x, z)) {
                int v = store.grid.groundY(x, z);
                if (v != Integer.MIN_VALUE) return v;
            }
        } catch (Throwable ignore) {}
        Integer fromCoords = terrainGroundYFromCoords(x, z);
        if (fromCoords != null) return fromCoords;
        return scanTopY(x, z);
    }

    private Integer terrainGroundYFromCoords(int x, int z) {
        try {
            if (coords == null || !coords.has("terrainGrid")) return null;
            JsonObject tg = coords.getAsJsonObject("terrainGrid");
            if (tg == null) return null;

            int minGX = tg.get("minX").getAsInt();
            int minGZ = tg.get("minZ").getAsInt();
            int width = tg.get("width").getAsInt();
            int idx = (z - minGZ) * width + (x - minGX);
            if (idx < 0) return null;

            if (tg.has("grids") && tg.get("grids").isJsonObject()) {
                JsonObject grids = tg.getAsJsonObject("grids");
                if (!grids.has("groundY")) return null;
                JsonArray groundY = grids.getAsJsonArray("groundY");
                if (idx >= groundY.size() || groundY.get(idx).isJsonNull()) return null;
                return groundY.get(idx).getAsInt();
            }

            if (tg.has("data") && tg.get("data").isJsonArray()) {
                JsonArray data = tg.getAsJsonArray("data");
                if (idx >= data.size() || data.get(idx).isJsonNull()) return null;
                return data.get(idx).getAsInt();
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private Integer scanTopY(int x, int z) {
        final int max = level.getMaxBuildHeight() - 1;
        final int min = level.getMinBuildHeight();
        for (int y = max; y >= min; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return null;
    }

    // ===== Преобразования / длины и JSON утилиты =====

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int) Math.round(centerX + dx), (int) Math.round(centerZ + dz)};
    }

    private static int approxPathLengthBlocks(JsonArray geom,
                                              double centerLat, double centerLng,
                                              double east, double west, double north, double south,
                                              int sizeMeters, int centerX, int centerZ) {
        if (geom == null || geom.size() < 2) return 0;
        int L = 0;
        for (int i = 1; i < geom.size(); i++) {
            JsonObject a = geom.get(i - 1).getAsJsonObject();
            JsonObject b = geom.get(i).getAsJsonObject();
            int[] A = latlngToBlock(a.get("lat").getAsDouble(), a.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            int[] B = latlngToBlock(b.get("lat").getAsDouble(), b.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            L += Math.max(Math.abs(B[0] - A[0]), Math.abs(B[1] - A[1]));
        }
        return L;
    }

    private static String opt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static JsonObject tagsOf(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }

    private static Long asLong(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static Double asDouble(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsDouble() : null; }
        catch (Throwable ignore) { return null; }
    }

    private static long safeLong(JsonElement el) {
        try { return el.getAsLong(); } catch (Throwable ignore) { return Long.MIN_VALUE; }
    }

    private static int widthFromWayTagsOrDefault(JsonObject tags) {
        Integer w = parseNumericWidth(tags);
        if (w != null && w > 0) return w;

        String hwy = opt(tags, "highway");
        if (hwy != null && RoadGenerator.hasRoadMaterial(hwy)) {
            return RoadGenerator.getRoadWidth(hwy);
        }
        return DEFAULT_WIDTH;
    }

    private static Integer parseNumericWidth(JsonObject tags) {
        if (tags == null) return null;
        String[] keys = new String[]{"width:carriageway", "width", "est_width"};
        for (String k : keys) {
            String v = opt(tags, k);
            if (v == null || v.isBlank()) continue;
            String s = v.trim().toLowerCase(Locale.ROOT).replace(",", ".");
            StringBuilder num = new StringBuilder();
            boolean dot = false;
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c == '.' && !dot) { num.append('.'); dot = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int) Math.round(meters); // 1 м ≈ 1 блок
                if (blocks > 0) return blocks;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // ===== Сервис =====
    private static void broadcast(ServerLevel level, String msg) {
        try {
            if (level.getServer() != null)
                for (ServerPlayer p : level.getServer().getPlayerList().getPlayers())
                    p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }
}