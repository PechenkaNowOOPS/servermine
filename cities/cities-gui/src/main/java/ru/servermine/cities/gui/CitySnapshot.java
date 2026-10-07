package ru.servermine.cities.gui;

import java.util.List;
import ru.servermine.cities.api.CityStage;

public record CitySnapshot(
        String name,
        CityStage stage,
        int territory,
        long treasury,
        long revision,
        List<ResidentEntry> residents
) {
    public CitySnapshot { residents = List.copyOf(residents); }
    public int territoryLimit() {
        return stage.chunkLimit();
    }

    public boolean territoryFull() {
        return territory >= territoryLimit();
    }

    public record ResidentEntry(String name, String role) {}
}
