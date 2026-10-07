package ru.servermine.cities.progression;

import ru.servermine.cities.api.CityProgressionService;
import ru.servermine.cities.api.CityPromotionCode;
import ru.servermine.cities.api.CityPromotionRequest;
import ru.servermine.cities.api.CityPromotionResult;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityPromotionDraft;
import ru.servermine.cities.core.CityRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Configured stage promotion workflow with a durable Cities intent and optional Economy reservation. */
public final class CityProgressionServiceImpl implements CityProgressionService {
    private final CityRepository repository;
    private final Map<CityStage, StagePromotionPolicy> policies;
    private volatile boolean ready = true;

    public CityProgressionServiceImpl(CityRepository repository, Map<CityStage, StagePromotionPolicy> policies) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policies = Map.copyOf(policies);
    }

    public void deactivate() { ready = false; }

    @Override public boolean isReady() { return ready; }

    @Override public long promotionPrice(CityStage currentStage) {
        StagePromotionPolicy policy = policies.get(currentStage);
        return policy == null || !policy.enabled() ? 0 : policy.price();
    }

    @Override public Optional<CityStage> nextStage(CityStage currentStage) {
        StagePromotionPolicy policy = policies.get(currentStage);
        return policy == null || !policy.enabled() ? Optional.empty() : StageProgression.next(currentStage);
    }

    @Override public int minimumResidents(CityStage currentStage) {
        StagePromotionPolicy policy = policies.get(currentStage);
        return policy == null || !policy.enabled() ? 0 : policy.minimumResidents();
    }

    @Override public int minimumChunks(CityStage currentStage) {
        StagePromotionPolicy policy = policies.get(currentStage);
        return policy == null || !policy.enabled() ? 0 : policy.minimumChunks();
    }

    @Override public CompletionStage<Optional<CityView>> cityForPlayer(UUID playerId) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Progression service is unavailable"));
        return repository.findForPlayer(Objects.requireNonNull(playerId, "playerId"));
    }

    @Override public CompletionStage<CityPromotionResult> promote(CityPromotionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!ready) return result(request.operationId(), CityPromotionCode.SERVICE_UNAVAILABLE);
        StagePromotionPolicy policy = policies.get(request.expectedStage());
        Optional<CityStage> next = nextStage(request.expectedStage());
        if (policy == null || !policy.enabled() || next.isEmpty()) {
            return result(request.operationId(), CityPromotionCode.INVALID_STAGE);
        }
        CityPromotionDraft draft = new CityPromotionDraft(request.operationId(), request.cityId(), request.actorId(),
                request.expectedRevision(), request.expectedStage(), next.get(), policy.price(),
                policy.minimumResidents(), policy.minimumChunks());
        return repository.preparePromotion(draft).thenCompose(prepared -> {
            if (prepared.code() == CityPromotionCode.REPLAYED || prepared.code() != CityPromotionCode.OPERATION_IN_PROGRESS) {
                return CompletableFuture.completedFuture(prepared);
            }
            if (prepared.idempotentReplay()) return CompletableFuture.completedFuture(prepared);
            return repository.reservePromotion(request.operationId()).thenCompose(reserved -> reserved
                    ? applyPrepared(draft) : result(request.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS));
        }).exceptionally(error -> new CityPromotionResult(request.operationId(), CityPromotionCode.INTERNAL_ERROR,
                Optional.empty(), false));
    }

    private CompletionStage<CityPromotionResult> applyPrepared(CityPromotionDraft draft) {
        return repository.applyPromotion(draft).thenCompose(applied -> {
            if (applied.code() == CityPromotionCode.PROMOTED || applied.code() == CityPromotionCode.REPLAYED) {
                return repository.completePromotion(draft.operationId()).thenApply(done -> promoted(draft.operationId(), done));
            }
            if (applied.code() == CityPromotionCode.OPERATION_IN_PROGRESS || applied.code() == CityPromotionCode.OPERATION_CONFLICT) {
                return CompletableFuture.completedFuture(applied);
            }
            return repository.markPromotionReleased(draft.operationId()).thenApply(ignored -> applied);
        });
    }

    private CityPromotionResult promoted(UUID operationId, CityPromotionResult completed) {
        return completed.city().isPresent()
                ? new CityPromotionResult(operationId, CityPromotionCode.PROMOTED, completed.city(), completed.idempotentReplay())
                : completed;
    }

    private CompletionStage<CityPromotionResult> result(UUID operationId, CityPromotionCode code) {
        return CompletableFuture.completedFuture(new CityPromotionResult(operationId, code, Optional.empty(), false));
    }
}
