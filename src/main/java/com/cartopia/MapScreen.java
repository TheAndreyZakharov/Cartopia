package com.cartopia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;


public class MapScreen extends Screen {

    protected MapScreen() {
        super(Component.literal("Выбор карты"));
    }

    private boolean realtimeEnabled = true; // значение подтянем с сервера

    @Override
    protected void init() {
        // 1) Синхронно спросим сервер состояние (локальный — ответит мгновенно)
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/realtime").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            if (conn.getResponseCode() / 100 == 2) {
                String s = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                // очень простенький парсинг без Gson
                this.realtimeEnabled = s.contains("\"enabled\":true");
            }
        } catch (Exception ignore) {}

        // 2) Кнопка «Открыть карту»
        this.addRenderableWidget(Button.builder(
                Component.literal("Открыть карту"),
                button -> {
                    try {
                        Player player = Minecraft.getInstance().player;
                        if (player == null) return;
                        double px = player.getX();
                        double pz = player.getZ();

                        // Браузер
                        Runtime.getRuntime().exec(new String[]{"open", "http://localhost:4567"});

                        // Отправим координаты
                        String json = String.format("{\"x\": %.2f, \"z\": %.2f}", px, pz);
                        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/player").openConnection();
                        conn.setDoOutput(true);
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(json.getBytes(StandardCharsets.UTF_8));
                        }
                        conn.getResponseCode();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .pos(this.width / 2 - 100, this.height / 2 - 10)
                .size(200, 20)
                .build());

        // 3) Тумблер «Реальное время/погода»
        CycleButton<Boolean> toggle = CycleButton.<Boolean>builder(val ->
                        val ? Component.literal("ВКЛ") : Component.literal("ВЫКЛ"))
                .withValues(Boolean.TRUE, Boolean.FALSE)
                .withInitialValue(realtimeEnabled)
                .create(
                        this.width / 2 - 100,
                        this.height / 2 + 20,
                        200, 20,
                        Component.literal("Реальное время/погода"),
                        (btn, value) -> {
                            this.realtimeEnabled = value;
                            try {
                                String json = "{\"enabled\":" + (value ? "true" : "false") + "}";
                                HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/realtime").openConnection();
                                conn.setDoOutput(true);
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty("Content-Type", "application/json");
                                try (OutputStream os = conn.getOutputStream()) {
                                    os.write(json.getBytes(StandardCharsets.UTF_8));
                                }
                                conn.getResponseCode();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });

        this.addRenderableWidget(toggle);
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }
}