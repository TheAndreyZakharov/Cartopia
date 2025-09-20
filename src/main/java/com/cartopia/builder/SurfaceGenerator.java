package com.cartopia.builder;

import com.cartopia.spawn.CartopiaSurfaceSpawn;
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
        LANDCOVER_CLASS_TO_BLOCK.put(185, "mangrove_log");       // Mangrove (лог в MC есть)
        LANDCOVER_CLASS_TO_BLOCK.put(186, "muddy_mangrove_roots"); // Salt marsh
        LANDCOVER_CLASS_TO_BLOCK.put(187, "sandstone");          // Tidal flat
        LANDCOVER_CLASS_TO_BLOCK.put(190, "moss_block");         // Impervious (города)
        LANDCOVER_CLASS_TO_BLOCK.put(140, "snow_block");         // Lichens and mosses
        LANDCOVER_CLASS_TO_BLOCK.put(0,   "water");              // no-data → вода (как в старом коде)
        LANDCOVER_CLASS_TO_BLOCK.put(250, "water");
        LANDCOVER_CLASS_TO_BLOCK.put(255, "water");
    }

    public SurfaceGenerator(ServerLevel level, JsonObject coordsJson, File demFile, File landcoverFileOrNull) {
        this.level = level;
        this.coordsJson = coordsJson;
        this.demFile = demFile;
        this.landcoverFileOrNull = landcoverFileOrNull;
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

        // ---------- OLM (landcover): локальный файл ИЛИ онлайн по WMS/шаблону ----------
        File lcFile = (landcoverFileOrNull != null && landcoverFileOrNull.exists() && landcoverFileOrNull.length() > 0)
                ? landcoverFileOrNull
                : downloadOLMIfConfigured(coordsJson, south, west, north, east);


        if (lcFile != null && lcFile.exists() && lcFile.length() > 0) {
            broadcast(level, "Читаю OpenLandMap landcover…");

            // Границы растрового файла: если заданы landcoverBounds — берём их; иначе
            // если файл онлайн-скачанный под наш bbox — используем bbox участка.
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

                        // вне растра/нет данных → вода (как в старом подходе)
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
                            olmAll.add(k);                         // помечаем как OLM
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("[Cartopia] OLM read failed: " + ex);
            }
        } else {
            System.out.println("[Cartopia] OLM landcover отсутствует (и онлайн не настроен) — пропускаю.");
        }
        // ---------- OSM зоны ----------
        List<ZonePoly> zones = extractZonesFromOSM(coordsJson);
        System.out.println("[Cartopia] ОSM зон для покраски: " + zones.size());

        // Сначала — вода (outer минус inner), затем прочее.
        for (int x=minX; x<=maxX; x++) {
            for (int z=minZ; z<=maxZ; z++) {
                RectLL cell = cellRectLatLon(x, z, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                long k = key(x,z);

                // Вода из OSM имеет приоритет, но уважает inner-дырки
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
                    if (!inWaterHole) {
                        surface.put(k, "water");
                        waterProtected.add(k);   // воду из OSM не трогаем и не учитываем
                        waterFromOLM.remove(k);  // если раньше пометили как OLM — убрать
                        continue; // вода победила
                    }
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

                if (bestMat != null && !"water".equals(surface.get(k))) {
                    surface.put(k, bestMat);
                    lockedOSM.add(k); // лочим OSM-сушу, чтобы сглаживание её не трогало
                }
            }
        }

        // Параметры сглаживания (теперь можно скруглять воду тоже)
        int surfaceBlurIters = SURFACE_BLUR_ITERS;
        int surfaceBlurMinMajority = SURFACE_BLUR_MIN_MAJORITY;
        boolean surfaceBlurIncludeWater = true; // <— хотим сглаживать и воду

        if (coordsJson.has("tuning") && coordsJson.get("tuning").isJsonObject()) {
            JsonObject t = coordsJson.getAsJsonObject("tuning");
            if (t.has("surfaceBlurIters")) surfaceBlurIters = Math.max(0, t.get("surfaceBlurIters").getAsInt());
            if (t.has("surfaceBlurMinMajority")) surfaceBlurMinMajority = Math.max(1, t.get("surfaceBlurMinMajority").getAsInt());
            if (t.has("surfaceBlurIncludeWater")) surfaceBlurIncludeWater = t.get("surfaceBlurIncludeWater").getAsBoolean();
        }

        // --- Скругление границ всех OLM-зон (и воды, и суши), OSM/моря не трогаем
        int olmFeatherRadius    = 5;  // толщина «пера»: 2–3 блока
        int olmFeatherIters     = 10;  // сколько проходов
        int olmFeatherMinVotes  = 2;  // минимум голосов из 8 соседей

        if (coordsJson.has("tuning") && coordsJson.get("tuning").isJsonObject()) {
            JsonObject t = coordsJson.getAsJsonObject("tuning");
            if (t.has("olmFeatherRadius"))   olmFeatherRadius   = Math.max(1, t.get("olmFeatherRadius").getAsInt());
            if (t.has("olmFeatherIters"))    olmFeatherIters    = Math.max(1, t.get("olmFeatherIters").getAsInt());
            if (t.has("olmFeatherMinVotes")) olmFeatherMinVotes = Math.max(1, t.get("olmFeatherMinVotes").getAsInt());
        }

        featherOlmZones(surface, minX, maxX, minZ, maxZ,
                olmFeatherRadius, olmFeatherIters, olmFeatherMinVotes,
                olmAll, waterFromOLM,          // кто из OLM и какая вода – тоже из OLM
                waterProtected, lockedOSM);    // защищённая вода/OSM — вне игры

        blurSurface(surface, minX, maxX, minZ, maxZ,
            surfaceBlurIters, surfaceBlurMinMajority,
            /*includeWater=*/surfaceBlurIncludeWater,
            waterProtected, waterFromOLM,
            lockedOSM);

        // Маска воды
        Set<Long> waterMask = new HashSet<>();
        for (Map.Entry<Long,String> e : surface.entrySet())
            if ("water".equals(e.getValue())) waterMask.add(e.getKey());

        broadcast(level, String.format("Размещение блоков (%d клеток)…", totalCells));
        placeBlocks(surface, terrainY, waterMask, minX, maxX, minZ, maxZ, totalCells);
        broadcast(level, "Размещение блоков завершено.");
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

    // ===== Поверхность: сглаживание только для воды из OLM; вода из OSM/моря — неизменяема и не учитывается =====
    // Поверхность: сглаживаем только фон (OLM/дефолт), но НЕ трогаем
    // 1) воду (sea+OSM) и 2) сушу, пришедшую из OSM (lockedOSM)
    // Также lockedOSM и защищённая вода НЕ участвуют в голосовании.
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
            if (protectedWater.contains(k) && !olmWater.contains(k)) continue; // чужая вода (не OLM) — не трогаем

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
                    if (!olmAll.contains(nk)) continue;                          // меняем только OLM
                    if (lockedOSM.contains(nk)) continue;                        // OSM — нельзя
                    if (protectedWater.contains(nk) && !olmWater.contains(nk)) continue; // «моря/OSM-вода» — нельзя
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
                        if (nx<minX || nx>maxX || nz<minZ || nz>maxZ) continue;

                        long nk = key(nx, nz);
                        if (!olmAll.contains(nk)) continue;                        // голосуют только OLM-соседи
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
                    next.put(k, best.getKey()); // позволяем переходы и в воду, и из воды — но только для OLM
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
        Map<Long,Integer> tmp = grayscaleOpen(h, minX, maxX, minZ, maxZ, radius);   // убирает маленькие "пики"
        tmp = grayscaleClose(tmp, minX, maxX, minZ, maxZ, radius);                  // засыпает маленькие "ямки"
        h.clear(); h.putAll(tmp);
        fixSingleCellSpikes(h, minX, maxX, minZ, maxZ);                              // точечные 1×1
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

    @SuppressWarnings("unused")
    private static String dominantAround(Map<Long,String> srf, int x, int z, int minX, int maxX, int minZ, int maxZ, int radius) {
        Map<String,Integer> cnt = new HashMap<>();
        for (int dx=-radius; dx<=radius; dx++) {
            for (int dz=-radius; dz<=radius; dz++) {
                if (dx==0 && dz==0) continue;
                int nx=x+dx, nz=z+dz;
                if (nx<minX||nx>maxX||nz<minZ||nz>maxZ) continue;
                String m = srf.get(key(nx,nz));
                if (m==null || "water".equals(m)) continue;
                if (!("moss_block".equals(m) || "snow_block".equals(m) || "sandstone".equals(m))) continue;
                cnt.merge(m, 1, Integer::sum);
            }
        }
        if (cnt.isEmpty()) return "moss_block";
        return cnt.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }

    private void placeBlocks(Map<Long,String> surface, Map<Long,Integer> terrain,
                             Set<Long> waterMask, int minX, int maxX, int minZ, int maxZ, int totalCells) {
        int done = 0;
        int nextConsole = 5;
        int[] chatMilestones = {25, 50, 75, 100};
        int chatIdx = 0;

        final int worldMin = level.getMinBuildHeight();
        final int worldMax = level.getMaxBuildHeight();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int yTop = terrain.getOrDefault(key(x, z), Y_BASE); // верх рельефа
                if (yTop <= worldMin + 2) yTop = worldMin + 3;
                if (yTop >= worldMax - 2) yTop = worldMax - 3;

                String mat = surface.getOrDefault(key(x, z), "moss_block");

                if ("water".equals(mat) || waterMask.contains(key(x, z))) {
                    // опора и вода
                    int yLapis = yTop - 2;
                    int yWater = yTop - 1;

                    // ниже опоры — чистим
                    clearColumnBelow(x, z, worldMin, yLapis - 1);

                    // опора + вода + воздух сверху
                    setBlock(x, yLapis, z, "minecraft:lapis_block");
                    setBlock(x, yWater, z, "minecraft:water");
                    setBlock(x, yTop,   z, "minecraft:air");

                    // выше — чистим воздухом до потолка
                    clearColumnAbove(x, yTop + 1, z, worldMax);
                } else {
                    // суша: материал на вершине
                    setBlock(x, yTop, z, "minecraft:" + mat);
                    clearColumnAbove(x, yTop + 1, z, worldMax);
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
        level.setBlock(new BlockPos(x,y,z), b.defaultBlockState(), 3);
    }
    private void clearColumnAbove(int x, int fromY, int z, int toYInclusive) {
        int maxY = Math.min(level.getMaxBuildHeight(), toYInclusive);
        for (int y=fromY; y<=maxY; y++) level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 3);
    }
    private void clearColumnBelow(int x, int z, int fromY, int toYInclusive) {
        int minY = Math.max(level.getMinBuildHeight(), fromY);
        for (int y=minY; y<=toYInclusive; y++) level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 3);
    }

    private static long key(int x, int z) { return (((long)x)<<32) ^ (z & 0xffffffffL); }

    // =================== OSM parsing ===================

    private static final class ZonePoly {
        final double[] lats, lons;
        final String material;
        final boolean isHole; // для inner-колец воды
        @SuppressWarnings("unused")
        final String tagKV;   // исходный тег key=value для спец-логики (например, residential)
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
                "landcover","military","aeroway","man_made","power","boundary","place" };
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
            for (int i=0;i<n;i++) poly.add(new double[]{ seg[0][i], seg[1][i] }); // [lat,lon]
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
        if ("riverbank".equals(optString(tags, "waterway"))) return new MatMatch("water", "waterway=riverbank");
        if ("water".equals(optString(tags, "natural")))      return new MatMatch("water", "natural=water");
        if ("reservoir".equals(optString(tags, "landuse")))  return new MatMatch("water", "landuse=reservoir");
        if (tags.has("water"))                                return new MatMatch("water", "water=*");

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

            // --- RELATION (multipolygon): сшиваем OUTER и вычитаем INNER ---
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
                    } // иначе — пропускаем
                }
                if (isWaterArea) {
                    for (double[][] h : inners) {
                        out.add(new ZonePoly(h[0], h[1], "water", true, tagKV));
                    }
                }

                continue;
            }

            // --- WAY: площадные или линейная вода ---
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
                        widen = ("river".equals(w) ? 8 : "canal".equals(w) ? 4 : 1); // метры
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

            // --- (редкий случай) relation с geometry[] на самом relation ---
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

    private static String optString(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
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

    // ====== Пересечение прямоугольника клетки с полигоном (любой ненулевой контакт) ======

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

    private static boolean rectIntersectsPolygon(RectLL r, double[] polyLats, double[] polyLons) {
        // 1) Любая вершина полигона внутри прямоугольника
        for (int i=0;i<polyLats.length;i++) {
            double lat = polyLats[i], lon = polyLons[i];
            if (lon >= r.minLon - EPS && lon <= r.maxLon + EPS &&
                lat >= r.minLat - EPS && lat <= r.maxLat + EPS) {
                return true;
            }
        }

        // 2) Любой угол прямоугольника внутри полигона
        double[][] rectPts = new double[][] {
                {r.minLat, r.minLon},
                {r.minLat, r.maxLon},
                {r.maxLat, r.maxLon},
                {r.maxLat, r.minLon}
        };
        for (double[] pt : rectPts) {
            if (pointInPolygon(pt[0], pt[1], polyLats, polyLons)) return true;
        }

        // 3) Пересечение рёбер полигона с рёбрами прямоугольника
        double[][] E = new double[][] {
                {r.minLon, r.minLat, r.maxLon, r.minLat}, // низ
                {r.maxLon, r.minLat, r.maxLon, r.maxLat}, // право
                {r.maxLon, r.maxLat, r.minLon, r.maxLat}, // верх
                {r.minLon, r.maxLat, r.minLon, r.minLat}  // лево
        };

        for (int i=0, j=polyLats.length-1; i<polyLats.length; j=i++) {
            double x1 = polyLons[j], y1 = polyLats[j];
            double x2 = polyLons[i], y2 = polyLats[i];
            for (double[] e : E) {
                if (segmentsIntersect(x1,y1,x2,y2, e[0],e[1],e[2],e[3])) return true;
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

        if (o1*o2 < 0 && o3*o4 < 0) return true; // общий случай

        // коллинеарные случаи
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
}
