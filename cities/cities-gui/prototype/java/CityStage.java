package ru.servermine.gradostroygui;

import java.util.Locale;

public enum CityStage {
    SETTLEMENT("Поселение", 4),
    VILLAGE("Деревня", 12),
    CITY("Город", 24),
    CAPITAL("Столица", 40),
    KINGDOM("Королевство", 64);

    private final String displayName;
    private final int chunkLimit;

    CityStage(String displayName, int chunkLimit) {
        this.displayName = displayName;
        this.chunkLimit = chunkLimit;
    }

    public String displayName() {
        return displayName;
    }

    public int chunkLimit() {
        return chunkLimit;
    }

    public static CityStage parse(String value) {
        if (value == null || value.isBlank()) return CITY;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CITY;
        }
    }
}
