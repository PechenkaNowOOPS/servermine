package ru.servermine.cities.api;

import java.util.Objects;
import java.util.UUID;

public record CityClaimRequest(UUID operationId, UUID cityId, UUID actorId,
                               long expectedRevision, ChunkPosition target) {
    public CityClaimRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(target, "target");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision cannot be negative");
    }
}
