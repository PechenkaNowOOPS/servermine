package ru.servermine.cities.progression;

import ru.servermine.cities.api.CityProgressionService;
import ru.servermine.cities.api.CityPromotionCode;
import ru.servermine.cities.api.CityPromotionRequest;
import ru.servermine.cities.api.CityPromotionResult;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityPromotionDraft;
import ru.servermine.cities.core.CityRepository;
import ru.servermine.economy.api.EconomyResult;
import ru.servermine.economy.api.EconomyService;
import ru.servermine.economy.api.MoneyRequest;
import ru.servermine.economy.api.OperationState;
import ru.servermine.economy.api.ResultCode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Configured stage promotion workflow with a durable Cities intent and optional Economy reservation. */
public final class CityProgressionServiceImpl implements CityProgressionService {
    private final CityRepository repository;
    private final EconomyService economy;
    private final Map<CityStage, StagePromotionPolicy> policies;
    private volatile boolean ready = true;

    public CityProgressionServiceImpl(CityRepository repository, EconomyService economy,
                                      Map<CityStage, StagePromotionPolicy> policies) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.economy = economy;
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
        if (policy.price() > 0 && (economy == null || !economy.isReady())) {
            return result(request.operationId(), CityPromotionCode.ECONOMY_UNAVAILABLE);
        }
        CityPromotionDraft draft = new CityPromotionDraft(request.operationId(), request.cityId(), request.actorId(),
                request.expectedRevision(), request.expectedStage(), next.get(), policy.price(),
                policy.minimumResidents(), policy.minimumChunks());
        return repository.preparePromotion(draft).thenCompose(prepared -> {
            if (prepared.code() == CityPromotionCode.REPLAYED || prepared.code() != CityPromotionCode.OPERATION_IN_PROGRESS) {
                return CompletableFuture.completedFuture(prepared);
            }
            if (prepared.idempotentReplay()) return CompletableFuture.completedFuture(prepared);
            if (policy.price() == 0) {
                return repository.reservePromotion(request.operationId()).thenCompose(reserved -> {
                    if (!reserved) return result(request.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS);
                    return applyPrepared(draft);
                });
            }
            MoneyRequest money = new MoneyRequest(request.operationId(), request.actorId(), policy.price(),
                    "ServerMineCities", "city_stage_promotion:" + request.expectedStage().name() + ":" + next.get().name());
            return economy.reserve(money).thenCompose(reservation -> handleReservation(draft, reservation));
        }).exceptionally(error -> new CityPromotionResult(request.operationId(), CityPromotionCode.INTERNAL_ERROR,
                Optional.empty(), false));
    }

    private CompletionStage<CityPromotionResult> handleReservation(CityPromotionDraft draft, EconomyResult reservation) {
        if (reservation.code() != ResultCode.OK || reservation.state() != OperationState.RESERVED) {
            if (reservation.state() == OperationState.UNKNOWN || reservation.state() == OperationState.PREPARED
                    || reservation.code() == ResultCode.UNKNOWN_OUTCOME || reservation.code() == ResultCode.INTERNAL_ERROR) {
                return result(draft.operationId(), CityPromotionCode.UNKNOWN_OUTCOME);
            }
            CityPromotionCode failure = reservation.code() == ResultCode.INSUFFICIENT_FUNDS
                    ? CityPromotionCode.INSUFFICIENT_FUNDS
                    : reservation.code() == ResultCode.OPERATION_CONFLICT
                    ? CityPromotionCode.OPERATION_CONFLICT : CityPromotionCode.UNKNOWN_OUTCOME;
            return repository.failPromotion(draft.operationId(), failure, false)
                    .thenApply(ignored -> new CityPromotionResult(draft.operationId(), failure, Optional.empty(), false));
        }
        return repository.reservePromotion(draft.operationId()).thenCompose(reserved -> {
            if (!reserved) return result(draft.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS);
            return applyPrepared(draft);
        });
    }

    private CompletionStage<CityPromotionResult> applyPrepared(CityPromotionDraft draft) {
        return repository.applyPromotion(draft).thenCompose(applied -> {
            if (applied.code() == CityPromotionCode.PROMOTED || applied.code() == CityPromotionCode.REPLAYED) {
                if (draft.price() == 0) return repository.completePromotion(draft.operationId()).thenApply(done -> promoted(draft.operationId(), done));
                return economy.commit(draft.operationId()).thenCompose(commit -> {
                    if (commit.code() == ResultCode.OK && commit.state() == OperationState.COMMITTED) {
                        return repository.completePromotion(draft.operationId()).thenApply(done -> promoted(draft.operationId(), done));
                    }
                    return repository.markPromotionCommitPending(draft.operationId())
                            .thenApply(ignored -> new CityPromotionResult(draft.operationId(), CityPromotionCode.UNKNOWN_OUTCOME,
                                    Optional.empty(), false));
                });
            }
            if (draft.price() == 0) return CompletableFuture.completedFuture(applied);
            if (applied.code() == CityPromotionCode.OPERATION_IN_PROGRESS || applied.code() == CityPromotionCode.OPERATION_CONFLICT) {
                return CompletableFuture.completedFuture(applied);
            }
            return economy.release(draft.operationId()).thenCompose(release -> {
                if (release.code() == ResultCode.OK && release.state() == OperationState.RELEASED) {
                    return repository.markPromotionReleased(draft.operationId()).thenApply(ignored -> applied);
                }
                return CompletableFuture.completedFuture(new CityPromotionResult(draft.operationId(), CityPromotionCode.UNKNOWN_OUTCOME,
                        Optional.empty(), false));
            });
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
