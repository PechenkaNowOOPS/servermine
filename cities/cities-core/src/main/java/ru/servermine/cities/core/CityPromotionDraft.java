package ru.servermine.cities.core;

import ru.servermine.cities.api.CityStage;

import java.util.Objects;
import java.util.UUID;

/** Immutable intent persisted before an optional Economy reservation. */
public record CityPromotionDraft(UUID operationId, UUID cityId, UUID actorId, long expectedRevision,
                                CityStage fromStage, CityStage toStage, long price,
                                int minimumResidents, int minimumChunks) {
    public CityPromotionDraft {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(fromStage, "fromStage");
        Objects.requireNonNull(toStage, "toStage");
        if (expectedRevision < 1 || price < 0 || minimumResidents < 1 || minimumChunks < 0) {
            throw new IllegalArgumentException("Invalid city promotion parameters");
        }
    }
}
