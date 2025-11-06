package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

// Разметка 1.17

public class StopMarkingGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Параметры разметки
    private static final int MARK_LEN   = 15; // длина вдоль дороги (в блоках)
    @SuppressWarnings("unused")
    private static final int MARK_WIDTH = 2;  // ширина поперёк (2 слоя)
    private static final int DEFAULT_WIDTH = 12; // на случай отсутствия данных

    // bbox → блоки (клиппинг)
    private int minX, maxX, minZ, maxZ;

    public StopMarkingGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }
    public StopMarkingGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

    // ==== запуск ====
    public void generate() {
        broadcast(level, "Generating stop markings ...");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "No coords or store — skipping StopMarkingGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // bbox → блоки
        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]);
        maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]);
        maxZ = Math.max(a[1], b[1]);

        // ===== PASS1: собираем только узлы-остановки (stream) =====
        Map<Long, int[]> stopNodeXZ = new HashMap<>();
        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                if (!"node".equals(opt(f, "type"))) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isStopLike(tags)) continue;

                Long id = asLong(f, "id");
                Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                if (id == null || lat == null || lon == null) continue;

                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                stopNodeXZ.put(id, xz);
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS1 (nodes): " + ex.getMessage());
            return;
        }

        // ===== PASS2a: выбираем ЛУЧШУЮ АВТО-дорогу, содержащую узел =====
        Map<Long, NodeChoice> best = new HashMap<>();        // «краевая» для рисования у кромки
        Map<Long, DDir>       fallbackDir = new HashMap<>(); // ориентир, если дорога не найдена (трамвай/платформа и т.п.)

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;
                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                int rank = roadPriorityRank(wtags); // приоритет класса дороги (авто > … > пеш.)
                boolean vehicular = (rank >= 30);

                int width = widthFromWayTagsOrDefault(wtags);

                JsonArray nds  = way.has("nodes") && way.get("nodes").isJsonArray() ? way.getAsJsonArray("nodes") : null;
                if (nds == null || nds.size() < 2) continue;

                JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                        ? way.getAsJsonArray("geometry") : null;

                for (int i = 0; i < nds.size(); i++) {
                    long nid = nds.get(i).getAsLong();
                    int[] xz = stopNodeXZ.get(nid);
                    if (xz == null) continue; // не остановка

                    if (geom != null && geom.size() >= 2) {
                        DirAndSide ds = directionAndSideNearPointFromGeometry(geom, xz[0], xz[1],
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        if (ds == null || ds.dir == null || ds.dir.isZero()) continue;

                        if (vehicular) {
                            // это кандидат для рисования у кромки — выбираем по rank → width
                            NodeChoice prev = best.get(nid);
                            if (prev == null || rank > prev.rank || (rank == prev.rank && width > prev.width)) {
                                best.put(nid, new NodeChoice(nid, xz[0], xz[1], ds.dir, width, ds.sideSign, rank, 0.0));
                            }
                        } else {
                            // запомним fallback-направление
                            fallbackDir.putIfAbsent(nid, ds.dir);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Error in PASS2a (ways containing node): " + ex.getMessage());
        }

        // ===== PASS2b: для узлов без авто-дороги — ищем БЛИЖАЙШУЮ авто-дорогу =====
        Set<Long> pending = new HashSet<>(stopNodeXZ.keySet());
        pending.removeAll(best.keySet());

        if (!pending.isEmpty()) {
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject way : fs) {
                    if (pending.isEmpty()) break;

                    if (!"way".equals(opt(way, "type"))) continue;
                    JsonObject wtags = tagsOf(way);
                    if (wtags == null) continue;

                    int rank = roadPriorityRank(wtags);
                    if (rank < 30) continue; // не автомобильная — пропускаем

                    int width = widthFromWayTagsOrDefault(wtags);

                    JsonArray geom = way.has("geometry") && way.get("geometry").isJsonArray()
                            ? way.getAsJsonArray("geometry") : null;
                    if (geom == null || geom.size() < 2) continue;

                    // превратим geometry в массив точек в блоках (по месту, без хранения всего файла)
                    List<int[]> pts = new ArrayList<>(geom.size());
                    for (int i = 0; i < geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
                    }

                    // пройдём все ещё не привязанные остановки
                    for (Long nid : new ArrayList<>(pending)) {
                        int[] xz = stopNodeXZ.get(nid);
                        if (xz == null) { pending.remove(nid); continue; }

                        NearestProj np = nearestOnPolyline(pts, xz[0], xz[1]);
                        if (np == null) continue;

                        DDir dir = new DDir(np.segDx, np.segDz).unitNormalized();
                        if (dir.isZero()) continue;

                        // какая сторона относительно направления дороги?
                        DDir c = dir.perp().unitNormalized();
                        double vx = xz[0] - np.nearX;
                        double vz = xz[1] - np.nearZ;
                        int sideSign = (vx * c.dx + vz * c.dz >= 0) ? +1 : -1;

                        NodeChoice prev = best.get(nid);
                        // выбираем ближайшую по расстоянию; при равенстве — по rank → width
                        if (prev == null
                                || np.dist2 < prev.dist2
                                || (Math.abs(np.dist2 - prev.dist2) < 1e-6 && (rank > prev.rank || (rank == prev.rank && width > prev.width)))) {
                            best.put(nid, new NodeChoice(nid, xz[0], xz[1], dir, width, sideSign, rank, np.dist2));
                        }
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Error in PASS2b (nearest road): " + ex.getMessage());
            }
        }

        // ===== Рисуем: только ОДНУ разметку на лучшей авто-дороге (если нашли) =====
        int drawn = 0;
        Set<Long> drawnIds = new HashSet<>();
        for (NodeChoice c : best.values()) {
            drawLadderMarkAtEdge(c.x, c.z, c.dir, c.width, c.sideSign);
            drawn++; drawnIds.add(c.id);
        }

        // ===== Крайний fallback: если вообще нет авто-дороги — рисуем по месту узла, вдоль ближайшего не-дорожного way или X-оси =====
        for (Map.Entry<Long,int[]> e : stopNodeXZ.entrySet()) {
            long nid = e.getKey();
            if (drawnIds.contains(nid)) continue;

            int[] xz = e.getValue();
            DDir dir = fallbackDir.getOrDefault(nid, new DDir(1, 0)); // если нет даже этого — по X
            drawLadderMarkCentered(xz[0], xz[1], dir);
            drawn++;
        }

        broadcast(level, "Stop markings placed at stops: " + drawn);
    }

    // ==== Рисование «лесенки» у края дороги (строго в bbox, на рельефе) ====
    private void drawLadderMarkAtEdge(int x0, int z0, DDir along, int roadWidth, int sideSign) {
        DDir a = along.unitNormalized();     // вдоль дороги
        DDir c = a.perp().unitNormalized();  // поперёк дороги (лево относительно a)

        // смещение к ВНУТРЕННЕМУ краю дороги со стороны остановки:
        int half = Math.max(1, roadWidth / 2);   // индекс клетки у кромки (внутри дороги)
        int inner = Math.max(0, half - 1);       // второй ряд — на 1 блок ближе к центру
        double offEdge = sideSign * half;        // ряд прямо у кромки (на дороге)
        double offFar  = sideSign * inner;       // ряд внутри проезжей части

        drawLadderPattern(x0, z0, a, c, offEdge, offFar);
    }

    // ==== Рисование «лесенки» прямо в месте узла (fallback, без смещения к кромке) ====
    private void drawLadderMarkCentered(int x0, int z0, DDir along) {
        DDir a = along.unitNormalized();     // вдоль направления
        DDir c = a.perp().unitNormalized();  // поперёк

        // два ряда: сам узел (0) и соседняя клетка поперёк (+1)
        double off0 = 0.0;
        double off1 = 1.0;

        drawLadderPattern(x0, z0, a, c, off0, off1);
    }

    // ==== Общая реализация узора «лесенкой» ====
    private void drawLadderPattern(int x0, int z0, DDir a, DDir c, double offRow0, double offRow1) {
        int startT = -(MARK_LEN / 2);
        int endT   = startT + MARK_LEN - 1;

        for (int t = startT; t <= endT; t++) {
            // узор:
            // ряд 0: ставим на чётных t
            // ряд 1: ставим, ставим, пропуск, повтор…
            boolean put0 = (t % 2 == 0);
            boolean put1 = (Math.floorMod(t, 3) != 2);

            // на обоих концах — ставим оба ряда
            if (t == startT || t == endT) { put0 = true; put1 = true; }

            double bx = x0 + a.dx * t;
            double bz = z0 + a.dz * t;

            if (put0) placeYellowAt(bx + c.dx * offRow0, bz + c.dz * offRow0);
            if (put1) placeYellowAt(bx + c.dx * offRow1, bz + c.dz * offRow1);
        }
    }

    private void placeYellowAt(double fx, double fz) {
        int bx = (int)Math.round(fx);
        int bz = (int)Math.round(fz);

        // Жёсткий клиппинг — никогда вне зоны генерации
        if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) return;

        Integer by = terrainGroundY(bx, bz);
        if (by == null) by = scanTopY(bx, bz);
        if (by == null) return;

        level.setBlock(new BlockPos(bx, by, bz), Blocks.YELLOW_CONCRETE.defaultBlockState(), 3);
    }

    // ==== Приоритеты дорог ====
    /** Чем больше rank, тем «главнее» дорога. Пешие типы дают низкий rank и не годятся для крайовой разметки. */
    private static int roadPriorityRank(JsonObject tags) {
        String hwy = opt(tags, "highway");
        if (hwy != null) {
            hwy = hwy.toLowerCase(Locale.ROOT);
            switch (hwy) {
                case "motorway":        return 100;
                case "trunk":           return 95;
                case "primary":         return 90;
                case "secondary":       return 80;
                case "tertiary":        return 70;
                case "residential":     return 60;
                case "living_street":   return 55;
                case "unclassified":    return 50;
                case "service":         return 45;
                case "track":           return 40;
                // пешеходные — низко (не используем для крайовой):
                case "pedestrian":
                case "footway":
                case "path":
                case "cycleway":
                    return 10;
                default:
                    return 35; // неизвестное — слабый приоритет, но потенциально автодорога
            }
        }
        // aeroway и railway сами по себе не подходят для крайовой разметки остановок ОТ
        // Возвращаем очень низкий приоритет.
        return 5;
    }

    // ==== Определяем «остановочность» узла ====
    private static boolean isStopLike(JsonObject tags) {
        if (tags == null) return false;

        String highway = opt(tags, "highway");
        String pt      = opt(tags, "public_transport");
        String amenity = opt(tags, "amenity");
        String railway = opt(tags, "railway");

        if ("bus_stop".equalsIgnoreCase(highway)) return true;
        if ("taxi".equalsIgnoreCase(amenity)) return true;

        // PTv2 с модальными булевыми
        if ("platform".equalsIgnoreCase(pt) || "stop_position".equalsIgnoreCase(pt) || "station".equalsIgnoreCase(pt)) {
            String bus        = opt(tags, "bus");
            String tram       = opt(tags, "tram");
            String trolleybus = opt(tags, "trolleybus");
            String lightRail  = opt(tags, "light_rail");
            if (isYes(bus) || isYes(tram) || isYes(trolleybus) || isYes(lightRail)) return true;
        }

        // legacy трамвайные
        if ("tram_stop".equalsIgnoreCase(railway)) return true;

        // общая PT-площадка без модальных флагов — тоже считаем остановкой
        if ("platform".equalsIgnoreCase(pt) || "stop_position".equalsIgnoreCase(pt)) return true;

        return false;
    }

    private static boolean isYes(String v) {
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("yes") || v.equals("true") || v.equals("1");
    }

    // ==== Ширина дороги из тегов/справочника (для выбора лучшей) ====
    private static int widthFromWayTagsOrDefault(JsonObject tags) {
        Integer w = parseNumericWidth(tags);
        if (w != null && w > 0) return w;

        String hwy = opt(tags, "highway");
        String aeroway = opt(tags, "aeroway");

        if (hwy != null && RoadGenerator.hasRoadMaterial(hwy)) {
            return RoadGenerator.getRoadWidth(hwy);
        }
        if (aeroway != null && RoadGenerator.hasRoadMaterial("aeroway:" + aeroway)) {
            return RoadGenerator.getRoadWidth("aeroway:" + aeroway);
        }
        return DEFAULT_WIDTH;
    }

    private static Integer parseNumericWidth(JsonObject tags) {
        if (tags == null) return null;
        String[] keys = new String[] { "width:carriageway", "width", "est_width", "runway:width", "taxiway:width" };
        for (String k : keys) {
            String v = opt(tags, k);
            if (v == null || v.isBlank()) continue;
            String s = v.trim().toLowerCase(Locale.ROOT).replace(",", ".");
            StringBuilder num = new StringBuilder();
            boolean dot = false;
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c=='.' && !dot) { num.append('.'); dot = true; }
            }
            if (num.length() == 0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters);
                if (blocks > 0) return blocks;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // ==== Геометрия / направление и сторона по ближайшей точке GEOMETRY ====
    private static DirAndSide directionAndSideNearPointFromGeometry(JsonArray geom,
                                                                    int x, int z,
                                                                    double centerLat, double centerLng,
                                                                    double east, double west, double north, double south,
                                                                    int sizeMeters, int centerX, int centerZ) {
        if (geom == null || geom.size() < 2) return null;

        // ближайшая вершина
        int bestIdx = -1;
        double bestD2 = Double.MAX_VALUE;
        int bestX = 0, bestZ = 0;

        for (int i = 0; i < geom.size(); i++) {
            JsonObject p = geom.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            double dx = xz[0] - x;
            double dz = xz[1] - z;
            double d2 = dx*dx + dz*dz;
            if (d2 < bestD2) { bestD2 = d2; bestIdx = i; bestX = xz[0]; bestZ = xz[1]; }
        }
        if (bestIdx == -1) return null;

        int i0 = Math.max(0, bestIdx - 1);
        int i1 = Math.min(geom.size() - 1, bestIdx + 1);
        if (i0 == i1) {
            if (bestIdx > 0) i0 = bestIdx - 1;
            if (bestIdx < geom.size() - 1) i1 = bestIdx + 1;
            if (i0 == i1) return null;
        }

        int[] A = latlngToBlock(geom.get(i0).getAsJsonObject().get("lat").getAsDouble(),
                geom.get(i0).getAsJsonObject().get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] B = latlngToBlock(geom.get(i1).getAsJsonObject().get("lat").getAsDouble(),
                geom.get(i1).getAsJsonObject().get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        DDir dir = new DDir(B[0]-A[0], B[1]-A[1]).unitNormalized();

        // определяем сторону остановки относительно направления dir
        DDir c = dir.perp().unitNormalized(); // «лево» относительно dir
        double vx = x - bestX;
        double vz = z - bestZ;
        double dot = vx * c.dx + vz * c.dz;
        int sideSign = (dot >= 0) ? +1 : -1;

        return new DirAndSide(dir, sideSign);
    }

    // ==== Поиск ближайшей точки на полилинии (в блоках) ====
    private static NearestProj nearestOnPolyline(List<int[]> pts, int x, int z) {
        if (pts == null || pts.size() < 2) return null;
        double bestD2 = Double.MAX_VALUE;
        double nearX = x, nearZ = z;
        double segDxBest = 0, segDzBest = 0;

        for (int i = 0; i < pts.size() - 1; i++) {
            int[] A = pts.get(i);
            int[] B = pts.get(i+1);
            double ax = A[0], az = A[1];
            double bx = B[0], bz = B[1];
            double vx = bx - ax, vz = bz - az;
            double wx = x - ax,  wz = z - az;

            double vv = vx*vx + vz*vz;
            if (vv < 1e-9) continue;
            double t = (vx*wx + vz*wz) / vv;
            if (t < 0) t = 0; else if (t > 1) t = 1;

            double px = ax + t * vx;
            double pz = az + t * vz;

            double dx = x - px, dz = z - pz;
            double d2 = dx*dx + dz*dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                nearX = px; nearZ = pz;
                segDxBest = vx; segDzBest = vz;
            }
        }
        return new NearestProj(nearX, nearZ, segDxBest, segDzBest, bestD2);
    }

    // ==== Рельеф (grid → coords → скан) ====
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

    // ==== Утилиты ====
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

    private static String opt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static Long   asLong  (JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsLong()   : null; } catch (Throwable ignore){return null;} }
    private static Double asDouble(JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsDouble() : null; } catch (Throwable ignore){return null;} }
    private static JsonObject tagsOf(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{(int)Math.round(centerX + dx), (int)Math.round(centerZ + dz)};
    }

    // === вспомогательные классы ===
    private static final class DDir {
        final double dx, dz;
        DDir(double dx, double dz){ this.dx = dx; this.dz = dz; }
        boolean isZero(){ return Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9; }
        double len(){ return Math.hypot(dx, dz); }
        DDir unitNormalized(){
            double L = len();
            if (L < 1e-9) return new DDir(0, 0);
            return new DDir(dx / L, dz / L);
        }
        DDir perp(){ return new DDir(-dz, dx); }
    }

    private static final class DirAndSide {
        final DDir dir; final int sideSign;
        DirAndSide(DDir dir, int sideSign){ this.dir = dir; this.sideSign = sideSign; }
    }

    private static final class NodeChoice {
        final long id; final int x, z, width; final DDir dir; final int sideSign; final int rank; final double dist2;
        NodeChoice(long id, int x, int z, DDir dir, int width, int sideSign, int rank, double dist2){
            this.id=id; this.x=x; this.z=z; this.dir=dir;
            this.width=Math.max(1,width);
            this.sideSign = (sideSign>=0?+1:-1);
            this.rank = rank;
            this.dist2 = dist2;
        }
    }

    private static final class NearestProj {
        final double nearX, nearZ;
        final double segDx, segDz;
        final double dist2;
        NearestProj(double nearX, double nearZ, double segDx, double segDz, double dist2){
            this.nearX=nearX; this.nearZ=nearZ; this.segDx=segDx; this.segDz=segDz; this.dist2=dist2;
        }
    }
}