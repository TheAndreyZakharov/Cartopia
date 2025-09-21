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
            // Рельеф
            SurfaceGenerator surface = new SurfaceGenerator(level, coords, demTifFile, landcoverTifFileOrNull);
            surface.generate();
            broadcast(level, "Поверхность готова.");

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

            // Сразу поднимаем всех игроков этого мира на безопасную поверхность
            broadcast(level, "Переставляю игроков на поверхность…");
            CartopiaSurfaceSpawn.adjustAllPlayersAsync(level);

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
