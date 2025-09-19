package com.cartopia.builder;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.server.level.ServerLevel;

public class RoadGenerator {
    private final ServerLevel level;
    private final JsonNode coords;

    public RoadGenerator(ServerLevel level, JsonNode coords) {
        this.level = level;
        this.coords = coords;
    }

    public void generate() {
        // TODO: перенести сюда твою брезенхем-прокладку линий, ширины по OSM tags и т.п.
    }
}
