package ru.servermine.cities.api;

public enum CityStage {
    SETTLEMENT("Поселение", 4), VILLAGE("Деревня", 12), CITY("Город", 24),
    CAPITAL("Столица", 40), KINGDOM("Королевство", 64);
    private final String displayName;
    private final int chunkLimit;
    CityStage(String displayName, int chunkLimit) { this.displayName = displayName; this.chunkLimit = chunkLimit; }
    public String displayName() { return displayName; }
    public int chunkLimit() { return chunkLimit; }
}
