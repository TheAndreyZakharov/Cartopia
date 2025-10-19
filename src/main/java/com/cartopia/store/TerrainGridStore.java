package com.cartopia.store;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

public final class TerrainGridStore implements AutoCloseable {
    public final int minX, minZ, width, height;
    private final MappedByteBuffer ground; // int32 LE
    private final MappedByteBuffer water;  // int16 LE (может быть null)
    private final MappedByteBuffer topIdx; // int32 LE (может быть null)
    private final String[] topDict;        // может быть null

    private TerrainGridStore(int minX, int minZ, int width, int height,
                             MappedByteBuffer ground, MappedByteBuffer water,
                             MappedByteBuffer topIdx, String[] topDict) {
        this.minX = minX; this.minZ = minZ; this.width = width; this.height = height;
        this.ground = ground; this.water = water; this.topIdx = topIdx; this.topDict = topDict;
        if (ground != null) ground.order(ByteOrder.LITTLE_ENDIAN);
        if (water  != null) water.order(ByteOrder.LITTLE_ENDIAN);
        if (topIdx != null) topIdx.order(ByteOrder.LITTLE_ENDIAN);
    }

    public static TerrainGridStore open(File terrainMetaJson) throws Exception {
        JsonObject m = JsonParser.parseString(java.nio.file.Files.readString(terrainMetaJson.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        int minX = m.get("minX").getAsInt();
        int minZ = m.get("minZ").getAsInt();
        int width = m.get("width").getAsInt();
        int height= m.get("height").getAsInt();

        File root = terrainMetaJson.getParentFile();
        File ground = new File(root.getParentFile(), m.get("groundY").getAsString());
        File water  = new File(root.getParentFile(), m.get("waterY").getAsString());
        File dictF  = new File(root.getParentFile(), m.get("topBlockDict").getAsString());
        File topI   = new File(root.getParentFile(), m.get("topBlockIndex").getAsString());

        long cells = (long)width * height;

        MappedByteBuffer gMap = map(ground, 4L * cells);
        MappedByteBuffer wMap = water.exists() ? map(water, 2L * cells) : null;
        MappedByteBuffer tMap = topI.exists()  ? map(topI, 4L * cells)  : null;
        String[] dict = dictF.exists() ? readDict(dictF) : null;

        return new TerrainGridStore(minX, minZ, width, height, gMap, wMap, tMap, dict);
    }

    private static MappedByteBuffer map(File f, long size) throws Exception {
        FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ);
        return ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
    }

    private static String[] readDict(File f) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        return lines.toArray(new String[0]);
    }

    private int idx(int x, int z) { return (z - minZ) * width + (x - minX); }

    public boolean inBounds(int x, int z) {
        return x >= minX && x < minX + width && z >= minZ && z < minZ + height;
    }

    public int groundY(int x, int z) {
        if (ground == null || !inBounds(x,z)) return Integer.MIN_VALUE;
        int i = idx(x,z) * 4;
        return ground.getInt(i);
    }

    /** null → воды нет (по сетке). */
    public Integer waterY(int x, int z) {
        if (water == null || !inBounds(x,z)) return null;
        int i = idx(x,z) * 2;
        short v = water.getShort(i);
        return (v == (short)-32768) ? null : (int)v;
    }

    /** может вернуть null, если нет данных топблока. */
    public String topBlockId(int x, int z) {
        if (topIdx == null || topDict == null || !inBounds(x,z)) return null;
        int i = idx(x,z) * 4;
        int code = topIdx.getInt(i);
        if (code < 0 || code >= topDict.length) return null;
        return topDict[code];
    }

    @Override public void close() { /* nothing, mmap освободит GC */ }
}
