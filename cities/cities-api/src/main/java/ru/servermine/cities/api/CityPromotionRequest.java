package ru.servermine.cities.api;

import java.util.Objects;
import java.util.UUID;

/** Stable request to advance a city's progression stage. */
public record CityPromotionRequest(UUID operationId, UUID cityId, UUID actorId,
                                   CityStage expectedStage, long expectedRevision) {
    public CityPromotionRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(expectedStage, "expectedStage");
        if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision must be positive");
    }
}
