package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CityLeaveResult(CityLeaveCode code, Optional<CityView> city, Optional<UUID> newRulerId) {
    public CityLeaveResult {
        Objects.requireNonNull(code, "code");
        city = Objects.requireNonNull(city, "city");
        newRulerId = Objects.requireNonNull(newRulerId, "newRulerId");
        if ((code == CityLeaveCode.LEFT) != city.isPresent()) {
            throw new IllegalArgumentException("A successful departure contains the updated city");
        }
        if (newRulerId.isPresent() && code != CityLeaveCode.LEFT) {
            throw new IllegalArgumentException("A new ruler is only returned after a successful departure");
        }
    }
}
