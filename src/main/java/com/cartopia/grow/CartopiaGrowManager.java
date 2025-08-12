package com.cartopia.grow;

import com.cartopia.CartopiaMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CartopiaMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CartopiaGrowManager {

    // радиус — сколько чанков от игрока в каждую сторону
    private static final int RADIUS_CHUNKS = 12;
    private static final int SAPLING_TICKS = 64;          // нормально
    private static final int MAX_SAPLINGS_PER_TICK_PER_PLAYER = 15000; // общий бюджет на тик/игрока

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        if (server == null) return;

        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                SaplingBooster.boostAroundPlayer(
                        level, player,
                        RADIUS_CHUNKS,
                        SAPLING_TICKS,
                        MAX_SAPLINGS_PER_TICK_PER_PLAYER
                );
            }
        }
    }
}

