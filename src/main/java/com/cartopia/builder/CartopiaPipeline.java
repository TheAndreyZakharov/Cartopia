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
        broadcast(level, "Loading coordinates/parameters...");
        // === Подготовка сайдкаров (стрим-режим) ===
        com.cartopia.store.GenerationStore store = null;
        try {
            store = com.cartopia.store.GenerationStore.prepare(coordsJsonFile.getParentFile(), coordsJsonFile);
            broadcast(level, "Prepared sidecars: features NDJSON and terrain grid (if available).");
        } catch (Exception splitErr) {
            broadcast(level, "Warning: failed to prepare sidecars: " + splitErr.getMessage());
            // store останется null — генераторы уйдут в fallback на coords.json
        }
        String json = Files.readString(coordsJsonFile.toPath(), StandardCharsets.UTF_8);
        JsonObject coords = JsonParser.parseString(json).getAsJsonObject();
        if (demTifFile == null) {
            throw new IllegalStateException("DEM file = null");
        }
        broadcast(level, "DEM: " + demTifFile.getAbsolutePath() + " (" + demTifFile.length() + " bytes)"
                + (demTifFile.exists() ? "" : " [FILE NOT FOUND]"));
        if (landcoverTifFileOrNull != null) {
            broadcast(level, "OLM: " + landcoverTifFileOrNull.getAbsolutePath() + " (" + landcoverTifFileOrNull.length() + " bytes)"
                    + (landcoverTifFileOrNull.exists() ? "" : " [FILE NOT FOUND]"));
        }
        broadcast(level, "Starting surface generation (DEM + painting) ...");
        try {


            


// ==========================================================================================
            // ===== РЕЛЬЕФ И ЕГО РАСКРАСКА, ДОРОГИ, ЖД =====
            // Рельеф
            SurfaceGenerator surface = new SurfaceGenerator(level, coords, demTifFile, landcoverTifFileOrNull, store);
            surface.generate();
            broadcast(level, "Surface ready.");
            // Сразу поднимаем всех игроков этого мира на безопасную поверхность
            broadcast(level, "Moving players to the surface...");
            CartopiaSurfaceSpawn.adjustAllPlayersAsync(level);
            // Дороги
            broadcast(level, "Starting road generation...");
            RoadGenerator roads = new RoadGenerator(level, coords, store);
            roads.generate();
            broadcast(level, "Roads ready.");
            // Рельсы
            broadcast(level, "Starting rail generation...");
            RailGenerator rails = new RailGenerator(level, coords, store);
            rails.generate();
            broadcast(level, "Rails ready.");
            // Пирсы
            broadcast(level, "Starting pier generation...");
            PierGenerator piers = new PierGenerator(level, coords, store);
            piers.generate();
            broadcast(level, "Piers ready.");
// ==========================================================================================
            // ===== РАЗМЕТКА =====
            // Пешеходные переходы
            broadcast(level, "Starting crosswalk marking...");
            CrosswalkGenerator crosswalks = new CrosswalkGenerator(level, coords, store);
            crosswalks.generate();
            broadcast(level, "Crosswalks ready.");
            // Разметка 1.17 у остановок (ёлочка)
            broadcast(level, "Starting stop markings...");
            StopMarkingGenerator busStops = new StopMarkingGenerator(level, coords, store);
            busStops.generate();
            broadcast(level, "Stop markings ready.");
            // ЖД переезды — стоп-линии
            broadcast(level, "Starting stop lines at railway crossings...");
            RailStopLineGenerator rxl = new RailStopLineGenerator(level, coords, store);
            rxl.generate();
            broadcast(level, "Stop lines at railway crossings ready.");
            // Вертолётные площадки
            broadcast(level, "Starting helipad generation...");
            HelipadGenerator helipads = new HelipadGenerator(level, coords, store);
            helipads.generate();
            broadcast(level, "Helipads ready.");
            // Парковочные места
            broadcast(level, "Starting parking stall marking...");
            ParkingStallGenerator stalls = new ParkingStallGenerator(level, coords, store);
            stalls.generate();
            broadcast(level, "Parking stalls ready.");
// ==========================================================================================
            // ===== МОСТЫ / ТУННЕЛИ =====
            // Мосты/эстакады (без тоннелей)
            broadcast(level, "Starting bridge/overpass generation...");
            BridgeGenerator bridges = new BridgeGenerator(level, coords, store);
            bridges.generate();
            broadcast(level, "Bridges/overpasses ready.");
            // Тоннели и подземные переходы (дороги и ЖД по логике «как мост, но вниз»)
            broadcast(level, "Starting tunnel/underpass generation...");
            TunnelGenerator tunnels = new TunnelGenerator(level, coords, store);
            tunnels.generate();
            broadcast(level, "Tunnels/underpasses ready.");
            // Дорожная кнопочная разметка
            broadcast(level, "Starting road button markings...");
            RoadButtonMarkingGenerator roadButtons = new RoadButtonMarkingGenerator(level, coords, store);
            roadButtons.generate();
            broadcast(level, "Road button markings ready.");
// ==========================================================================================
            // ===== ЗДАНИЯ =====
            // Здания
            broadcast(level, "Starting building generation...");
            BuildingGenerator buildings = new BuildingGenerator(level, coords, store);
            buildings.generate();
            broadcast(level, "Buildings ready.");
// ==========================================================================================
            // ===== ОСВЕЩЕНИЕ =====
            // Дорожные фонари
            broadcast(level, "Starting placement of road lamps...");
            RoadLampGenerator roadLamps = new RoadLampGenerator(level, coords, store);
            roadLamps.generate();
            broadcast(level, "Road lamps ready.");
            // Фонари вдоль рельсов
            broadcast(level, "Starting placement of lamps along rails...");
            RailLampGenerator railLamps = new RailLampGenerator(level, coords, store);
            railLamps.generate();
            broadcast(level, "Lamps along rails ready.");
// ==========================================================================================
            // ===== ИНФРАСТРУКТУРА =====
            // Утилитарные уличные боксы однотипно
            broadcast(level, "Starting utility box generation...");
            UtilityBoxGenerator utilBoxGen = new UtilityBoxGenerator(level, coords, store);
            utilBoxGen.generate();
            broadcast(level, "Utility boxes ready.");
            // Маяки
            broadcast(level, "Starting lighthouse generation...");
            LighthouseGenerator lighthouseGen = new LighthouseGenerator(level, coords, store);
            lighthouseGen.generate();
            broadcast(level, "Lighthouses ready.");
            // Ветряные турбины
            broadcast(level, "Starting wind turbine generation...");
            WindTurbineGenerator wtGen = new WindTurbineGenerator(level, coords, store);
            wtGen.generate();
            broadcast(level, "Wind turbines ready.");
            // Ветряные мельницы 
            broadcast(level, "Starting windmill generation...");
            ClassicWindmillGenerator wmGen = new ClassicWindmillGenerator(level, coords, store);
            wmGen.generate();
            broadcast(level, "Windmills ready.");
            // Наблюдательные вышки
            broadcast(level, "Starting watchtower generation...");
            WatchtowerGenerator wtowerGen = new WatchtowerGenerator(level, coords, store);
            wtowerGen.generate();
            broadcast(level, "Towers ready.");
            // Трубы (дымоходы)
            broadcast(level, "Starting chimney generation...");
            ChimneyGenerator chGen = new ChimneyGenerator(level, coords, store);
            chGen.generate();
            broadcast(level, "Chimneys ready.");
            // Вышки/мачты
            broadcast(level, "Starting tower/mast generation...");
            TowerMastGenerator tmGen = new TowerMastGenerator(level, coords, store);
            tmGen.generate();
            broadcast(level, "Towers ready.");
            // Башни-резервуары
            broadcast(level, "Starting utility tank tower generation...");
            UtilityTankTowerGenerator tankGen = new UtilityTankTowerGenerator(level, coords, store);
            tankGen.generate();
            broadcast(level, "Utility tank towers ready.");
            // Солнечные панели
            broadcast(level, "Starting solar array generation...");
            SolarPanelGenerator solarGen = new SolarPanelGenerator(level, coords, store);
            solarGen.generate();
            broadcast(level, "Solar arrays ready.");
            // Электроподстанции
            broadcast(level, "Starting substation generation...");
            SubstationGenerator subGen = new SubstationGenerator(level, coords, store);
            subGen.generate();
            broadcast(level, "Substations ready.");
            // ЛЭП: столбы, вышки и провода
            broadcast(level, "Starting power lines (poles/towers/wires) generation...");
            PowerLinesGenerator plGen = new PowerLinesGenerator(level, coords, store);
            plGen.generate();
            broadcast(level, "Power lines ready.");
            // Бензоколонки на АЗС
            broadcast(level, "Starting fuel pump generation...");
            FuelPumpGenerator fuelGen = new FuelPumpGenerator(level, coords, store);
            fuelGen.generate();
            broadcast(level, "Fuel pumps ready.");
            // Автомойки
            broadcast(level, "Starting car wash generation...");
            CarWashGenerator washGen = new CarWashGenerator(level, coords, store); 
            washGen.generate();
            broadcast(level, "Car washes ready.");
            // Электрозарядки
            broadcast(level, "Starting EV charger generation...");
            ElectricChargerGenerator evGen = new ElectricChargerGenerator(level, coords, store);
            evGen.generate();
            broadcast(level, "EV chargers ready.");
            // Места отдыха: скамейки, столы, BBQ, беседки, палатки
            broadcast(level, "Starting rest area generation...");
            LeisureRestGenerator leisureGen = new LeisureRestGenerator(level, coords, store);
            leisureGen.generate();
            broadcast(level, "Rest areas ready.");
            // Пляжный отдых (лежаки)
            broadcast(level, "Starting beach area generation...");
            BeachResortGenerator beachGen = new BeachResortGenerator(level, coords, store);
            beachGen.generate();
            broadcast(level, "Beach areas ready.");
            // Спортплощадки: футбол, баскетбол, теннис, волейбол/бадминтон, гольф, стрельбища, фитнес
            broadcast(level, "Starting sports facility generation...");
            SportsFacilitiesGenerator sportsGen = new SportsFacilitiesGenerator(level, coords, store);
            sportsGen.generate();
            broadcast(level, "Sports facilities ready.");
            // Мусорная инфраструктура: урны, переработка, площадки под мусор
            broadcast(level, "Starting waste infrastructure generation...");
            WasteGenerator wasteGen = new WasteGenerator(level, coords, store);
            wasteGen.generate();
            broadcast(level, "Waste infrastructure ready.");
            // Надземные трубопроводы
            broadcast(level, "Starting overground pipeline generation...");
            OvergroundPipelinesGenerator pipesGen = new OvergroundPipelinesGenerator(level, coords, store);
            pipesGen.generate();
            broadcast(level, "Overground pipelines ready.");
            // Остановочные павильоны
            broadcast(level, "Starting stop shelter generation...");
            PublicTransportShelterGenerator shelterGen = new PublicTransportShelterGenerator(level, coords, store);
            shelterGen.generate();
            broadcast(level, "Shelters ready.");
            // Светофоры
            broadcast(level, "Starting traffic light generation");
            TrafficLightGenerator tlGen = new TrafficLightGenerator(level, coords, store);
            tlGen.generate();
            broadcast(level, "Traffic lights ready.");
            // Флагштоки
            broadcast(level, "Starting flagpole generation");
            FlagpoleGenerator fpGen = new FlagpoleGenerator(level, coords, store);
            fpGen.generate();
            broadcast(level, "Flagpoles ready.");
            // Адресные точки - простые дома 
            broadcast(level, "Starting address house generation...");
            AddressPointBuildingsGenerator addrGen = new AddressPointBuildingsGenerator(level, coords, store);
            addrGen.generate();
            broadcast(level, "Address houses built.");
            // Ограждения, заборы, отбойники
            broadcast(level, "Starting fences/barriers/guardrails generation...");
            FenceAndBarrierGenerator fenceGen = new FenceAndBarrierGenerator(level, coords, store);
            fenceGen.generate();
            broadcast(level, "Fences/barriers/guardrails ready.");
            // Кладбища (надгробия)
            broadcast(level, "Starting cemetery generation.");
            CemeteryGravesGenerator cemGen = new CemeteryGravesGenerator(level, coords, store);
            cemGen.generate();
            broadcast(level, "Cemeteries ready.");
            // Источники воды (колонки/колодцы/питьевые точки)
            broadcast(level, "Starting water source generation...");
            WaterSourcesGenerator waterGen = new WaterSourcesGenerator(level, coords, store);
            waterGen.generate();
            broadcast(level, "Water sources ready.");
            // Успокоители трафика (traffic calming)
            broadcast(level, "Starting traffic calming generation...");
            TrafficCalmingGenerator tcGen = new TrafficCalmingGenerator(level, coords, store);
            tcGen.generate();
            broadcast(level, "Traffic calming ready.");
            // Пожарные гидранты
            broadcast(level, "Starting fire hydrant generation...");
            FireHydrantGenerator hydrGen = new FireHydrantGenerator(level, coords, store);
            hydrGen.generate();
            broadcast(level, "Fire hydrants ready.");
            // Паркоматы и автоматы оплаты парковки
            broadcast(level, "Starting parking meter generation...");
            ParkingMetersGenerator pmGen = new ParkingMetersGenerator(level, coords, store);
            pmGen.generate();
            broadcast(level, "Parking meters ready.");
            // Велопарковки
            broadcast(level, "Starting bicycle parking generation...");
            BicycleParkingGenerator bpGen = new BicycleParkingGenerator(level, coords, store);
            bpGen.generate();
            broadcast(level, "Bicycle parking ready.");
            // Почтовые ящики
            broadcast(level, "Starting postbox generation");
            PostBoxGenerator postGen = new PostBoxGenerator(level, coords, store);
            postGen.generate();
            broadcast(level, "Postboxes ready.");
            // Камеры (скорости и видеонаблюдение)
            broadcast(level, "Starting camera generation...");
            CameraGenerator camGen = new CameraGenerator(level, coords, store);
            camGen.generate();
            broadcast(level, "Cameras ready.");
            // Информационные стенды / табло / указатели 
            broadcast(level, "Starting information board generation...");
            InfoBoardsGenerator infoGen = new InfoBoardsGenerator(level, coords, store);
            infoGen.generate();
            broadcast(level, "Information boards ready.");
            // Ульи и пасеки
            broadcast(level, "Starting beehive generation...");
            ApiaryBeehivesGenerator bees = new ApiaryBeehivesGenerator(level, coords, store);
            bees.generate();
            broadcast(level, "Beehives ready.");
            // Карьеры, шахты
            broadcast(level, "Starting mineral generation...");
            MiningOresScatterGenerator miningGen = new MiningOresScatterGenerator(level, coords, store);
            miningGen.generate();
            broadcast(level, "Minerals ready.");
            // Аэродромные флажки (windsock)
            broadcast(level, "Starting windsock generation");
            WindsockFlagsGenerator windsockGen = new WindsockFlagsGenerator(level, coords, store);
            windsockGen.generate();
            broadcast(level, "Windsocks ready.");
            // Стройплощадки (landuse=construction)
            broadcast(level, "Starting construction site landscaping...");
            ConstructionSiteDecorator cons = new ConstructionSiteDecorator(level, coords, store);
            cons.generate();
            broadcast(level, "Construction sites decorated.");
            // Краны 
            broadcast(level, "Starting crane generation ...");
            CraneGenerator craneGen = new CraneGenerator(level, coords, store);
            craneGen.generate();
            broadcast(level, "Cranes ready.");
            // Реклама
            broadcast(level, "Starting advertising generation...");
            AdvertisingGenerator adGen = new AdvertisingGenerator(level, coords, store);
            adGen.generate();
            broadcast(level, "Advertising ready.");
            // Фонтаны
            broadcast(level, "Starting fountain generation...");
            FountainGenerator fGen = new FountainGenerator(level, coords, store);
            fGen.generate();
            broadcast(level, "Fountains ready.");
            // Памятники, арт-объекты, монументы
            broadcast(level, "Starting monument generation...");
            MonumentGenerator monGen = new MonumentGenerator(level, coords, store);
            monGen.generate();
            broadcast(level, "Monuments ready.");
            // Подъёмники
            broadcast(level, "Starting lift generation...");
            AerialwayGenerator awGen = new AerialwayGenerator(level, coords, store);
            awGen.generate();
            broadcast(level, "Lifts ready.");
            // Входы в пещеры
            broadcast(level, "Starting cave entrance generation...");
            CaveEntranceGenerator caveGen = new CaveEntranceGenerator(level, coords, store);
            caveGen.generate();
            broadcast(level, "Cave entrances ready.");
// ==========================================================================================
            // ===== РАСТИТЕЛЬНОСТЬ ======
            // Растительность
            broadcast(level, "Starting vegetation generation...");
            VegetationScatterGenerator vegGen = new VegetationScatterGenerator(level, coords, store);
            vegGen.generate();
            broadcast(level, "Vegetation ready.");
// ==========================================================================================
            // ===== ПОГОДА И ВРЕМЯ =====
            try {
                com.cartopia.weather.WeatherTimeController.start(level, coords);
                broadcast(level, "Time/weather controller started (1 request/hour, previous hour).");
            } catch (Exception e) {
                broadcast(level, "НFailed to start weather/time controller " + e.getMessage());
            }
// ==========================================================================================
            // ===== СОХРАНЕНИЕ =====
            broadcast(level, "Saving world...");
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
            broadcast(level, "Generation error: " + cls + (msg == null ? "" : (": " + msg)) + causeStr);
            System.err.println("[Cartopia] --- STACKTRACE START ---");
            e.printStackTrace();
            System.err.println("[Cartopia] --- STACKTRACE END ---");
            throw e;
        } finally {
            try { if (store != null) store.close(); } catch (Exception ignore) {}
        }
    }
}
