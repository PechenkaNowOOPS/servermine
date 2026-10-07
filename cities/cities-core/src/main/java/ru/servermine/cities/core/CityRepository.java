package ru.servermine.cities.core;

import ru.servermine.cities.api.CityView;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Cities-owned persistence port. Implementations must never expose connections or mutable entities. */
public interface CityRepository {
    CompletionStage<Optional<CityView>> find(UUID cityId);

    CompletionStage<Optional<CityView>> findForPlayer(UUID playerId);
}
