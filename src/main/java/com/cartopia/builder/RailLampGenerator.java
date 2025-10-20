package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.cartopia.store.TerrainGridStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * RailLampGenerator — версия со стримингом OSM-элементов (NDJSON) и чтением рельефа из TerrainGridStore (mmap).
 * Поведение логики не менялось: ставим фонари вдоль "surface" железных дорог, без метро/мостов/тоннелей.
 */
public class RailLampGenerator {

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;      // может быть null (fallback к старому поведению)
    private final TerrainGridStore grid;      // может быть null

    private static final int RAIL_LAMP_PERIOD = 100;  // 1-в-1 как у тебя
    private static final int RAIL_LAMP_COLUMN_WALLS = 5;

    private static final class Counter { int v = 0; }

    // НОВЫЙ конструктор: передаём store (из пайплайна). coords оставляем для геопривязки и fallback'ов.
    public RailLampGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
        this.grid = (store != null) ? store.grid : null;
    }

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

    public void generate() {
        broadcast(level, "💡 Расставляю фонари вдоль железных дорог…");

        // Геопривязка и границы из coords (как было)
        if (coords == null || !coords.has("center") || !coords.has("bbox")) {
            broadcast(level, "Нет center/bbox в coords — пропускаю RailLampGenerator.");
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

        // === Путь 1 (НОВЫЙ): читаем features построчно из NDJSON через store ===
        if (store != null) {
            int totalRails = 0;

            // Первый проход — считаем подходящие ways (для прогресса)
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                    if (tags == null) continue;
                    if (!isRailLike(tags)) continue;
                    if (!"way".equals(optString(e,"type"))) continue;
                    if (!e.has("geometry") || !e.get("geometry").isJsonArray()) continue;
                    if (e.getAsJsonArray("geometry").size() < 2) continue;
                    // surface only
                    boolean isSubway = "subway".equals(normalizedRailKind(tags));
                    if (isSubway) continue;
                    if (isElevatedLike(tags) || isUndergroundLike(tags)) continue;
                    totalRails++;
                }
            } catch (Exception ex) {
                broadcast(level, "Ошибка чтения features NDJSON: " + ex.getMessage() + " — откатываюсь на старый путь.");
                // fallback к старому пути ниже
                handleLegacyFeaturesPath(minX, maxX, minZ, maxZ, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                return;
            }

            // Второй проход — фактическая расстановка
            int processed = 0;
            try (FeatureStream fs = store.featureStream()) {
                for (JsonObject e : fs) {
                    JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
                    if (tags == null) continue;
                    if (!isRailLike(tags)) continue;
                    if (!"way".equals(optString(e,"type"))) continue;

                    JsonArray geom = e.getAsJsonArray("geometry");
                    if (geom == null || geom.size() < 2) continue;

                    boolean isSubway = "subway".equals(normalizedRailKind(tags));

                    // только surface рельсы
                    if (isSubway || isElevatedLike(tags) || isUndergroundLike(tags)) {
                        processed++;
                        continue;
                    }

                    Integer yHintTop = null;
                    int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;
                    Counter lamp = new Counter();

                    for (int i=0; i<geom.size(); i++) {
                        JsonObject p = geom.get(i).getAsJsonObject();
                        double lat = p.get("lat").getAsDouble();
                        double lon = p.get("lon").getAsDouble();
                        int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        int x = xz[0], z = xz[1];

                        if (prevX != Integer.MIN_VALUE) {
                            placeLampsAlongSurfaceRailSegment(prevX, prevZ, x, z,
                                    minX, maxX, minZ, maxZ, yHintTop, lamp);
                        }

                        // hint — по миру (быстро), строго не зависит от coords/grid
                        yHintTop = findTopNonAirNearSkippingRails(x, z, yHintTop);
                        prevX = x; prevZ = z;
                    }

                    processed++;
                    if (totalRails > 0 && processed % Math.max(1, totalRails/10) == 0) {
                        int pct = (int)Math.round(100.0 * processed / Math.max(1,totalRails));
                        broadcast(level, "Фонари на рельсах: ~" + pct + "%");
                    }
                }
            } catch (Exception ex) {
                broadcast(level, "Ошибка второго прохода NDJSON: " + ex.getMessage());
            }

            broadcast(level, "Фонари вдоль железных дорог готовы.");
            return;
        }

        // === Путь 2 (СТАРЫЙ): если store == null, читаем из coords.features.elements (как раньше) ===
        handleLegacyFeaturesPath(minX, maxX, minZ, maxZ, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
    }

    // Старый путь — оставляем как fallback на случай, если store == null или NDJSON сломан
    private void handleLegacyFeaturesPath(int minX, int maxX, int minZ, int maxZ,
                                          double centerLat, double centerLng,
                                          double east, double west, double north, double south,
                                          int sizeMeters, int centerX, int centerZ) {
        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю RailLampGenerator.");
            return;
        }
        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements пуст — пропускаю фонари на рельсах.");
            return;
        }

        int totalRails = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRailLike(tags)) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!e.has("geometry") || !e.get("geometry").isJsonArray()) continue;
            if (e.getAsJsonArray("geometry").size() < 2) continue;

            String type = optString(tags, "railway");
            boolean isSubway = "subway".equals(type);
            if (isSubway || isElevatedLike(tags) || isUndergroundLike(tags)) continue;

            totalRails++;
        }

        int processed = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRailLike(tags)) continue;
            if (!"way".equals(optString(e,"type"))) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            boolean isSubway = "subway".equals(normalizedRailKind(tags));
            if (isSubway || isElevatedLike(tags) || isUndergroundLike(tags)) {
                processed++;
                continue;
            }

            Integer yHintTop = null;
            int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;
            Counter lamp = new Counter();

            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                double lat = p.get("lat").getAsDouble();
                double lon = p.get("lon").getAsDouble();
                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                int x = xz[0], z = xz[1];

                if (prevX != Integer.MIN_VALUE) {
                    placeLampsAlongSurfaceRailSegment(prevX, prevZ, x, z,
                            minX, maxX, minZ, maxZ, yHintTop, lamp);
                }

                yHintTop = findTopNonAirNearSkippingRails(x, z, yHintTop);
                prevX = x; prevZ = z;
            }

            processed++;
            if (totalRails > 0 && processed % Math.max(1, totalRails/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalRails));
                broadcast(level, "Фонари на рельсах: ~" + pct + "%");
            }
        }

        broadcast(level, "Фонари вдоль железных дорог готовы.");
    }

    // === ЛОГИКА ДЛЯ ЛИНИЙ ===

    private void placeLampsAlongSurfaceRailSegment(int x1, int z1, int x2, int z2,
                                                   int minX, int maxX, int minZ, int maxZ,
                                                   Integer yHintTop,
                                                   Counter lamp) {
        List<int[]> line = bresenhamLine(x1, z1, x2, z2);
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        boolean horizontalMajor = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        int dirX = Integer.signum(x2 - x1);
        int dirZ = Integer.signum(z2 - z1);
        // смещение на 1 блок влево от направления движения (как было)
        final int offX = horizontalMajor ? 0      : -dirZ;
        final int offZ = horizontalMajor ? dirX   : 0;

        for (int[] pt : line) {
            int x = pt[0], z = pt[1];
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

            // базовый y — по grid, если есть; иначе как раньше
            int yBase = terrainYFromGridOrWorld(x, z, yHintTop);
            if (yBase < worldMin || yBase + 1 > worldMax) {
                lamp.v++;
                continue;
            }

            if (lamp.v % RAIL_LAMP_PERIOD == 0) {
                // сначала слева
                int lxL = x + offX, lzL = z + offZ;
                int towardL = horizontalMajor ? -offZ : -offX;

                boolean placed = tryPlaceRailLampAt(lxL, lzL, yHintTop, horizontalMajor, towardL,
                                                    minX, maxX, minZ, maxZ);

                if (!placed) {
                    // если слева нельзя — пробуем справа (симметрично)
                    int lxR = x - offX, lzR = z - offZ;
                    int towardR = -towardL;
                    tryPlaceRailLampAt(lxR, lzR, yHintTop, horizontalMajor, towardR,
                                    minX, maxX, minZ, maxZ);
                }
            }
            lamp.v++;

            yHintTop = yBase;
        }
    }

    // === ПОСТАНОВКА ФОНАРЯ (1-в-1 как в твоём RailGenerator) ===

    private static boolean isGrayConcrete(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        if (key == null) return false;
        String id = key.toString();
        return "minecraft:gray_concrete".equals(id)
            || "minecraft:white_concrete".equals(id)
            || "minecraft:yellow_concrete".equals(id);
    }

    private static boolean isLampComponent(Block b) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(b);
        if (key == null) return false;
        String id = key.toString();
        return "minecraft:andesite_wall".equals(id)
            || "minecraft:smooth_stone_slab".equals(id)
            || "minecraft:glowstone".equals(id);
    }

    private void placeBottomSlab(int x, int y, int z, Block slabBlock) {
        BlockState st = slabBlock.defaultBlockState();
        if (st.hasProperty(SlabBlock.TYPE)) {
            st = st.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        level.setBlock(new BlockPos(x, y, z), st, 3);
    }

    // === УТИЛИТЫ / ФИЛЬТРЫ (как было) ===

    @SuppressWarnings("unused")
    private static boolean isRailCandidate(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        return r.equals("rail") || r.equals("tram") || r.equals("light_rail") || r.equals("subway");
    }

    private static String normalizedRailKind(JsonObject tags) {
        String r = optString(tags, "railway");
        String v = (r == null ? "" : r.trim().toLowerCase(Locale.ROOT));

        // активные
        if (v.equals("rail") || v.equals("tram") || v.equals("light_rail") || v.equals("subway")) return v;

        // стройка / план
        if (v.equals("construction") || v.equals("proposed")) {
            String k = v; // "construction" или "proposed"
            String t1 = optString(tags, k);
            String t2 = optString(tags, k + ":railway");
            String t  = (t2 != null ? t2 : (t1 != null ? t1 : "")).trim().toLowerCase(Locale.ROOT);
            if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
        }

        // заброшенные / выведенные
        if (v.equals("disused") || v.equals("abandoned")) {
            String d1 = optString(tags, "disused:railway");
            if (d1 != null) {
                String t = d1.trim().toLowerCase(Locale.ROOT);
                if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
            }
            return "rail";
        }

        // вторичные ключи
        String d2 = optString(tags, "disused:railway");
        if (d2 != null) {
            String t = d2.trim().toLowerCase(Locale.ROOT);
            if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
        }
        String a2 = optString(tags, "abandoned:railway");
        if (a2 != null) {
            String t = a2.trim().toLowerCase(Locale.ROOT);
            if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
        }
        String rc = optString(tags, "railway:construction");
        if (rc != null) {
            String t = rc.trim().toLowerCase(Locale.ROOT);
            if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
        }
        String rp = optString(tags, "railway:proposed");
        if (rp != null) {
            String t = rp.trim().toLowerCase(Locale.ROOT);
            if (t.equals("rail") || t.equals("tram") || t.equals("light_rail") || t.equals("subway")) return t;
        }

        return "";
    }

    private static boolean isRailLike(JsonObject tags) {
        return !normalizedRailKind(tags).isEmpty();
    }

    private static boolean isBridge(JsonObject tags) {
        String v = optString(tags, "bridge");
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
    }

    private static boolean isTunnel(JsonObject tags) {
        String v = optString(tags, "tunnel");
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("no") || v.equals("false") || v.equals("0"));
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

    private static boolean isUndergroundLike(JsonObject tags) {
        if (isTunnel(tags)) return true;
        String layer = optString(tags, "layer");
        if (layer != null && layer.matches(".*-\\d+.*")) return true;
        String level = optString(tags, "level");
        if (level != null && level.matches(".*-\\d+.*")) return true;
        String loc = optString(tags, "location");
        if (loc != null) {
            String l = loc.trim().toLowerCase(Locale.ROOT);
            if (l.contains("underground") || l.contains("below_ground")) return true;
        }
        return false;
    }

    private static boolean isElevatedLike(JsonObject tags) {
        if (isBridge(tags)) return true;
        String layer = optString(tags, "layer");
        if (layer != null && layer.matches(".*\\b[1-9]\\d*.*")) return true;
        String level = optString(tags, "level");
        if (level != null && level.matches(".*\\b[1-9]\\d*.*")) return true;
        if (optString(tags, "bridge:structure") != null) return true;
        String loc = optString(tags, "location");
        if (loc != null && loc.trim().toLowerCase(Locale.ROOT).contains("overground")) return true;
        return false;
    }

    // === НОВОЕ: доступ к рельефу через TerrainGridStore (mmap) с fallback'ами ===

    /** Базовый Y по grid, если он есть; иначе как раньше (по миру). */
    private int terrainYFromGridOrWorld(int x, int z, Integer hintY) {
        Integer gy = groundYFromGrid(x, z);
        if (gy != null) return gy;
        return findTopNonAirNearSkippingRails(x, z, hintY);
    }

    private Integer terrainGroundYFromAny(int x, int z) {
        Integer gy = groundYFromGrid(x, z);  // mmap
        if (gy != null) return gy;
        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject tg = coords.getAsJsonObject("terrainGrid");
                int minX = tg.get("minX").getAsInt();
                int minZ = tg.get("minZ").getAsInt();
                int width = tg.get("width").getAsInt();
                int idx = (z - minZ) * width + (x - minX);
                if (idx >= 0) {
                    // v2
                    if (tg.has("grids")) {
                        JsonObject grids = tg.getAsJsonObject("grids");
                        if (grids.has("groundY")) {
                            JsonArray g = grids.getAsJsonArray("groundY");
                            if (idx < g.size()) return g.get(idx).getAsInt();
                        }
                    }
                    // v1
                    if (tg.has("data")) {
                        JsonArray d = tg.getAsJsonArray("data");
                        if (idx < d.size()) return d.get(idx).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }


    private Integer groundYFromGrid(int x, int z) {
        try {
            if (grid != null && grid.inBounds(x, z)) {
                int v = grid.groundY(x, z);
                if (v != Integer.MIN_VALUE) return v;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private boolean isWaterCellByGrid(int x, int z) {
        try {
            if (grid == null || !grid.inBounds(x,z)) return false;
            Integer wy = grid.waterY(x, z);
            if (wy != null) return true;
            String tb = grid.topBlockId(x, z);
            return "minecraft:water".equals(tb);
        } catch (Throwable ignore) {}
        return false;
    }

    private boolean isWaterCellByAny(int x, int z) {
        if (isWaterCellByGrid(x, z)) return true;
        // фолбэк по coords, как в дорожном генераторе
        try {
            if (coords == null || !coords.has("terrainGrid")) return false;
            JsonObject tg = coords.getAsJsonObject("terrainGrid");
            int minX = tg.get("minX").getAsInt();
            int minZ = tg.get("minZ").getAsInt();
            int width = tg.get("width").getAsInt();
            int idx = (z - minZ) * width + (x - minX);
            if (idx < 0) return false;
            if (tg.has("grids")) {
                JsonObject grids = tg.getAsJsonObject("grids");
                if (grids.has("waterY")) {
                    JsonArray waterY = grids.getAsJsonArray("waterY");
                    if (idx < waterY.size()) return !waterY.get(idx).isJsonNull();
                }
                if (grids.has("topBlock")) {
                    JsonArray tb = grids.getAsJsonArray("topBlock");
                    if (idx < tb.size()) return "minecraft:water".equals(tb.get(idx).getAsString());
                }
            }
        } catch (Throwable ignore) {}
        return false;
    }


    private static boolean isRailBlock(Block b) {
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    /** Верхний не-air, но рельсы и детали фонаря считаем как воздух (чтобы база не «лезла» на рельс). */
    private int findTopNonAirNearSkippingRails(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (hintY != null) {
            int from = Math.min(worldMax, hintY + 16);
            int to   = Math.max(worldMin, hintY - 16);
            for (int y = from; y >= to; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                Block b = level.getBlockState(pos).getBlock();
                if (isRailBlock(b) || isLampComponent(b)) continue; // эти — «как воздух»
                if (!level.getBlockState(pos).isAir()) return y;
            }
        }
        for (int y = worldMax; y >= worldMin; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            Block b = level.getBlockState(pos).getBlock();
            if (isRailBlock(b) || isLampComponent(b)) continue;
            if (!level.getBlockState(pos).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Пытается поставить фонарь в точке; возвращает true, если получилось. */
    private boolean tryPlaceRailLampAt(int edgeX, int edgeZ, Integer hintY,
                                    boolean horizontalMajor, int towardCenterSign,
                                    int minX, int maxX, int minZ, int maxZ) {
        if (edgeX < minX || edgeX > maxX || edgeZ < minZ || edgeZ > maxZ) return false;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        // *** ТОЛЬКО groundY из гридов + запрет воды ***
        Integer gridY = terrainGroundYFromAny(edgeX, edgeZ);
        if (gridY == null) return false;
        if (isWaterCellByAny(edgeX, edgeZ)) return false;

        // запрещённые основания (дорожные бетоны)
        Block under = level.getBlockState(new BlockPos(edgeX, gridY, edgeZ)).getBlock();
        if (isGrayConcrete(under)) return false;

        int y0 = gridY + 1;
        if (y0 > worldMax) return false;

        int yTop  = Math.min(y0 + RAIL_LAMP_COLUMN_WALLS - 1, worldMax);
        int ySlab = yTop + 1;
        if (ySlab > worldMax) return false;

        int sx = horizontalMajor ? 0 : towardCenterSign;
        int sz = horizontalMajor ? towardCenterSign : 0;

        int gx = edgeX + sx;
        int gz = edgeZ + sz;
        int gy = ySlab - 1;

        // --- Сухой прогон на воздух (ничего не перетираем)
        for (int y = y0; y <= yTop; y++) {
            if (!level.getBlockState(new BlockPos(edgeX, y, edgeZ)).isAir()) return false;
        }
        if (!level.getBlockState(new BlockPos(edgeX,      ySlab, edgeZ     )).isAir()) return false;
        if (!level.getBlockState(new BlockPos(edgeX + sx, ySlab, edgeZ + sz)).isAir()) return false;
        if (gy >= worldMin && gy <= worldMax && gx >= minX && gx <= maxX && gz >= minZ && gz <= maxZ) {
            if (!level.getBlockState(new BlockPos(gx, gy, gz)).isAir()) return false;
        }

        // --- Постановка
        for (int y = y0; y <= yTop; y++) {
            level.setBlock(new BlockPos(edgeX, y, edgeZ), Blocks.ANDESITE_WALL.defaultBlockState(), 3);
        }
        placeBottomSlab(edgeX,      ySlab, edgeZ,      Blocks.SMOOTH_STONE_SLAB);
        placeBottomSlab(edgeX + sx, ySlab, edgeZ + sz, Blocks.SMOOTH_STONE_SLAB);
        if (gy >= worldMin && gy <= worldMax && gx >= minX && gx <= maxX && gz >= minZ && gz <= maxZ) {
            level.setBlock(new BlockPos(gx, gy, gz), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        return true;
    }
}
