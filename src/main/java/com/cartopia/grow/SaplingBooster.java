package com.cartopia.grow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public class SaplingBooster {

    /** Каждый тик приоритизирует ближайшие чанки к игроку и ускоряет ТОЛЬКО саженцы. */
    public static void boostAroundPlayer(ServerLevel level,
                                         ServerPlayer player,
                                         int radiusChunks,
                                         int saplingTicksEach,
                                         int maxSaplingsBudget) {
        if (maxSaplingsBudget <= 0 || saplingTicksEach <= 0 || radiusChunks < 0) return;

        final int pcx = Mth.floor(player.getX()) >> 4;
        final int pcz = Mth.floor(player.getZ()) >> 4;

        int budget = maxSaplingsBudget;

        // Кольца: сначала центр (r=0), потом r=1, r=2, ...
        for (int r = 0; r <= radiusChunks && budget > 0; r++) {
            if (r == 0) {
                LevelChunk center = level.getChunkSource().getChunkNow(pcx, pcz);
                if (center != null) {
                    budget -= growInChunk(level, center, saplingTicksEach, budget);
                }
                continue;
            }

            // Проходим «рамку» квадрата с радиусом r вокруг центра
            int xMin = pcx - r, xMax = pcx + r;
            int zMin = pcz - r, zMax = pcz + r;

            // верхняя и нижняя стороны
            for (int cx = xMin; cx <= xMax && budget > 0; cx++) {
                LevelChunk top = level.getChunkSource().getChunkNow(cx, zMin);
                if (top != null) budget -= growInChunk(level, top, saplingTicksEach, budget);

                if (zMax != zMin && budget > 0) {
                    LevelChunk bottom = level.getChunkSource().getChunkNow(cx, zMax);
                    if (bottom != null) budget -= growInChunk(level, bottom, saplingTicksEach, budget);
                }
            }
            // лево и право (без углов, они уже обработаны)
            for (int cz = zMin + 1; cz <= zMax - 1 && budget > 0; cz++) {
                LevelChunk left = level.getChunkSource().getChunkNow(xMin, cz);
                if (left != null) budget -= growInChunk(level, left, saplingTicksEach, budget);

                if (xMax != xMin && budget > 0) {
                    LevelChunk right = level.getChunkSource().getChunkNow(xMax, cz);
                    if (right != null) budget -= growInChunk(level, right, saplingTicksEach, budget);
                }
            }
        }
    }

    /** Обрабатывает чанк, возвращает сколько саженцев реально обработали (для вычитания из бюджета). */
    private static int growInChunk(ServerLevel level,
                                   LevelChunk chunk,
                                   int saplingTicksEach,
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

                // Шире вертикальный коридор, чтобы точно поймать саженцы возле поверхности
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                int yMin = Math.max(worldYMin, surfaceY - 12);
                int yMax = Math.min(worldYMax, surfaceY + 24);

                for (int y = yMin; y <= yMax; y++) {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState st = level.getBlockState(pos);
                    if (!(st.getBlock() instanceof SaplingBlock)) continue;

                    // Несколько randomTick подряд; если уже выросло — выходим
                    for (int k = 0; k < saplingTicksEach; k++) {
                        BlockState cur = level.getBlockState(pos);
                        if (!(cur.getBlock() instanceof SaplingBlock)) break;
                        if (cur.isRandomlyTicking()) cur.randomTick(level, pos, rnd);
                    }

                    processed++;
                    if (processed >= budget) return processed; // бюджет на этот чанк исчерпан
                }
            }
        }
        return processed;
    }
}
