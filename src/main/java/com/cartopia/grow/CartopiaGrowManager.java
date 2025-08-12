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

    // Радиус — сколько чанков от игрока в каждую сторону
    private static final int RADIUS_CHUNKS = 12;

    // Сколько "псевдо-рандом-тиков" подряд давать каждой целевой растюхе
    private static final int GROW_TICKS_EACH = 64;

    // Жёсткий потолок на количество обработанных растений за один серверный тик на игрока
    private static final int MAX_TARGETS_PER_TICK_PER_PLAYER = 15_000;

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
                        GROW_TICKS_EACH,
                        MAX_TARGETS_PER_TICK_PER_PLAYER
                );
            }
        }
    }
}
