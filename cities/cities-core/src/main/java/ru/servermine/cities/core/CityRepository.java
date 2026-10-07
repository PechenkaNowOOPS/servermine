package ru.servermine.cities.core;

import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityFoundationDraft;
import ru.servermine.cities.api.CityFoundationResult;
import ru.servermine.cities.api.CityPromotionResult;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Cities-owned persistence port. Implementations must never expose connections or mutable entities. */
public interface CityRepository {
    CompletionStage<Optional<CityView>> find(UUID cityId);

    CompletionStage<Optional<CityView>> findForPlayer(UUID playerId);

    CompletionStage<Optional<CityFoundationResult>> findFoundationReplay(CityFoundationDraft draft);

    CompletionStage<CityFoundationResult> found(CityFoundationDraft draft);

    CompletionStage<CityPromotionResult> preparePromotion(CityPromotionDraft draft);

    CompletionStage<Boolean> reservePromotion(UUID operationId);

    CompletionStage<CityPromotionResult> applyPromotion(CityPromotionDraft draft);

    CompletionStage<Void> markPromotionCommitPending(UUID operationId);

    CompletionStage<CityPromotionResult> completePromotion(UUID operationId);

    CompletionStage<Void> failPromotion(UUID operationId, ru.servermine.cities.api.CityPromotionCode code,
                                        boolean releasePending);

    CompletionStage<Void> markPromotionReleased(UUID operationId);
}
