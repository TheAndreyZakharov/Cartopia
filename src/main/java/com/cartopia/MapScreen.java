package com.cartopia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
                        Runtime.getRuntime().exec(new String[]{"open", "http://localhost:4567"});
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
