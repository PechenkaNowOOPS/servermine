package ru.servermine.cities.api;

import java.util.Objects;
import java.util.Optional;

public record CityMembershipResult(CityMembershipCode code, Optional<CityView> city) {
    public CityMembershipResult {
        Objects.requireNonNull(code, "code");
        city = Objects.requireNonNull(city, "city");
        if ((code == CityMembershipCode.INVITED || code == CityMembershipCode.ACCEPTED) != city.isPresent()) {
            throw new IllegalArgumentException("Successful membership results contain the affected city");
        }
    }
}
