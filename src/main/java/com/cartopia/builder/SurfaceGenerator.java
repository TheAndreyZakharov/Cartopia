package com.cartopia.builder;

import com.cartopia.spawn.CartopiaSurfaceSpawn;
import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.Raster;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;

public class SurfaceGenerator {

    // Регистрируем TIFF-плагин и проверяем доступность
    static {
        try {
            ImageIO.scanForPlugins();
            boolean tiff = ImageIO.getImageReadersByFormatName("tiff").hasNext();
            boolean tif  = ImageIO.getImageReadersByFormatName("tif").hasNext();
            System.out.println("[Cartopia] TIFF readers present? tiff=" + tiff + ", tif=" + tif);
        } catch (Throwable t) {
            System.err.println("[Cartopia] SPI scan failed: " + t);
        }
    }

    // --- параметры генерации
    private static final int Y_BASE = -60;          // базовая отметка для нормализации DEM
    private static final int MAX_HEIGHT_DIFF = 3;
    private static final int HEIGHT_BLUR_ITERS = 20;
    private static final int SURFACE_BLUR_ITERS = 8;
    private static final int SURFACE_BLUR_MIN_MAJORITY = 4;

    private final ServerLevel level;
    private final JsonObject coordsJson;
    private final File demFile;
    private final File landcoverFileOrNull; // GeoTIFF OLM (классы покрытия)

    // Новое: доступ к NDJSON и совместимой сетке
    private final GenerationStore store; // может быть null

    // уменьшаем аллокации
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

