package ru.servermine.cities.core;

import java.util.Objects;
import java.util.UUID;

public record CityTreasuryDraft(UUID operationId, UUID cityId, UUID actorId, long amount) {
    public CityTreasuryDraft {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(actorId, "actorId");
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
    }
}
