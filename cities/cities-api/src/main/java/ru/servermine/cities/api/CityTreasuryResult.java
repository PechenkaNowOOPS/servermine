package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CityTreasuryResult(UUID operationId, CityTreasuryCode code, Optional<CityView> city) {
    public CityTreasuryResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(code, "code");
        city = Objects.requireNonNull(city, "city");
        if ((code == CityTreasuryCode.DEPOSITED) != city.isPresent()) {
            throw new IllegalArgumentException("Successful treasury operation contains the updated city");
        }
    }
}
