package com.cartopia;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;

@Mod(CartopiaMod.MODID)
public class CartopiaMod {
    public static final String MODID = "cartopia";

    public CartopiaMod(FMLJavaModLoadingContext context) { // получаем контекст через параметр конструктора
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(final FMLCommonSetupEvent e) {
        e.enqueueWork(com.cartopia.builder.BuildHttpServer::start);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // клиентские биндинги, UI и т.п.
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onKeyPress(InputEvent.Key event) {
        if (KeyBindings.openMapKey != null && KeyBindings.openMapKey.isDown()) {
            MapScreen.open();
        }
    }
}
