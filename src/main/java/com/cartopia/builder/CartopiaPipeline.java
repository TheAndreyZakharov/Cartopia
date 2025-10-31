package com.cartopia.builder;
import com.cartopia.spawn.CartopiaSurfaceSpawn;
import com.cartopia.clean.DroppedEntitiesCleaner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CartopiaPipeline {
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
    public static void run(ServerLevel level, File coordsJsonFile, File demTifFile, File landcoverTifFileOrNull) throws Exception {
        broadcast(level, "Загружаю координаты/параметры…");
        // === Подготовка сайдкаров (стрим-режим) ===
        com.cartopia.store.GenerationStore store = null;
        try {
            store = com.cartopia.store.GenerationStore.prepare(coordsJsonFile.getParentFile(), coordsJsonFile);
            broadcast(level, "Подготовил сайдкары: features NDJSON и terrain grid (если есть).");
        } catch (Exception splitErr) {
            broadcast(level, "Внимание: не удалось подготовить сайдкары: " + splitErr.getMessage());
            // store останется null — генераторы уйдут в fallback на coords.json
        }
        String json = Files.readString(coordsJsonFile.toPath(), StandardCharsets.UTF_8);
        JsonObject coords = JsonParser.parseString(json).getAsJsonObject();
        if (demTifFile == null) {
            throw new IllegalStateException("DEM файл = null");
        }
        broadcast(level, "DEM: " + demTifFile.getAbsolutePath() + " (" + demTifFile.length() + " байт)"
                + (demTifFile.exists() ? "" : " [ФАЙЛ НЕ НАЙДЕН]"));
        if (landcoverTifFileOrNull != null) {
            broadcast(level, "OLM: " + landcoverTifFileOrNull.getAbsolutePath() + " (" + landcoverTifFileOrNull.length() + " байт)"
                    + (landcoverTifFileOrNull.exists() ? "" : " [ФАЙЛ НЕ НАЙДЕН]"));
        }
        broadcast(level, "Старт генерации поверхности (DEM + покраска) …");
        try {

            



            // ===== РЕЛЬЕФ И ЕГО РАСКРАСКА, ДОРОГИ, ЖД =====
            // Рельеф
            SurfaceGenerator surface = new SurfaceGenerator(level, coords, demTifFile, landcoverTifFileOrNull, store);
            surface.generate();
            broadcast(level, "Поверхность готова.");
            // Сразу поднимаем всех игроков этого мира на безопасную поверхность
            broadcast(level, "Переставляю игроков на поверхность…");
            CartopiaSurfaceSpawn.adjustAllPlayersAsync(level);
            // Дороги
            broadcast(level, "Старт генерации дорог…");
            RoadGenerator roads = new RoadGenerator(level, coords, store);
            roads.generate();
            broadcast(level, "Дороги готовы.");
            // Рельсы
            broadcast(level, "Старт генерации рельсов…");
            RailGenerator rails = new RailGenerator(level, coords, store);
            rails.generate();
            broadcast(level, "Рельсы готовы.");
            // Пирсы
            broadcast(level, "Старт генерации пирсов…");
            PierGenerator piers = new PierGenerator(level, coords, store);
            piers.generate();
            broadcast(level, "Пирсы готовы.");
            // ===== РАЗМЕТКА =====
            // Пешеходные переходы
            broadcast(level, "Старт разметки пешеходных переходов…");
            CrosswalkGenerator crosswalks = new CrosswalkGenerator(level, coords, store);
            crosswalks.generate();
            broadcast(level, "Пешеходные переходы готовы.");
            // Разметка 1.17 у остановок (ёлочка)
            broadcast(level, "Старт разметки остановок (1.17)...");
            StopMarkingGenerator busStops = new StopMarkingGenerator(level, coords, store);
            busStops.generate();
            broadcast(level, "Разметка остановок готова.");
            // ЖД переезды — стоп-линии
            broadcast(level, "Старт стоп-линий у ЖД-переездов…");
            RailStopLineGenerator rxl = new RailStopLineGenerator(level, coords, store);
            rxl.generate();
            broadcast(level, "Стоп-линии у ЖД-переездов готовы.");
            // Вертолётные площадки
            broadcast(level, "Старт генерации вертолётных площадок…");
            HelipadGenerator helipads = new HelipadGenerator(level, coords, store);
            helipads.generate();
            broadcast(level, "Вертолётные площадки готовы.");
            // Парковочные места
            broadcast(level, "Старт разметки парковочных мест…");
            ParkingStallGenerator stalls = new ParkingStallGenerator(level, coords, store);
            stalls.generate();
            broadcast(level, "Парковочные места готовы.");
            // ===== МОСТЫ / ТУННЕЛИ =====
            // Мосты/эстакады (без тоннелей)
            broadcast(level, "Старт генерации мостов/эстакад…");
            BridgeGenerator bridges = new BridgeGenerator(level, coords, store);
            bridges.generate();
            broadcast(level, "Мосты/эстакады готовы.");
            // Тоннели и подземные переходы (дороги и ЖД по логике «как мост, но вниз»)
            broadcast(level, "Старт генерации тоннелей/подземных переходов…");
            TunnelGenerator tunnels = new TunnelGenerator(level, coords, store);
            tunnels.generate();
            broadcast(level, "Тоннели/подземные переходы готовы.");
            // Дорожная кнопочная разметка
            broadcast(level, "Старт дорожной кнопочной разметки…");
            RoadButtonMarkingGenerator roadButtons = new RoadButtonMarkingGenerator(level, coords, store);
            roadButtons.generate();
            broadcast(level, "Дорожная кнопочная разметка готова.");
            // ===== ЗДАНИЯ =====
            // Здания
            broadcast(level, "Старт генерации зданий…");
            BuildingGenerator buildings = new BuildingGenerator(level, coords, store);
            buildings.generate();
            broadcast(level, "Здания готовы.");
            // ===== ОСВЕЩЕНИЕ =====
            // Дорожные фонари
            broadcast(level, "Старт расстановки дорожных фонарей…");
            RoadLampGenerator roadLamps = new RoadLampGenerator(level, coords, store);
            roadLamps.generate();
            broadcast(level, "Дорожные фонари готовы.");
            // Фонари вдоль рельсов
            broadcast(level, "Старт расстановки фонарей вдоль рельсов…");
            RailLampGenerator railLamps = new RailLampGenerator(level, coords, store);
            railLamps.generate();
            broadcast(level, "Фонари вдоль рельсов готовы.");
            // ===== ИНФРАСТРУКТУРА =====
            // Утилитарные уличные боксы однотипно
            broadcast(level, "Старт генерации утилитарных боксов…");
            UtilityBoxGenerator utilBoxGen = new UtilityBoxGenerator(level, coords, store);
            utilBoxGen.generate();
            broadcast(level, "Утилитарные боксы готовы.");
            // Маяки
            broadcast(level, "Старт генерации маяков…");
            LighthouseGenerator lighthouseGen = new LighthouseGenerator(level, coords, store);
            lighthouseGen.generate();
            broadcast(level, "Маяки готовы.");
            // Ветряные турбины
            broadcast(level, "Старт генерации ветряков…");
            WindTurbineGenerator wtGen = new WindTurbineGenerator(level, coords, store);
            wtGen.generate();
            broadcast(level, "Ветряки готовы.");
            // Наблюдательные вышки
            broadcast(level, "Старт генерации наблюдательных вышек…");
            WatchtowerGenerator wtowerGen = new WatchtowerGenerator(level, coords, store);
            wtowerGen.generate();
            broadcast(level, "Вышки готовы.");
            // Трубы (дымоходы)
            broadcast(level, "Старт генерации труб…");
            ChimneyGenerator chGen = new ChimneyGenerator(level, coords, store);
            chGen.generate();
            broadcast(level, "Трубы готовы.");
            // Вышки/мачты
            broadcast(level, "Старт генерации вышек…");
            TowerMastGenerator tmGen = new TowerMastGenerator(level, coords, store);
            tmGen.generate();
            broadcast(level, "Вышки готовы.");
            // Башни-резервуары
            broadcast(level, "Старт генерации башен-резервуаров…");
            UtilityTankTowerGenerator tankGen = new UtilityTankTowerGenerator(level, coords, store);
            tankGen.generate();
            broadcast(level, "Башни-резервуары готовы.");
            // Солнечные панели
            broadcast(level, "Старт генерации солнечных зон…");
            SolarPanelGenerator solarGen = new SolarPanelGenerator(level, coords, store);
            solarGen.generate();
            broadcast(level, "Солнечные зоны готовы.");
            // Электроподстанции
            broadcast(level, "Старт генерации электроподстанций…");
            SubstationGenerator subGen = new SubstationGenerator(level, coords, store);
            subGen.generate();
            broadcast(level, "Подстанции готовы.");
            // ЛЭП: столбы, вышки и провода
            broadcast(level, "Старт генерации ЛЭП (столбы/вышки/провода)…");
            PowerLinesGenerator plGen = new PowerLinesGenerator(level, coords, store);
            plGen.generate();
            broadcast(level, "ЛЭП готовы.");
            // Бензоколонки на АЗС
            broadcast(level, "Старт генерации бензоколонок…");
            FuelPumpGenerator fuelGen = new FuelPumpGenerator(level, coords, store);
            fuelGen.generate();
            broadcast(level, "Бензоколонки готовы.");
            // Автомойки
            broadcast(level, "Старт генерации автомоек…");
            CarWashGenerator washGen = new CarWashGenerator(level, coords, store); 
            washGen.generate();
            broadcast(level, "Автомойки готовы.");
            // Электрозарядки
            broadcast(level, "Старт генерации электрозарядок…");
            ElectricChargerGenerator evGen = new ElectricChargerGenerator(level, coords, store);
            evGen.generate();
            broadcast(level, "Электрозарядки готовы.");
            // Ограждения, заборы, отбойники
            broadcast(level, "Старт генерации ограждений/заборов/отбойников…");
            FenceAndBarrierGenerator fenceGen = new FenceAndBarrierGenerator(level, coords, store);
            fenceGen.generate();
            broadcast(level, "Ограждения/заборы/отбойники готовы.");
            // Места отдыха: скамейки, столы, BBQ, беседки
            broadcast(level, "Старт генерации мест отдыха…");
            LeisureRestGenerator leisureGen = new LeisureRestGenerator(level, coords, store);
            leisureGen.generate();
            broadcast(level, "Места отдыха готовы.");
            // Пляжный отдых (лежаки)
            broadcast(level, "Старт генерации пляжных зон…");
            BeachResortGenerator beachGen = new BeachResortGenerator(level, coords, store);
            beachGen.generate();
            broadcast(level, "Пляжные зоны готовы.");
            // Спортплощадки: футбол, баскетбол, теннис, волейбол/бадминтон, гольф, стрельбища, фитнес
            broadcast(level, "Старт генерации спортплощадок…");
            SportsFacilitiesGenerator sportsGen = new SportsFacilitiesGenerator(level, coords, store);
            sportsGen.generate();
            broadcast(level, "Спортплощадки готовы.");



// natural=arete хребты, кряжа, утёсы, вершины
// natural	glacier лёд + ice и всё подобное и оно перекрывает воду как болота

// палатки добавить в места отдыха

// Десткие площадки. разноцветный терракот ставить на рандом + в центре пирамиду из рандомного цветного бетона и сверху колокол

// Урны, Места мусорных баков Tags amenity	урны - waste_basket, мысорки? - waste_disposal, recycling

// Надземные трубы - сами трубы на высоте +5

// Остановки общественного транспорта
// Светофоры
// Кладбища ? 
// Источники воды, колонки, колодцы, с питьевой водой

// Пожарные гидранты
// паркоматы: amenity=parking_meter, amenity	vending_machine, parking_tickets
// Велопарковки
// почтовые ящики: amenity=post_box
// Информационнын стенды, гиды, знаки
// пасеки, фермы
// Открытые ископаемые в шахтах
// Здания без полигонов и тегов. выше по пайплайну, сделать первым в инфраструктуре. разные теповые дома. разные материалы, формы крыш, этажность, размеры - на рандом выбирается


// горы, океаны чек


// =============================================



// флагштоки: man_made=flagpole
// На аэродромах инфраструктура для ветра и тд aeroway=windsock
// камеры наблюдения и скорости: highway=speed_camera, видеонаблюдение: man_made=surveillance (+ surveillance:type=camera) и тп камеры
// успокоители трафика, лежач полицейские: traffic_calming=table|hump|bump|cushion|chicane|island (ways) - полублоками, столбиками
// ? Рекламные щиты, сденды, билборды, рекламные телевизоры на домах? - картинами



// =============================================



// Фонтаны
// Памятники, Арт-объекты
// Места строек отдельно оформить - заборы, башенные краны, кучи кирпичей, досок, песка
// Краны башенные отдельно  man_made	crane , некоторые как зоны отмечены - crane:mobile	rail
// Драг лифты - поверхностные подъёмники
// Фуникулёры  aerialway



// ==========================================================================================





            // ===== РАСТИТЕЛЬНОСТЬ ======
// Поля с посевами - по цветам
// Всё засадить соответствующими типами деревьев, травы и тд
// Учитывать зоны - просеки под ЛЭП и тд
// Рассаживать везде, но учитывать интенсивность от зон
// кусты leaf_type	broadleaved natural	tree_row и тп


            // ===== ОЖИВЛЕНИЕ =====
// Где точно зона лесов - спавн волков, лис, медведей, лошадей и тд
// Где зона ферм и тд - спавн овец, коров, куриц, лошадей и тд
// В воде - спавн треска, лосось и тд. если вода большая - дельфин
// Где пасеки - спавн пчел
// На тропинках, тротуарах, в парках, на парковках, жилых зонах и тд спавн жителей - чтобы они не выходили за свои зоны
// Логика "автомобилей", плавно "ездящих" по дорогам


            // ===== ВРЕМЯ =====
// Устанавливать время в игре, соответствующее реальному времени в генерируемой зоне
// Сопоставлять координаты, нахождение игрока и перепроверять время на этих координатах


            // ===== ПОГОДА =====
// Устанавливать погоду в игре, соответствующую реальной погоде в генерируемой зоне
// Сопоставлять координаты, нахождение игрока и перепроверять погоду на этих координатах





            // ===== СОХРАНЕНИЕ =====
            broadcast(level, "Сохраняю мир…");
            level.save(null, true, false);
            broadcast(level, "Generation finished.");
            // Через ~3 секунды после завершения генерации — очистка выпавших предметов
            DroppedEntitiesCleaner.schedule(level, 60); // 60 тиков ≈ 3 сек + задержки
            DroppedEntitiesCleaner.schedule(level, 200);  // ещё раз через ~10 сек + задержки
        } catch (Exception e) {
            // твой текущий catch остаётся как есть…
            String cls = e.getClass().getSimpleName();
            String msg = e.getMessage();
            Throwable cause = e.getCause();
            String causeStr = (cause == null ? "" : " | cause: " + cause.getClass().getSimpleName() +
                    (cause.getMessage() == null ? "" : (" - " + cause.getMessage())));
            broadcast(level, "Ошибка генерации: " + cls + (msg == null ? "" : (": " + msg)) + causeStr);
            System.err.println("[Cartopia] --- STACKTRACE START ---");
            e.printStackTrace();
            System.err.println("[Cartopia] --- STACKTRACE END ---");
            throw e;
        } finally {
            try { if (store != null) store.close(); } catch (Exception ignore) {}
        }
    }
}
