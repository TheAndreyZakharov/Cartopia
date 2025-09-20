package com.cartopia.spawn;

import com.cartopia.CartopiaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.tags.FluidTags;

import java.util.Objects;


@Mod.EventBusSubscriber(modid = CartopiaMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CartopiaSurfaceSpawn {

    private CartopiaSurfaceSpawn() {}

    private static final int MAX_CHUNK_WAIT_TICKS = 40; // подождать до ~2 сек, если чанк не успел загрузиться
    private static final int NEAR_SEARCH_RADIUS   = 24; // радиус поиска ближайшей безопасной точки
    private static final int TELEPORT_THRESHOLD   = 1;  // телепортим если Y отличается хотя бы на 1

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            scheduleAdjust(p, 0);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            scheduleAdjust(p, 0);
        }
    }

    /** Безопасно планируем выполнение на треде сервера, без предупреждений про NPE. */
    private static void schedule(ServerPlayer p, Runnable r) {
        final MinecraftServer srv = p.getServer();
        if (srv != null) srv.execute(r);
    }

    /** План с повторными попытками, пока чанк не будет в памяти. */
    private static void scheduleAdjust(ServerPlayer p, int attempt) {
        schedule(p, () -> adjustNow(p, attempt));
    }

    private static void adjustNow(ServerPlayer player, int attempt) {
        if (player.isRemoved()) return;

        final ServerLevel level = player.serverLevel();
        final int x = Mth.floor(player.getX());
        final int z = Mth.floor(player.getZ());

        // ждём, пока чанк прогрузится
        final int cx = x >> 4;
        final int cz = z >> 4;
        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
            if (attempt < MAX_CHUNK_WAIT_TICKS) {
                scheduleAdjust(player, attempt + 1);
            }
            return;
        }

        BlockPos target = findTopSafe(level, x, z);
        if (target == null) {
            target = findNearestTopSafe(level, x, z, NEAR_SEARCH_RADIUS);
        }
        if (target == null) return;

        int py = Mth.floor(player.getY());
        boolean xzSame = (Mth.floor(player.getX()) == target.getX())
                && (Mth.floor(player.getZ()) == target.getZ());

        boolean needTeleport =
                (Math.abs(py - target.getY()) >= TELEPORT_THRESHOLD)  // сильно различается высота
                || !xzSame                                            // различается X/Z
                || !isSafeStand(level, new BlockPos(x, py, z));       // текущее место небезопасно

        if (needTeleport) {
            player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, player.getYRot(), player.getXRot());
        }
    }

    /** Внешний API: переставить игрока на безопасную поверхность, ассинхронно на треде сервера. */
    public static void adjustPlayerAsync(ServerPlayer p) {
        scheduleAdjust(p, 0);
    }

    /** Внешний API: переставить всех игроков этого уровня (мира) сразу после генерации. */
    public static void adjustAllPlayersAsync(ServerLevel level) {
        final MinecraftServer srv = Objects.requireNonNull(level.getServer(), "ServerLevel.getServer() returned null");
        srv.execute(() -> {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p.serverLevel() == level) {
                    scheduleAdjust(p, 0);
                }
            }
        });
    }

    /** Ищем сверху вниз первую безопасную точку стояния на колонке (x,z). */
    private static BlockPos findTopSafe(ServerLevel level, int x, int z) {
        int top = level.getMaxBuildHeight() - 2;  // -2 чтобы хватило места для головы
        int bottom = level.getMinBuildHeight() + 1;

        for (int y = top; y >= bottom; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (isSafeStand(level, feet)) {
                return feet;
            }
        }
        return null;
    }

    /** Поиск ближайшей безопасной точки вокруг стартовой (по квадратным «кольцам»). */
    private static BlockPos findNearestTopSafe(ServerLevel level, int x, int z, int radius) {
        // r=0..R, проходим периметр квадрата r, чтобы брать ближайшее
        for (int r = 0; r <= radius; r++) {
            // верхняя грань
            for (int dx = -r; dx <= r; dx++) {
                BlockPos p = findTopSafe(level, x + dx, z - r);
                if (p != null) return p;
            }
            // нижняя грань — только если r > 0 (иначе это та же линия)
            if (r > 0) {
                for (int dx = -r; dx <= r; dx++) {
                    BlockPos p = findTopSafe(level, x + dx, z + r);
                    if (p != null) return p;
                }
            }
            // лево/право
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                BlockPos p1 = findTopSafe(level, x - r, z + dz);
                if (p1 != null) return p1;
                BlockPos p2 = findTopSafe(level, x + r, z + dz);
                if (p2 != null) return p2;
            }
        }
        return null;
    }

    /** Можно ли безопасно стоять в (feet): твёрдо под ногами, в ногах/голове нет коллизии и жидкости. */
    private static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockPos head  = feet.above();

        BlockState stBelow = level.getBlockState(below);

        // 1) Обычный случай: под ногами твёрдый блок
        boolean supportOk = isSolid(stBelow, level, below);

        // 2) Водная поверхность: под ногами вода, а на два блока ниже — твёрдый блок
        if (!supportOk && isWater(stBelow)) {
            BlockPos base = below.below();
            BlockState stBase = level.getBlockState(base);
            supportOk = isSolid(stBase, level, base);
        }

        if (!supportOk) return false;

        // Требуем «дышать» и не застрять: ноги и голова — без коллизии и без жидкости (т.е. воздух)
        BlockState stFeet = level.getBlockState(feet);
        BlockState stHead = level.getBlockState(head);
        return isEmpty(stFeet, level, feet) && isEmpty(stHead, level, head);
    }

    private static boolean isSolid(BlockState st, ServerLevel level, BlockPos pos) {
        return st.getFluidState().isEmpty() && !st.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isEmpty(BlockState st, ServerLevel level, BlockPos pos) {
        return st.getFluidState().isEmpty() && st.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isWater(BlockState st) {
        return st.getFluidState().is(FluidTags.WATER);
    }
}
