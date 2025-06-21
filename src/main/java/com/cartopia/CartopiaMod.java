package com.cartopia;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CartopiaMod.MODID)
public class CartopiaMod {

    public static final String MODID = "cartopia";

    public CartopiaMod(FMLJavaModLoadingContext context) {
        // Используем внедрение зависимости вместо устаревшего get()
        context.getModEventBus().addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // Здесь можно инициализировать клиентские вещи (клавиши и т.п.)
    }

    @SubscribeEvent
    public void onKeyPress(InputEvent.Key event) {
        if (KeyBindings.openMapKey != null && KeyBindings.openMapKey.isDown()) {
            MapScreen.open();
        }
    }
}
