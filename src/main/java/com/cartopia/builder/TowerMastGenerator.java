package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TowerMastGenerator {

    // ===== Конфиг =====
    private static final int DEFAULT_HEIGHT = 50;

    // материалы
    private static final Block METAL_BLOCK   = Blocks.IRON_BLOCK;  // «металлический блок»
    private static final Block METAL_LATTICE = Blocks.IRON_BARS;   // «металлическая решётка»
    private static final Block LIGHT_BLOCK   = Blocks.GLOWSTONE;   // подсветка снизу платформы

    // геометрия по умолчанию
    private static final int MAST_SIZE       = 2;   // мачта 2×2 «четыре блока рядом»
    private static final int MAST_PLAT       = 4;   // верхняя платформа 4×4
    private static final int TOWER_BASE      = 10;  // основание «башни» 10×10
    private static final int TOWER_TOP       = 2;   // на вершине сужается до 2×2
    private static final int TOWER_PLAT      = 4;   // верхняя платформа 4×4

    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public TowerMastGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ===== вещалка =====
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
    private enum Kind { MAST, TOWER }

    private static final class PolyXZ {
        @SuppressWarnings("unused")
        final int[] xs, zs; final int n; final int cx, cz;
        PolyXZ(int[] xs, int[] zs) {
            this.xs = xs; this.zs = zs; this.n = xs.length;
            long sx=0, sz=0; for (int i=0;i<n;i++){ sx+=xs[i]; sz+=zs[i]; }
            cx = (int)Math.round(sx/(double)n);
            cz = (int)Math.round(sz/(double)n);
        }
    }

    private static final class Feature {
        final int x,z;
        int height;
        Kind kind;
        boolean lattice;       // только для MAST (если true — тело из IRON_BARS, с нижней «подложкой» из IRON_BLOCK)
        boolean lighting;      // подсветка платформы и небольшой «конус» сверху
        boolean communication; // флаги по периметру платформы
        Feature(int x, int z){ this.x=x; this.z=z; }
    }

    // ===== публичный запуск =====
    public void generate() {
        if (coords == null) {
            broadcast(level, "TowerMastGenerator: coords == null — пропускаю.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "TowerMastGenerator: нет center/bbox — пропускаю.");
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

        List<Feature> list = new ArrayList<>();

        // ===== сбор признаков со стримингом =====
        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        collectFeature(e, list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "TowerMastGenerator: нет coords.features — пропускаю.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "TowerMastGenerator: features.elements пуст — пропускаю.");
                    return;
                }
                for (JsonElement el : elements) {
                    collectFeature(el.getAsJsonObject(), list, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "TowerMastGenerator: ошибка чтения features: " + ex.getMessage());
        }

        if (list.isEmpty()) {
            broadcast(level, "TowerMastGenerator: подходящих вышек не найдено — готово.");
            return;
        }

        // ===== рендер =====
        int done = 0;
        for (Feature f : list) {
            try {
                if (f.x<minX||f.x>maxX||f.z<minZ||f.z>maxZ) continue;
                if (f.kind == Kind.MAST) renderMast(f);
                else renderTower(f);
            } catch (Exception ex) {
                broadcast(level, "TowerMastGenerator: ошибка на ("+f.x+","+f.z+"): " + ex.getMessage());
            }
            done++;
            if (done % Math.max(1, list.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * done / Math.max(1, list.size()));
                broadcast(level, "Вышки: ~" + pct + "%");
            }
        }
    }

    // ===== фильтр и разбор признаков =====
    private void collectFeature(JsonObject e, List<Feature> out,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        JsonObject t = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
        if (t == null) return;

        // 0) Жёсткие пропуски
        if (t.has("building") || t.has("building:part")) return;         // никаких «башен-строений»
        if (hasPower(t)) return;                                          // не трогаем ЛЭП и электричество
        if (isExcludedTower(t)) return;                                   // исключаем watchtower/lifeguard/lighthouse/см. ниже
        if (isChimneyLike(t)) return;                                     // трубы и дымоходы — в другом генераторе
        if (isWindTurbineLike(t)) return;                                 // ветряки — в другом генераторе

        // 1) Классификация: mast vs tower
        Kind kind = classifyKind(t);
        if (kind == null) return; // не наша сущность

        // 2) Координаты
        String type = optString(e,"type");
        int tx, tz;
        if ("node".equals(type)) {
            double lat, lon;
            if (e.has("lat") && e.has("lon")) { lat=e.get("lat").getAsDouble(); lon=e.get("lon").getAsDouble(); }
            else {
                JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
                if (g == null || g.size()==0) return;
                JsonObject p = g.get(0).getAsJsonObject();
                lat=p.get("lat").getAsDouble(); lon=p.get("lon").getAsDouble();
            }
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            tx=xz[0]; tz=xz[1];
        } else if ("way".equals(type) || "relation".equals(type)) {
            JsonArray g = (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
            if (g == null || g.size()<1) return;
            int[] xs = new int[g.size()], zs = new int[g.size()];
            for (int i=0;i<g.size();i++){
                JsonObject p=g.get(i).getAsJsonObject();
                int[] xz=latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            PolyXZ poly = new PolyXZ(xs, zs);
            tx=poly.cx; tz=poly.cz;
        } else return;

        Feature f = new Feature(tx, tz);
        f.kind        = kind;
        f.lattice     = isLatticeMast(t);                     // актуально для MAST
        f.lighting    = hasLighting(t);
        f.communication=hasCommunication(t);
        f.height      = extractHeightBlocks(t);

        out.add(f);
    }

    // --- helpers: классификация/фильтры
    private static Kind classifyKind(JsonObject t) {
        String mm  = low(optString(t, "man_made"));
        String tt  = low(optString(t, "tower:type"));
        String tele= low(optString(t, "telecom"));
        boolean hasCommKey = hasAnyKeyPrefix(t, "communication:") != null;

        // 1) Жёсткий приоритет по форме:
        //    tower -> ПИРАМИДА, mast -> МАЧТА
        if ("tower".equals(mm) || (mm != null && mm.contains("tower"))) return Kind.TOWER;
        if ("mast".equals(mm)) return Kind.MAST;

        // 2) Явные указания типа
        if (tt != null) {
            if (tt.contains("mast")) return Kind.MAST; // только явное "mast"
            // communication / lighting НЕ делают мачту — это всё ещё башни по форме
            if (tt.contains("communication") || tt.contains("lighting")) return Kind.TOWER;
            if (tt.contains("tower")) return Kind.TOWER;
        }

        // 3) Косвенные признаки связи — трактуем как башню по умолчанию (форма 2)
        if (tele != null || hasCommKey) return Kind.TOWER;

        // 4) Прочие "похожи на башню" (высоты/конструкция) — тоже башня по умолчанию
        if (t.has("tower:construction") || t.has("tower:height")) return Kind.TOWER;

        // 5) Иначе не наша сущность
        return null;
    }

    private static boolean isLatticeMast(JsonObject t) {
        String tc = low(optString(t, "tower:construction"));
        String mat= low(optString(t, "material"));
        return (tc != null && tc.contains("lattice")) || "lattice".equals(mat);
    }

    private static boolean hasLighting(JsonObject t) {
        String tt = low(optString(t, "tower:type"));
        String lighting = low(optString(t, "lighting"));
        String light = low(optString(t, "light"));
        return (tt != null && tt.contains("lighting")) || "yes".equals(lighting) || "yes".equals(light);
    }

    private static boolean hasCommunication(JsonObject t) {
        String tt = low(optString(t, "tower:type"));
        if (tt != null && tt.contains("communication")) return true;
        // любое communication:* = yes / true
        for (String k : t.keySet()) {
            if (k.toLowerCase(Locale.ROOT).startsWith("communication:")) {
                String v = low(optString(t, k));
                if (v != null && (v.equals("yes") || v.equals("true") || v.equals("1"))) return true;
            }
        }
        return false;
    }

    private static boolean hasPower(JsonObject t) {
        // всё, что как-то связано с ЛЭП и электроопорами — пропускаем
        String power = low(optString(t, "power"));
        String tt = low(optString(t, "tower:type"));
        String mm = low(optString(t, "man_made"));
        if (power != null) return true; // power=tower/pole/substation/…
        if (tt != null && tt.contains("power")) return true;
        if ("pylon".equals(mm)) return true;
        return false;
    }

    private static boolean isExcludedTower(JsonObject t) {
        String mm = low(optString(t, "man_made"));
        String tt = low(optString(t, "tower:type"));
        String em = low(optString(t, "emergency"));
        String seamark = low(optString(t, "seamark:type"));
        String wt = low(optString(t, "water_tower"));

        if ("watchtower".equals(mm)) return true;
        if ("lifeguard_tower".equals(mm) || "lifeguard_tower".equals(em)) return true;
        if ("lighthouse".equals(mm) || "lighthouse".equals(seamark)) return true;
        if (tt != null && (tt.contains("watch") || tt.contains("observation") || tt.contains("lifeguard"))) return true;
        if (mm != null && mm.contains("water_tower")) return true;
        if (wt != null) return true;
        return false;
    }

    private static boolean isChimneyLike(JsonObject t) {
        String mm = low(optString(t, "man_made"));
        String tt = low(optString(t, "tower:type"));
        String ind= low(optString(t, "industrial"));
        String ch = low(optString(t, "chimney"));
        String ss = low(optString(t, "smokestack"));
        if ("chimney".equals(mm) || "flare".equals(mm) || "smokestack".equals(mm)) return true;
        if (mm != null && mm.contains("stack")) return true;
        if (tt != null && tt.contains("chimney")) return true;
        if (ind != null && ind.contains("chimney")) return true;
        if ("yes".equals(ch) || "yes".equals(ss)) return true;
        return false;
    }

    private static boolean isWindTurbineLike(JsonObject t) {
        String power = low(optString(t, "power"));
        String gm = low(optString(t, "generator:method"));
        String gsrc = low(optString(t, "generator:source"));
        String gtype = low(optString(t, "generator:type"));
        if ("generator".equals(power) && gtype != null && gtype.contains("wind")) return true;
        if ("wind_turbine".equals(gm)) return true;
        if ("wind".equals(gsrc)) return true;
        return false;
    }

    private static String hasAnyKeyPrefix(JsonObject t, String prefix) {
        String lp = prefix.toLowerCase(Locale.ROOT);
        for (String k : t.keySet()) if (k.toLowerCase(Locale.ROOT).startsWith(lp)) return k;
        return null;
    }

    // ===== извлечение высоты =====
    private int extractHeightBlocks(JsonObject t) {
        String[] keys = new String[]{
                "height", "tower:height", "mast:height", "height:tower", "height:mast"
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

    private static Integer parseFirstInt(String s) {
        int len = s.length(), i = 0;
        while (i < len && !Character.isDigit(s.charAt(i))) i++;
        if (i == len) return null;
        int j = i;
        while (j < len && Character.isDigit(s.charAt(j))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception ignore) { return null; }
    }

    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }

    // ===== рендер: MAST =====
    private void renderMast(Feature f) {
        final int H = Math.max(2, f.height);

        // 1) 2×2 стоек: каждая «сидит» на своём рельефе и растёт ровно на H
        for (int dx = 0; dx < MAST_SIZE; dx++) {
            for (int dz = 0; dz < MAST_SIZE; dz++) {
                int x = f.x + dx, z = f.z + dz;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                int yStart = yBase + 1;
                for (int y = yStart; y < yStart + H; y++) {
                    Block body = f.lattice ? METAL_LATTICE : METAL_BLOCK;
                    // нижний ряд (подложка) — всегда из железных блоков, если lattice=true
                    if (f.lattice && y == yStart) body = METAL_BLOCK;
                    setBlock(x, y, z, body);
                }
            }
        }

        // 2) Платформа 4×4 на абсолютной «макушке» каждой клетки (по местному H)
        fillPlatformFollowingTop(f.x, f.z, MAST_PLAT, H, METAL_BLOCK);

        // 3) Подсветка, если lighting=true — по периметру снизу платформы
        if (f.lighting) {
            placeUnderPlatformLights(f.x, f.z, MAST_PLAT, H, LIGHT_BLOCK);
            // небольшой «колпак-конус» сверху платформы (3×3 → 2×2 → 1×1)
            int yc = localTopY(f.x, f.z, H); // референс по центру
            fillCenteredSquare(f.x, f.z, yc + 1, 3, METAL_BLOCK);
            fillCenteredSquare(f.x, f.z, yc + 2, 2, METAL_BLOCK);
            fillCenteredSquare(f.x, f.z, yc + 3, 1, METAL_BLOCK);
        }

        // 4) Флаги, если communication=true — белые баннеры по периметру платформы (сверху)
        //    + «висячие» по бокам платформы
        if (f.communication) {
            int yPlat = localTopY(f.x, f.z, H); // платформа на этом уровне
            placeBannersOnPlatformPerimeter(f.x, f.z, MAST_PLAT, yPlat + 1);
            placeWallBannersAroundPlatform(f.x, f.z, MAST_PLAT, H); 
        }
    }

    // ===== рендер: TOWER (пирамида-«Эйфелева») =====
    private void renderTower(Feature f) {
        final int H = Math.max(2, f.height);

        // 0) Основание 10×10: каждый блок «садим» на свой рельеф
        for (int dx = -rangeHalfEven(TOWER_BASE).min; dx <= rangeHalfEven(TOWER_BASE).max; dx++) {
            for (int dz = -rangeHalfEven(TOWER_BASE).min; dz <= rangeHalfEven(TOWER_BASE).max; dz++) {
                int x = f.x + dx, z = f.z + dz;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                setBlock(x, yBase + 1, z, METAL_BLOCK);
            }
        }

        // 1) Конусное тело из решётки: квадрат на каждом «этаже», линейно сужается от 10×10 до 2×2
        for (int h = 0; h < H; h++) {
            double t = (H <= 1) ? 1.0 : (h / (double)(H - 1));
            int w = Math.max(TOWER_TOP, (int)Math.round( TOWER_BASE - (TOWER_BASE - TOWER_TOP) * t ));
            // рисуем «периметр» квадрата w×w из IRON_BARS — и так для каждой колонки по её локальной базе
            drawPerimeterLevelFollowingTerrain(f.x, f.z, w, h, METAL_LATTICE);
        }

        // 2) Верхняя платформа 4×4 — на уровне локального «верха» (terrain+1+H)
        fillPlatformFollowingTop(f.x, f.z, TOWER_PLAT, H, METAL_BLOCK);

        // 3) Подсветка и «конусок» при lighting=true
        if (f.lighting) {
            placeUnderPlatformLights(f.x, f.z, TOWER_PLAT, H, LIGHT_BLOCK);
            int yc = localTopY(f.x, f.z, H);
            fillCenteredSquare(f.x, f.z, yc + 1, 3, METAL_BLOCK);
            fillCenteredSquare(f.x, f.z, yc + 2, 2, METAL_BLOCK);
            fillCenteredSquare(f.x, f.z, yc + 3, 1, METAL_BLOCK);
        }

        // 4) Флаги по периметру при communication=true
        //    + «висячие» по бокам платформы
        if (f.communication) {
            int yPlat = localTopY(f.x, f.z, H);
            placeBannersOnPlatformPerimeter(f.x, f.z, TOWER_PLAT, yPlat + 1);
            placeWallBannersAroundPlatform(f.x, f.z, TOWER_PLAT, H); 
        }
    }

    // ===== периметрный «этаж», растущий по локальным базам =====
    private void drawPerimeterLevelFollowingTerrain(int cx, int cz, int w, int h, Block block) {
        Range r = rangeHalfEven(w);
        int minX = cx - r.min, maxX = cx + r.max;
        int minZ = cz - r.min, maxZ = cz + r.max;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean border = (x==minX || x==maxX || z==minZ || z==maxZ);
                if (!border) continue;
                int yBase = terrainYFromCoordsOrWorld(x, z, null);
                if (yBase == Integer.MIN_VALUE) continue;
                int y = yBase + 1 + h;
                setBlock(x, y, z, block);
            }
        }
    }

    // ===== платформы и свет =====
    private void fillPlatformFollowingTop(int cx, int cz, int w, int H, Block block) {
        Range r = rangeHalfEven(w);
        for (int x = cx - r.min; x <= cx + r.max; x++) {
            for (int z = cz - r.min; z <= cz + r.max; z++) {
                int yTop = localTopY(x, z, H);
                if (yTop == Integer.MIN_VALUE) continue;
                setBlock(x, yTop, z, block);
            }
        }
    }

    private void placeUnderPlatformLights(int cx, int cz, int w, int H, Block glow) {
        Range r = rangeHalfEven(w);
        for (int x = cx - r.min; x <= cx + r.max; x++) {
            for (int z = cz - r.min; z <= cz + r.max; z++) {
                boolean edge = (x==cx - r.min || x==cx + r.max || z==cz - r.min || z==cz + r.max);
                if (!edge) continue;
                int yTop = localTopY(x, z, H);
                if (yTop == Integer.MIN_VALUE) continue;
                // ставим свет прямо под платформой
                setBlock(x, yTop - 1, z, glow);
            }
        }
    }

    private void placeBannersOnPlatformPerimeter(int cx, int cz, int w, int y) {
        Range r = rangeHalfEven(w);
        // 8 точек: 4 угла + 4 середины граней
        int[][] pts = new int[][]{
                {cx - r.min, cz - r.min}, {cx + r.max, cz - r.min},
                {cx - r.min, cz + r.max}, {cx + r.max, cz + r.max},
                {cx,          cz - r.min}, {cx,          cz + r.max},
                {cx - r.min,  cz},         {cx + r.max,  cz}
        };
        for (int[] p : pts) {
            int x = p[0], z = p[1];
            // Под баннер — если тут платформа ещё не поставлена (на всякий случай)
            // баннер — стоячий, повернём примерно «наружу»
            int rot = rotationOutwards(cx, cz, x, z);
            BlockState st = Blocks.WHITE_BANNER.defaultBlockState()
                    .setValue(BlockStateProperties.ROTATION_16, rot);
            level.setBlock(new BlockPos(x, y, z), st, 3);
        }
    }

    /** «Висячие» флаги по бокам платформы: белые настенные баннеры на внешней стороне. */
    private void placeWallBannersAroundPlatform(int cx, int cz, int w, int H) {
        Range r = rangeHalfEven(w);

        // Северная грань (наружу = NORTH): баннер в блоке перед кромкой (z-1)
        int zN = cz - r.min;
        for (int x = cx - r.min; x <= cx + r.max; x++) {
            int y = localTopY(x, zN, H);
            BlockPos p = new BlockPos(x, y, zN - 1);
            BlockState st = Blocks.WHITE_WALL_BANNER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            level.setBlock(p, st, 3);
        }

        // Южная грань (наружу = SOUTH): баннер в блоке после кромки (z+1)
        int zS = cz + r.max;
        for (int x = cx - r.min; x <= cx + r.max; x++) {
            int y = localTopY(x, zS, H);
            BlockPos p = new BlockPos(x, y, zS + 1);
            BlockState st = Blocks.WHITE_WALL_BANNER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            level.setBlock(p, st, 3);
        }

        // Западная грань (наружу = WEST): баннер в блоке слева (x-1)
        int xW = cx - r.min;
        for (int z = cz - r.min; z <= cz + r.max; z++) {
            int y = localTopY(xW, z, H);
            BlockPos p = new BlockPos(xW - 1, y, z);
            BlockState st = Blocks.WHITE_WALL_BANNER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
            level.setBlock(p, st, 3);
        }

        // Восточная грань (наружу = EAST): баннер в блоке справа (x+1)
        int xE = cx + r.max;
        for (int z = cz - r.min; z <= cz + r.max; z++) {
            int y = localTopY(xE, z, H);
            BlockPos p = new BlockPos(xE + 1, y, z);
            BlockState st = Blocks.WHITE_WALL_BANNER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            level.setBlock(p, st, 3);
        }
    }

    // ===== геометрия «центрированного» квадрата =====
    private static final class Range { final int min,max; Range(int min,int max){this.min=min; this.max=max;} }
    /** для чётной ширины даёт смещения по одну и другую сторону (напр., 4 → [-1..+2], 10 → [-4..+5]) */
    private Range rangeHalfEven(int w) {
        int half = w / 2;        // для 10 → 5
        return new Range(half-1, half); // [-4 .. +5] для 10
    }

    private void fillCenteredSquare(int cx, int cz, int y, int w, Block b) {
        Range r = (w % 2 == 0) ? rangeHalfEven(w) : new Range(w/2, w/2);
        int minX = cx - r.min, maxX = cx + r.max;
        int minZ = cz - r.min, maxZ = cz + r.max;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setBlock(x, y, z, b);
            }
        }
    }

    private int rotationOutwards(int cx, int cz, int x, int z) {
        // приблизительно повернём баннеры «наружу» (0..15)
        int dx = Integer.compare(x, cx);
        int dz = Integer.compare(z, cz);
        if (dz < 0 && dx == 0) return 8;   // север
        if (dz > 0 && dx == 0) return 0;   // юг
        if (dx < 0 && dz == 0) return 12;  // запад
        if (dx > 0 && dz == 0) return 4;   // восток
        if (dx < 0 && dz < 0) return 10;   // северо-запад
        if (dx > 0 && dz < 0) return 6;    // северо-восток
        if (dx < 0 && dz > 0) return 14;   // юго-запад
        if (dx > 0 && dz > 0) return 2;    // юго-восток
        return 0;
    }

    // ===== низкоуровневые сеттеры и рельеф =====
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x,y,z), b.defaultBlockState(), 3);
    }

    /** локальный «верх» для (x,z) при высоте H: terrain+1+H */
    private int localTopY(int x, int z, int H) {
        int base = terrainYFromCoordsOrWorld(x, z, null);
        if (base == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return base + 1 + H;
    }

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