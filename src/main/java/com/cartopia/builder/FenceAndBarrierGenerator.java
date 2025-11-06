package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;
import java.util.function.Predicate;


public class FenceAndBarrierGenerator {

    // ===== Материалы =====
    private static final Block[] WOOD_FENCES = new Block[]{
            Blocks.SPRUCE_FENCE, Blocks.OAK_FENCE, Blocks.BIRCH_FENCE, Blocks.BAMBOO_FENCE,
            Blocks.CHERRY_FENCE, Blocks.CRIMSON_FENCE, Blocks.MANGROVE_FENCE, Blocks.ACACIA_FENCE,
            Blocks.JUNGLE_FENCE, Blocks.WARPED_FENCE
    };
    private static final Block[] HEAVY_WALLS = new Block[]{
            Blocks.STONE_BRICK_WALL, Blocks.COBBLESTONE_WALL, Blocks.MUD_BRICK_WALL
    };
    private static final Block   DEFAULT_WOOD  = Blocks.OAK_FENCE;
    private static final Block   DARK_OAK      = Blocks.DARK_OAK_FENCE;
    private static final Block   IRON_LATTICE  = Blocks.IRON_BARS;
    private static final Block   BRICK_WALL    = Blocks.BRICK_WALL;
    private static final Block   ANDESITE_WALL = Blocks.ANDESITE_WALL;
    private static final Block   GRANITE_WALL  = Blocks.GRANITE_WALL;
    private static final Block   OAK_GATE      = Blocks.OAK_FENCE_GATE;

    // — Въезды/ворота: ключевые маркеры (узлы)
    private static final class GatePoint { final int x,z; GatePoint(int x,int z){this.x=x;this.z=z;} }
    private final List<GatePoint> gatePoints = new ArrayList<>();
    private final List<GatePoint> gapPoints  = new ArrayList<>(); // «проезд/заезд» → разрыв

    // NO-FENCE: клетки, где запрещено ставить ограждения (мосты/туннели, дороги на них)
    private final Set<Long> noFenceMask = new HashSet<>();
    private static final int NOFENCE_BUFFER = 2; // радиус (в блоках) по обе стороны линии

    // ===== Геометрия зон =====
    private static final class Ring {
        final int[] xs, zs; final int n;
        Ring(int[] xs, int[] zs) { this.xs = xs; this.zs = zs; this.n = xs.length; }
    }
    private static final class Area {
        final List<Ring> outers = new ArrayList<>();
        final List<Ring> inners = new ArrayList<>();
        JsonObject srcTags; // для классификации
    }

    // Линейные барьеры (polyline)
    private static final class BarrierLine {
        final List<int[]> pts = new ArrayList<>();
        JsonObject tags;
    }

    // Выбранный стиль для контура/линии
    private static final class FenceStyle {
        Block block;         // материал
        int   minHeight = 1; // минимум (категорийный)
        int   defHeight = 1; // дефолт (если height отсутствует)
        FenceStyle(Block b, int def, int min){ this.block=b; this.defHeight=def; this.minHeight=min; }
        FenceStyle copy(){ return new FenceStyle(block, defHeight, minHeight); }
    }

