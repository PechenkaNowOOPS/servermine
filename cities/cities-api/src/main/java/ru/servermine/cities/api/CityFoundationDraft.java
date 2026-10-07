package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Normalized, policy-approved input to one SQLite transaction. */
public record CityFoundationDraft(
        UUID operationId,
        UUID cityId,
        UUID founderId,
        String founderName,
        String name,
        String nameKey,
        Set<ChunkPosition> initialChunks
) {
    public CityFoundationDraft {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(founderName, "founderName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(nameKey, "nameKey");
        initialChunks = Set.copyOf(initialChunks);
        if (initialChunks.size() != 4) throw new IllegalArgumentException("A city starts with exactly four chunks");
    }
}
