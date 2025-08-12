package com.cartopia.grow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.BlockTags;

/**
 * Ускоряет рост растений вокруг игрока.
 * Таргеты:
 *  - саженцы деревьев (SaplingBlock)
 *  - посевы (любой CropBlock: пшеница/морковь/картофель/свёкла и т.п.)
 *  - стебли тыквы/дыни (StemBlock)
 *  - адский нарост (NetherWartBlock)
 *
 * Механика: многократно вызывает randomTick у таргетных блоков (пока они не превратились во «выросшее» состояние),
 * приоритизируя чанки ближайшие к игроку и соблюдая общий бюджет на тик.
 */
public class SaplingBooster {

    public static void boostAroundPlayer(ServerLevel level,
                                         ServerPlayer player,
                                         int radiusChunks,
                                         int growTicksEach,
                                         int maxTargetsBudget) {
        if (maxTargetsBudget <= 0 || growTicksEach <= 0 || radiusChunks < 0) return;

        final int pcx = Mth.floor(player.getX()) >> 4;
        final int pcz = Mth.floor(player.getZ()) >> 4;

        int budget = maxTargetsBudget;

        // Кольца: центр -> наружу
        for (int r = 0; r <= radiusChunks && budget > 0; r++) {
            if (r == 0) {
                LevelChunk center = level.getChunkSource().getChunkNow(pcx, pcz);
                if (center != null) {
                    budget -= growInChunk(level, center, growTicksEach, budget);
                }
                continue;
            }

            int xMin = pcx - r, xMax = pcx + r;
            int zMin = pcz - r, zMax = pcz + r;

            // верх/низ рамки
            for (int cx = xMin; cx <= xMax && budget > 0; cx++) {
                LevelChunk top = level.getChunkSource().getChunkNow(cx, zMin);
                if (top != null) budget -= growInChunk(level, top, growTicksEach, budget);

                if (zMax != zMin && budget > 0) {
                    LevelChunk bottom = level.getChunkSource().getChunkNow(cx, zMax);
                    if (bottom != null) budget -= growInChunk(level, bottom, growTicksEach, budget);
                }
            }
            // лево/право рамки (без углов)
            for (int cz = zMin + 1; cz <= zMax - 1 && budget > 0; cz++) {
                LevelChunk left = level.getChunkSource().getChunkNow(xMin, cz);
                if (left != null) budget -= growInChunk(level, left, growTicksEach, budget);

                if (xMax != xMin && budget > 0) {
                    LevelChunk right = level.getChunkSource().getChunkNow(xMax, cz);
                    if (right != null) budget -= growInChunk(level, right, growTicksEach, budget);
                }
            }
        }
    }

    /** Возвращает, сколько целей реально обработали в чанке (для вычитания из бюджета). */
    private static int growInChunk(ServerLevel level,
                                   LevelChunk chunk,
                                   int growTicksEach,
                                   int budget) {

        RandomSource rnd = level.getRandom();
        int processed = 0;

        final int worldYMin = level.getMinBuildHeight();
        final int worldYMax = level.getMaxBuildHeight() - 1;

        final int baseX = chunk.getPos().getMinBlockX();
        final int baseZ = chunk.getPos().getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                // Диапазон по высоте вокруг поверхности — чтобы не сканировать весь столб
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                int yMin = Math.max(worldYMin, surfaceY - 24);
                int yMax = Math.min(worldYMax, surfaceY + 48);

                for (int y = yMin; y <= yMax; y++) {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState st = level.getBlockState(pos);
                    if (!isGrowTarget(st)) continue;

                    // Несколько randomTick подряд; если за время цикла блок сменился — останавливаемся
                    for (int k = 0; k < growTicksEach; k++) {
                        BlockState cur = level.getBlockState(pos);
                        if (!isGrowTarget(cur)) break;          // уже вырос/сменился
                        if (cur.isRandomlyTicking()) cur.randomTick(level, pos, rnd);
                    }

                    processed++;
                    if (processed >= budget) return processed; // этот чанк исчерпал бюджет
                }
            }
        }
        return processed;
    }

    /** Кого считаем целями ускорения. */
    private static boolean isGrowTarget(BlockState state) {
        Block b = state.getBlock();

        // 1) деревья — всегда
        if (b instanceof SaplingBlock) return true;

        // 2) посевы (пшеница/морковь/картофель/свёкла и т.п.)
        if (b instanceof CropBlock) return true;

        // 3) стебли (тыква/дыня)
        if (b instanceof StemBlock) return true;

        // 4) адский нарост
        if (b instanceof NetherWartBlock) return true;

        // 5) на всякий — поддержка тега CROPS (если моды добавят свои культуры)
        // (state.is(BlockTags.CROPS) истинно и для многих модовых растений)
        if (state.is(BlockTags.CROPS)) return true;

        // ничего из списка
        return false;
    }
}
