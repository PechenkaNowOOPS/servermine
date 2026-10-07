package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CityPromotionResult(UUID operationId, CityPromotionCode code,
                                 Optional<CityView> city, boolean idempotentReplay) {
    public CityPromotionResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(code, "code");
        city = Objects.requireNonNull(city, "city");
        if ((code == CityPromotionCode.PROMOTED || code == CityPromotionCode.REPLAYED) != city.isPresent()) {
            throw new IllegalArgumentException("Successful promotion results contain one city view");
        }
    }
}
