package com.cartopia;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CartopiaMod.MODID)
public class CartopiaMod {
    public static final String MODID = "cartopia";

    public CartopiaMod(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);

        // СЕРВЕРНЫЕ события — запуск/остановка нашего веб-сервера
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);

        // Подписки на обычной шине (клавиши и т.п.)
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(final FMLCommonSetupEvent e) {
        // Ничего не запускаем здесь, чтобы не плодить сервер до старта мира.
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // клиентские биндинги, UI и т.п.
    }

    private void onServerStarting(ServerStartingEvent e) {
        com.cartopia.builder.BuildHttpServer.start();
    }

    private void onServerStopping(ServerStoppingEvent e) {
        com.cartopia.builder.BuildHttpServer.stop();
    }

    @OnlyIn(Dist.CLIENT)
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onKeyPress(InputEvent.Key event) {
        if (KeyBindings.openMapKey != null && KeyBindings.openMapKey.isDown()) {
            MapScreen.open();
        }
    }
}