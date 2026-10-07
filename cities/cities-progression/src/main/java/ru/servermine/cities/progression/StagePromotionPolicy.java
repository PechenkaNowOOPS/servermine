package ru.servermine.cities.progression;

/** Balance and minimum requirements for one configured stage transition. */
public record StagePromotionPolicy(boolean enabled, long price, int minimumResidents, int minimumChunks) {
    public StagePromotionPolicy {
        if (price < 0 || minimumResidents < 1 || minimumChunks < 0) {
            throw new IllegalArgumentException("Invalid stage promotion policy");
        }
    }
}
