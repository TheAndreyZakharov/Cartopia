package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChimneyGenerator {

    // ===== Конфиг =====
    private static final int   DEFAULT_HEIGHT = 50;              // дефолтная высота
    private static final int   RADIUS         = 2;               // диаметр = 5 ⇒ радиус 2
    private static final Block MATERIAL       = Blocks.BRICKS;   // материал — кирпич

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public ChimneyGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // --- широковещалка
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

    // ===== типы =====
    private static final class PolyXZ {
        @SuppressWarnings("unused")
        final int[] xs, zs; final int n; final int cx, cz;
        PolyXZ(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            long sx = 0, sz = 0;
            for (int i=0;i<n;i++){ sx+=xs[i]; sz+=zs[i]; }
            cx = (int)Math.round(sx/(double)n);
            cz = (int)Math.round(sz/(double)n);
        }
    }

    private static final class Chimney {
        final int x, z;
        int height;
        int radius; // радиус в блоках
        Chimney(int x, int z){ this.x=x; this.z=z; }
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "ChimneyGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "ChimneyGenerator: нет center/bbox — пропускаю.");
            return;
        }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        List<Chimney> list = new ArrayList<>();

        // ===== Сбор признаков (стриминг) =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, list,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "ChimneyGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "ChimneyGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), list,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "ChimneyGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (list.isEmpty()) {
            broadcast(level, "ChimneyGenerator: подходящих труб не найдено — готово.");
            return;
        }

        // ===== Рендер =====
        int done = 0;
        for (Chimney c : list) {
            try {
                if (c.x<minX||c.x>maxX||c.z<minZ||c.z>maxZ) continue;
                renderChimney(c);
            } catch (Exception ex) {
                broadcast(level, "ChimneyGenerator: ошибка на ("+c.x+","+c.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, list.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, list.size()));
                broadcast(level, "Трубы: ~" + pct + "%");
            }
        }
    }

    // ===== сбор признаков =====
    private void collectFeature(JsonObject e, List<Chimney> out,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (tags == null) return;

        // ВАЖНО: не строим, если есть тег building/ building:part
        if (tags.has("building") || tags.has("building:part")) return;

        if (!isChimneyLike(tags)) return;

        String type = optString(e,"type");
        int cx, cz;

        if ("node".equals(type)) {
            double lat, lon;
            if (e.has("lat") && e.has("lon")) {
                lat = e.get("lat").getAsDouble();
                lon = e.get("lon").getAsDouble();
            } else {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                        ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size() == 0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat = p.get("lat").getAsDouble();
                lon = p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            cx = xz[0]; cz = xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray())
                    ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size() < 1) return;
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            PolyXZ poly = new PolyXZ(xs, zs);
            cx = poly.cx; cz = poly.cz;
        } else return;

        Chimney c = new Chimney(cx, cz);
        c.height = extractHeightBlocks(tags);
        c.radius = extractRadiusBlocks(tags);
        out.add(c);
    }

    private static boolean isChimneyLike(JsonObject t) {
        String mm = lower(optString(t, "man_made"));
        String tt = lower(optString(t, "tower:type"));
        String ind= lower(optString(t, "industrial"));
        String ch = lower(optString(t, "chimney"));
        String ss = lower(optString(t, "smokestack"));

        if (mm != null) {
            if (mm.equals("chimney")) return true;
            if (mm.equals("flare")) return true;                  // факельная труба
            if (mm.equals("smokestack")) return true;             // редкое
            if (mm.contains("stack")) return true;                // на всякий случай (chimney_stack / stack)
        }
        if (tt != null && tt.contains("chimney")) return true;    // tower:type=chimney
        if (ind != null && ind.contains("chimney")) return true;  // industrial=chimney
        if ("yes".equals(ch) || "yes".equals(ss)) return true;    // chimney=yes / smokestack=yes

        return false;
    }

    private static String lower(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

    private int extractHeightBlocks(JsonObject t) {
        String[] keys = new String[]{
                "height",           // основной
                "chimney:height",   // встречается
                "height:chimney"    // и так тоже
        };
        for (String k : keys) {
            String v = optString(t, k);
            if (v != null) {
                Integer num = parseFirstInt(v);
                if (num != null && num > 0) return num;
            }
        }
        return DEFAULT_HEIGHT;
    }

    private int extractRadiusBlocks(JsonObject t) {
        // сначала пробуем диаметр (в блоках ≈ метрах), если есть — радиус = diameter/2
        String[] diamKeys = new String[]{
                "diameter", "chimney:diameter", "diameter:chimney",
                "outer_diameter", "diameter:outer", "width"
        };
        for (String k : diamKeys) {
            String v = optString(t, k);
            if (v != null) {
                Integer d = parseFirstInt(v);
                if (d != null && d > 0) return Math.max(1, d / 2);
            }
        }

        // затем пробуем радиус напрямую
        String[] radKeys = new String[]{
                "radius", "chimney:radius", "radius:chimney"
        };
        for (String k : radKeys) {
            String v = optString(t, k);
            if (v != null) {
                Integer r = parseFirstInt(v);
                if (r != null && r > 0) return Math.max(1, r);
            }
        }

        // дефолт — как было (диаметр 5 ⇒ радиус 2)
        return RADIUS;
    }

    private static Integer parseFirstInt(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && !Character.isDigit(s.charAt(i))) i++;
        if (i == len) return null;
        int j = i;
        while (j < len && (Character.isDigit(s.charAt(j)))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    // ===== рендер одной трубы =====
    private void renderChimney(Chimney c) {
        final int H = Math.max(2, c.height);

        List<int[]> ring = ringOffsets(c.radius); // только оболочка, внутри пусто
        for (int[] d : ring) {
            int x = c.x + d[0], z = c.z + d[1];
            int yBase = terrainYFromCoordsOrWorld(x, z, null);
            if (yBase == Integer.MIN_VALUE) continue;
            int yStart = yBase + 1;
            int yEnd   = yStart + H - 1;
            for (int y = yStart; y <= yEnd; y++) {
                setBlockSafe(x, y, z, MATERIAL);
            }
        }
    }

    // ===== геометрия круга =====
    @SuppressWarnings("unused")
    private List<int[]> diskOffsets(int r) {
        ArrayList<int[]> out = new ArrayList<>();
        int rr = r*r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx*dx + dz*dz <= rr + 1) out.add(new int[]{dx, dz});
            }
        }
        return out;
    }

    /** Кольцо толщиной 1 блока: r^2 >= dx^2+dz^2 > (r-1)^2 */
    private List<int[]> ringOffsets(int r) {
        ArrayList<int[]> out = new ArrayList<>();
        if (r <= 0) return out;
        int rr  = r * r;
        int ri  = r - 1;
        int rri = ri * ri;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx*dx + dz*dz;
                if (d2 <= rr && d2 > rri) out.add(new int[]{dx, dz});
            }
        }

        // спец-случай r=1 — помогаем «округлить» минимальную трубу
        if (r == 1 && out.isEmpty()) {
            out.add(new int[]{ 1, 0}); out.add(new int[]{-1, 0});
            out.add(new int[]{ 0, 1}); out.add(new int[]{ 0,-1});
            out.add(new int[]{ 1, 1}); out.add(new int[]{-1, 1});
            out.add(new int[]{ 1,-1}); out.add(new int[]{-1,-1});
        }
        return out;
    }

    // ===== низкоуровневые set'ы =====
    private void setBlockSafe(int x, int y, int z, Block block) {
        level.setBlock(new BlockPos(x,y,z), block.defaultBlockState(), 3);
    }

    // ===== высота рельефа =====
    private int terrainYFromCoordsOrWorld(int x, int z, Integer hintY) {
        try {
            if (store != null && store.grid != null && store.grid.inBounds(x, z)) {
                int gy = store.grid.groundY(x, z);
                if (gy != Integer.MIN_VALUE) return gy;
            }
        } catch (Throwable ignore) {}

        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                if (g.has("minX")) {
                    int minX = g.get("minX").getAsInt();
                    int minZ = g.get("minZ").getAsInt();
                    int w    = g.get("width").getAsInt();
                    int h    = g.get("height").getAsInt();
                    int ix = x - minX, iz = z - minZ;
                    if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz * w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}

        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    // ===== утилиты =====
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
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
}