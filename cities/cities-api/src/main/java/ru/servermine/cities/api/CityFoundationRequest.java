package ru.servermine.cities.api;

import java.util.Objects;
import java.util.UUID;

/** Stable request for founding a city on a 2x2 footprint anchored at the supplied chunk. */
public record CityFoundationRequest(
        UUID operationId,
        UUID founderId,
        String founderName,
        String cityName,
        ChunkPosition anchor
) {
    public CityFoundationRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(founderName, "founderName");
        Objects.requireNonNull(cityName, "cityName");
        Objects.requireNonNull(anchor, "anchor");
    }
}
