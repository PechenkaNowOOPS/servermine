package ru.servermine.cities.core;

import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityView;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bootstrap wiring only. No fake city is exposed as persistent domain state. */
public final class UnconfiguredCitiesService implements CitiesService {
    public String apiVersion() { return "1-bootstrap"; }
    public boolean isReady() { return false; }
    public CompletionStage<Optional<CityView>> city(UUID cityId) { return CompletableFuture.completedFuture(Optional.empty()); }
    public CompletionStage<Optional<CityView>> cityForPlayer(UUID playerId) { return CompletableFuture.completedFuture(Optional.empty()); }
}
