package ru.servermine.cities.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** City stage advancement API. Stage and permission checks are enforced by the implementation. */
public interface CityProgressionService {
    boolean isReady();

    long promotionPrice(CityStage currentStage);

    Optional<CityStage> nextStage(CityStage currentStage);

    int minimumResidents(CityStage currentStage);

    int minimumChunks(CityStage currentStage);

    CompletionStage<CityPromotionResult> promote(CityPromotionRequest request);

    CompletionStage<Optional<CityView>> cityForPlayer(UUID playerId);
}