    // ===== Инфраструктура =====
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    public FenceAndBarrierGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level  = level;
        this.coords = coords;
        this.store  = store;
    }

    // ===== Вещалка =====
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

    // ===== Публичный запуск =====
    public void generate() {
        if (coords == null && (store == null || store.indexJsonObject() == null)) {
            broadcast(level, "FenceAndBarrierGenerator: no source coordinates — skipping.");
            return;
        }

        JsonObject sourceIndex = (store != null && store.indexJsonObject() != null)
                ? store.indexJsonObject() : coords;

        JsonObject center = sourceIndex.getAsJsonObject("center");
        JsonObject bbox   = sourceIndex.getAsJsonObject("bbox");
        if (center == null || bbox == null) {
            broadcast(level, "FenceAndBarrierGenerator: no center/bbox — skipping.");
            return;
        }

        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int    sizeMeters = sourceIndex.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = sourceIndex.has("player") ? (int)Math.round(sourceIndex.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = sourceIndex.has("player") ? (int)Math.round(sourceIndex.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int worldMinX = Math.min(a[0], b[0]);
        final int worldMaxX = Math.max(a[0], b[0]);
        final int worldMinZ = Math.min(a[1], b[1]);
        final int worldMaxZ = Math.max(a[1], b[1]);

        List<Area> areaList       = new ArrayList<>();
        List<BarrierLine> barriers= new ArrayList<>();

        boolean streaming = (store != null);

        // ==== 1) Сбор входных данных ====
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject e : fs) {
                        JsonObject tags = tagObj(e);
                        if (tags == null) continue;

                        // 1a. Узлы ворот/въездов
                        if ("node".equals(optString(e,"type"))) {
                            collectGateNode(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                            continue;
                        }

                        // Пропускаем здания — тут не работаем по building*
                        if (hasAnyKey(tags, k -> k.startsWith("building"))) continue;

                        // === NO-FENCE MASK: мосты/туннели и дороги на них
                        if (isBridgeOrTunnel(tags) && hasGeometry(e)) {
                            markNoFenceAlongGeometry(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        }

                        // 1b. Линейные барьеры (включая guard_rail, jersey_barrier)
                        if (isBarrierLine(tags) && hasGeometry(e)) {
                            BarrierLine bl = toBarrierLine(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                            if (bl != null) barriers.add(bl);
                            continue;
                        }

                        // 1c. Полигоны-зоны — строго по whitelist
                        if (isWhitelistedArea(tags) && isPolygon(e)) {
                            Area ar = toArea(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                            if (ar != null) { ar.srcTags = tags; areaList.add(ar); }
                        }
                    }
                }
            } else {
                // Фолбэк: coords.features.elements
                JsonArray elements = safeElementsArray(coords);
                if (elements == null) {
                    broadcast(level, "FenceAndBarrierGenerator: features.elements is empty — skipping.");
                    return;
                }
                for (JsonElement el : elements) {
                    JsonObject e = el.getAsJsonObject();
                    JsonObject tags = tagObj(e);
                    if (tags == null) continue;

                    if ("node".equals(optString(e,"type"))) {
                        collectGateNode(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        continue;
                    }

                    if (hasAnyKey(tags, k -> k.startsWith("building"))) continue;

                    // === NO-FENCE MASK: мосты/туннели и дороги на них
                    if (isBridgeOrTunnel(tags) && hasGeometry(e)) {
                        markNoFenceAlongGeometry(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                    
                    if (isBarrierLine(tags) && hasGeometry(e)) {
                        BarrierLine bl = toBarrierLine(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        if (bl != null) barriers.add(bl);
                        continue;
                    }

                    if (isWhitelistedArea(tags) && isPolygon(e)) {
                        Area ar = toArea(e, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        if (ar != null) { ar.srcTags = tags; areaList.add(ar); }
                    }
                }
            }
        } catch (Exception ex) {
            broadcast(level, "FenceAndBarrierGenerator: error reading features: " + ex.getMessage());
        }

        // ==== 2) Построение по зонам ====
        int aIdx = 0;
        for (Area ar : areaList) {
            aIdx++;
            try {
                FenceStyle style = classifyAreaStyle(ar.srcTags);
                for (Ring r : ar.outers) {
                    buildPerimeter(r, style, worldMinX, worldMaxX, worldMinZ, worldMaxZ);
                }
                // inners (дыры) принципиально не огораживаем
            } catch (Exception ex) {
                broadcast(level, "Zone fence #" + aIdx + ": error " + ex.getMessage());
            }
            if (aIdx % Math.max(1, areaList.size()/5) == 0) {
                int pct = (int)Math.round(100.0 * aIdx / Math.max(1, areaList.size()));
                broadcast(level, "Zone fences: ~" + pct + "%");
            }
        }

        // ==== 3) Линейные барьеры ====
        int blIdx = 0;
        for (BarrierLine bl : barriers) {
            blIdx++;
            try {
                FenceStyle style = classifyBarrierStyle(bl.tags);
                buildBarrierLine(bl, style, worldMinX, worldMaxX, worldMinZ, worldMaxZ);
            } catch (Exception ex) {
                broadcast(level, "Linear barrier  #" + blIdx + ": error " + ex.getMessage());
            }
        }

        broadcast(level, "Fences/enclosures/barriers: done.");
    }

    // ======== WHITELIST ЗОН ========
    private static boolean isWhitelistedArea(JsonObject t) {
        String landuse  = low(optString(t,"landuse"));
        String amenity  = low(optString(t,"amenity"));
        String leisure  = low(optString(t,"leisure"));
        String power    = low(optString(t,"power"));
        String military = low(optString(t,"military"));
        String aeroway  = low(optString(t,"aeroway"));
        String office   = low(optString(t,"office"));
        String embassy  = low(optString(t,"embassy"));
        String prison   = low(optString(t,"prison"));
        String historic = low(optString(t,"historic"));
        boolean hasHealthcareKey = t.has("healthcare");

        // Кладбища
        if ("cemetery".equals(landuse) || "grave_yard".equals(amenity)) return true;

        // Тяжёлые/режимные/важные
        if ("industrial".equals(landuse) || "construction".equals(landuse) || "depot".equals(landuse)
                || "port".equals(landuse) || "harbour".equals(landuse)
                || "plant".equals(power) || "substation".equals(power) || "generator".equals(power)
                || military != null || "government".equals(office) || aeroway != null || embassy != null || prison != null) return true;

        // Соц. учреждения: детсады/школы/вуз/медицина
        if ("kindergarten".equals(amenity) || "school".equals(amenity) || "college".equals(amenity) || "university".equals(amenity)
                || "clinic".equals(amenity) || "hospital".equals(amenity) || "doctors".equals(amenity) || hasHealthcareKey
                || "childcare".equals(amenity)) return true;

        // Парки/сады/памятники/скверы
        if ("park".equals(leisure) || "garden".equals(leisure) || "nature_reserve".equals(leisure)
                || "common".equals(leisure) || "recreation_ground".equals(landuse)
                || "monument".equals(historic) || "memorial".equals(historic)) return true;

        // Спорт/площадки
        if ("pitch".equals(leisure) || "sports_centre".equals(leisure) || "stadium".equals(leisure)
                || "playground".equals(leisure) || "track".equals(leisure)) return true;

        // Парковки (зоны)
        if ("parking".equals(amenity)) return true;

        // Всё прочее — не берём (чтобы не ограждать «районы», residential и т.п.)
        return false;
    }

    // ======== КЛАССИФИКАЦИЯ СТИЛЕЙ ЗОН ========
    private FenceStyle classifyAreaStyle(JsonObject t) {
        // Материал по подстрокам в любых тегах
        boolean hasLattice  = containsAnyValue(t, v -> v.contains("lattice"));
        boolean hasBrickish = containsAnyValue(t, v -> v.contains("brick"));
        boolean hasGranite  = containsAnyValue(t, v -> v.contains("granite"));

        String landuse  = low(optString(t,"landuse"));
        String amenity  = low(optString(t,"amenity"));
        String leisure  = low(optString(t,"leisure"));
        String power    = low(optString(t,"power"));
        String military = low(optString(t,"military"));
        String aeroway  = low(optString(t,"aeroway"));
        String office   = low(optString(t,"office"));
        String embassy  = low(optString(t,"embassy"));
        String prison   = low(optString(t,"prison"));
        boolean hasHealthcareKey = t.has("healthcare");

        // Кладбища — строго по ТЗ: только тёмный дуб, h=1
        if ("cemetery".equals(landuse) || "grave_yard".equals(amenity)) {
            return new FenceStyle(DARK_OAK, 1, 1);
        }

        // Тяжёлые/режимные
        if ("industrial".equals(landuse) || "construction".equals(landuse) || "depot".equals(landuse)
                || "port".equals(landuse) || "harbour".equals(landuse)
                || "plant".equals(power) || "substation".equals(power) || "generator".equals(power)
                || military != null || "government".equals(office) || aeroway != null || embassy != null || prison != null) {
            Block base = randomOf(HEAVY_WALLS, t);
            if (hasGranite) base = GRANITE_WALL;
            else if (hasBrickish) base = BRICK_WALL;
            else if (hasLattice) base = IRON_LATTICE;
            FenceStyle f = new FenceStyle(base, 3, 3);
            return applyHeightOverrideFromTags(t, f);
        }

        // Соц. учреждения: садики/школы/вуз/медицина — min 2
        if ("kindergarten".equals(amenity) || "school".equals(amenity) || "college".equals(amenity) || "university".equals(amenity)
                || "clinic".equals(amenity) || "hospital".equals(amenity) || "doctors".equals(amenity)
                || hasHealthcareKey || "childcare".equals(amenity)) {
            Block base = randomOf(WOOD_FENCES, t);
            if (hasGranite) base = GRANITE_WALL;
            else if (hasBrickish) base = BRICK_WALL;
            else if (hasLattice) base = IRON_LATTICE;
            FenceStyle f = new FenceStyle(base, 2, 2);
            return applyHeightOverrideFromTags(t, f);
        }

        // Парки/сады/памятники/скверы — def 1
        if ("park".equals(leisure) || "garden".equals(leisure) || "nature_reserve".equals(leisure)
                || "common".equals(leisure) || "recreation_ground".equals(landuse)
                || "monument".equals(low(optString(t,"historic"))) || "memorial".equals(low(optString(t,"historic")))) {
            Block base = randomOf(WOOD_FENCES, t);
            if (hasGranite) base = GRANITE_WALL;
            else if (hasBrickish) base = BRICK_WALL;
            else if (hasLattice) base = IRON_LATTICE;
            FenceStyle f = new FenceStyle(base, 1, 1);
            return applyHeightOverrideFromTags(t, f);
        }

        // Спорт и детские площадки — def 1
        if ("pitch".equals(leisure) || "sports_centre".equals(leisure) || "stadium".equals(leisure)
                || "playground".equals(leisure) || "track".equals(leisure)) {
            Block base = randomOf(WOOD_FENCES, t);
            if (hasGranite) base = GRANITE_WALL;
            else if (hasBrickish) base = BRICK_WALL;
            else if (hasLattice) base = IRON_LATTICE;
            FenceStyle f = new FenceStyle(base, 1, 1);
            return applyHeightOverrideFromTags(t, f);
        }

        // Парковки (зоны) — andesite wall, h=1
        if ("parking".equals(amenity)) {
            FenceStyle f = new FenceStyle(ANDESITE_WALL, 1, 1);
            return applyHeightOverrideFromTags(t, f);
        }

        // По умолчанию — дубовый, h=1 (на случай «упомянута, но не распознана»)
        Block base = DEFAULT_WOOD;
        if (hasGranite) base = GRANITE_WALL;
        else if (hasBrickish) base = BRICK_WALL;
        else if (hasLattice) base = IRON_LATTICE;
        FenceStyle f = new FenceStyle(base, 1, 1);
        return applyHeightOverrideFromTags(t, f);
    }

    private FenceStyle applyHeightOverrideFromTags(JsonObject t, FenceStyle base) {
        Integer h = readHeightBlocks(t);
        if (h != null) {
            int hh = Math.max(base.minHeight, h);
            FenceStyle f = base.copy();
            f.defHeight = hh;                // дефолт становится «теговый или минимум, что выше»
            f.minHeight = Math.max(base.minHeight, 1);
            return f;
        }
        return base;
    }

    // ======== КЛАССИФИКАЦИЯ СТИЛЕЙ ЛИНЕЙНЫХ БАРЬЕРОВ ========
    private FenceStyle classifyBarrierStyle(JsonObject t) {
        String barrier = low(optString(t,"barrier"));
        boolean hasLattice  = containsAnyValue(t, v -> v.contains("lattice"));
        boolean hasBrickish = containsAnyValue(t, v -> v.contains("brick"));
        boolean hasGranite  = containsAnyValue(t, v -> v.contains("granite"));

        // guard_rail / jersey_barrier → andesite wall 
        if ("guard_rail".equals(barrier) || "jersey_barrier".equals(barrier)) {
            FenceStyle f = new FenceStyle(ANDESITE_WALL, 1, 1);
            return applyHeightOverrideFromTags(t, f);
        }

        // живая изгородь (barrier=hedge) — только flowering azalea leaves
        if ("hedge".equals(barrier)) {
            FenceStyle f = new FenceStyle(Blocks.FLOWERING_AZALEA_LEAVES, 1, 1);
            return applyHeightOverrideFromTags(t, f);
        }

        if ("wall".equals(barrier)) {
            Block b = BRICK_WALL;
            if (hasGranite) b = GRANITE_WALL;
            else if (!hasBrickish) b = randomOf(HEAVY_WALLS, t);
            return applyHeightOverrideFromTags(t, new FenceStyle(b, 1, 1));
        }

        if ("fence".equals(barrier)) {
            Block b = randomOf(WOOD_FENCES, t);
            if (hasGranite) b = GRANITE_WALL;
            else if (hasBrickish) b = BRICK_WALL;
            else if (hasLattice)  b = IRON_LATTICE;
            return applyHeightOverrideFromTags(t, new FenceStyle(b, 1, 1));
        }

        // Прочие барьеры — как дубовый h=1 с материалными подсказками
        Block b = DEFAULT_WOOD;
        if (hasGranite) b = GRANITE_WALL;
        else if (hasBrickish) b = BRICK_WALL;
        else if (hasLattice)  b = IRON_LATTICE;
        return applyHeightOverrideFromTags(t, new FenceStyle(b, 1, 1));
    }

    // ======== ПЕРИМЕТРЫ ========
    private void buildPerimeter(Ring r, FenceStyle style, int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        if (r == null || r.n < 2) return;
        for (int i=0;i<r.n;i++) {
            int j = (i+1) % r.n;
            drawFenceSegment(r.xs[i], r.zs[i], r.xs[j], r.zs[j], style, wMinX, wMaxX, wMinZ, wMaxZ);
        }
    }

    private void drawFenceSegment(int x0, int z0, int x1, int z1, FenceStyle style,
                                  int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        List<int[]> pts = bresenhamLine(x0, z0, x1, z1);
        if (pts.isEmpty()) return;

        Direction face = dirFromVector(x1 - x0, z1 - z0);

        for (int idx=0; idx<pts.size(); idx++) {
            int x = pts.get(idx)[0], z = pts.get(idx)[1];
            if (x < wMinX || x > wMaxX || z < wMinZ || z > wMaxZ) continue;

            // <<< ГЛАВНЫЙ ФИЛЬТР: НИЧЕГО НЕ СТАВИМ на мостах/в туннелях >>>
            if (noFenceAt(x, z)) continue;

            boolean isGate = nearPoint(x, z, gatePoints);
            boolean isGap  = nearPoint(x, z, gapPoints);

            if (isGap && !isGate) {
                // разрыв: пропустим текущую и следующую точки
                idx += 1;
                continue;
            }

            int height = Math.max(style.minHeight, style.defHeight);

            if (isGate) {
                placeGateColumn(x, z, face);
                if (idx+1 < pts.size()) {
                    int nx = pts.get(idx+1)[0], nz = pts.get(idx+1)[1];
                    placeGateColumn(nx, nz, face);
                    idx++;
                }
                continue;
            }

            placeFenceColumn(x, z, style.block, height);
        }
    }

    private void placeFenceColumn(int x, int z, Block block, int height) {
        int yBase = groundY(x, z);
        if (yBase == Integer.MIN_VALUE) return;
        for (int h = 1; h <= height; h++) {
            BlockState st = block.defaultBlockState();
            // Для листьев: делаем их «постоянными», чтобы не разлагались
            if (st.hasProperty(BlockStateProperties.PERSISTENT)) {
                st = st.setValue(BlockStateProperties.PERSISTENT, true);
            }
            if (st.hasProperty(BlockStateProperties.DISTANCE)) {
                st = st.setValue(BlockStateProperties.DISTANCE, 1);
            }
            level.setBlock(new BlockPos(x, yBase + h, z), st, 3);
        }
    }

    private void placeGateColumn(int x, int z, Direction facing) {
        int yBase = groundY(x, z);
        if (yBase == Integer.MIN_VALUE) return;
        BlockState st = OAK_GATE.defaultBlockState();
        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        level.setBlock(new BlockPos(x, yBase + 1, z), st, 3);
    }

    // ======== ЛИНЕЙНЫЕ БАРЬЕРЫ (barrier=*) ========
    private void buildBarrierLine(BarrierLine bl, FenceStyle style,
                                  int wMinX, int wMaxX, int wMinZ, int wMaxZ) {
        if (bl.pts.size() < 2) return;
        for (int i=0;i<bl.pts.size()-1;i++) {
            int[] a = bl.pts.get(i);
            int[] b = bl.pts.get(i+1);
            drawFenceSegment(a[0], a[1], b[0], b[1], style, wMinX, wMaxX, wMinZ, wMaxZ);
        }
    }

    // ======== ВХОДНЫЕ ФИЛЬТРЫ/ПРЕОБРАЗОВАНИЯ ========
    private static boolean isBarrierLine(JsonObject t) {
        return low(optString(t,"barrier")) != null;
    }

    private static boolean isPolygon(JsonObject e) {
        String type = optString(e,"type");
        if ("way".equals(type)) {
            JsonArray g = geometryArray(e);
            return g != null && g.size() >= 3 && firstEqualsLast(g);
        }
        if ("relation".equals(type)) {
            String rtype = low(optString(tagObj(e),"type"));
            return "multipolygon".equals(rtype);
        }
        return false;
    }

    private static boolean firstEqualsLast(JsonArray g) {
        if (g.size() < 2) return false;
        JsonObject a = g.get(0).getAsJsonObject();
        JsonObject b = g.get(g.size()-1).getAsJsonObject();
        return Math.abs(a.get("lat").getAsDouble() - b.get("lat").getAsDouble()) < 1e-9
            && Math.abs(a.get("lon").getAsDouble() - b.get("lon").getAsDouble()) < 1e-9;
    }

    private Area toArea(JsonObject e,
                        double centerLat, double centerLng,
                        double east, double west, double north, double south,
                        int sizeMeters, int centerX, int centerZ) {
        Area ar = new Area();
        String type = optString(e,"type");

        if ("way".equals(type)) {
            JsonArray g = geometryArray(e);
            if (g == null || g.size() < 3) return null;
            int n = g.size();
            int[] xs = new int[n], zs = new int[n];
            for (int i=0;i<n;i++){
                JsonObject p = g.get(i).getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                xs[i]=xz[0]; zs[i]=xz[1];
            }
            ar.outers.add(new Ring(xs, zs));
            return ar;
        }

        if ("relation".equals(type)) {
            JsonArray members = (e.has("members") && e.get("members").isJsonArray()) ? e.getAsJsonArray("members") : null;
            if (members == null || members.size()==0) {
                JsonArray g = geometryArray(e);
                if (g == null || g.size() < 3) return null;
                int n = g.size();
                int[] xs = new int[n], zs = new int[n];
                for (int i=0;i<n;i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                ar.outers.add(new Ring(xs, zs));
                return ar;
            }
            for (JsonElement mEl : members) {
                JsonObject m = mEl.getAsJsonObject();
                String role = low(optString(m,"role"));
                JsonArray g = geometryArray(m);
                if (g == null || g.size() < 3) continue;
                int n = g.size();
                int[] xs = new int[n], zs = new int[n];
                for (int i=0;i<n;i++){
                    JsonObject p = g.get(i).getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    xs[i]=xz[0]; zs[i]=xz[1];
                }
                if ("inner".equals(role)) ar.inners.add(new Ring(xs, zs)); else ar.outers.add(new Ring(xs, zs));
            }
            return ar.outers.isEmpty()? null : ar;
        }

        return null;
    }

    private BarrierLine toBarrierLine(JsonObject e,
                                      double centerLat, double centerLng,
                                      double east, double west, double north, double south,
                                      int sizeMeters, int centerX, int centerZ) {
        JsonArray g = geometryArray(e);
        if (g == null || g.size() < 2) return null;
        BarrierLine bl = new BarrierLine();
        bl.tags = tagObj(e);
        for (int i=0;i<g.size();i++){
            JsonObject p = g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            bl.pts.add(new int[]{xz[0], xz[1]});
        }
        return bl;
    }

    private void collectGateNode(JsonObject e,
                                 double centerLat, double centerLng,
                                 double east, double west, double north, double south,
                                 int sizeMeters, int centerX, int centerZ) {
        JsonObject t = tagObj(e);
        if (t == null) return;
        String barrier = low(optString(t,"barrier"));
        String entr    = low(optString(t,"entrance"));
        String amen    = low(optString(t,"amenity"));
        String gate    = low(optString(t,"gate"));
        String access  = low(optString(t,"access"));

        boolean isGate = "gate".equals(barrier) || "lift_gate".equals(barrier) || "swing_gate".equals(barrier)
                || "entrance".equals(entr) || "main".equals(entr) || "parking_entrance".equals(amen)
                || (gate != null) || "yes".equals(entr);

        boolean isGap  = "yes".equals(access) || "delivery".equals(access) || "private".equals(access);

        if (isGate || isGap) {
            double lat = e.get("lat").getAsDouble();
            double lon = e.get("lon").getAsDouble();
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (isGate) gatePoints.add(new GatePoint(xz[0], xz[1]));
            if (isGap)  gapPoints.add(new GatePoint(xz[0], xz[1]));
        }
    }

    // ======== ВСПОМОГАТЕЛЬНОЕ ========
    private boolean nearPoint(int x, int z, List<GatePoint> list) {
        for (GatePoint gp : list) {
            if (Math.abs(gp.x - x) <= 1 && Math.abs(gp.z - z) <= 1) return true;
        }
        return false;
    }

    private Direction dirFromVector(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return (dx >= 0) ? Direction.EAST : Direction.WEST;
        return (dz >= 0) ? Direction.SOUTH : Direction.NORTH;
    }

    private Block randomOf(Block[] arr, JsonObject tags) {
        int hash = Objects.hash(tags != null ? tags.toString() : "x");
        return arr[Math.floorMod(hash, arr.length)];
    }

    private static Integer readHeightBlocks(JsonObject t) {
        // читаем height / fence:height / wall:height / любые *height*
        String[] keys = new String[]{"height","fence:height","wall:height","height:meters","height:height"};
        for (String k : keys) {
            String v = optString(t, k);
            if (v == null) continue;
            Integer cm = parseFirstInt(v);
            if (cm != null && cm > 0) return (int)Math.ceil(cm);
        }
        for (Map.Entry<String, JsonElement> e : t.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (k.toLowerCase(Locale.ROOT).contains("height")) {
                String v = optString(t, k);
                Integer cm = parseFirstInt(v);
                if (cm != null && cm > 0) return (int)Math.ceil(cm);
            }
        }
        return null;
    }

    private static Integer parseFirstInt(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT).replace(',','.');
        StringBuilder num = new StringBuilder();
        boolean dotSeen=false;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) num.append(c);
            else if (c=='.' && !dotSeen) { num.append('.'); dotSeen=true; }
        }
        if (num.length()==0) return null;
        try {
            double d = Double.parseDouble(num.toString());
            return (int)Math.ceil(d);
        } catch (Exception ignore) { return null; }
    }

    private static boolean containsAnyValue(JsonObject t, Predicate<String> pred) {
        try {
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                if (e.getValue()==null || e.getValue().isJsonNull()) continue;
                String v = e.getValue().getAsString().toLowerCase(Locale.ROOT);
                if (pred.test(v)) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static boolean hasAnyKey(JsonObject t, Predicate<String> pred) {
        try {
            for (String k : t.keySet()) {
                if (pred.test(k.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

private boolean truthy(String v){
    if (v == null) return false;
    v = v.trim().toLowerCase(Locale.ROOT);
    return !v.isEmpty() && !"no".equals(v);
}

/** Является ли объект мостом/туннелем ИЛИ дорогой на мосту/в туннеле */
private boolean isBridgeOrTunnel(JsonObject t){
    if (t == null) return false;
    String highway = low(optString(t,"highway"));
    String railway = low(optString(t,"railway"));
    String bridge  = low(optString(t,"bridge"));
    String tunnel  = low(optString(t,"tunnel"));
    String manmade = low(optString(t,"man_made"));
    String rtype   = low(optString(t,"type")); // relation type=bridge

    boolean onWay   = (highway != null || railway != null);
    boolean isBridge = truthy(bridge) || "viaduct".equals(bridge) || "aqueduct".equals(bridge) || "boardwalk".equals(bridge)
                       || "bridge".equals(manmade) || "bridge".equals(rtype);
    boolean isTunnel = truthy(tunnel) || "culvert".equals(tunnel) || "building_passage".equals(tunnel);

    // «дорога на мосту/в туннеле» ИЛИ само место помечено как мост/туннель
    if (isBridge && (onWay || "bridge".equals(manmade) || "bridge".equals(rtype))) return true;
    if (isTunnel && (onWay || "tunnel".equals(rtype))) return true;
    return false;
}

    /** Пометить клетки вдоль геометрии как «запрещённые для ограждений» + буфер по радиусу */
    private void markNoFenceAlongGeometry(JsonObject e,
                                        double centerLat, double centerLng,
                                        double east, double west, double north, double south,
                                        int sizeMeters, int centerX, int centerZ) {
        JsonArray g = geometryArray(e);
        if (g != null && g.size() >= 2) {
            int[] prev = null;
            for (JsonElement pe : g) {
                JsonObject p = pe.getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                if (prev != null) {
                    for (int[] q : bresenhamLine(prev[0], prev[1], xz[0], xz[1])) {
                        stampNoFence(q[0], q[1], NOFENCE_BUFFER);
                    }
                }
                prev = xz;
            }
        }
        // На случай relation без top-level geometry — пройдёмся по members
        if ("relation".equals(optString(e,"type")) && e.has("members") && e.get("members").isJsonArray()) {
            for (JsonElement mEl : e.getAsJsonArray("members")) {
                JsonObject m = mEl.getAsJsonObject();
                JsonArray mg = geometryArray(m);
                if (mg == null || mg.size() < 2) continue;
                int[] prev = null;
                for (JsonElement pe : mg) {
                    JsonObject p = pe.getAsJsonObject();
                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    if (prev != null) {
                        for (int[] q : bresenhamLine(prev[0], prev[1], xz[0], xz[1])) {
                            stampNoFence(q[0], q[1], NOFENCE_BUFFER);
                        }
                    }
                    prev = xz;
                }
            }
        }
    }

    private void stampNoFence(int x, int z, int r){
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                noFenceMask.add(BlockPos.asLong(x + dx, 0, z + dz));
            }
        }
    }

    private boolean noFenceAt(int x, int z){
        return noFenceMask.contains(BlockPos.asLong(x, 0, z));
    }

    @SuppressWarnings("unused")
    // ======== Низкоуровневые сеттеры и рельеф ========
    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x,y,z), b.defaultBlockState(), 3);
    }

    private int groundY(int x, int z) {
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

    // ======== Утилиты гео/OSM ========
    private static JsonObject tagObj(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }
    private static boolean hasGeometry(JsonObject e) {
        return e.has("geometry") && e.get("geometry").isJsonArray() && e.getAsJsonArray("geometry").size() >= 2;
    }
    private static JsonArray geometryArray(JsonObject e) {
        return (e.has("geometry") && e.get("geometry").isJsonArray()) ? e.getAsJsonArray("geometry") : null;
    }
    private static JsonArray safeElementsArray(JsonObject coords) {
        if (coords == null) return null;
        if (!coords.has("features")) return null;
        JsonObject f = coords.getAsJsonObject("features");
        if (f == null || !f.has("elements")) return null;
        return f.getAsJsonArray("elements");
    }
    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
    private static String low(String s) { return s==null? null : s.toLowerCase(Locale.ROOT); }

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