    public SurfaceGenerator(ServerLevel level, JsonObject coordsJson, File demFile, File landcoverFileOrNull) {
        this(level, coordsJson, demFile, landcoverFileOrNull, null);
    }
    public SurfaceGenerator(ServerLevel level, JsonObject coordsJson, File demFile, File landcoverFileOrNull, GenerationStore store) {
        this.level = level;
        this.coordsJson = coordsJson;
        this.demFile = demFile;
        this.landcoverFileOrNull = landcoverFileOrNull;
        this.store = store;
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

    /** маппинг OSM-тегов -> блок Minecraft */
    private static final Map<String, String> ZONE_MATERIALS = new HashMap<>();
    static {
        // Вода
        ZONE_MATERIALS.put("natural=water","water");
        ZONE_MATERIALS.put("water=river","water");
        ZONE_MATERIALS.put("water=lake","water");
        ZONE_MATERIALS.put("water=pond","water");
        ZONE_MATERIALS.put("water=reservoir","water");
        ZONE_MATERIALS.put("landuse=reservoir","water");
        ZONE_MATERIALS.put("waterway=riverbank","water");

        // Природные покрытия
        ZONE_MATERIALS.put("natural=sand","sandstone");
        ZONE_MATERIALS.put("natural=beach","sandstone");
        ZONE_MATERIALS.put("natural=bare_rock","stone");
        ZONE_MATERIALS.put("natural=grassland","moss_block");
        ZONE_MATERIALS.put("natural=wood","moss_block");
        ZONE_MATERIALS.put("natural=desert","sandstone");
        ZONE_MATERIALS.put("natural=jungle","moss_block");
        ZONE_MATERIALS.put("natural=swamp","muddy_mangrove_roots");
        ZONE_MATERIALS.put("natural=savanna","red_sandstone");
        ZONE_MATERIALS.put("natural=snow","snow_block");

        // Землепользование / рекреация
        ZONE_MATERIALS.put("landuse=forest","moss_block");
        ZONE_MATERIALS.put("landuse=residential","moss_block");
        ZONE_MATERIALS.put("landuse=industrial","stone");
        ZONE_MATERIALS.put("landuse=farmland","farmland");
        ZONE_MATERIALS.put("landuse=meadow","moss_block");
        ZONE_MATERIALS.put("landuse=quarry","stone");
        ZONE_MATERIALS.put("landuse=recreation_ground","moss_block");
        ZONE_MATERIALS.put("leisure=park","moss_block");
        ZONE_MATERIALS.put("leisure=playground","moss_block");
        ZONE_MATERIALS.put("leisure=sports_centre","moss_block");
        ZONE_MATERIALS.put("leisure=stadium","moss_block");
        ZONE_MATERIALS.put("leisure=pitch","moss_block");
        ZONE_MATERIALS.put("leisure=garden","moss_block");

        // Объекты
        ZONE_MATERIALS.put("amenity=school","moss_block");
        ZONE_MATERIALS.put("amenity=kindergarten","moss_block");
        ZONE_MATERIALS.put("amenity=hospital","stone");
        ZONE_MATERIALS.put("amenity=university","moss_block");
        ZONE_MATERIALS.put("amenity=college","moss_block");
        ZONE_MATERIALS.put("landuse=cemetery","moss_block");
        ZONE_MATERIALS.put("amenity=prison","stone");
        ZONE_MATERIALS.put("amenity=fire_station","moss_block");
        ZONE_MATERIALS.put("amenity=police","moss_block");
        ZONE_MATERIALS.put("amenity=sports_centre","moss_block");
        ZONE_MATERIALS.put("amenity=parking","stone");
        ZONE_MATERIALS.put("amenity=grave_yard","moss_block");
        ZONE_MATERIALS.put("amenity=marketplace","moss_block");

        // Транспорт / платформы
        ZONE_MATERIALS.put("aeroway=apron","stone");
        ZONE_MATERIALS.put("aeroway=runway","gray_concrete");
        ZONE_MATERIALS.put("aeroway=taxiway","gray_concrete");
        ZONE_MATERIALS.put("aeroway=helipad","gray_concrete");
        ZONE_MATERIALS.put("aeroway=runway_link","gray_concrete");
        ZONE_MATERIALS.put("aeroway=taxilane","gray_concrete");
        ZONE_MATERIALS.put("railway=platform","stone");
        ZONE_MATERIALS.put("public_transport=platform","stone");

        // Прочая инфраструктура
        ZONE_MATERIALS.put("man_made=pier","stone");
        ZONE_MATERIALS.put("man_made=breakwater","stone");
        ZONE_MATERIALS.put("power=substation","stone");

        // --- Wetlands / болота и пр. ---
        ZONE_MATERIALS.put("natural=wetland",     "muddy_mangrove_roots");
        ZONE_MATERIALS.put("natural=marsh",       "muddy_mangrove_roots");
        ZONE_MATERIALS.put("natural=bog",         "muddy_mangrove_roots");
        ZONE_MATERIALS.put("natural=fen",         "muddy_mangrove_roots");
        ZONE_MATERIALS.put("natural=tidalflat",   "muddy_mangrove_roots"); //можно вернуть sandstone
        // на всякий случай (редкие теги)
        ZONE_MATERIALS.put("landuse=wetland",     "muddy_mangrove_roots");
    }

    /** OLM: класс → блок */
    private static final Map<Integer, String> LANDCOVER_CLASS_TO_BLOCK = new HashMap<>();
    static {
        // см. старый Python-словарь
        LANDCOVER_CLASS_TO_BLOCK.put(210, "water");              // Water body
        LANDCOVER_CLASS_TO_BLOCK.put(220, "snow_block");         // Permanent ice and snow
        LANDCOVER_CLASS_TO_BLOCK.put(200, "sandstone");          // Bare areas
        LANDCOVER_CLASS_TO_BLOCK.put(201, "moss_block");         // Consolidated bare areas
        LANDCOVER_CLASS_TO_BLOCK.put(202, "sandstone");          // Unconsolidated bare areas
        LANDCOVER_CLASS_TO_BLOCK.put(130, "moss_block");         // Grassland
        LANDCOVER_CLASS_TO_BLOCK.put(120, "moss_block");         // Shrubland
        LANDCOVER_CLASS_TO_BLOCK.put(121, "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(122, "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(10,  "moss_block");         // Rainfed cropland
        LANDCOVER_CLASS_TO_BLOCK.put(11,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(12,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(20,  "moss_block");         // Irrigated cropland
        LANDCOVER_CLASS_TO_BLOCK.put(51,  "moss_block");         // Open evergreen broadleaved forest
        LANDCOVER_CLASS_TO_BLOCK.put(52,  "moss_block");         // Closed evergreen broadleaved forest
        LANDCOVER_CLASS_TO_BLOCK.put(61,  "moss_block");         // Open deciduous broadleaved forest
        LANDCOVER_CLASS_TO_BLOCK.put(62,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(71,  "moss_block");         // Open evergreen needle-leaved forest
        LANDCOVER_CLASS_TO_BLOCK.put(72,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(81,  "moss_block");         // Open deciduous needle-leaved forest
        LANDCOVER_CLASS_TO_BLOCK.put(82,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(91,  "moss_block");         // Open mixed leaf forest
        LANDCOVER_CLASS_TO_BLOCK.put(92,  "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(150, "moss_block");         // Sparse vegetation
        LANDCOVER_CLASS_TO_BLOCK.put(152, "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(153, "moss_block");
        LANDCOVER_CLASS_TO_BLOCK.put(181, "muddy_mangrove_roots"); // Swamp
        LANDCOVER_CLASS_TO_BLOCK.put(182, "muddy_mangrove_roots"); // Marsh
        LANDCOVER_CLASS_TO_BLOCK.put(183, "muddy_mangrove_roots"); // Flooded flat
        LANDCOVER_CLASS_TO_BLOCK.put(184, "sandstone");          // Saline
        LANDCOVER_CLASS_TO_BLOCK.put(185, "muddy_mangrove_roots");       // Mangrove
        LANDCOVER_CLASS_TO_BLOCK.put(186, "muddy_mangrove_roots"); // Salt marsh
        LANDCOVER_CLASS_TO_BLOCK.put(187, "sandstone");          // Tidal flat
        LANDCOVER_CLASS_TO_BLOCK.put(190, "moss_block");         // Impervious (города)
        LANDCOVER_CLASS_TO_BLOCK.put(140, "snow_block");         // Lichens and mosses
        LANDCOVER_CLASS_TO_BLOCK.put(0,   "water");              // no-data → вода
        LANDCOVER_CLASS_TO_BLOCK.put(250, "water");
        LANDCOVER_CLASS_TO_BLOCK.put(255, "water");
    }

    // ====== Генерация ======
    public void generate() throws Exception {
        final JsonObject center = coordsJson.getAsJsonObject("center");
        final double centerLat = center.get("lat").getAsDouble();
        final double centerLng = center.get("lng").getAsDouble();
        final int sizeMeters = coordsJson.get("sizeMeters").getAsInt();

        final JsonObject bbox = coordsJson.getAsJsonObject("bbox");
        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coordsJson.has("player") ? (int)Math.round(coordsJson.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coordsJson.has("player") ? (int)Math.round(coordsJson.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // Материалы по умолчанию + опциональный уровень моря (метры высоты из DEM)
        String defaultBlock = "moss_block";
        double seaLevelMeters = Double.NEGATIVE_INFINITY; // если отсутствует — игнорим
        if (coordsJson.has("materials") && coordsJson.get("materials").isJsonObject()) {
            JsonObject mats = coordsJson.getAsJsonObject("materials");
            if (mats.has("default")) defaultBlock = mats.get("default").getAsString();
            if (mats.has("seaLevel")) seaLevelMeters = mats.get("seaLevel").getAsDouble();
        }

        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int minX = Math.min(a[0], b[0]);
        int maxX = Math.max(a[0], b[0]);
        int minZ = Math.min(a[1], b[1]);
        int maxZ = Math.max(a[1], b[1]);

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        int totalCells = Math.max(1, width * height);

        broadcast(level, String.format("Область %dx%d блоков (minX=%d, maxX=%d, minZ=%d, maxZ=%d)", width, height, minX, maxX, minZ, maxZ));
        broadcast(level, "Читаю DEM…");

        if (demFile == null || !demFile.exists() || demFile.length() == 0) {
            throw new IllegalStateException("DEM не найден или пуст: " + String.valueOf(demFile));
        }

        // ===== DEM → карта высот (метры), затем нормализация в Y мира
        Map<Long, Double> heightMap = new HashMap<>(totalCells);
        double minElevation = Double.POSITIVE_INFINITY;

        try (HeightSampler dem = new HeightSampler(demFile, west, east, south, north)) {
            for (int x=minX; x<=maxX; x++) {
                for (int z=minZ; z<=maxZ; z++) {
                    double[] ll = blockToLatLng(x, z, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    double elev = dem.sampleByLatLon(ll[0], ll[1]);
                    if (Double.isNaN(elev)) elev = 0.0;
                    heightMap.put(key(x,z), elev);
                    if (elev < minElevation) minElevation = elev;
                }
            }
        }

        fillMissingHeights(heightMap, minX, maxX, minZ, maxZ);

        Map<Long, Integer> terrainY = new HashMap<>(heightMap.size());
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                double elev = heightMap.getOrDefault(key(x,z), 0.0);
                int y = Y_BASE + (int)Math.round(elev - minElevation);
                terrainY.put(key(x,z), y);
            }
        }

        // Ограничение уклонов + лесенка + сглаживание
        limitSlope(terrainY, minX, maxX, minZ, maxZ, MAX_HEIGHT_DIFF);
        staircase(terrainY, minX, maxX, minZ, maxZ);
        blurHeight(terrainY, minX, maxX, minZ, maxZ, HEIGHT_BLUR_ITERS);

        // СТРИЖЕМ мелкие бугорки/ямки (окно 5×5 => radius=2)
        despeckleHeights(terrainY, minX, maxX, minZ, maxZ, 2);

        // ---------- БАЗОВАЯ ПОВЕРХНОСТЬ ----------
        Map<Long, String> surface = new HashMap<>(terrainY.size());
        for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) surface.put(key(x,z), defaultBlock);

        // --- маски источников воды
        Set<Long> waterFromOLM = new HashSet<>();
        Set<Long> waterProtected = new HashSet<>(); // OSM и морской уровень — запрещено менять и учитывать в сглаживании

        // Клетки суши, пришедшие из OSM, которые менять нельзя
        Set<Long> lockedOSM = new HashSet<>();

        // Все клетки, покрашенные именно из OLM (и вода, и суша)
        Set<Long> olmAll = new HashSet<>();

        // Море/вода по уровню (если задан): это «подложка»
        if (seaLevelMeters != Double.NEGATIVE_INFINITY) {
            for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
                long k = key(x,z);
                double elev = heightMap.get(k);
                if (elev <= seaLevelMeters) {
                    surface.put(k, "water");
                    waterProtected.add(k); // морская вода — не сглаживаем и не учитываем
                }
            }
        }

        // ---------- OLM (landcover): локальный файл ИЛИ онлайн ----------
        File lcFile = (landcoverFileOrNull != null && landcoverFileOrNull.exists() && landcoverFileOrNull.length() > 0)
                ? landcoverFileOrNull
                : downloadOLMIfConfigured(coordsJson, south, west, north, east);

        if (lcFile != null && lcFile.exists() && lcFile.length() > 0) {
            broadcast(level, "Читаю OpenLandMap landcover…");

            // Границы растрового файла:
            double lcovW = -180.0, lcovE = 180.0, lcovS = -90.0, lcovN = 90.0;
            if (coordsJson.has("landcoverBounds") && coordsJson.get("landcoverBounds").isJsonObject()) {
                JsonObject lb = coordsJson.getAsJsonObject("landcoverBounds");
                if (lb.has("west"))  lcovW = lb.get("west").getAsDouble();
                if (lb.has("east"))  lcovE = lb.get("east").getAsDouble();
                if (lb.has("south")) lcovS = lb.get("south").getAsDouble();
                if (lb.has("north")) lcovN = lb.get("north").getAsDouble();
            } else if (lcFile != landcoverFileOrNull) {
                lcovW = west; lcovE = east; lcovS = south; lcovN = north;
            }

            try (LandcoverSampler lcov = new LandcoverSampler(lcFile, lcovW, lcovE, lcovS, lcovN)) {
                for (int x=minX; x<=maxX; x++) {
                    for (int z=minZ; z<=maxZ; z++) {
                        double[] ll = blockToLatLng(x, z, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        int cls = lcov.sampleClassByLatLon(ll[0], ll[1]);

                        // вне растра/нет данных → вода
                        String block = (cls == Integer.MIN_VALUE) ? "water" : blockForLandcoverClass(cls);
                        if (block == null) block = "moss_block";

                        long k = key(x,z);
                        if ("water".equals(block)) {
                            surface.put(k, "water");              // вода из OLM
                            olmAll.add(k);                         // помечаем как OLM
                            if (!waterProtected.contains(k)) {
                                waterFromOLM.add(k);              // это именно OLM-вода (разрешим её менять)
                            }
                        } else if (!"water".equals(surface.get(k))) {
                            surface.put(k, block);                 // суша из OLM
                            olmAll.add(k);
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("[Cartopia] OLM read failed: " + ex);
            }
        } else {
            System.out.println("[Cartopia] OLM landcover отсутствует (и онлайн не настроен) — пропускаю.");
        }

        // ---------- OSM зоны: читаем из NDJSON если можем, иначе из coords.features ----------
        List<ZonePoly> zones = new ArrayList<>();
        if (store != null) zones.addAll(extractZonesFromOSMStream(store));
        // Фолбэк как в старом коде: дополняем зонами из coordsJson
        List<ZonePoly> z2 = extractZonesFromOSM(coordsJson);
        if (!z2.isEmpty()) zones.addAll(z2);

        System.out.println("[Cartopia] ОSM зон для покраски: " + zones.size());

        // Дырки (inners) у НЕводных площадей — будем уважать их при покраске «прочих зон»
        List<ZonePoly> nonWaterHoles = new ArrayList<>();
        for (ZonePoly zp : zones) {
            if (zp.isHole && !"water".equals(zp.material)) {
                nonWaterHoles.add(zp);
            }
        }

        // Сначала — вода (outer минус inner), затем прочее.
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                RectLL cell = cellRectLatLon(x, z, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                long k = key(x,z);

                // Кандидат на воду из OSM (outer без дырки). ПОКА не пишем, даём шанс неводным OSM-площадям перекрыть воду.
                boolean inWaterOuter = false, inWaterHole = false;
                for (ZonePoly zp : zones) {
                    if (!"water".equals(zp.material) || zp.isHole) continue;
                    if (!rectsOverlap(cell.minLat, cell.maxLat, cell.minLon, cell.maxLon, zp.minLat, zp.maxLat, zp.minLon, zp.maxLon)) continue;
                    if (rectIntersectsPolygon(cell, zp.lats, zp.lons)) { inWaterOuter = true; break; }
                }
                if (inWaterOuter) {
                    for (ZonePoly zp : zones) {
                        if (!"water".equals(zp.material) || !zp.isHole) continue;
                        if (!rectsOverlap(cell.minLat, cell.maxLat, cell.minLon, cell.maxLon, zp.minLat, zp.maxLat, zp.minLon, zp.maxLon)) continue;
                        if (rectIntersectsPolygon(cell, zp.lats, zp.lons)) { inWaterHole = true; break; }
                    }
                }
                boolean candidateOSMWater = inWaterOuter && !inWaterHole;

                // Попадает ли клетка в ЛЮБУЮ inner-дырку НЕводной площади (например, пруд как inner в парке)?
                boolean inNonWaterHole = false;
                for (ZonePoly hole : nonWaterHoles) {
                    if (!rectsOverlap(cell.minLat, cell.maxLat, cell.minLon, cell.maxLon,
                                    hole.minLat, hole.maxLat, hole.minLon, hole.maxLon)) continue;
                    if (rectIntersectsPolygon(cell, hole.lats, hole.lons)) { inNonWaterHole = true; break; }
                }

                // Прочие зоны: не затираем воду
                String bestMat = null;
                double bestArea = Double.POSITIVE_INFINITY;

                for (ZonePoly zp : zones) {
                    if ("water".equals(zp.material) || zp.isHole) continue;
                    if (!rectsOverlap(cell.minLat, cell.maxLat, cell.minLon, cell.maxLon, zp.minLat, zp.maxLat, zp.minLon, zp.maxLon)) continue;
                    if (rectIntersectsPolygon(cell, zp.lats, zp.lons)) {
                        double area = (zp.maxLat - zp.minLat) * (zp.maxLon - zp.minLon);
                        if (area < bestArea) {
                            bestArea = area;
                            bestMat  = zp.material;
                        }
                    }
                }

                // Новое правило: если есть НЕводная OSM-площадь, она перекрывает ЛЮБУЮ воду (OLM, OSM и подложку по seaLevel).
                if (bestMat != null && !inNonWaterHole) {
                    surface.put(k, bestMat);
                    lockedOSM.add(k);
                    // если раньше клетка была водой любого происхождения — снимаем «водные» метки
                    waterFromOLM.remove(k);
                    waterProtected.remove(k);
                } else if (candidateOSMWater) {
                    // не нашлось неводной OSM-площади — тогда ставим OSM-воду и защищаем её
                    surface.put(k, "water");
                    waterProtected.add(k);
                    waterFromOLM.remove(k);
                }
            }
        }

        // Параметры сглаживания
        int surfaceBlurIters = SURFACE_BLUR_ITERS;
        int surfaceBlurMinMajority = SURFACE_BLUR_MIN_MAJORITY;
        boolean surfaceBlurIncludeWater = true;

        if (coordsJson.has("tuning") && coordsJson.get("tuning").isJsonObject()) {
            JsonObject t = coordsJson.getAsJsonObject("tuning");
            if (t.has("surfaceBlurIters")) surfaceBlurIters = Math.max(0, t.get("surfaceBlurIters").getAsInt());
            if (t.has("surfaceBlurMinMajority")) surfaceBlurMinMajority = Math.max(1, t.get("surfaceBlurMinMajority").getAsInt());
            if (t.has("surfaceBlurIncludeWater")) surfaceBlurIncludeWater = t.get("surfaceBlurIncludeWater").getAsBoolean();
        }

        // --- Скругление границ OLM-зон (и воды, и суши), OSM/моря не трогаем
        int olmFeatherRadius    = 5;
        int olmFeatherIters     = 10;
        int olmFeatherMinVotes  = 2;

        if (coordsJson.has("tuning") && coordsJson.get("tuning").isJsonObject()) {
            JsonObject t = coordsJson.getAsJsonObject("tuning");
            if (t.has("olmFeatherRadius"))   olmFeatherRadius   = Math.max(1, t.get("olmFeatherRadius").getAsInt());
            if (t.has("olmFeatherIters"))    olmFeatherIters    = Math.max(1, t.get("olmFeatherIters").getAsInt());
            if (t.has("olmFeatherMinVotes")) olmFeatherMinVotes = Math.max(1, t.get("olmFeatherMinVotes").getAsInt());
        }

        featherOlmZones(surface, minX, maxX, minZ, maxZ,
                olmFeatherRadius, olmFeatherIters, olmFeatherMinVotes,
                olmAll, waterFromOLM,
                waterProtected, lockedOSM);

        blurSurface(surface, minX, maxX, minZ, maxZ,
                surfaceBlurIters, surfaceBlurMinMajority,
                /*includeWater=*/surfaceBlurIncludeWater,
                waterProtected, waterFromOLM,
                lockedOSM);

        // Маска воды
        Set<Long> waterMask = new HashSet<>();
        for (Map.Entry<Long,String> e : surface.entrySet())
            if ("water".equals(e.getValue())) waterMask.add(e.getKey());

        // === НОВОЕ: клетки обрывов/укреплений из OSM (через стрим, если есть)
        Set<Long> cliffCapCells = (store != null)
                ? extractCliffCellsFromOSMStream(store,
                        centerLat, centerLng, east, west, north, south,
                        sizeMeters, centerX, centerZ,
                        minX, maxX, minZ, maxZ)
                : extractCliffCellsFromOSM(
                        coordsJson,
                        centerLat, centerLng, east, west, north, south,
                        sizeMeters, centerX, centerZ,
                        minX, maxX, minZ, maxZ
                );

        broadcast(level, String.format("Размещение блоков (%d клеток)…", totalCells));

        // уровни воды
        Map<Long, Integer> waterSurfaceY = computeWaterSurfaceY(surface, terrainY, minX, maxX, minZ, maxZ);

        // === экспорт/сохранение сетки рельефа — черновик (v1) для совместимости на ранних этапах
        publishTerrainGrid(coordsJson, terrainY, minX, maxX, minZ, maxZ);
        File areaDir = detectAreaPackDir(demFile);
        if (areaDir != null) {
            try {
                File out = new File(areaDir, "terrain-grid.json");
                JsonObject tg = coordsJson.getAsJsonObject("terrainGrid");
                if (tg != null) {
                    Files.writeString(out.toPath(), tg.toString(), StandardCharsets.UTF_8);
                    broadcast(level, "Сетка рельефа сохранена (черновик): " + out.getAbsolutePath());
                }
            } catch (Exception e) {
                System.err.println("[Cartopia] Не удалось сохранить terrain-grid.json: " + e);
            }
        }

        // ВЫЗОВ placeBlocks с новым параметром
        placeBlocks(surface, terrainY, waterMask, waterSurfaceY, cliffCapCells, minX, maxX, minZ, maxZ, totalCells);
        broadcast(level, "Размещение блоков завершено.");

        // === FINAL JSON (v2) + дублируем groundY как data для обратной совместимости ===
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight();

        JsonObject fin = new JsonObject();
        fin.addProperty("version", 2);
        fin.addProperty("minX", minX);
        fin.addProperty("minZ", minZ);
        fin.addProperty("width", width);
        fin.addProperty("height", height);
        fin.addProperty("order", "row-major(Z,X)");
        fin.addProperty("worldMin", worldMin);
        fin.addProperty("worldMax", worldMax);

        // компактные "гриды" для быстрого доступа по индексу (z,x)
        JsonArray groundYGrid = new JsonArray();
        JsonArray topYGrid    = new JsonArray();
        JsonArray waterYGrid  = new JsonArray();
        JsonArray topBlockGrid= new JsonArray();

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long k = key(x, z);
                int yTop0 = terrainY.getOrDefault(k, Y_BASE);
                if (yTop0 <= worldMin + 2) yTop0 = worldMin + 3;
                if (yTop0 >= worldMax - 2) yTop0 = worldMax - 3;

                String mat0 = surface.getOrDefault(k, "moss_block");

                if ("water".equals(mat0) || waterMask.contains(k)) {
                    int yWaterSurface = (waterSurfaceY != null && waterSurfaceY.containsKey(k))
                            ? waterSurfaceY.get(k)
                            : (yTop0 - 1);
                    groundYGrid.add(yWaterSurface - 1);       // lapis
                    topYGrid.add(yWaterSurface);
                    waterYGrid.add(yWaterSurface);
                    topBlockGrid.add("minecraft:water");
                } else {
                    boolean isCliff0 = (cliffCapCells != null && cliffCapCells.contains(k));
                    groundYGrid.add(yTop0);
                    topYGrid.add(isCliff0 ? (yTop0 + 1) : yTop0);
                    waterYGrid.add(com.google.gson.JsonNull.INSTANCE);
                    topBlockGrid.add(isCliff0 ? "minecraft:cracked_stone_bricks" : ("minecraft:" + mat0));
                }
            }
        }

        JsonObject grids = new JsonObject();
        grids.add("groundY", groundYGrid);
        grids.add("topY",    topYGrid);
        grids.add("waterY",  waterYGrid);
        grids.add("topBlock",topBlockGrid);
        fin.add("grids", grids);

        // Дублируем groundY как "data" (старый формат grid v1)
        fin.add("data", groundYGrid.deepCopy());

        coordsJson.add("terrainGrid", fin);
        try {
            File areaDir2 = detectAreaPackDir(demFile);
            if (areaDir2 != null) {
                File outFile = new File(areaDir2, "terrain-grid.json");
                Files.writeString(outFile.toPath(), fin.toString(), StandardCharsets.UTF_8);
                broadcast(level, "Финальный рельеф сохранён (v2 + data): " + outFile.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[Cartopia] Не удалось сохранить финальный terrain-grid.json: " + e);
        }
    }

    // ===== helpers =====
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

    private static double[] blockToLatLng(int x, int z,
                                          double centerLat, double centerLng,
                                          double east, double west, double north, double south,
                                          int sizeMeters, int centerX, int centerZ) {
        double dx = (x - centerX) / (double)sizeMeters * (east - west);
        double dz = (z - centerZ) / (double)sizeMeters * (south - north);
        double lng = centerLng + dx;
        double lat = centerLat + dz;
        return new double[]{lat, lng};
    }

    // double-версия для полуцелых координат клетки
    private static double[] blockToLatLngD(double x, double z,
                                           double centerLat, double centerLng,
                                           double east, double west, double north, double south,
                                           int sizeMeters, int centerX, int centerZ) {
        double dx = (x - centerX) / (double)sizeMeters * (east - west);
        double dz = (z - centerZ) / (double)sizeMeters * (south - north);
        double lng = centerLng + dx;
        double lat = centerLat + dz;
        return new double[]{lat, lng};
    }

    private static void fillMissingHeights(Map<Long, Double> hm, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double v = hm.getOrDefault(key(x, z), Double.NaN);
                if (!Double.isNaN(v)) continue;

                List<Double> neigh = new ArrayList<>(4);
                double v1 = hm.getOrDefault(key(x + 1, z), Double.NaN);
                double v2 = hm.getOrDefault(key(x - 1, z), Double.NaN);
                double v3 = hm.getOrDefault(key(x, z + 1), Double.NaN);
                double v4 = hm.getOrDefault(key(x, z - 1), Double.NaN);
                if (!Double.isNaN(v1)) neigh.add(v1);
                if (!Double.isNaN(v2)) neigh.add(v2);
                if (!Double.isNaN(v3)) neigh.add(v3);
                if (!Double.isNaN(v4)) neigh.add(v4);

                double fill = neigh.isEmpty()
                        ? 0.0
                        : neigh.stream().mapToDouble(d -> d).average().orElse(0.0);

                hm.put(key(x, z), fill);
            }
        }
    }

    // ===== DEM sampler (устойчив к маленьким TIFF, с fallback) =====
    private static final class HeightSampler implements AutoCloseable {
        private final int width, height;
        private final float[] data; // row-major
        private final double west, east, south, north;

        HeightSampler(File demTif, double west, double east, double south, double north) throws IOException {
            this.west = west; this.east = east; this.south = south; this.north = north;

            System.out.println("[Cartopia] DEM: " + demTif.getAbsolutePath() + " (" + demTif.length() + " bytes)");
            ImageIO.scanForPlugins();

            try (ImageInputStream iis = ImageIO.createImageInputStream(new FileInputStream(demTif))) {
                if (iis == null) throw new IOException("Cannot open DEM: " + demTif);

                // Выбираем TIFF-ридер (предпочтительно JAI)
                ImageReader reader = null;
                Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
                while (it.hasNext()) {
                    ImageReader cand = it.next();
                    String cn = cand.getClass().getName();
                    if (cn.contains("jai") || cn.contains("JAI") || cn.contains("com.github.jai-imageio")) {
                        reader = cand; break;
                    }
                    if (reader == null) reader = cand;
                }
                if (reader == null) throw new IOException("No ImageIO reader for TIFF");

                reader.setInput(iis, true, true);

                this.width  = reader.getWidth(0);
                this.height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("DEM has zero size (" + width + "x" + height + ")");
                }

                Raster ras;
                try {
                    ras = reader.readRaster(0, null);
                } catch (UnsupportedOperationException uoe) {
                    System.out.println("[Cartopia] readRaster() unsupported by " + reader.getClass().getName() + ", fallback to read().getRaster()");
                    ras = reader.read(0).getRaster();
                }

                float[] vals = new float[width * height];
                ras.getSamples(0, 0, width, height, 0, vals);
                this.data = vals;

                System.out.println("[Cartopia] DEM loaded: " + width + "x" + height +
                        ", bbox W:" + west + " E:" + east + " S:" + south + " N:" + north +
                        ", reader=" + reader.getClass().getSimpleName());
                reader.dispose();
            }
        }

        float sampleByLatLon(double lat, double lon) {
            double px = (lon - west)  / (east - west)   * width;
            double py = (north - lat) / (north - south) * height;

            int x = (int)Math.floor(px);
            int y = (int)Math.floor(py);

            if (x < 0) x = 0; else if (x >= width)  x = width  - 1;
            if (y < 0) y = 0; else if (y >= height) y = height - 1;

            return data[y * width + x];
        }

        @Override public void close() {}
    }

    private static File downloadOLMIfConfigured(JsonObject coordsJson, double south, double west, double north, double east) {
        try {
            if (!coordsJson.has("olm") || !coordsJson.get("olm").isJsonObject()) return null;
            JsonObject olm = coordsJson.getAsJsonObject("olm");
            String mode = optString(olm, "mode");
            if (!"wcs".equalsIgnoreCase(mode)) {
                System.out.println("[Cartopia] OLM present, but mode != wcs — skipping online download.");
                return null;
            }

            // базовые параметры
            final String baseUrl = optString(olm, "baseUrl");
            final String altBaseUrl = optString(olm, "altBaseUrl"); // опциональный запасной
            final String coverage = optString(olm, "coverage");
            if (baseUrl == null || coverage == null) {
                System.out.println("[Cartopia] OLM WCS config incomplete (need baseUrl + coverage).");
                return null;
            }
            int width  = olm.has("width")  ? Math.max(64, olm.get("width").getAsInt())  : 1024;
            int height = olm.has("height") ? Math.max(64, olm.get("height").getAsInt()) : 1024;

            boolean wantNearest = false;
            if (olm.has("interpolation")) {
                try {
                    wantNearest = "nearest".equalsIgnoreCase(olm.get("interpolation").getAsString());
                } catch (Exception ignore) {}
            }

            // формируем кандидаты запросов: WCS 1.0.0 и WCS 2.0.1, для обоих baseUrl (если alt задан)
            List<String> bases = new ArrayList<>();
            bases.add(baseUrl);
            if (altBaseUrl != null && !altBaseUrl.isBlank()) bases.add(altBaseUrl);

            String bbox = west + "," + south + "," + east + "," + north;

            List<String> urls = new ArrayList<>();
            for (String b : bases) {
                // WCS 1.0.0
                urls.add(b + (b.contains("?") ? "&" : "?")
                    + "SERVICE=WCS&VERSION=1.0.0&REQUEST=GetCoverage"
                    + "&COVERAGE=" + URLEncoder.encode(coverage, StandardCharsets.UTF_8)
                    + "&CRS=EPSG:4326"
                    + "&BBOX=" + URLEncoder.encode(bbox, StandardCharsets.UTF_8)
                    + "&WIDTH=" + width
                    + "&HEIGHT=" + height
                    + (wantNearest ? "&INTERPOLATION=nearest" : "")
                    + "&FORMAT=image/tiff");

                // WCS 2.0.1
                urls.add(b + (b.contains("?") ? "&" : "?")
                    + "SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage"
                    + "&COVERAGEID=" + URLEncoder.encode(coverage, StandardCharsets.UTF_8)
                    + "&SUBSET=Long(" + west + "," + east + ")"
                    + "&SUBSET=Lat(" + south + "," + north + ")"
                    + "&SCALESIZE=Long(" + width + "),Lat(" + height + ")"
                    + (wantNearest ? "&INTERPOLATION=nearest" : "")
                    + "&FORMAT=image/tiff");
            }

            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

            byte[] data = null;
            int attempt = 0;

            // обходим все варианты с простыми ретраями
            for (String url : urls) {
                for (int retry = 0; retry < 3 && data == null; retry++) {
                    attempt++;
                    System.out.println("[Cartopia] OLM WCS try " + attempt + ": " + url);
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "image/tiff, application/octet-stream")
                        .header("User-Agent", "Cartopia/1.0")
                        .timeout(Duration.ofSeconds(240))
                        .GET()
                        .build();
                    try {
                        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                        int sc = res.statusCode();
                        if (sc >= 200 && sc < 300 && res.body() != null && res.body().length > 0) {
                            data = res.body();
                            break;
                        } else {
                            System.out.println("[Cartopia] OLM HTTP " + sc + ", bytes=" + (res.body() != null ? res.body().length : 0));
                        }
                    } catch (Exception e) {
                        System.out.println("[Cartopia] OLM request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    try { Thread.sleep(1000L * (retry + 1)); } catch (InterruptedException ignored) {}
                }
                if (data != null) break;
            }

            if (data == null) {
                System.out.println("[Cartopia] OLM download failed after all attempts.");
                return null;
            }

            // сохраняем временный GeoTIFF
            File tmp = File.createTempFile("cartopia_olm_", ".tif");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(data);
            }

            // сообщаем генератору реальные границы этого фрагмента
            JsonObject lb = new JsonObject();
            lb.addProperty("west",  west);
            lb.addProperty("east",  east);
            lb.addProperty("south", south);
            lb.addProperty("north", north);
            coordsJson.add("landcoverBounds", lb);

            System.out.println("[Cartopia] OLM saved to " + tmp.getAbsolutePath() + " (" + tmp.length() + " bytes)");
            return tmp;

        } catch (Exception e) {
            System.out.println("[Cartopia] OLM download failed: " + e);
            return null;
        }
    }


    // ===== OLM sampler (границы файла, не участка!) =====
    private static final class LandcoverSampler implements AutoCloseable {
        private final int width, height;
        private final int[] data; // row-major
        private final double fWest, fEast, fSouth, fNorth;

        LandcoverSampler(File tif, double fileWest, double fileEast, double fileSouth, double fileNorth) throws IOException {
            this.fWest = fileWest; this.fEast = fileEast; this.fSouth = fileSouth; this.fNorth = fileNorth;

            System.out.println("[Cartopia] Landcover: " + tif.getAbsolutePath() + " (" + tif.length() + " bytes)");
            ImageIO.scanForPlugins();

            try (ImageInputStream iis = ImageIO.createImageInputStream(new FileInputStream(tif))) {
                if (iis == null) throw new IOException("Cannot open Landcover: " + tif);

                ImageReader reader = null;
                Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
                while (it.hasNext()) {
                    ImageReader cand = it.next();
                    String cn = cand.getClass().getName();
                    if (cn.contains("jai") || cn.contains("JAI") || cn.contains("com.github.jai-imageio")) { reader = cand; break; }
                    if (reader == null) reader = cand;
                }
                if (reader == null) throw new IOException("No ImageIO reader for TIFF");

                reader.setInput(iis, true, true);
                this.width  = reader.getWidth(0);
                this.height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new IOException("Landcover has zero size");

                Raster ras;
                try { ras = reader.readRaster(0, null); }
                catch (UnsupportedOperationException uoe) {
                    System.out.println("[Cartopia] LC readRaster() unsupported by " + reader.getClass().getName() + ", fallback to read().getRaster()");
                    ras = reader.read(0).getRaster();
                }

                int numBands = ras.getNumBands();
                if (numBands != 1) {
                    reader.dispose();
                    throw new IOException("Landcover TIFF must be single-band with class codes, got " + numBands + " bands. " +
                            "Most likely you requested WMS styled raster. Switch to WCS (GetCoverage) as in app.js.");
                }

                int[] vals = new int[width * height];
                ras.getSamples(0, 0, width, height, 0, vals);
                this.data = vals;

                System.out.println("[Cartopia] Landcover loaded: " + width + "x" + height +
                                   " | file bounds W:" + fWest + " E:" + fEast + " S:" + fSouth + " N:" + fNorth +
                                   " | reader=" + reader.getClass().getSimpleName());
                reader.dispose();
            }
        }

        int sampleClassByLatLon(double lat, double lon) {
            double px = (lon - fWest)  / (fEast - fWest)   * width;
            double py = (fNorth - lat) / (fNorth - fSouth) * height;

            int x = (int)Math.floor(px);
            int y = (int)Math.floor(py);

            if (x < 0) x = 0; else if (x >= width)  x = width  - 1;
            if (y < 0) y = 0; else if (y >= height) y = height - 1;

            return data[y * width + x];
        }

        @Override public void close() {}
    }

    private static String blockForLandcoverClass(Integer cls) {
        if (cls == null || cls == Integer.MIN_VALUE) return null;
        return LANDCOVER_CLASS_TO_BLOCK.getOrDefault(cls, "moss_block");
    }

    // ===== Поверхность: сглаживание и перо OLM =====
    private static void blurSurface(Map<Long,String> srf,
                                    int minX, int maxX, int minZ, int maxZ,
                                    int iters, int minMajority, boolean includeWater,
                                    Set<Long> protectedWater, Set<Long> olmWater,
                                    Set<Long> lockedOSM) {
        Map<Long,String> cur = new HashMap<>(srf);
        Map<Long,String> next = new HashMap<>(srf.size());

        for (int it=0; it<iters; it++) {
            next.clear();
            for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
                long k = key(x,z);
                String curVal = cur.get(k);
                if (curVal == null) { next.put(k, curVal); continue; }

                // Воду (sea/OSM) и лоченую OSM-сушу не меняем
                if (protectedWater.contains(k) || lockedOSM.contains(k)) {
                    next.put(k, curVal);
                    continue;
                }

                // Считаем голоса соседей
                long[] nks = new long[] {
                    key(x+1,z), key(x-1,z),
                    key(x,z+1), key(x,z-1),
                    key(x+1,z+1), key(x-1,z-1),
                    key(x+1,z-1), key(x-1,z+1)
                };
                Map<String,Integer> cnt = new HashMap<>();
                for (long nk : nks) {
                    String t = cur.get(nk);
                    if (t == null) continue;

                    // соседи: не учитываем воду и лоченую OSM-сушу
                    if (protectedWater.contains(nk) || lockedOSM.contains(nk)) continue;
                    if ("water".equals(t) && !includeWater) continue;

                    cnt.merge(t, 1, Integer::sum);
                }

                if (cnt.isEmpty()) { next.put(k, curVal); continue; }

                Map.Entry<String,Integer> best = cnt.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).get();

                // Меняем только фон (не вода) при явном большинстве
                if (!best.getKey().equals(curVal) && best.getValue() >= minMajority) {
                    if (!"water".equals(curVal)) next.put(k, best.getKey());
                    else next.put(k, curVal); // вода остаётся водой
                } else {
                    next.put(k, curVal);
                }
            }
            Map<Long,String> tmp = cur; cur = next; next = tmp;
        }
        srf.clear(); srf.putAll(cur);
    }

    /**
     * Скругляет границы тайловых зон OLM в кольце шириной radius.
     * Меняем ТОЛЬКО те клетки, что пришли из OLM (и воду, и сушу).
     * Клетки из OSM и «морская» вода (waterProtected) не меняются и не голосуют.
     */
    private static void featherOlmZones(Map<Long,String> srf,
                                        int minX, int maxX, int minZ, int maxZ,
                                        int radius, int iters, int minVotes,
                                        Set<Long> olmAll, Set<Long> olmWater,
                                        Set<Long> protectedWater, Set<Long> lockedOSM) {

        // 1) Находим «границы» между разными материалами ВНУТРИ OLM
        HashSet<Long> ring = new HashSet<>();
        for (long k : olmAll) {
            if (lockedOSM.contains(k)) continue;                         // на всякий
            if (protectedWater.contains(k) && !olmWater.contains(k)) continue; // чужая вода — не трогаем

            int x = (int)(k >> 32);
            int z = (int)k;
            String m0 = srf.get(k);
            if (m0 == null) continue;

            boolean isEdge = false;
            for (int dx=-1; dx<=1 && !isEdge; dx++) {
                for (int dz=-1; dz<=1 && !isEdge; dz++) {
                    if (dx==0 && dz==0) continue;
                    long nk = key(x+dx, z+dz);
                    if (!olmAll.contains(nk)) continue;                  // интересует только OLM↔OLM
                    String m1 = srf.get(nk);
                    if (m1 != null && !m1.equals(m0)) isEdge = true;
                }
            }
            if (!isEdge) continue;

            // расширяем в «кольцо» шириной radius, но всё равно только по OLM-клеткам
            for (int dx=-radius; dx<=radius; dx++) {
                for (int dz=-radius; dz<=radius; dz++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx<minX || nx>maxX || nz<minZ || nz>maxZ) continue;
                    long nk = key(nx, nz);
                    if (!olmAll.contains(nk)) continue;
                    if (lockedOSM.contains(nk)) continue;
                    if (protectedWater.contains(nk) && !olmWater.contains(nk)) continue;
                    ring.add(nk);
                }
            }
        }

        // 2) Несколько итераций мягкого majority-голосования внутри кольца
        Map<Long,String> cur = new HashMap<>(srf);
        for (int it=0; it<iters; it++) {
            Map<Long,String> next = new HashMap<>(cur);
            for (long k : ring) {
                if (lockedOSM.contains(k)) continue;
                if (protectedWater.contains(k) && !olmWater.contains(k)) continue;

                String curVal = cur.get(k);
                if (curVal == null) continue;

                int x = (int)(k >> 32), z = (int)k;

                Map<String,Integer> votes = new HashMap<>();
                for (int dx=-1; dx<=1; dx++) {
                    for (int dz=-1; dz<=1; dz++) {
                        if (dx==0 && dz==0) continue;
                        int nx = x+dx, nz = z+dz;
                        if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;

                        long nk = key(nx, nz);
                        if (!olmAll.contains(nk)) continue;
                        if (lockedOSM.contains(nk)) continue;
                        if (protectedWater.contains(nk) && !olmWater.contains(nk)) continue;

                        String mv = cur.get(nk);
                        if (mv != null) votes.merge(mv, 1, Integer::sum);
                    }
                }

                if (votes.isEmpty()) continue;

                Map.Entry<String,Integer> best = votes.entrySet()
                        .stream().max(Map.Entry.comparingByValue()).get();

                if (!best.getKey().equals(curVal) && best.getValue() >= minVotes) {
                    next.put(k, best.getKey());
                }
            }
            cur = next;
        }

        srf.clear();
        srf.putAll(cur);
    }

    private static void limitSlope(Map<Long,Integer> h, int minX, int maxX, int minZ, int maxZ, int maxDiff) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
                int y = h.getOrDefault(key(x,z), Y_BASE);
                int[][] n = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : n) {
                    int nx=x+d[0], nz=z+d[1];
                    if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                    int ny = h.getOrDefault(key(nx,nz), y);
                    if (Math.abs(y-ny) > maxDiff) {
                        if (y>ny) { h.put(key(x,z), ny+maxDiff); changed=true; }
                        else { h.put(key(nx,nz), y+maxDiff); changed=true; }
                    }
                }
            }
        }
    }

    private static void staircase(Map<Long,Integer> h, int minX, int maxX, int minZ, int maxZ) {
        List<int[]> offsets = new ArrayList<>();
        for (int d=1; d<=3; d++) {
            offsets.add(new int[]{-d,0}); offsets.add(new int[]{d,0});
            offsets.add(new int[]{0,-d}); offsets.add(new int[]{0,d});
        }
        boolean changed = true;
        while (changed) {
            changed=false;
            for (int x=minX; x<=maxX; x++) for (int z=maxZ; z>=minZ; z--) {
                int y = h.getOrDefault(key(x,z), Y_BASE);
                for (int[] d : offsets) {
                    int nx=x+d[0], nz=z+d[1];
                    if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                    int ny = h.getOrDefault(key(nx,nz), y);
                    int dist = Math.max(Math.abs(d[0]), Math.abs(d[1]));
                    if (Math.abs(y-ny) > 1) {
                        for (int i=1; i<dist; i++) {
                            int mx = x + d[0]/dist * i;
                            int mz = z + d[1]/dist * i;
                            int avg = (y*(dist-i) + ny*i) / dist;
                            int cur = h.getOrDefault(key(mx,mz), avg);
                            if (Math.abs(cur-avg) > 1) { h.put(key(mx,mz), avg); changed = true; }
                        }
                        if (y-ny > 1) { h.put(key(x,z), ny+1); changed=true; }
                        else if (ny-y > 1) { h.put(key(nx,nz), y+1); changed=true; }
                    }
                }
            }
        }
    }

    private static void blurHeight(Map<Long,Integer> h, int minX, int maxX, int minZ, int maxZ, int iters) {
        Map<Long,Integer> cur = new HashMap<>(h);
        Map<Long,Integer> next = new HashMap<>(h.size());
        for (int it=0; it<iters; it++) {
            next.clear();
            for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
                int y = cur.get(key(x,z));
                int[] ns = {
                    cur.getOrDefault(key(x+1,z), y), cur.getOrDefault(key(x-1,z), y),
                    cur.getOrDefault(key(x,z+1), y), cur.getOrDefault(key(x,z-1), y),
                    cur.getOrDefault(key(x+1,z+1), y), cur.getOrDefault(key(x-1,z-1), y),
                    cur.getOrDefault(key(x+1,z-1), y), cur.getOrDefault(key(x-1,z+1), y),
                };
                Arrays.sort(ns);
                int med = ns[ns.length/2];
                next.put(key(x,z), (med + y)/2);
            }
            Map<Long,Integer> tmp = cur; cur = next; next = tmp;
        }
        h.clear(); h.putAll(cur);
    }

