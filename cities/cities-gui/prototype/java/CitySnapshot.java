package ru.servermine.gradostroygui;

import java.util.List;

public record CitySnapshot(
        String name,
        CityStage stage,
        int territory,
        long treasury,
        long revision,
        List<ResidentEntry> residents
) {
    public int territoryLimit() {
        return stage.chunkLimit();
    }

    public boolean territoryFull() {
        return territory >= territoryLimit();
    }

    public record ResidentEntry(String name, String role) {}
}
