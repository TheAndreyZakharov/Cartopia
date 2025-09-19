package com.cartopia.builder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class BuildHttpServer {
    private static HttpServer httpServer;
    private static volatile boolean welcomeSent = false;

    public static void start() {
        if (httpServer != null) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 4568), 0);

            httpServer.createContext("/build", BuildHttpServer::handleBuild);

            // /health работает и на интегрированном, и на выделенном сервере
            httpServer.createContext("/health", ex -> {
                try {
                    MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                    String status = (s != null) ? "UP" : "STARTING";
                    byte[] data = status.getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, data.length);
                    ex.getResponseBody().write(data);
                } finally { ex.close(); }
            });

            httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            httpServer.start();
            System.out.println("[Cartopia] BuildHttpServer started on 127.0.0.1:4568");

            // Мягкая подсказка один раз, когда есть игрок
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                        if (s != null && !welcomeSent && !s.getPlayerList().getPlayers().isEmpty()) {
                            final String[] lines = new String[] {
                                "Генератор Cartopia готов.",
                                "1) Через веб/страницу задайте прямоугольник и нажмите Build.",
                                "2) Не выходите из мира, пока идёт генерация.",
                                "Подсказка: финальное сообщение будет: 'Generation finished.'"
                            };
                            s.execute(() -> { for (String line : lines) broadcast(s, line); });
                            welcomeSent = true;
                        }
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) { return; }
                    catch (Throwable ignore) {}
                }
            }, "CartopiaWelcome");
            t.setDaemon(true);
            t.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleBuild(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); return;
        }

        byte[] body = ex.getRequestBody().readAllBytes();
        String json = new String(body, StandardCharsets.UTF_8);

        String coordsPath, demPath, landcoverPath = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            coordsPath    = root.get("coordsPath").getAsString();
            demPath       = root.get("demPath").getAsString();
            if (root.has("landcoverPath") && !root.get("landcoverPath").isJsonNull()) {
                landcoverPath = root.get("landcoverPath").getAsString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            ex.sendResponseHeaders(400, -1);
            return;
        }

        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) {
            byte[] resp = "Server not ready".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(503, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
            return;
        }

        String finalLandcoverPath = landcoverPath; // чтоб было «effectively final» внутри лямбды
        s.execute(() -> {
            try {
                broadcast(s, "Старт генерации… не выходите из мира.");
                ServerLevel level = s.overworld();
                CartopiaPipeline.run(
                    level,
                    Path.of(coordsPath).toFile(),
                    Path.of(demPath).toFile(),
                    finalLandcoverPath != null ? Path.of(finalLandcoverPath).toFile() : null
                );
                broadcast(s, "Генерация завершена. Можно исследовать область!");
            } catch (Exception e) {
                e.printStackTrace();
                broadcast(s, "Ошибка генерации: " + e.getMessage());
            }
        });

        byte[] resp = "OK".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, resp.length);
        ex.getResponseBody().write(resp);
        ex.close();
    }

    private static void broadcast(MinecraftServer s, String msg) {
        try {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
            }
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }
}
