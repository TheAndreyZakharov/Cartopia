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
        boolean sameSpot = py == target.getY()
                && Mth.floor(player.getX()) == target.getX()
                && Mth.floor(player.getZ()) == target.getZ();

        if (!sameSpot || Math.abs(py - target.getY()) >= TELEPORT_THRESHOLD || !isSafeStand(level, new BlockPos(x, py, z))) {
            player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, player.getYRot(), player.getXRot());
        }
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
            // верх/низ
            for (int dx = -r; dx <= r; dx++) {
                BlockPos p1 = findTopSafe(level, x + dx, z - r);
                if (p1 != null) return p1;
                BlockPos p2 = findTopSafe(level, x + dx, z + r);
                if (p2 != null) return p2;
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
        if (!isSolid(stBelow, level, below)) return false;

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
}
