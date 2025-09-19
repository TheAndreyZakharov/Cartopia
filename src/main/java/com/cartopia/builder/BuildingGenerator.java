package com.cartopia.builder;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.server.level.ServerLevel;

public class BuildingGenerator {
    private final ServerLevel level;
    private final JsonNode coords;

    public BuildingGenerator(ServerLevel level, JsonNode coords) {
        this.level = level;
        this.coords = coords;
    }

    public void generate() {
        // TODO: позже — высотность по height / building:levels, посадка на getY(...)
    }
}
