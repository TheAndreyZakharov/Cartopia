package com.cartopia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MapScreen extends Screen {

    protected MapScreen() {
        super(Component.literal("Выбор карты"));
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(
                Component.literal("Открыть карту"),
                button -> {
                    try {
                        Player player = Minecraft.getInstance().player;
                        double px = player.getX();
                        double pz = player.getZ();

                        // Запускаем браузер
                        Runtime.getRuntime().exec(new String[]{"open", "http://localhost:4567"});

                        // Сохраняем в tmp файл координаты игрока
                        String json = String.format("{\"x\": %.2f, \"z\": %.2f}", px, pz);
                        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:4567/player").openConnection();
                        conn.setDoOutput(true);
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(json.getBytes());
                        }
                        conn.getResponseCode(); // чтобы отправилось
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .pos(this.width / 2 - 100, this.height / 2)
                .size(200, 20)
                .build());
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }
}