    // === Удаляем бугорки/ямки меньше 5×5 ===
    private static void despeckleHeights(Map<Long,Integer> h, int minX, int maxX, int minZ, int maxZ, int radius) {
        Map<Long,Integer> tmp = grayscaleOpen(h, minX, maxX, minZ, maxZ, radius);
        tmp = grayscaleClose(tmp, minX, maxX, minZ, maxZ, radius);
        h.clear(); h.putAll(tmp);
        fixSingleCellSpikes(h, minX, maxX, minZ, maxZ);
    }

    private static Map<Long,Integer> grayscaleOpen(Map<Long,Integer> src, int minX, int maxX, int minZ, int maxZ, int r) {
        return grayscaleDilate(grayscaleErode(src, minX, maxX, minZ, maxZ, r), minX, maxX, minZ, maxZ, r);
    }
    private static Map<Long,Integer> grayscaleClose(Map<Long,Integer> src, int minX, int maxX, int minZ, int maxZ, int r) {
        return grayscaleErode(grayscaleDilate(src, minX, maxX, minZ, maxZ, r), minX, maxX, minZ, maxZ, r);
    }
    private static Map<Long,Integer> grayscaleErode(Map<Long,Integer> src, int minX, int maxX, int minZ, int maxZ, int r) {
        Map<Long,Integer> out = new HashMap<>(src.size());
        for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
            int m = Integer.MAX_VALUE;
            int center = src.get(key(x,z));
            for (int dx=-r; dx<=r; dx++) for (int dz=-r; dz<=r; dz++) {
                int nx=x+dx, nz=z+dz; if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                int v = src.getOrDefault(key(nx,nz), center);
                if (v<m) m=v;
            }
            out.put(key(x,z), m);
        }
        return out;
    }
    private static Map<Long,Integer> grayscaleDilate(Map<Long,Integer> src, int minX, int maxX, int minZ, int maxZ, int r) {
        Map<Long,Integer> out = new HashMap<>(src.size());
        for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
            int M = Integer.MIN_VALUE;
            int center = src.get(key(x,z));
            for (int dx=-r; dx<=r; dx++) for (int dz=-r; dz<=r; dz++) {
                int nx=x+dx, nz=z+dz; if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                int v = src.getOrDefault(key(nx,nz), center);
                if (v>M) M=v;
            }
            out.put(key(x,z), M);
        }
        return out;
    }
    private static void fixSingleCellSpikes(Map<Long,Integer> h, int minX, int maxX, int minZ, int maxZ) {
        for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
            long k = key(x,z);
            int y = h.get(k);
            int n1 = h.getOrDefault(key(x+1,z), y);
            int n2 = h.getOrDefault(key(x-1,z), y);
            int n3 = h.getOrDefault(key(x,z+1), y);
            int n4 = h.getOrDefault(key(x,z-1), y);
            if (Math.abs(n1-n2)<=1 && Math.abs(n1-n3)<=1 && Math.abs(n1-n4)<=1) {
                int avg = Math.round((n1+n2+n3+n4)/4f);
                if (Math.abs(y-avg) <= 2) h.put(k, avg);
            }
        }
    }

    private void placeBlocks(Map<Long,String> surface, Map<Long,Integer> terrain,
                             Set<Long> waterMask, Map<Long,Integer> waterSurfaceY,
                             Set<Long> cliffCapCells,
                             int minX, int maxX, int minZ, int maxZ, int totalCells) {
        int done = 0;
        int nextConsole = 5;
        int[] chatMilestones = {25, 50, 75, 100};
        int chatIdx = 0;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int yTop = terrain.getOrDefault(key(x, z), Y_BASE);
                if (yTop <= worldMin + 2) yTop = worldMin + 3;
                if (yTop >= worldMax - 2) yTop = worldMax - 3;

                String mat = surface.getOrDefault(key(x, z), "moss_block");

                if ("water".equals(mat) || waterMask.contains(key(x, z))) {
                    // вода
                    int yWaterSurface = (waterSurfaceY != null && waterSurfaceY.containsKey(key(x,z)))
                            ? waterSurfaceY.get(key(x,z))
                            : (yTop - 1);

                    int yLapis = yWaterSurface - 1;
                    int yAirTop = yWaterSurface + 1;

                    clearColumnBelow(x, z, worldMin, yLapis - 1);
                    setBlock(x, yLapis,         z, "minecraft:lapis_block");
                    setBlock(x, yWaterSurface,  z, "minecraft:water");
                    setBlock(x, yAirTop,        z, "minecraft:air");
                    clearColumnAbove(x, yAirTop + 1, z, worldMax);
                } else {
                    // суша
                    setBlock(x, yTop, z, "minecraft:" + mat);

                    boolean isCliff = (cliffCapCells != null && cliffCapCells.contains(key(x, z)));
                    if (isCliff) {
                        setBlock(x, yTop + 1, z, "minecraft:cracked_stone_bricks");
                        clearColumnAbove(x, yTop + 2, z, worldMax);
                    } else {
                        clearColumnAbove(x, yTop + 1, z, worldMax);
                    }

                    clearColumnBelow(x, z, worldMin, yTop - 1);
                }

                done++;
                int pct = (int)((long)done * 100 / totalCells);
                if (pct >= nextConsole) {
                    System.out.println("[Cartopia] placing blocks: " + pct + "%");
                    nextConsole += 5;
                }
                if (chatIdx < chatMilestones.length && pct >= chatMilestones[chatIdx]) {
                    broadcast(level, "Прогресс генерации: " + chatMilestones[chatIdx] + "%");
                    CartopiaSurfaceSpawn.adjustAllPlayersAsync(level);
                    chatIdx++;
                }
            }
        }
    }

    private void setBlock(int x, int y, int z, String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        if (b == null) b = Blocks.MOSS_BLOCK;
        level.setBlock(mpos.set(x,y,z), b.defaultBlockState(), 3);
    }
    private void clearColumnAbove(int x, int fromY, int z, int toYInclusive) {
        int maxY = Math.min(level.getMaxBuildHeight(), toYInclusive);
        for (int y=fromY; y<=maxY; y++) level.setBlock(mpos.set(x,y,z), Blocks.AIR.defaultBlockState(), 3);
    }
    private void clearColumnBelow(int x, int z, int fromY, int toYInclusive) {
        int minY = Math.max(level.getMinBuildHeight(), fromY);
        for (int y=minY; y<=toYInclusive; y++) level.setBlock(mpos.set(x,y,z), Blocks.AIR.defaultBlockState(), 3);
    }

    private static long key(int x, int z) { return (((long)x)<<32) ^ (z & 0xffffffffL); }

    // =================== OSM parsing ===================

    private static final class ZonePoly {
        final double[] lats, lons;
        final String material;
        final boolean isHole; // для inner-колец воды
        @SuppressWarnings("unused")
        final String tagKV;   // исходный тег key=value
        final double minLat, maxLat, minLon, maxLon;

        ZonePoly(double[] lats, double[] lons, String material, boolean isHole, String tagKV) {
            this.lats = lats; this.lons = lons; this.material = material; this.isHole = isHole; this.tagKV = tagKV;
            double minLa=Double.POSITIVE_INFINITY, maxLa=Double.NEGATIVE_INFINITY;
            double minLo=Double.POSITIVE_INFINITY, maxLo=Double.NEGATIVE_INFINITY;
            for (double v : lats) { if (v<minLa) minLa=v; if (v>maxLa) maxLa=v; }
            for (double v : lons) { if (v<minLo) minLo=v; if (v>maxLo) maxLo=v; }
            this.minLat=minLa; this.maxLat=maxLa; this.minLon=minLo; this.maxLon=maxLo;
        }
    }

    private static boolean isClosed(JsonArray geom) {
        if (geom == null || geom.size() < 4) return false;
        JsonObject a = geom.get(0).getAsJsonObject();
        JsonObject b = geom.get(geom.size()-1).getAsJsonObject();
        double la = a.get("lat").getAsDouble(), lo = a.get("lon").getAsDouble();
        double lb = b.get("lat").getAsDouble(), lob = b.get("lon").getAsDouble();
        return Math.abs(la - lb) < 1e-6 && Math.abs(lo - lob) < 1e-6;
    }

    private static boolean isAreaByTags(JsonObject tags) {
        if ("yes".equals(optString(tags, "area"))) return true;
        String[] areaKeys = { "natural","landuse","leisure","amenity","building","water",
        "landcover","military","aeroway","man_made","power","boundary","place","waterway" };
        for (String k: areaKeys) if (tags.has(k)) return true;
        return false;
    }

    private static boolean isLinearWater(JsonObject tags) {
        String w = optString(tags, "waterway");
        if (w == null) return false;
        return w.equals("river") || w.equals("stream") || w.equals("canal")
                || w.equals("drain") || w.equals("ditch") || w.equals("tidal_channel");
    }

    private static List<double[][]> stitchSegmentsToRings(List<double[][]> segs) {
        final double EPSP = 1e-7;
        List<List<double[]>> work = new ArrayList<>();
        for (double[][] seg : segs) {
            int n = seg[0].length;
            List<double[]> poly = new ArrayList<>(n);
            for (int i=0;i<n;i++) poly.add(new double[]{ seg[0][i], seg[1][i] });
            work.add(poly);
        }
        List<double[][]> rings = new ArrayList<>();
        boolean changed = true;
        while (changed && !work.isEmpty()) {
            changed = false;
            for (int i=0; i<work.size(); i++) {
                List<double[]> a = work.get(i);
                if (a == null) continue;
                double[] aStart = a.get(0), aEnd = a.get(a.size()-1);
                boolean closed = Math.abs(aStart[0]-aEnd[0])<EPSP && Math.abs(aStart[1]-aEnd[1])<EPSP;
                if (closed && a.size() >= 4) {
                    double[] lats = new double[a.size()], lons = new double[a.size()];
                    for (int k=0;k<a.size();k++) { lats[k]=a.get(k)[0]; lons[k]=a.get(k)[1]; }
                    rings.add(new double[][]{ lats, lons });
                    work.set(i, null);
                    changed = true;
                    continue;
                }
                for (int j=0; j<work.size(); j++) if (i!=j && work.get(j)!=null) {
                    List<double[]> b = work.get(j);
                    double[] bStart = b.get(0), bEnd = b.get(b.size()-1);
                    if (Math.abs(aEnd[0]-bStart[0])<EPSP && Math.abs(aEnd[1]-bStart[1])<EPSP) {
                        a.addAll(b.subList(1, b.size()));
                        work.set(j, null); changed = true; break;
                    } else if (Math.abs(aEnd[0]-bEnd[0])<EPSP && Math.abs(aEnd[1]-bEnd[1])<EPSP) {
                        Collections.reverse(b);
                        a.addAll(b.subList(1, b.size()));
                        work.set(j, null); changed = true; break;
                    } else if (Math.abs(aStart[0]-bEnd[0])<EPSP && Math.abs(aStart[1]-bEnd[1])<EPSP) {
                        List<double[]> merged = new ArrayList<>(b);
                        merged.addAll(a.subList(1, a.size()));
                        work.set(i, merged); work.set(j, null); changed = true; break;
                    } else if (Math.abs(aStart[0]-bStart[0])<EPSP && Math.abs(aStart[1]-bStart[1])<EPSP) {
                        Collections.reverse(b);
                        List<double[]> merged = new ArrayList<>(b);
                        merged.addAll(a.subList(1, a.size()));
                        work.set(i, merged); work.set(j, null); changed = true; break;
                    }
                }
            }
            work.removeIf(Objects::isNull);
        }
        return rings;
    }

    private static class MatMatch { final String material; final String tagKV; MatMatch(String m, String k){material=m; tagKV=k;} }

    private static MatMatch materialAndKeyForTags(JsonObject tags) {
        if (tags == null) return null;

        // вода — как было
        if ("riverbank".equals(optString(tags, "waterway"))) return new MatMatch("water", "waterway=riverbank");
        if ("water".equals(optString(tags, "natural")))      return new MatMatch("water", "natural=water");
        if ("reservoir".equals(optString(tags, "landuse")))  return new MatMatch("water", "landuse=reservoir");
        if (tags.has("water"))                                return new MatMatch("water", "water=*");

        // --- NEW: любые wetlands считаем болотом (грязные мангровые корни)
        String nat = optString(tags, "natural");
        if ("wetland".equals(nat)) {
            return new MatMatch("muddy_mangrove_roots", "natural=wetland");
        }
        String wet = optString(tags, "wetland");
        if (wet != null) {
            String w = wet.toLowerCase(Locale.ROOT).replace('-', '_');
            switch (w) {
                case "marsh": case "swamp": case "bog": case "fen":
                case "mire": case "reedbed": case "wet_meadow":
                case "saltmarsh": case "salt_marsh": case "string_bog":
                case "mangrove": // хотим тоже в muddy roots
                case "tidalflat": // если предпочитаешь песок: верни "sandstone"
                    return new MatMatch("muddy_mangrove_roots", "wetland=" + wet);
            }
            // по умолчанию любые неизвестные подтипы — тоже болото
            return new MatMatch("muddy_mangrove_roots", "wetland=" + wet);
        }

        // дальше — как было: точные сопоставления key=value
        for (Map.Entry<String,String> m : ZONE_MATERIALS.entrySet()) {
            String kv = m.getKey();
            int k = kv.indexOf('=');
            if (k <= 0) continue;
            String key = kv.substring(0, k), val = kv.substring(k+1);
            String tv = optString(tags, key);
            if (val.equals(tv)) return new MatMatch(m.getValue(), kv);
        }
        return null;
    }

    // --- Вариант через NDJSON-стрим ---
    private static List<ZonePoly> extractZonesFromOSMStream(GenerationStore store) {
        List<ZonePoly> out = new ArrayList<>();
        if (store == null) return out;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject e : fs) {
                String type = optString(e, "type");
                if (type == null) continue;

                JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
                MatMatch mm = materialAndKeyForTags(tags);
                String mat = mm == null ? null : mm.material;
                String tagKV = mm == null ? null : mm.tagKV;
                JsonArray geom = e.has("geometry") && e.get("geometry").isJsonArray() ? e.getAsJsonArray("geometry") : null;

                if ("relation".equals(type) && e.has("members") && e.get("members").isJsonArray()) {
                    boolean treatAsArea = (mat != null) || isAreaByTags(tags);
                    // NEW: считать мультиполигон реки как площадь, даже если теги только waterway=riverbank на relation
                    if (!treatAsArea) {
                        String ww = optString(tags, "waterway");
                        String w  = optString(tags, "water");
                        if ("riverbank".equals(ww) || "river".equals(w)) {
                            treatAsArea = true;
                            if (mat == null) {
                                mat = "water";
                                tagKV = (ww != null ? "waterway=" + ww : "water=" + w);
                            }
                        }
                    }

                    if (!treatAsArea) continue;

                    List<double[][]> outerSegs = new ArrayList<>();
                    List<double[][]> innerSegs = new ArrayList<>();

                    for (JsonElement memEl : e.getAsJsonArray("members")) {
                        JsonObject mem = memEl.getAsJsonObject();
                        if (!"way".equals(optString(mem, "type"))) continue;
                        String role = optString(mem, "role");
                        JsonArray mGeom = mem.has("geometry") && mem.get("geometry").isJsonArray()
                                ? mem.getAsJsonArray("geometry") : null;
                        if (mGeom == null || mGeom.size() < 2) continue;

                        int n = mGeom.size();
                        double[] la = new double[n], lo = new double[n];
                        for (int i=0;i<n;i++){ JsonObject p=mGeom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                        ("inner".equals(role) ? innerSegs : outerSegs).add(new double[][]{ la, lo });
                    }

                    List<double[][]> outers = stitchSegmentsToRings(outerSegs);
                    List<double[][]> inners = stitchSegmentsToRings(innerSegs);

                    boolean isWaterArea = "water".equals(mat)
                            || "riverbank".equals(optString(tags, "waterway"))
                            || "river".equals(optString(tags, "water"));

                    for (double[][] r : outers) {
                        if (isWaterArea) out.add(new ZonePoly(r[0], r[1], "water", false, tagKV));
                        else if (mat != null) out.add(new ZonePoly(r[0], r[1], mat, false, tagKV));
                    }

                    if (!isWaterArea) {
                        for (double[][] h : inners) {
                            // помечаем как "дырку" у любой неводной площади
                            out.add(new ZonePoly(h[0], h[1], "void", true, tagKV));
                        }
                    } else {
                        // как было для водных relation — inner как water-дыры
                        for (double[][] h : inners) {
                            out.add(new ZonePoly(h[0], h[1], "water", true, tagKV));
                        }
                    }

                    // Fallback: если замкнутых колец не получилось (стрим не дал всех сегментов),
                    // рисуем по самим outerSegs как по "открытым" цепочкам — rectIntersectsPolygon теперь их понимает.
                    if ((outers == null || outers.isEmpty()) && !outerSegs.isEmpty()) {
                        if (isWaterArea || mat != null) {
                            for (double[][] seg : outerSegs) {
                                double[] la = seg[0];
                                double[] lo = seg[1];
                                if (la != null && lo != null && la.length >= 2 && la.length == lo.length) {
                                    out.add(new ZonePoly(la, lo, (isWaterArea ? "water" : mat), false, tagKV));
                                }
                            }
                        }
                    }

                    continue;
                }

                if ("way".equals(type) && geom != null && geom.size() >= 2) {
                    boolean closed = isClosed(geom);
                    if (closed && (mat != null || isAreaByTags(tags))) {
                        int n = geom.size();
                        double[] la = new double[n], lo = new double[n];
                        for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                        if (mat != null) out.add(new ZonePoly(la, lo, mat, false, tagKV));
                        continue;
                    }
                    else if (!closed && (mat != null || isAreaByTags(tags))) {
                        // Fallback для площадных way, которые пришли незамкнутыми: добавим как "открытую" цепочку.
                        int n = geom.size();
                        double[] la = new double[n], lo = new double[n];
                        for (int i=0; i<n; i++) {
                            JsonObject p = geom.get(i).getAsJsonObject();
                            la[i] = p.get("lat").getAsDouble();
                            lo[i] = p.get("lon").getAsDouble();
                        }
                        if (mat != null) {
                            out.add(new ZonePoly(la, lo, mat, false, tagKV));
                        }
                        // если mat == null, ничего не добавляем (иначе может «потянуть» нерелевантные зоны)
                    }

                    // линейная гидрография → расширяем до «ленты»
                    if (isLinearWater(tags)) {
                        int n = geom.size();
                        double[] lats = new double[n], lons = new double[n];
                        for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); lats[i]=p.get("lat").getAsDouble(); lons[i]=p.get("lon").getAsDouble(); }

                        double widen = 0.0;
                        String wTag = optString(tags, "width");
                        if (wTag == null) wTag = optString(tags, "width:river");
                        if (wTag == null) wTag = optString(tags, "est_width");
                        if (wTag != null) try { widen = Double.parseDouble(wTag.replace(",", ".")); } catch (Exception ignore) {}
                        if (widen <= 0) {
                            String w = optString(tags, "waterway");
                            widen = ("river".equals(w) ? 8 : "canal".equals(w) ? 4 : 1);
                        }

                        double midLat = lats[Math.max(0, n/2)];
                        double dLat = widen / 111320.0;
                        double dLon = widen / (111320.0 * Math.max(0.35, Math.cos(Math.toRadians(midLat))));

                        double[] la = new double[2*n], lo = new double[2*n];
                        for (int i=0;i<n;i++) {
                            la[i]           = lats[i] + dLat; lo[i]           = lons[i] + dLon;
                            la[2*n-1 - i]   = lats[i] - dLat; lo[2*n-1 - i]   = lons[i] - dLon;
                        }
                        out.add(new ZonePoly(la, lo, "water", false, "waterway=" + optString(tags,"waterway")));
                    }
                }

                if ("relation".equals(type) && geom != null && geom.size() >= 4 && (mat != null || isAreaByTags(tags))) {
                    int n = geom.size();
                    double[] la = new double[n], lo = new double[n];
                    for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                    if (mat != null) out.add(new ZonePoly(la, lo, mat, false, tagKV));
                }
            }
        } catch (Exception ex) {
            System.err.println("[Cartopia] featureStream for zones failed: " + ex);
        }
        return out;
    }

    // --- Старый вариант через coords.features ---
    private static List<ZonePoly> extractZonesFromOSM(JsonObject root) {
        List<ZonePoly> out = new ArrayList<>();
        if (!root.has("features")) return out;
        JsonObject features = root.getAsJsonObject("features");
        if (features == null || !features.has("elements")) return out;
        JsonArray elements = features.getAsJsonArray("elements");
        if (elements == null) return out;

        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();

            String type = optString(e, "type");
            if (type == null) continue;

            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;

            MatMatch mm = materialAndKeyForTags(tags);
            String mat = mm == null ? null : mm.material;
            String tagKV = mm == null ? null : mm.tagKV;

            JsonArray geom = e.has("geometry") && e.get("geometry").isJsonArray() ? e.getAsJsonArray("geometry") : null;

            // --- RELATION (multipolygon) ---
            if ("relation".equals(type) && e.has("members") && e.get("members").isJsonArray()) {
                boolean treatAsArea = (mat != null) || isAreaByTags(tags);
                if (!treatAsArea) continue;

                List<double[][]> outerSegs = new ArrayList<>();
                List<double[][]> innerSegs = new ArrayList<>();

                for (JsonElement memEl : e.getAsJsonArray("members")) {
                    JsonObject mem = memEl.getAsJsonObject();
                    if (!"way".equals(optString(mem, "type"))) continue;
                    String role = optString(mem, "role");
                    JsonArray mGeom = mem.has("geometry") && mem.get("geometry").isJsonArray()
                            ? mem.getAsJsonArray("geometry") : null;
                    if (mGeom == null || mGeom.size() < 2) continue;

                    int n = mGeom.size();
                    double[] la = new double[n], lo = new double[n];
                    for (int i=0;i<n;i++){ JsonObject p=mGeom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                    ("inner".equals(role) ? innerSegs : outerSegs).add(new double[][]{ la, lo });
                }

                List<double[][]> outers = stitchSegmentsToRings(outerSegs);
                List<double[][]> inners = stitchSegmentsToRings(innerSegs);

                boolean isWaterArea = "water".equals(mat);
                for (double[][] r : outers) {
                    if (isWaterArea) {
                        out.add(new ZonePoly(r[0], r[1], "water", false, tagKV));
                    } else if (mat != null) {
                        out.add(new ZonePoly(r[0], r[1], mat, false, tagKV));
                    }
                }
                if (!isWaterArea) {
                    for (double[][] h : inners) {
                        out.add(new ZonePoly(h[0], h[1], "void", true, tagKV)); // inner у НЕводных
                    }
                } else {
                    for (double[][] h : inners) {
                        out.add(new ZonePoly(h[0], h[1], "water", true, tagKV)); // как было
                    }
                }

                continue;
            }

            // --- WAY ---
            if ("way".equals(type) && geom != null && geom.size() >= 2) {
                boolean closed = isClosed(geom);
                if (closed && (mat != null || isAreaByTags(tags))) {
                    int n = geom.size();
                    double[] la = new double[n], lo = new double[n];
                    for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                    if (mat != null) {
                        out.add(new ZonePoly(la, lo, mat, false, tagKV));
                    }
                    continue;
                }

                // линейная гидрография
                if (isLinearWater(tags)) {
                    int n = geom.size();
                    double[] lats = new double[n], lons = new double[n];
                    for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); lats[i]=p.get("lat").getAsDouble(); lons[i]=p.get("lon").getAsDouble(); }

                    double widen = 0.0;
                    String wTag = optString(tags, "width");
                    if (wTag == null) wTag = optString(tags, "width:river");
                    if (wTag == null) wTag = optString(tags, "est_width");
                    if (wTag != null) try { widen = Double.parseDouble(wTag.replace(",", ".")); } catch (Exception ignore) {}
                    if (widen <= 0) {
                        String w = optString(tags, "waterway");
                        widen = ("river".equals(w) ? 8 : "canal".equals(w) ? 4 : 1);
                    }

                    double midLat = lats[Math.max(0, n/2)];
                    double dLat = widen / 111320.0;
                    double dLon = widen / (111320.0 * Math.max(0.35, Math.cos(Math.toRadians(midLat))));

                    double[] la = new double[2*n], lo = new double[2*n];
                    for (int i=0;i<n;i++) {
                        la[i]           = lats[i] + dLat; lo[i]           = lons[i] + dLon;
                        la[2*n-1 - i]   = lats[i] - dLat; lo[2*n-1 - i]   = lons[i] - dLon;
                    }
                    out.add(new ZonePoly(la, lo, "water", false, "waterway=" + optString(tags,"waterway")));
                }
            }

            // --- relation с geometry на самом relation ---
            if ("relation".equals(type) && geom != null && geom.size() >= 4 && (mat != null || isAreaByTags(tags))) {
                int n = geom.size();
                double[] la = new double[n], lo = new double[n];
                for (int i=0;i<n;i++){ JsonObject p=geom.get(i).getAsJsonObject(); la[i]=p.get("lat").getAsDouble(); lo[i]=p.get("lon").getAsDouble(); }
                if (mat != null) {
                    out.add(new ZonePoly(la, lo, mat, false, tagKV));
                }
            }
        }
        return out;
    }

    /** Чётно-нечётный алгоритм, lat/lon в градусах (lon = X, lat = Y) */
    private static boolean pointInPolygon(double lat, double lon, double[] lats, double[] lons) {
        boolean inside = false;
        for (int i = 0, j = lats.length - 1; i < lats.length; j = i++) {
            boolean intersect = ((lats[i] > lat) != (lats[j] > lat)) &&
                    (lon < (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i] + 1e-12) + lons[i]);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    // ====== Пересечение прямоугольника клетки с полигоном ======

    private static final double EPS = 1e-12;

    private static final class RectLL {
        final double minLat, maxLat, minLon, maxLon;
        RectLL(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat=minLat; this.maxLat=maxLat; this.minLon=minLon; this.maxLon=maxLon;
        }
    }

    private static RectLL cellRectLatLon(int x, int z,
                                         double centerLat, double centerLng,
                                         double east, double west, double north, double south,
                                         int sizeMeters, int centerX, int centerZ) {
        double[] a = blockToLatLngD(x-0.5, z-0.5, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        double[] b = blockToLatLngD(x+0.5, z-0.5, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        double[] c = blockToLatLngD(x+0.5, z+0.5, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        double[] d = blockToLatLngD(x-0.5, z+0.5, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);

        double minLat = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0]));
        double maxLat = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0]));
        double minLon = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]));
        double maxLon = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]));
        return new RectLL(minLat, maxLat, minLon, maxLon);
    }

    private static boolean rectsOverlap(double aMinLat, double aMaxLat, double aMinLon, double aMaxLon,
                                        double bMinLat, double bMaxLat, double bMinLon, double bMaxLon) {
        return !(aMaxLon < bMinLon || aMinLon > bMaxLon || aMaxLat < bMinLat || aMinLat > bMaxLat);
    }

    // Кольцо считается замкнутым, если первая и последняя точки совпадают
    private static boolean isClosedRing(double[] lats, double[] lons) {
        if (lats == null || lons == null || lats.length < 2 || lats.length != lons.length) return false;
        return Math.abs(lats[0] - lats[lats.length - 1]) < EPS && Math.abs(lons[0] - lons[lons.length - 1]) < EPS;
    }


    // Пересечение прямоугольника клетки с полигоном ИЛИ незамкнутой цепочкой (любой ненулевой контакт)
    private static boolean rectIntersectsPolygon(RectLL r, double[] polyLats, double[] polyLons) {
        if (polyLats == null || polyLons == null || polyLats.length < 2 || polyLats.length != polyLons.length) return false;

        final boolean closed = isClosedRing(polyLats, polyLons);

        // 1) Любая вершина поли(линии) внутри прямоугольника
        for (int i = 0; i < polyLats.length; i++) {
            double lat = polyLats[i], lon = polyLons[i];
            if (lon >= r.minLon - EPS && lon <= r.maxLon + EPS &&
                lat >= r.minLat - EPS && lat <= r.maxLat + EPS) {
                return true;
            }
        }

        // 2) Любой угол прямоугольника внутри полигона — только для ЗАМКНУТЫХ
        if (closed) {
            double[][] rectPts = new double[][]{
                    {r.minLat, r.minLon},
                    {r.minLat, r.maxLon},
                    {r.maxLat, r.maxLon},
                    {r.maxLat, r.minLon}
            };
            for (double[] pt : rectPts) {
                if (pointInPolygon(pt[0], pt[1], polyLats, polyLons)) return true;
            }
        }

        // 3) Пересечение рёбер: идём по сегментам [i-1 -> i]
        //    Для незамкнутых НЕ добавляем "замыкающее" ребро last->first.
        double[][] E = new double[][]{
                {r.minLon, r.minLat, r.maxLon, r.minLat},
                {r.maxLon, r.minLat, r.maxLon, r.maxLat},
                {r.maxLon, r.maxLat, r.minLon, r.maxLat},
                {r.minLon, r.maxLat, r.minLon, r.minLat}
        };

        // сегменты внутри цепочки
        for (int i = 1; i < polyLats.length; i++) {
            double x1 = polyLons[i - 1], y1 = polyLats[i - 1];
            double x2 = polyLons[i],     y2 = polyLats[i];
            for (double[] e : E) {
                if (segmentsIntersect(x1, y1, x2, y2, e[0], e[1], e[2], e[3])) return true;
            }
        }

        // если замкнутый — проверим ещё ребро last->first
        if (closed) {
            double x1 = polyLons[polyLons.length - 1], y1 = polyLats[polyLats.length - 1];
            double x2 = polyLons[0],                   y2 = polyLats[0];
            for (double[] e : E) {
                if (segmentsIntersect(x1, y1, x2, y2, e[0], e[1], e[2], e[3])) return true;
            }
        }

        return false;
    }


    private static boolean segmentsIntersect(double x1, double y1, double x2, double y2,
                                             double x3, double y3, double x4, double y4) {
        double o1 = orient(x1,y1,x2,y2,x3,y3);
        double o2 = orient(x1,y1,x2,y2,x4,y4);
        double o3 = orient(x3,y3,x4,y4,x1,y1);
        double o4 = orient(x3,y3,x4,y4,x2,y2);

        if (o1*o2 < 0 && o3*o4 < 0) return true;

        if (Math.abs(o1) < EPS && onSegment(x1,y1,x2,y2,x3,y3)) return true;
        if (Math.abs(o2) < EPS && onSegment(x1,y1,x2,y2,x4,y4)) return true;
        if (Math.abs(o3) < EPS && onSegment(x3,y3,x4,y4,x1,y1)) return true;
        if (Math.abs(o4) < EPS && onSegment(x3,y3,x4,y4,x2,y2)) return true;

        return false;
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx-ax)*(cy-ay) - (by-ay)*(cx-ax);
    }

    private static boolean onSegment(double ax, double ay, double bx, double by, double px, double py) {
        return px >= Math.min(ax,bx)-EPS && px <= Math.max(ax,bx)+EPS &&
               py >= Math.min(ay,by)-EPS && py <= Math.max(ay,by)+EPS;
    }

    /** Формирует желаемую высоту поверхности воды (см. комментарии в исходнике) */
    private Map<Long, Integer> computeWaterSurfaceY(Map<Long,String> surface,
                                                    Map<Long,Integer> terrainY,
                                                    int minX, int maxX, int minZ, int maxZ) {
        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight() - 1;

        HashSet<Long> water = new HashSet<>();
        boolean hasLand = false;
        for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
            long k = key(x,z);
            if ("water".equals(surface.get(k))) water.add(k);
            else hasLand = true;
        }

        Map<Long,Integer> out = new HashMap<>();
        if (water.isEmpty()) return out;

        if (!hasLand) {
            int flat = 62;
            try { flat = level.getSeaLevel(); } catch (Throwable ignore) {}
            flat = Math.max(worldMin + 3, Math.min(worldMax - 3, flat));
            for (long k : water) out.put(k, flat);
            return out;
        }

        HashSet<Long> visited = new HashSet<>();
        int[][] dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

        for (int sx=minX; sx<=maxX; sx++) {
            for (int sz=minZ; sz<=maxZ; sz++) {
                long sk = key(sx,sz);
                if (!water.contains(sk) || visited.contains(sk)) continue;

                ArrayDeque<long[]> q = new ArrayDeque<>();
                ArrayList<long[]> compCells = new ArrayList<>();
                q.add(new long[]{sx,sz});
                visited.add(sk);

                while (!q.isEmpty()) {
                    long[] cur = q.poll();
                    int x = (int)cur[0], z = (int)cur[1];
                    compCells.add(cur);
                    for (int[] d : dirs) {
                        int nx = x + d[0], nz = z + d[1];
                        if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                        long nk = key(nx,nz);
                        if (!water.contains(nk) || visited.contains(nk)) continue;
                        visited.add(nk);
                        q.add(new long[]{nx,nz});
                    }
                }

                ArrayDeque<long[]> seeds = new ArrayDeque<>();
                Map<Long,Integer> seedHeight = new HashMap<>();
                int minSeed = Integer.MAX_VALUE;
                int maxR = 0;

                for (long[] cell : compCells) {
                    int x = (int)cell[0], z = (int)cell[1];
                    long k = key(x,z);

                    boolean isShore = false;
                    int shoreH = Integer.MIN_VALUE;

                    for (int[] d : dirs) {
                        int nx = x + d[0], nz = z + d[1];
                        if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                        long nk = key(nx,nz);
                        if (!water.contains(nk)) {
                            isShore = true;
                            int hLand = terrainY.getOrDefault(nk, terrainY.getOrDefault(k, worldMin+3));
                            shoreH = Math.max(shoreH, hLand);
                        }
                    }
                    if (isShore) {
                        int hWaterAtShore = Math.max(worldMin+3, Math.min(worldMax-3, shoreH - 1));
                        seeds.add(new long[]{x,z});
                        seedHeight.put(k, hWaterAtShore);
                        if (hWaterAtShore < minSeed) minSeed = hWaterAtShore;
                    }
                }

                if (seeds.isEmpty()) {
                    continue;
                }

                Map<Long,Integer> dist = new HashMap<>();
                Map<Long,Integer> nearestH = new HashMap<>();
                for (long[] s : seeds) {
                    int x = (int)s[0], z = (int)s[1];
                    long k = key(x,z);
                    dist.put(k, 0);
                    nearestH.put(k, seedHeight.get(k));
                }

                ArrayDeque<long[]> qq = new ArrayDeque<>(seeds);
                while (!qq.isEmpty()) {
                    long[] cur = qq.poll();
                    int x = (int)cur[0], z = (int)cur[1];
                    long k = key(x,z);
                    int cd = dist.get(k);
                    int ch = nearestH.get(k);
                    if (cd > maxR) maxR = cd;

                    for (int[] d : dirs) {
                        int nx = x + d[0], nz = z + d[1];
                        if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                        long nk = key(nx,nz);
                        if (!water.contains(nk)) continue;
                        if (dist.containsKey(nk)) continue;
                        dist.put(nk, cd + 1);
                        nearestH.put(nk, ch);
                        qq.add(new long[]{nx,nz});
                    }
                }

                if (maxR < 15) {
                    continue;
                }

                int R = Math.max(1, maxR);
                int Wmin = minSeed;

                for (Map.Entry<Long,Integer> e : dist.entrySet()) {
                    long k = e.getKey();
                    int d = e.getValue();
                    int hs = nearestH.get(k);
                    double t = 1.0 - (d / (double)R);
                    int ySurf = (int)Math.round(Wmin + (hs - Wmin) * Math.max(0.0, Math.min(1.0, t)));
                    ySurf = Math.max(worldMin+3, Math.min(worldMax-3, ySurf));
                    out.put(k, ySurf);
                }
            }
        }
        return out;
    }

    private static boolean isCliffLike(JsonObject tags) {
        if (tags == null) return false;
        String nat = optString(tags, "natural");
        String mm  = optString(tags, "man_made");
        String barr= optString(tags, "barrier");
        String emb = optString(tags, "embankment");

        if ("cliff".equals(nat)) return true;
        if ("earth_bank".equals(nat)) return true;
        if ("embankment".equals(mm)) return true;
        if ("yes".equals(emb)) return true;
        if ("retaining_wall".equals(barr)) return true;
        if ("retaining_wall".equals(mm)) return true;

        return false;
    }

    /** Рисуем линию на сетке (Брезенхэм) и собираем клетки в out. */
    private static void drawLineCells(int x1, int z1, int x2, int z2,
                                    int minX, int maxX, int minZ, int maxZ,
                                    Set<Long> out) {
        int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
        int dz = Math.abs(z2 - z1), sz = z1 < z2 ? 1 : -1;
        int err = (dx > dz ? dx : -dz) / 2;
        int x = x1, z = z1;
        while (true) {
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                out.add(key(x, z));
            }
            if (x == x2 && z == z2) break;
            int e2 = err;
            if (e2 > -dx) { err -= dz; x += sx; }
            if (e2 <  dz) { err += dx; z += sz; }
        }
    }

    // NDJSON-стрим для обрывов/укреплений
    private static Set<Long> extractCliffCellsFromOSMStream(
            GenerationStore store,
            double centerLat, double centerLng,
            double east, double west, double north, double south,
            int sizeMeters, int centerX, int centerZ,
            int minX, int maxX, int minZ, int maxZ) {

        Set<Long> out = new HashSet<>();
        if (store == null) return out;

        try (FeatureStream fs = store.featureStream()) {
            for (JsonObject e : fs) {
                String type = optString(e, "type");
                JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
                if (!isCliffLike(tags)) continue;

                if ("way".equals(type) && e.has("geometry") && e.get("geometry").isJsonArray()) {
                    JsonArray geom = e.getAsJsonArray("geometry");
                    if (geom.size() < 2) continue;
                    JsonObject p0 = geom.get(0).getAsJsonObject();
                    int[] prev = latlngToBlock(
                            p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                    for (int i = 1; i < geom.size(); i++) {
                        JsonObject pi = geom.get(i).getAsJsonObject();
                        int[] cur = latlngToBlock(
                                pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                        drawLineCells(prev[0], prev[1], cur[0], cur[1], minX, maxX, minZ, maxZ, out);
                        prev = cur;
                    }
                }

                if ("relation".equals(type) && e.has("members") && e.get("members").isJsonArray()) {
                    for (JsonElement memEl : e.getAsJsonArray("members")) {
                        JsonObject mem = memEl.getAsJsonObject();
                        if (!"way".equals(optString(mem, "type"))) continue;
                        if (!mem.has("geometry") || !mem.get("geometry").isJsonArray()) continue;
                        JsonArray geom = mem.getAsJsonArray("geometry");
                        if (geom.size() < 2) continue;

                        JsonObject p0 = geom.get(0).getAsJsonObject();
                        int[] prev = latlngToBlock(
                                p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                        for (int i = 1; i < geom.size(); i++) {
                            JsonObject pi = geom.get(i).getAsJsonObject();
                            int[] cur = latlngToBlock(
                                    pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                                    centerLat, centerLng, east, west, north, south,
                                    sizeMeters, centerX, centerZ);
                            drawLineCells(prev[0], prev[1], cur[0], cur[1], minX, maxX, minZ, maxZ, out);
                            prev = cur;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("[Cartopia] featureStream for cliffs failed: " + ex);
        }
        return out;
    }

    // Старый вариант (coords.features)
    private static Set<Long> extractCliffCellsFromOSM(
            JsonObject root,
            double centerLat, double centerLng,
            double east, double west, double north, double south,
            int sizeMeters, int centerX, int centerZ,
            int minX, int maxX, int minZ, int maxZ) {

        Set<Long> out = new HashSet<>();
        if (root == null || !root.has("features")) return out;
        JsonObject features = root.getAsJsonObject("features");
        if (features == null || !features.has("elements")) return out;
        JsonArray elements = features.getAsJsonArray("elements");
        if (elements == null) return out;

        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String type = optString(e, "type");
            JsonObject tags = e.has("tags") && e.get("tags").isJsonObject() ? e.getAsJsonObject("tags") : null;
            if (!isCliffLike(tags)) continue;

            // WAY
            if ("way".equals(type) && e.has("geometry") && e.get("geometry").isJsonArray()) {
                JsonArray geom = e.getAsJsonArray("geometry");
                if (geom.size() < 2) continue;
                JsonObject p0 = geom.get(0).getAsJsonObject();
                int[] prev = latlngToBlock(
                        p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south,
                        sizeMeters, centerX, centerZ);
                for (int i = 1; i < geom.size(); i++) {
                    JsonObject pi = geom.get(i).getAsJsonObject();
                    int[] cur = latlngToBlock(
                            pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                    drawLineCells(prev[0], prev[1], cur[0], cur[1], minX, maxX, minZ, maxZ, out);
                    prev = cur;
                }
            }

            // RELATION
            if ("relation".equals(type) && e.has("members") && e.get("members").isJsonArray()) {
                for (JsonElement memEl : e.getAsJsonArray("members")) {
                    JsonObject mem = memEl.getAsJsonObject();
                    if (!"way".equals(optString(mem, "type"))) continue;
                    if (!mem.has("geometry") || !mem.get("geometry").isJsonArray()) continue;
                    JsonArray geom = mem.getAsJsonArray("geometry");
                    if (geom.size() < 2) continue;

                    JsonObject p0 = geom.get(0).getAsJsonObject();
                    int[] prev = latlngToBlock(
                            p0.get("lat").getAsDouble(), p0.get("lon").getAsDouble(),
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ);
                    for (int i = 1; i < geom.size(); i++) {
                        JsonObject pi = geom.get(i).getAsJsonObject();
                        int[] cur = latlngToBlock(
                                pi.get("lat").getAsDouble(), pi.get("lon").getAsDouble(),
                                centerLat, centerLng, east, west, north, south,
                                sizeMeters, centerX, centerZ);
                        drawLineCells(prev[0], prev[1], cur[0], cur[1], minX, maxX, minZ, maxZ, out);
                        prev = cur;
                    }
                }
            }
        }
        return out;
    }

    private static void publishTerrainGrid(JsonObject coordsJson,
                                        Map<Long,Integer> terrainY,
                                        int minX, int maxX, int minZ, int maxZ) {
        int width  = maxX - minX + 1;
        int height = maxZ - minZ + 1;

        JsonObject g = new JsonObject();
        g.addProperty("minX", minX);
        g.addProperty("minZ", minZ);
        g.addProperty("width", width);
        g.addProperty("height", height);

        JsonArray data = new JsonArray();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long k = (((long)x)<<32) ^ (z & 0xffffffffL);
                int y = terrainY.getOrDefault(k, Y_BASE);
                data.add(y);
            }
        }
        g.add("data", data);

        coordsJson.add("terrainGrid", g);
    }

    private static File detectAreaPackDir(File demFile) {
        if (demFile == null) return null;
        File dir = demFile.getParentFile();
        // поднимемся на 0–3 уровня, ищем директорию вида area_...
        for (int i = 0; i < 4 && dir != null; i++) {
            if (dir.getName().startsWith("area_")) return dir;
            dir = dir.getParentFile();
        }
        // если не нашли — кладём рядом с DEM
        return demFile.getParentFile();
    }

    private static String optString(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }
}
