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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class RailGenerator {

    private final ServerLevel level;
    private final JsonObject coords;

    public RailGenerator(ServerLevel level, JsonObject coords) {
        this.level = level;
        this.coords = coords;
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
        broadcast(level, "🚆 Генерация железных дорог…");

        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю RailGenerator.");
            return;
        }

        // Геопривязка и границы области генерации (в блоках)
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
            broadcast(level, "OSM elements пуст — пропускаю рельсы.");
            return;
        }

        Block cobble = resolveBlock("minecraft:cobblestone");
        Block rail   = resolveBlock("minecraft:rail");

        int totalRails = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRailCandidate(tags)) continue;
            if (!"way".equals(optString(e,"type"))) continue;
            if (!e.has("geometry") || !e.get("geometry").isJsonArray()) continue;
            if (e.getAsJsonArray("geometry").size() < 2) continue;
            totalRails++;
        }

        int processed = 0;
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isRailCandidate(tags)) continue;
            if (!"way".equals(optString(e,"type"))) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;

            String type = optString(tags, "railway");
            boolean isSubway = "subway".equals(type);

            if (!isSubway && isBridgeOrTunnel(tags)) {
                // для surface-рельсов не поддерживаем мосты/тоннели — пропускаем такие ways
                processed++;
                continue;
            }

            // Идём по сегментам
            int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;
            for (int i=0; i<geom.size(); i++) {
                JsonObject p = geom.get(i).getAsJsonObject();
                double lat = p.get("lat").getAsDouble();
                double lon = p.get("lon").getAsDouble();
                int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                int x = xz[0], z = xz[1];

                if (prevX != Integer.MIN_VALUE) {
                    if (isSubway) {
                        paintSubwaySegment(prevX, z1(prevZ), x, z, minX, maxX, minZ, maxZ, cobble, rail);
                    } else {
                        paintSurfaceRailSegment(prevX, z1(prevZ), x, z, minX, maxX, minZ, maxZ, cobble, rail);
                    }
                }

                prevX = x; prevZ = z;
            }

            processed++;
            if (totalRails > 0 && processed % Math.max(1, totalRails/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalRails));
                broadcast(level, "Рельсы: ~" + pct + "%");
            }
        }

        broadcast(level, "Рельсы готовы.");
    }

    // --- обычные (surface) рельсы: базовый Y = высота рельефа; cobble на базе, rail на базе+1 ---
    private void paintSurfaceRailSegment(int x1, int z1, int x2, int z2,
                                         int minX, int maxX, int minZ, int maxZ,
                                         Block cobble, Block railBlock) {
        List<int[]> line = bresenhamLine(x1, z1, x2, z2);
        Integer yHint = null;
        final int worldMax = level.getMaxBuildHeight() - 1;

        for (int[] pt : line) {
            int x = pt[0], z = pt[1];
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

            Integer yBase = findSurfaceBaseY(x, z, yHint);
            if (yBase == null) continue;

            // база: заменяем верхний блок рельефа на булыжник
            BlockPos basePos = new BlockPos(x, yBase, z);
            level.setBlock(basePos, cobble.defaultBlockState(), 3);

            // рельса строго над базой
            if (yBase + 1 <= worldMax) {
                level.setBlock(new BlockPos(x, yBase + 1, z), railBlock.defaultBlockState(), 3);
            }

            yHint = yBase;
        }
    }

    // --- метро на фиксированных глубинах (-62/-61), как в Python ---
    private void paintSubwaySegment(int x1, int z1, int x2, int z2,
                                    int minX, int maxX, int minZ, int maxZ,
                                    Block cobble, Block rail) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist == 0) {
            placeSubwayPoint(x1, z1, minX, maxX, minZ, maxZ, cobble, rail);
            return;
        }
        for (int i=0; i<=dist; i++) {
            int x = (int)Math.round(x1 + dx * (i / (double)dist));
            int z = (int)Math.round(z1 + dz * (i / (double)dist));
            placeSubwayPoint(x, z, minX, maxX, minZ, maxZ, cobble, rail);
        }
    }

    private void placeSubwayPoint(int x, int z, int minX, int maxX, int minZ, int maxZ, Block cobble, Block rail) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;

        int yBase = -62, yRail = -61;
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        if (yBase < worldMin + 1 || yRail > worldMax) return;

        level.setBlock(new BlockPos(x, yBase, z), cobble.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x, yRail, z), rail.defaultBlockState(), 3);
    }

    // ===== высотные утилиты =====

    private Integer findSurfaceBaseY(int x, int z, Integer hintY) {
        final int worldMin = level.getMinBuildHeight();

        int yTop = findTopNonAirNear(x, z, hintY);
        if (yTop == Integer.MIN_VALUE) return null;

        BlockPos topPos = new BlockPos(x, yTop, z);
        BlockState top = level.getBlockState(topPos);

        // Вода сверху — не строим мосты
        // Если сверху вода — считаем это валидной базой: заменим воду на булыжник.
        if (top.getBlock() == Blocks.WATER) {
            return yTop; // cobble пойдёт сюда, rail — на yTop+1
        }

        if (isRailLike(top) || top.getBlock() == Blocks.SNOW) {
            if (yTop - 1 < worldMin) return null;
            // Даже если на базе вода — разрешаем перекрывать её.
            return yTop - 1;
        }

        // Обычный случай: база = верхний не-air, но убедимся, что это не вода
        if (top.getBlock() == Blocks.WATER) return null;
        return yTop;
    }

    private boolean isRailLike(BlockState st) {
        Block b = st.getBlock();
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    /** быстрый поиск верхнего не-air по колонке, с локальной подсказкой по высоте */
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

    // ===== прочие утилиты =====

    private static Block resolveBlock(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        return (b != null ? b : Blocks.STONE);
    }

    private static boolean isRailCandidate(JsonObject tags) {
        String r = optString(tags, "railway");
        if (r == null) return false;
        return r.equals("rail") || r.equals("tram") || r.equals("light_rail") || r.equals("subway");
    }

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

    // маленький хелпер, чтобы не путаться в сигнатурах
    private static int z1(int z) { return z; }
}