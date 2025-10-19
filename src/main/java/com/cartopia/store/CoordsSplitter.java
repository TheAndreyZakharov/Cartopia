package com.cartopia.store;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class CoordsSplitter {

    public static void split(File coordsJson, File genDir) throws Exception {
        // ensure dirs
        File featuresDir = new File(genDir, "features");
        File terrainDir  = new File(genDir, "terrain");
        File scratchDir  = new File(genDir, "scratch");
        featuresDir.mkdirs(); terrainDir.mkdirs(); scratchDir.mkdirs();

        // outputs
        File featuresNdjson = new File(featuresDir, "elements.ndjson");
        File terrainMeta    = new File(terrainDir, "grid.meta.json");
        File groundBin      = new File(terrainDir, "groundY.i32");
        File waterBin       = new File(terrainDir, "waterY.i16");
        File topDict        = new File(terrainDir, "topBlock.dict.txt");
        File topIndex       = new File(terrainDir, "topBlock.i32");

        // маленький индекс
        JsonObject idx = new JsonObject();
        idx.addProperty("version", 1);
        idx.addProperty("coordsFile", coordsJson.getName());

        // будем аккуратно читать только нужные секции
        try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(coordsJson), StandardCharsets.UTF_8));
             BufferedWriter nd = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(featuresNdjson), StandardCharsets.UTF_8))) {

            r.beginObject();
            JsonObject center = null, bbox = null, player = null;
            Integer sizeMeters = null;

            // terrain state
            boolean terrainSeen = false;
            int minX=0, minZ=0, width=0, height=0;
            boolean wroteTerrain = false;

            // словарь topBlock
            DictionaryBuilder dict = new DictionaryBuilder(topDict);

            while (r.hasNext()) {
                String name = r.nextName();

                if ("center".equals(name)) {
                    center = readObjectAsJson(r);
                } else if ("bbox".equals(name)) {
                    bbox = readObjectAsJson(r);
                } else if ("player".equals(name)) {
                    player = readObjectAsJson(r);
                } else if ("sizeMeters".equals(name)) {
                    sizeMeters = readIntLoose(r);
                } else if ("terrainGrid".equals(name) && r.peek()==JsonToken.BEGIN_OBJECT) {
                    terrainSeen = true;
                    // читаем мету и потоки массивов по-секционно
                    int[] wh = new int[4]; // minX,minZ,width,height
                    wroteTerrain = splitTerrainGrid(r, wh, groundBin, waterBin, topIndex, dict);
                    minX = wh[0]; minZ = wh[1]; width = wh[2]; height = wh[3];
                } else if ("features".equals(name) && r.peek()==JsonToken.BEGIN_OBJECT) {
                    // внутри features ищем elements: [...]
                    r.beginObject();
                    while (r.hasNext()) {
                        String fn = r.nextName();
                        if ("elements".equals(fn) && r.peek()==JsonToken.BEGIN_ARRAY) {
                            r.beginArray();
                            long count=0;
                            while (r.hasNext()) {
                                // каждый элемент пишем как отдельную строку
                                JsonObject el = readObjectAsJson(r);
                                nd.write(el.toString());
                                nd.write('\n');
                                count++;
                            }
                            r.endArray();
                            idx.addProperty("features_count", count);
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                } else {
                    r.skipValue(); // всё остальное пропускаем
                }
            }
            r.endObject();

            // пишем terrain meta (если было)
            if (terrainSeen && wroteTerrain) {
                JsonObject tmeta = new JsonObject();
                tmeta.addProperty("minX", minX);
                tmeta.addProperty("minZ", minZ);
                tmeta.addProperty("width", width);
                tmeta.addProperty("height", height);
                tmeta.addProperty("endianness", "LE");
                tmeta.addProperty("groundY", "terrain/groundY.i32");
                tmeta.addProperty("waterY", "terrain/waterY.i16");
                tmeta.addProperty("topBlockDict", "terrain/topBlock.dict.txt");
                tmeta.addProperty("topBlockIndex", "terrain/topBlock.i32");
                Files.writeString(terrainMeta.toPath(), tmeta.toString(), StandardCharsets.UTF_8);
            }

            if (center != null) idx.add("center", center);
            if (bbox != null)   idx.add("bbox", bbox);
            if (player != null) idx.add("player", player);
            if (sizeMeters != null) idx.addProperty("sizeMeters", sizeMeters);
        }

        // финальный индекс
        File indexJson = new File(genDir, "cartopia.index.json");
        Files.writeString(indexJson.toPath(), idx.toString(), StandardCharsets.UTF_8);
    }

    // --- helpers ---

    private static JsonObject readObjectAsJson(JsonReader r) throws IOException {
        JsonElement el = JsonParser.parseReader(r);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static int readIntLoose(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NUMBER) return (int)Math.round(r.nextDouble());
        if (t == JsonToken.STRING) {
            try { return Integer.parseInt(r.nextString().trim()); } catch (Exception ignore) { return 0; }
        }
        r.skipValue(); return 0;
    }

    /**
     * Читает terrainGrid объект потоком и пишет бинарные файлы.
     * Возвращает true, если что-то реально записали.
     * wh = {minX, minZ, width, height}
     */
    private static boolean splitTerrainGrid(JsonReader r, int[] wh,
                                            File groundBin, File waterBin, File topIndex,
                                            DictionaryBuilder dict) throws Exception {
        Integer minX=null, minZ=null, width=null, height=null;

        // ленивые каналы
        FileChannel gCh=null, wCh=null, tCh=null;
        ByteBuffer gBuf=null, wBuf=null, tBuf=null;

        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();

            // --- мета ---
            if ("minX".equals(n))        { minX = readIntLoose(r); }
            else if ("minZ".equals(n))   { minZ = readIntLoose(r); }
            else if ("width".equals(n))  { width = readIntLoose(r); }
            else if ("height".equals(n)) { height = readIntLoose(r); }

            // --- v1: terrainGrid.data[] == groundY ---
            else if ("data".equals(n) && r.peek()==JsonToken.BEGIN_ARRAY) {
                if (gCh == null) {
                    if (minX==null||minZ==null||width==null||height==null) {
                        throw new IllegalStateException("terrainGrid meta incomplete");
                    }
                    gCh = FileChannel.open(groundBin.toPath(),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    gBuf = ByteBuffer.allocateDirect(4 * 8192).order(ByteOrder.LITTLE_ENDIAN);
                }
                r.beginArray();
                while (r.hasNext()) {
                    int v = readIntLoose(r);
                    putInt32(gCh, gBuf, v);
                }
                r.endArray();
                // (необязательно) можно проверить длину: count == width*height

            // --- v2: terrainGrid.grids.{ groundY[], waterY[], topBlock[] } ---
            } else if ("grids".equals(n) && r.peek()==JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    String gn = r.nextName();

                    // groundY[] : int32 LE
                    if ("groundY".equals(gn) && r.peek()==JsonToken.BEGIN_ARRAY) {
                        if (gCh == null) {
                            if (minX==null||minZ==null||width==null||height==null) {
                                throw new IllegalStateException("terrainGrid meta incomplete");
                            }
                            gCh = FileChannel.open(groundBin.toPath(),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            gBuf = ByteBuffer.allocateDirect(4 * 8192).order(ByteOrder.LITTLE_ENDIAN);
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            int v = readIntLoose(r);
                            putInt32(gCh, gBuf, v);
                        }
                        r.endArray();

                    // waterY[] : int16 LE, null -> -32768
                    } else if ("waterY".equals(gn) && r.peek()==JsonToken.BEGIN_ARRAY) {
                        if (wCh == null) {
                            wCh = FileChannel.open(waterBin.toPath(),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            wBuf = ByteBuffer.allocateDirect(2 * 8192).order(ByteOrder.LITTLE_ENDIAN);
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek()==JsonToken.NULL) { r.nextNull(); putInt16(wCh, wBuf, (short)-32768); }
                            else { putInt16(wCh, wBuf, (short)readIntLoose(r)); }
                        }
                        r.endArray();

                    // topBlock[] : строковый словарь + индекс int32 LE
                    } else if ("topBlock".equals(gn) && r.peek()==JsonToken.BEGIN_ARRAY) {
                        if (tCh == null) {
                            tCh = FileChannel.open(topIndex.toPath(),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            tBuf = ByteBuffer.allocateDirect(4 * 4096).order(ByteOrder.LITTLE_ENDIAN);
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            String id;
                            if (r.peek() == JsonToken.NULL) {
                                r.nextNull();
                                id = null;
                            } else {
                                id = r.nextString();
                            }
                            int code = dict.codeFor(id == null ? "" : id);
                            putInt32(tCh, tBuf, code);
                        }
                        r.endArray();

                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();

            } else {
                r.skipValue();
            }
        }
        r.endObject();

        // завершение
        flushClose(gCh, gBuf);
        flushClose(wCh, wBuf);
        flushClose(tCh, tBuf);
        dict.close();

        if (minX!=null && minZ!=null && width!=null && height!=null) {
            wh[0]=minX; wh[1]=minZ; wh[2]=width; wh[3]=height;
        }
        return (groundBin.exists() || waterBin.exists() || topIndex.exists());
    }


    private static void putInt32(FileChannel ch, ByteBuffer buf, int v) throws IOException {
        if (buf.remaining() < 4) { buf.flip(); ch.write(buf); buf.clear(); }
        buf.putInt(v);
    }
    private static void putInt16(FileChannel ch, ByteBuffer buf, short v) throws IOException {
        if (buf.remaining() < 2) { buf.flip(); ch.write(buf); buf.clear(); }
        buf.putShort(v);
    }
    private static void flushClose(FileChannel ch, ByteBuffer buf) throws IOException {
        if (ch == null) return;
        if (buf != null) { buf.flip(); while (buf.hasRemaining()) ch.write(buf); }
        ch.close();
    }

    // простой словарь строк → int
    static final class DictionaryBuilder implements Closeable {
        private final BufferedWriter out;
        private final java.util.Map<String,Integer> map = new java.util.HashMap<>();
        DictionaryBuilder(File file) throws IOException {
            file.getParentFile().mkdirs();
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        }
        int codeFor(String s) throws IOException {
            Integer c = map.get(s);
            if (c != null) return c;
            int code = map.size();
            map.put(s, code);
            out.write(s == null ? "" : s);
            out.write('\n');
            return code;
        }
        @Override public void close() throws IOException { out.close(); }
    }
}
