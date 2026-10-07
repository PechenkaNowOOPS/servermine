package ru.servermine.cities.core;

import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityFoundationDraft;
import ru.servermine.cities.api.CityFoundationResult;
import ru.servermine.cities.api.CityPromotionResult;
import ru.servermine.cities.api.CityClaimResult;
import ru.servermine.cities.api.CityLeaveResult;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Cities-owned persistence port. Implementations must never expose connections or mutable entities. */
public interface CityRepository {
    CompletionStage<Optional<CityView>> find(UUID cityId);

    CompletionStage<Optional<CityView>> findForPlayer(UUID playerId);

    CompletionStage<List<CityView>> findAllCities();

    CompletionStage<CityLeaveResult> leaveCity(UUID playerId);

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

    CompletionStage<CityClaimResult> prepareClaim(CityClaimDraft draft);

    CompletionStage<Boolean> reserveClaim(UUID operationId);

    CompletionStage<CityClaimResult> applyClaim(CityClaimDraft draft);

    CompletionStage<Void> markClaimCommitPending(UUID operationId);

    CompletionStage<CityClaimResult> completeClaim(UUID operationId);

    CompletionStage<Void> failClaim(UUID operationId, ru.servermine.cities.api.CityClaimCode code);

    CompletionStage<Void> markClaimReleasePending(UUID operationId, ru.servermine.cities.api.CityClaimCode code);

    CompletionStage<Void> markClaimReleased(UUID operationId);

    CompletionStage<Set<ru.servermine.cities.api.ChunkPosition>> claimedChunks(Set<ru.servermine.cities.api.ChunkPosition> candidates);
}
