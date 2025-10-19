package com.cartopia.store;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class GenerationStore implements AutoCloseable {
    public final File genDir;           // корень конкретной генерации
    public final File coordsJson;       // оригинальный coords.json
    public final File indexJson;        // cartopia.index.json
    public final File featuresNdjson;   // features/elements.ndjson
    public final File terrainMeta;      // terrain/grid.meta.json
    public final TerrainGridStore grid; // memory-mapped сетка рельефа (ленивая)

    private final JsonObject index;     // метаданные (лёгкие)

    private GenerationStore(File genDir, File coordsJson, JsonObject index, TerrainGridStore grid) {
        this.genDir = genDir;
        this.coordsJson = coordsJson;
        this.indexJson = new File(genDir, "cartopia.index.json");
        this.featuresNdjson = new File(genDir, "features/elements.ndjson");
        this.terrainMeta = new File(genDir, "terrain/grid.meta.json");
        this.index = index;
        this.grid = grid;
    }

    public static GenerationStore prepare(File genDir, File coordsJson) throws Exception {
        File idx = new File(genDir, "cartopia.index.json");
        boolean needSplit = true;
        if (idx.exists()) {
            long srcTs = coordsJson.lastModified();
            long idxTs = idx.lastModified();
            needSplit = idxTs < srcTs; // если coords.json свежее — пересобрать сайдкары
        }
        if (needSplit) {
            CoordsSplitter.split(coordsJson, genDir);
        }
        // читаем лёгкий индекс
        JsonObject index = JsonParser.parseString(Files.readString(idx.toPath(), StandardCharsets.UTF_8))
                                     .getAsJsonObject();
        // лениво открываем grid (может отсутствовать, если в coords не было terrainGrid)
        File gridMeta = new File(genDir, "terrain/grid.meta.json");
        TerrainGridStore grid = gridMeta.exists() ? TerrainGridStore.open(gridMeta) : null;

        return new GenerationStore(genDir, coordsJson, index, grid);
    }

    public JsonObject indexJsonObject() { return index; }

    /** Построчный поток OSM-элементов (ленивый). */
    public FeatureStream featureStream() throws IOException {
        File f = new File(genDir, "features/elements.ndjson");
        return new FeatureStream(f);
    }

    @Override public void close() throws Exception {
        if (grid != null) grid.close();
    }
}
