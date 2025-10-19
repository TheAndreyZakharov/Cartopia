package com.cartopia.store;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class FeatureStream implements Iterable<JsonObject>, AutoCloseable {
    private final BufferedReader br;

    public FeatureStream(File ndjson) throws IOException {
        this.br = new BufferedReader(new InputStreamReader(new FileInputStream(ndjson), StandardCharsets.UTF_8), 1<<20);
    }

    @Override public Iterator<JsonObject> iterator() {
        return new Iterator<>() {
            String nextLine = null;
            @Override public boolean hasNext() {
                try {
                    if (nextLine != null) return true;
                    nextLine = br.readLine();
                    return nextLine != null;
                } catch (IOException e) { return false; }
            }
            @Override public JsonObject next() {
                if (!hasNext()) throw new NoSuchElementException();
                String s = nextLine; nextLine = null;
                JsonElement el = JsonParser.parseString(s);
                return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            }
        };
    }

    @Override public void close() throws Exception { br.close(); }
}
