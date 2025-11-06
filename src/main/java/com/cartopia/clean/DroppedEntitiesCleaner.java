package com.cartopia.clean;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class DroppedEntitiesCleaner {

    private DroppedEntitiesCleaner() {}

    // ---------- Планировщик задач (тик-обработчик) ----------

    private static final ConcurrentLinkedQueue<Task> TASKS = new ConcurrentLinkedQueue<>();

    private static final class Task {
        final ServerLevel level;
        int ticksLeft;
        final Region regionOrNull; // null => чистим только загруженные чанки
        boolean warmed;            // для регионов: первый проход ставит форс-грузку и ждёт 5 тиков

        Task(ServerLevel level, int delayTicks, Region regionOrNull) {
            this.level = Objects.requireNonNull(level);
            this.ticksLeft = Math.max(0, delayTicks);
            this.regionOrNull = regionOrNull;
            this.warmed = false;
        }
    }

    /** Регион в чанках. */ 
    private static final class Region {
        final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        Region(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
            this.minChunkX = minChunkX;
            this.maxChunkX = maxChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkZ = maxChunkZ;
        }
    }

    static {
        // Регистрируемся на тик-события один раз.
        MinecraftForge.EVENT_BUS.register(DroppedEntitiesCleaner.class);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (TASKS.isEmpty()) return;

        List<Task> done = new ArrayList<>();
        for (Task t : TASKS) {
            if (--t.ticksLeft > 0) continue;

            // Если указан регион и мы ещё не «прогрели» чанки: форс-лоадим и ждём 5 тиков
            if (t.regionOrNull != null && !t.warmed) {
                forceLoadRegion(t.level, t.regionOrNull, true);
                broadcast(t.level, "Cleaner: forced region chunks to load; waiting 5 ticks before cleaning...");
                t.warmed = true;
                t.ticksLeft = 5; // дать чанкам догрузиться
                continue;
            }

            // Чистка
            int removed = cleanNow(t.level);
            broadcast(t.level, "Cleaned " + removed + " dropped items "
                    + (t.regionOrNull != null ? " (across the entire generation area)" : "") + ".");

            // Снять форс у региона (если был)
            if (t.regionOrNull != null) {
                forceLoadRegion(t.level, t.regionOrNull, false);
            }
            done.add(t);
        }
        if (!done.isEmpty()) TASKS.removeAll(done);
    }

    // ---------- Публичные API ----------

    /** Запланировать очистку только по уже загруженным чанкам этого мира. */
    public static void schedule(ServerLevel level, int delayTicks) {
        if (level == null) return;
        TASKS.add(new Task(level, delayTicks, null));
    }

    /**
     * Запланировать очистку по всей области генерации (на время чистки чанки будут форс-загружены).
     * Предпочитает coords.terrainGrid (точные границы), иначе берёт квадрат sizeMeters вокруг player.x/z.
     */
    public static void scheduleForCoords(ServerLevel level, JsonObject coords, int delayTicks) {
        if (level == null) return;
        Region r = computeRegionFromCoords(coords);
        TASKS.add(new Task(level, delayTicks, r));
    }

    // ---------- Реализация ----------

    /** Немедленно удалить все ItemEntity в текущих ЗАГРУЖЕННЫХ чанках мира. */
    public static int cleanNow(ServerLevel level) {
        if (level == null) return 0;
        int removed = 0;
        try {
            List<? extends ItemEntity> items =
                    level.getEntities(EntityTypeTest.forClass(ItemEntity.class), e -> true);
            for (ItemEntity it : items) {
                it.discard(); // безопасное удаление
                removed++;
            }
        } catch (Throwable t) {
            broadcast(level, "Cleaner: error during cleaning" + t.getClass().getSimpleName());
        }
        return removed;
    }

    /** Вычислить регион по coords. */
    private static Region computeRegionFromCoords(JsonObject coords) {
        // Если координат нет — не вычисляем регион (почистим только загруженные чанки)
        if (coords == null) return null;

        // 1) Предпочитаем точные границы terrainGrid
        try {
            if (coords.has("terrainGrid") && coords.get("terrainGrid").isJsonObject()) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                if (g.has("minX") && g.has("minZ") && g.has("width") && g.has("height")) {
                    int minX = g.get("minX").getAsInt();
                    int minZ = g.get("minZ").getAsInt();
                    int w    = g.get("width").getAsInt();
                    int h    = g.get("height").getAsInt();
                    int maxX = minX + Math.max(0, w) - 1;
                    int maxZ = minZ + Math.max(0, h) - 1;
                    return new Region(minX >> 4, maxX >> 4, minZ >> 4, maxZ >> 4);
                } 
            } 
        } catch (Throwable ignore) {} 

        // 2) Fallback: квадрат sizeMeters вокруг player.x/z
        try {
            int size = 512; // дефолт, если поля нет
            if (coords.has("sizeMeters")) {
                size = Math.max(64, coords.get("sizeMeters").getAsInt());
            }
            int half = Math.max(32, size / 2);

            int cx = 0, cz = 0;
            if (coords.has("player") && coords.get("player").isJsonObject()) {
                JsonObject player = coords.getAsJsonObject("player");
                if (player.has("x")) cx = (int) Math.round(player.get("x").getAsDouble());
                if (player.has("z")) cz = (int) Math.round(player.get("z").getAsDouble());
            }

            int minX = cx - half, maxX = cx + half;
            int minZ = cz - half, maxZ = cz + half;
            return new Region(minX >> 4, maxX >> 4, minZ >> 4, maxZ >> 4);
        } catch (Throwable ignore) {}

        // 3) Совсем ничего не вышло — чистим только загруженное (region=null)
        return null;
    }

    /** Форс-грузка / снятие форс-грузки для всего прямоугольника чанков. */
    private static void forceLoadRegion(ServerLevel level, Region r, boolean force) {
        if (r == null) return;
        for (int cz = r.minChunkZ; cz <= r.maxChunkZ; cz++) {
            for (int cx = r.minChunkX; cx <= r.maxChunkX; cx++) {
                level.setChunkForced(cx, cz, force);
            }
        }
    }

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
}