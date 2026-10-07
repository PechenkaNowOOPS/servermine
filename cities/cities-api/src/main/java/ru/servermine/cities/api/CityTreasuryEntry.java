package ru.servermine.cities.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CityTreasuryEntry(UUID operationId, long amount, long balanceAfter, String reason, Instant occurredAt) {
    public CityTreasuryEntry {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (amount == 0 || balanceAfter < 0 || reason.isBlank()) throw new IllegalArgumentException("Invalid treasury entry");
    }
}
