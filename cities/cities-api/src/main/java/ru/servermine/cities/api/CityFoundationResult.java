package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CityFoundationResult(
        UUID operationId,
        CityFoundationCode code,
        Optional<CityView> city,
        boolean idempotentReplay
) {
    public CityFoundationResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(code, "code");
        city = Objects.requireNonNull(city, "city");
        if ((code == CityFoundationCode.CREATED || code == CityFoundationCode.REPLAYED) != city.isPresent()) {
            throw new IllegalArgumentException("Successful founding results contain exactly one city view");
        }
    }
}
