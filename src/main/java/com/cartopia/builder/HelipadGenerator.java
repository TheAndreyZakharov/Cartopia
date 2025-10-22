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


public class HelipadGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // Размеры/константы (центрируем строго относительно квадрата OUTER_SIZE×OUTER_SIZE)
    private static final int OUTER_SIZE = 15; // серый квадрат 15×15 (u,v ∈ [-7..+7])
    private static final int CIRCLE_D   = 13; // диаметр окружности (радиус 6.5)
    private static final int H_HEIGHT   = 6;  // высота «Н» (по v)
    private static final int H_WIDTH    = 5;  // ширина «Н» (по u)

    // bbox - блоки (клиппинг)
    private int minX, maxX, minZ, maxZ;

    public HelipadGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store  = store;
    }
    public HelipadGenerator(ServerLevel level, JsonObject coords) { this(level, coords, null); }

    // ===== Запуск =====
    public void generate() {
        broadcast(level, "🚁 Генерация вертолётных площадок (stream)…");

        if (coords == null || !coords.has("center") || !coords.has("bbox") || store == null) {
            broadcast(level, "❌ Нет coords или store — пропуск HelipadGenerator.");
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

        // ===== PASS1: собираем центры площадок (узлы + центроиды ways) =====
        List<HeliPad> pads = new ArrayList<>();

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject f : fs) {
                String type = opt(f, "type");
                if (type == null) continue;
                JsonObject tags = tagsOf(f);
                if (tags == null) continue;
                if (!isHelipad(tags)) continue;

                if ("node".equals(type)) {
                    Long id = asLong(f, "id");
                    Double lat = asDouble(f, "lat"), lon = asDouble(f, "lon");
                    if (id == null || lat == null || lon == null) continue;
                    int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    pads.add(new HeliPad(id, xz[0], xz[1]));
                } else if ("way".equals(type)) {
                    if (!f.has("geometry")) continue;
                    JsonArray geom = f.getAsJsonArray("geometry");
                    if (geom.size() == 0) continue;
                    // грубый центроид: среднее по вершинам
                    double sumX = 0, sumZ = 0;
                    for (int i = 0; i < geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        sumX += xz[0]; sumZ += xz[1];
                    }
                    int cx = (int)Math.round(sumX / geom.size());
                    int cz = (int)Math.round(sumZ / geom.size());
                    Long id = asLong(f, "id");
                    pads.add(new HeliPad(id != null ? id : -1L, cx, cz));
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Ошибка PASS1 (helipads): " + ex.getMessage());
            return;
        }

        if (pads.isEmpty()) {
            broadcast(level, "ℹ️ Вертолётных площадок не найдено.");
            return;
        }

        // ===== PASS2: ищем БЛИЖАЙШУЮ дорогу и её направление для каждой площадки =====
        for (HeliPad p : pads) p.bestDist2 = Double.MAX_VALUE;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject way : fs) {
                if (!"way".equals(opt(way, "type"))) continue;
                JsonObject wtags = tagsOf(way);
                if (wtags == null) continue;

                String hwy = opt(wtags, "highway");
                if (hwy == null || !RoadGenerator.hasRoadMaterial(hwy)) continue; // только дороги

                JsonArray geom = (way.has("geometry") && way.get("geometry").isJsonArray())
                        ? way.getAsJsonArray("geometry") : null;
                if (geom == null || geom.size() < 2) continue;

                int n = geom.size();
                int[] gx = new int[n];
                int[] gz = new int[n];
                for (int i = 0; i < n; i++) {
                    JsonObject p = geom.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    gx[i] = xz[0]; gz[i] = xz[1];
                }

                for (HeliPad pad : pads) {
                    int padX = pad.x, padZ = pad.z;
                    double bestD2 = pad.bestDist2;
                    DDir bestDir = pad.along;

                    for (int i = 0; i < n - 1; i++) {
                        int ax = gx[i], az = gz[i];
                        int bx2 = gx[i+1], bz2 = gz[i+1];

                        double d2 = pointSegmentDist2(padX, padZ, ax, az, bx2, bz2);
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            bestDir = new DDir(bx2 - ax, bz2 - az).unitNormalized();
                        }
                    }

                    if (bestD2 < pad.bestDist2 && bestDir != null && !bestDir.isZero()) {
                        pad.bestDist2 = bestD2;
                        pad.along = bestDir;
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "Ошибка PASS2 (roads): " + ex.getMessage());
        }

        // ===== DRAW =====
        int drawn = 0;
        for (HeliPad p : pads) {
            DDir along = (p.along != null && !p.along.isZero()) ? p.along : new DDir(1, 0);
            drawHelipad(p.x, p.z, along); // ВСЁ строго центрируем относительно (p.x, p.z)
            drawn++;
        }

        broadcast(level, "✅ Вертолётных площадок построено: " + drawn);
    }

    // ========= Геометрия и отрисовка площадки =========
    private void drawHelipad(int cx, int cz, DDir alongRoad) {
        DDir a = alongRoad.unitNormalized();   // вдоль дороги
        DDir c = a.perp().unitNormalized();    // поперёк дороги (ножки «Н» || c)

        final int half = OUTER_SIZE / 2;             // 7 для 15×15
        final double radius = CIRCLE_D / 2.0;        // 6.5 для D=13

        // 1) Серый квадрат OUTER_SIZE×OUTER_SIZE (u,v ∈ [-half..+half]) — строго вокруг центра
        fillRotatedRect(cx, cz, a, c, -half, +half, -half, +half, Blocks.GRAY_CONCRETE);

        // 2) Жёлтая окружность диаметром CIRCLE_D (радиус radius) — КОНТУР
        drawRotatedCircleOutline(cx, cz, a, c, radius, half, Blocks.YELLOW_CONCRETE);

        // 3) Белая буква «Н»: ширина H_WIDTH (ножки u = uMin/uMax), высота H_HEIGHT
        drawH(cx, cz, a, c, H_WIDTH, H_HEIGHT);
    }

    private void drawH(int cx, int cz, DDir a, DDir c, int width, int height) {
        // Центрированная «Н»
        int uMin = -(width / 2);           // для ширины 5 -> -2
        int uMax = uMin + width - 1;       // -2..+2  (всего 5)
        int vMin = -(height / 2);          // для высоты 6 -> -3
        int vMax = vMin + height - 1;      // -3..+2  (всего 6)

        int uLeft  = uMin;                 // -2
        int uRight = uMax;                 // +2

        // Левая ножка
        for (int v = vMin; v <= vMax; v++) {
            placeBlockAt(cx + a.dx * uLeft + c.dx * v,  cz + a.dz * uLeft + c.dz * v,  Blocks.WHITE_CONCRETE);
        }
        // Правая ножка
        for (int v = vMin; v <= vMax; v++) {
            placeBlockAt(cx + a.dx * uRight + c.dx * v, cz + a.dz * uRight + c.dz * v, Blocks.WHITE_CONCRETE);
        }
        // Перекладина по центру v=0
        for (int u = uMin; u <= uMax; u++) {
            placeBlockAt(cx + a.dx * u + c.dx * 0,      cz + a.dz * u + c.dz * 0,      Blocks.WHITE_CONCRETE);
        }
    }

    // ====== Примитивы отрисовки в повернутой системе координат ======
    private void fillRotatedRect(int cx, int cz, DDir a, DDir c,
                                 int u0, int u1, int v0, int v1,
                                 net.minecraft.world.level.block.Block block) {
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                placeBlockAt(cx + a.dx * u + c.dx * v, cz + a.dz * u + c.dz * v, block);
            }
        }
    }

    // Толщина контура ~1 блок, в пределах того же окна [-half..+half],
    // чтобы центр и границы совпадали с квадратом.
    private void drawRotatedCircleOutline(int cx, int cz, DDir a, DDir c,
                                        double radius, int half,
                                        net.minecraft.world.level.block.Block block) {
        for (int u = -half; u <= half; u++) {
            for (int v = -half; v <= half; v++) {
                double d = Math.sqrt(u*u + v*v);
                if (Math.abs(d - radius) <= 0.5) {
                    placeBlockAt(cx + a.dx * u + c.dx * v, cz + a.dz * u + c.dz * v, block);
                }
            }
        }
    }

    private void placeBlockAt(double fx, double fz, net.minecraft.world.level.block.Block block) {
        int bx = (int)Math.round(fx);
        int bz = (int)Math.round(fz);

        // Жёсткий клиппинг
        if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) return;

        Integer by = terrainGroundY(bx, bz);
        if (by == null) by = scanTopY(bx, bz);
        if (by == null) return;

        level.setBlock(new BlockPos(bx, by, bz), block.defaultBlockState(), 3);
    }

    // ======= Вспомогательная геометрия =======
    private static double pointSegmentDist2(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double wx = px - ax, wz = pz - az;
        double vv = vx*vx + vz*vz;
        if (vv <= 1e-9) {
            double dx = px - ax, dz = pz - az;
            return dx*dx + dz*dz;
        }
        double t = (wx*vx + wz*vz) / vv;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double cx = ax + t * vx, cz = az + t * vz;
        double dx = px - cx, dz = pz - cz;
        return dx*dx + dz*dz;
    }

    // ====== Рельеф ======
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

    // ====== JSON / OSM утилиты ======
    private static boolean isHelipad(JsonObject tags) {
        String aeroway = opt(tags, "aeroway");
        if (aeroway == null) return false;
        aeroway = aeroway.toLowerCase(Locale.ROOT);
        return "helipad".equals(aeroway) || "heliport".equals(aeroway);
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

    // ====== Векторы ======
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

    private static final class HeliPad {
        @SuppressWarnings("unused")
        final long id;
        final int x, z;
        DDir along = null;
        double bestDist2 = Double.MAX_VALUE;
        HeliPad(long id, int x, int z){ this.id = id; this.x = x; this.z = z; }
    }

    // ====== Логгирование ======
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
}