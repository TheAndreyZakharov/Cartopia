package com.cartopia.builder;
import com.cartopia.spawn.CartopiaSurfaceSpawn;
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
// Башни - Товерс - Трубы, chimney и тд
// Бункеры
// Общественные туалеты
// Радиотелескопы
// Оптические телескопы
// Градирни и тп
// Антенны
// Арки - Припять





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
// Фонари в зонах аэропортов, портов и тд
// Фонари на парковках





            // ===== ИНФРАСТРУКТУРА =====
// Заборы, ограждения
// Надземные трубы
// Остановки общественного транспорта
// Светофоры
// Скамейки
// Урны, Места мусорных баков
// Информационнын стенды, гиды
// Места отдыха
// Просто навесы отдельные
// Спорт площадки
// Кладбища
// Заправки
// Велопарковки
// Поля с посевами - по цветам
// Ветряки
// Маяки
// breakwater волнорезы как утёсы - ширина 2, высота +1, +2 над водой
// Фонтаны
// Источники воды, колодцы, с питьевой водой
// Пожарные гидранты
// Памятники
// Высоковольтные линии, ЛЭП и тд + провода
// Солнечные батареи
// Открытые ископаемые в шахтах
// Места строек отдельно оформить - заборы, башенные краны
// Краны башенные отдельно
// Драг лифты - поверхностные подъёмники
// Пасеки, Фермы
// ? Фуникулёры ? 
// ? Рекламные щиты, сденды, билборды, рекламные телевизоры на домах? 
// ? Антенны большие ? 





            // ===== РАСТИТЕЛЬНОСТЬ ======
// Всё засадить соответствующими типами деревьев, травы и тд





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
