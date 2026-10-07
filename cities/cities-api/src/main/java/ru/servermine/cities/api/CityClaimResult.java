package ru.servermine.cities.api;

import java.util.Optional;
import java.util.UUID;

public record CityClaimResult(UUID operationId, CityClaimCode code,
                              Optional<CityView> city, boolean idempotentReplay) {
    public CityClaimResult {
        if (operationId == null || code == null || city == null) throw new IllegalArgumentException("Claim result fields are required");
    }
}
