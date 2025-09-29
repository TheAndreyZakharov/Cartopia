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
            SurfaceGenerator surface = new SurfaceGenerator(level, coords, demTifFile, landcoverTifFileOrNull);
            surface.generate();
            broadcast(level, "Поверхность готова.");
            // Сразу поднимаем всех игроков этого мира на безопасную поверхность
            broadcast(level, "Переставляю игроков на поверхность…");
            CartopiaSurfaceSpawn.adjustAllPlayersAsync(level);
            // Дороги
            broadcast(level, "Старт генерации дорог…");
            RoadGenerator roads = new RoadGenerator(level, coords);
            roads.generate();
            broadcast(level, "Дороги готовы.");
            // Рельсы
            broadcast(level, "Старт генерации рельсов…");
            RailGenerator rails = new RailGenerator(level, coords);
            rails.generate();
            broadcast(level, "Рельсы готовы.");
// Пирсы, причалы, швартовые





            // ===== РАЗМЕТКА =====
// Переходы
// Остановки общественного транспорта
// Жд переезды
// Вертолетные площадки
// Парковки авто





            // ===== ОСВЕЩЕНИЕ =====
            // Дорожные фонари
            broadcast(level, "Старт расстановки дорожных фонарей…");
            RoadLampGenerator roadLamps = new RoadLampGenerator(level, coords);
            roadLamps.generate();
            broadcast(level, "Дорожные фонари готовы.");
            // Фонари вдоль рельсов
            broadcast(level, "Старт расстановки фонарей вдоль рельсов…");
            RailLampGenerator railLamps = new RailLampGenerator(level, coords);
            railLamps.generate();
            broadcast(level, "Фонари вдоль рельсов готовы.");
// Фонари в зонах аэропортов, портов и тд
// Фонари на парковках





            // ===== ЗДАНИЯ =====
// Здания
            /*
            BuildingGenerator.java
            broadcast(level, "Старт генерации зданий…");
            BuildingGenerator buildings = new BuildingGenerator(level, coords);
            buildings.generate();
            broadcast(level, "Здания готовы.");
            */
// Башни - Товерс - Трубы и тд
// Бункеры
// Общественные туалеты
// Радиотелескопы
// Оптические телескопы





            // ===== МОСТЫ / ТУННЕЛИ =====
            // Мосты/эстакады (без тоннелей)
            broadcast(level, "Старт генерации мостов/эстакад…");
            BridgeGenerator bridges = new BridgeGenerator(level, coords);
            bridges.generate();
            broadcast(level, "Мосты/эстакады готовы.");
            // Тоннели и подземные переходы (дороги и ЖД по логике «как мост, но вниз»)
            broadcast(level, "Старт генерации тоннелей/подземных переходов…");
            TunnelGenerator tunnels = new TunnelGenerator(level, coords);
            tunnels.generate();
            broadcast(level, "Тоннели/подземные переходы готовы.");





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
// Фонтаны
// Источники воды, колодцы, с питьевой водой
// Пожарные гидранты
// Памятники
// Высоковольтные линии, ЛЭП и тд + провода
// Солнечные батареи
// Открытые ископаемые в шахтах
// Места строек отдельно оформить - заборы, башенные краны
// Драг лифты - поверхностные подъёмники
// ? Фуникулёры ? 
// ? Рекламные щиты, сденды ? 
// ? Антенны большие ? 





            // ===== РАСТИТЕЛЬНОСТЬ ======
// Всё засадить соответствующими типами деревьев, травы и тд





            // + Время, Погода; Оживление города





            // ===== СОХРАНЕНИЕ =====
            broadcast(level, "Сохраняю мир…");
            level.save(null, true, false);
            broadcast(level, "Generation finished.");
        } catch (Exception e) {
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
        }
    }
}
