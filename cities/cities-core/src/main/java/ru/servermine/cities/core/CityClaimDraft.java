package ru.servermine.cities.core;

import ru.servermine.cities.api.ChunkPosition;

import java.util.Objects;
import java.util.UUID;

public record CityClaimDraft(UUID operationId, UUID cityId, UUID actorId, long expectedRevision,
                             ChunkPosition target, long price) {
    public CityClaimDraft {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(target, "target");
        if (expectedRevision < 0 || price < 0) throw new IllegalArgumentException("Invalid claim revision or price");
    }
}
