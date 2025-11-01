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
            // Ветряные мельницы 
            broadcast(level, "Старт генерации мельниц…");
            ClassicWindmillGenerator wmGen = new ClassicWindmillGenerator(level, coords, store);
            wmGen.generate();
            broadcast(level, "Мельницы готовы.");
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
            // Места отдыха: скамейки, столы, BBQ, беседки, палатки
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
            // Мусорная инфраструктура: урны, переработка, площадки под мусор
            broadcast(level, "Старт генерации мусорной инфраструктуры…");
            WasteGenerator wasteGen = new WasteGenerator(level, coords, store);
            wasteGen.generate();
            broadcast(level, "мусорная инфраструктура готова.");
            // Надземные трубопроводы
            broadcast(level, "Старт генерации надземных труб…");
            OvergroundPipelinesGenerator pipesGen = new OvergroundPipelinesGenerator(level, coords, store);
            pipesGen.generate();
            broadcast(level, "Надземные трубы готовы.");
            // Остановочные павильоны
            broadcast(level, "Старт генерации остановочных павильонов…");
            PublicTransportShelterGenerator shelterGen = new PublicTransportShelterGenerator(level, coords, store);
            shelterGen.generate();
            broadcast(level, "Павильоны готовы.");
            // Светофоры
            broadcast(level, "Старт генерации светофоров…");
            TrafficLightGenerator tlGen = new TrafficLightGenerator(level, coords, store);
            tlGen.generate();
            broadcast(level, "Светофоры готовы.");
            // Флагштоки
            broadcast(level, "Старт генерации флагштоков…");
            FlagpoleGenerator fpGen = new FlagpoleGenerator(level, coords, store);
            fpGen.generate();
            broadcast(level, "Флагштоки готовы.");
            // Адресные точки - простые дома 
            broadcast(level, "Старт генерации адресных домов…");
            AddressPointBuildingsGenerator addrGen = new AddressPointBuildingsGenerator(level, coords, store);
            addrGen.generate();
            broadcast(level, "Адресные дома построены.");
            // Ограждения, заборы, отбойники
            broadcast(level, "Старт генерации ограждений/заборов/отбойников…");
            FenceAndBarrierGenerator fenceGen = new FenceAndBarrierGenerator(level, coords, store);
            fenceGen.generate();
            broadcast(level, "Ограждения/заборы/отбойники готовы.");
            // Кладбища (надгробия)
            broadcast(level, "Старт генерации кладбищ…");
            CemeteryGravesGenerator cemGen = new CemeteryGravesGenerator(level, coords, store);
            cemGen.generate();
            broadcast(level, "Кладбища готовы.");
            // Источники воды (колонки/колодцы/питьевые точки)
            broadcast(level, "Старт генерации источников воды…");
            WaterSourcesGenerator waterGen = new WaterSourcesGenerator(level, coords, store);
            waterGen.generate();
            broadcast(level, "Источники воды готовы.");
            // Успокоители трафика (traffic calming)
            broadcast(level, "Старт генерации успокоителей трафика…");
            TrafficCalmingGenerator tcGen = new TrafficCalmingGenerator(level, coords, store);
            tcGen.generate();
            broadcast(level, "Успокоители трафика готовы.");
            // Пожарные гидранты
            broadcast(level, "Старт генерации пожарных гидрантов…");
            FireHydrantGenerator hydrGen = new FireHydrantGenerator(level, coords, store);
            hydrGen.generate();
            broadcast(level, "Пожарные гидранты готовы.");
            // Паркоматы и автоматы оплаты парковки
            broadcast(level, "Старт генерации паркоматов…");
            ParkingMetersGenerator pmGen = new ParkingMetersGenerator(level, coords, store);
            pmGen.generate();
            broadcast(level, "Паркоматы готовы.");
            // Велопарковки
            broadcast(level, "Старт генерации велопарковок…");
            BicycleParkingGenerator bpGen = new BicycleParkingGenerator(level, coords, store);
            bpGen.generate();
            broadcast(level, "Велопарковки готовы.");
            // Почтовые ящики
            broadcast(level, "Старт генерации почтовых ящиков…");
            PostBoxGenerator postGen = new PostBoxGenerator(level, coords, store);
            postGen.generate();
            broadcast(level, "Почтовые ящики готовы.");
            // Камеры (скорости и видеонаблюдение)
            broadcast(level, "Старт генерации камер…");
            CameraGenerator camGen = new CameraGenerator(level, coords, store);
            camGen.generate();
            broadcast(level, "Камеры готовы.");
            // Информационные стенды / табло / указатели 
            broadcast(level, "Старт генерации инфостендов…");
            InfoBoardsGenerator infoGen = new InfoBoardsGenerator(level, coords, store);
            infoGen.generate();
            broadcast(level, "Инфостенды готовы.");
            // Ульи и пасеки
            broadcast(level, "Старт генерации ульев…");
            ApiaryBeehivesGenerator bees = new ApiaryBeehivesGenerator(level, coords, store);
            bees.generate();
            broadcast(level, "Ульи готовы.");
            // Карьеры, шахты
            broadcast(level, "Старт генерации ископаемых…");
            MiningOresScatterGenerator miningGen = new MiningOresScatterGenerator(level, coords, store);
            miningGen.generate();
            broadcast(level, "Ископаемые готовы.");
            // Аэродромные флажки (windsock)
            broadcast(level, "Старт генерации флажков (windsock)...");
            WindsockFlagsGenerator windsockGen = new WindsockFlagsGenerator(level, coords, store);
            windsockGen.generate();
            broadcast(level, "Флажки готовы.");
            // Стройплощадки (landuse=construction)
            broadcast(level, "Старт благоустройства стройплощадок…");
            ConstructionSiteDecorator cons = new ConstructionSiteDecorator(level, coords, store);
            cons.generate();
            broadcast(level, "Стройплощадки оформлены.");
            // Краны 
            broadcast(level, "Старт генерации кранов (категория 1)...");
            CraneGenerator craneGen = new CraneGenerator(level, coords, store);
            craneGen.generate();
            broadcast(level, "Краны (категория 1) готовы.");
            // Реклама
            broadcast(level, "Старт генерации рекламы…");
            AdvertisingGenerator adGen = new AdvertisingGenerator(level, coords, store);
            adGen.generate();
            broadcast(level, "Реклама готова.");








// Фонтаны
// из блоков смус кварц блок строим.
// на уровне земли ставим квадрат со стороной 5 блоков
// далее по периметру этого квадрата ставим еще блоки на один уровень
// далее в центре нашего квадрата ставим столб из блоков (по дефолтку 5 блоков, если не указана высота фонатана, а если указана - то берем ее)
// и на верхушку этого столба ставим источник воды




// Памятники, Арт-объекты, обелиски и тд и тп
// Драг лифты - поверхностные подъёмники
// Фуникулёры  aerialway

// =============================================



































// ==========================================================================================





            // ===== РАСТИТЕЛЬНОСТЬ ======
// Поля с посевами - по цветам
// Всё засадить соответствующими типами деревьев, травы и тд
// Учитывать зоны - просеки под ЛЭП и тд
// Рассаживать везде, но учитывать интенсивность от зон
// кусты leaf_type	broadleaved natural	tree_row и тп
// отдельные деревья natural	tree
// 	tree_row стены из деревьев
// barrier	hedge добавить в заборы из листвы




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
