package ru.servermine.cities.territory;

import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityClaimCode;
import ru.servermine.cities.api.CityClaimRequest;
import ru.servermine.cities.api.CityClaimResult;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityTerritoryService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityClaimDraft;
import ru.servermine.cities.core.CityRepository;
import ru.servermine.economy.api.EconomyResult;
import ru.servermine.economy.api.EconomyService;
import ru.servermine.economy.api.MoneyRequest;
import ru.servermine.economy.api.OperationState;
import ru.servermine.economy.api.ResultCode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Durable claim workflow. The Economy payment is always owned by the requesting ruler. */
public final class CityTerritoryServiceImpl implements CityTerritoryService {
    private static final Comparator<ChunkPosition> POSITION_ORDER = Comparator
            .comparing((ChunkPosition p) -> p.worldId().toString()).thenComparingInt(ChunkPosition::x)
            .thenComparingInt(ChunkPosition::z);

    private final CityRepository repository;
    private final EconomyService economy;
    private final long price;
    private final Function<ChunkPosition, CompletionStage<Boolean>> checkTarget;
    private final Function<Set<ChunkPosition>, CompletionStage<List<ChunkPosition>>> checkTargets;
    private volatile boolean ready = true;

    public CityTerritoryServiceImpl(CityRepository repository, EconomyService economy, long price,
                                    Function<ChunkPosition, CompletionStage<Boolean>> checkTarget,
                                    Function<Set<ChunkPosition>, CompletionStage<List<ChunkPosition>>> checkTargets) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.economy = economy;
        if (price < 0) throw new IllegalArgumentException("territory chunk price cannot be negative");
        this.price = price;
        this.checkTarget = Objects.requireNonNull(checkTarget, "checkTarget");
        this.checkTargets = Objects.requireNonNull(checkTargets, "checkTargets");
    }

    public void deactivate() { ready = false; }
    @Override public boolean isReady() { return ready; }
    @Override public long chunkPrice() { return price; }

    @Override
    public CompletionStage<List<ChunkPosition>> availableTargets(UUID cityId) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Territory service unavailable"));
        return repository.find(Objects.requireNonNull(cityId, "cityId")).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(List.of());
            CityView city = found.get();
            if (city.stage() == CityStage.SETTLEMENT || city.chunks().size() >= city.stage().chunkLimit()) {
                return CompletableFuture.completedFuture(List.of());
            }
            Set<ChunkPosition> frontier = frontier(city.chunks());
            if (frontier.isEmpty()) return CompletableFuture.completedFuture(List.of());
            return repository.claimedChunks(frontier).thenCompose(claimed -> {
                Set<ChunkPosition> unclaimed = new HashSet<>(frontier);
                unclaimed.removeAll(claimed);
                return checkTargets.apply(Set.copyOf(unclaimed)).thenApply(allowed -> allowed.stream()
                        .distinct().sorted(POSITION_ORDER).toList());
            });
        });
    }

    @Override
    public CompletionStage<CityClaimResult> purchase(CityClaimRequest request) {
        Objects.requireNonNull(request, "request");
        if (!ready) return result(request.operationId(), CityClaimCode.SERVICE_UNAVAILABLE);
        if (price > 0 && (economy == null || !economy.isReady())) {
            return result(request.operationId(), CityClaimCode.ECONOMY_UNAVAILABLE);
        }
        CityClaimDraft draft = new CityClaimDraft(request.operationId(), request.cityId(), request.actorId(),
                request.expectedRevision(), request.target(), price);
        return checkTarget.apply(request.target()).thenCompose(decision -> {
            if (!decision) {
                CityClaimCode code = CityClaimCode.TARGET_UNAVAILABLE;
                return result(request.operationId(), code);
            }
            return repository.prepareClaim(draft).thenCompose(prepared -> {
                if (prepared.code() == CityClaimCode.REPLAYED || prepared.code() != CityClaimCode.OPERATION_IN_PROGRESS
                        || prepared.idempotentReplay()) return CompletableFuture.completedFuture(prepared);
                if (price == 0) return reserveAndApply(draft);
                MoneyRequest money = new MoneyRequest(request.operationId(), request.actorId(), price,
                        "ServerMineCities", "city_chunk_claim:" + request.target().worldId() + ":"
                        + request.target().x() + ":" + request.target().z());
                return economy.reserve(money).thenCompose(reservation -> handleReservation(draft, reservation));
            });
        }).exceptionally(error -> new CityClaimResult(request.operationId(), CityClaimCode.INTERNAL_ERROR,
                Optional.empty(), false));
    }

    private CompletionStage<CityClaimResult> handleReservation(CityClaimDraft draft, EconomyResult reservation) {
        if (reservation.code() != ResultCode.OK || reservation.state() != OperationState.RESERVED) {
            if (reservation.state() == OperationState.UNKNOWN || reservation.state() == OperationState.PREPARED
                    || reservation.code() == ResultCode.UNKNOWN_OUTCOME || reservation.code() == ResultCode.INTERNAL_ERROR) {
                return result(draft.operationId(), CityClaimCode.UNKNOWN_OUTCOME);
            }
            CityClaimCode failure = reservation.code() == ResultCode.INSUFFICIENT_FUNDS
                    ? CityClaimCode.INSUFFICIENT_FUNDS
                    : reservation.code() == ResultCode.OPERATION_CONFLICT
                    ? CityClaimCode.OPERATION_CONFLICT : CityClaimCode.TARGET_UNAVAILABLE;
            return repository.failClaim(draft.operationId(), failure).thenApply(ignored ->
                    new CityClaimResult(draft.operationId(), failure, Optional.empty(), false));
        }
        return reserveAndApply(draft);
    }

    private CompletionStage<CityClaimResult> reserveAndApply(CityClaimDraft draft) {
        return repository.reserveClaim(draft.operationId()).thenCompose(reserved -> {
            if (!reserved) return release(draft, CityClaimCode.OPERATION_IN_PROGRESS);
            return checkTarget.apply(draft.target()).thenCompose(decision -> {
                if (!decision) {
                    CityClaimCode failure = CityClaimCode.TARGET_UNAVAILABLE;
                    return release(draft, failure);
                }
                return applyPrepared(draft);
            });
        });
    }

    private CompletionStage<CityClaimResult> applyPrepared(CityClaimDraft draft) {
        return repository.applyClaim(draft).thenCompose(applied -> {
            if (applied.code() == CityClaimCode.CLAIMED || applied.code() == CityClaimCode.REPLAYED) {
                if (price == 0) return repository.completeClaim(draft.operationId()).thenApply(done -> claimed(draft.operationId(), done));
                return economy.commit(draft.operationId()).thenCompose(commit -> {
                    if (commit.code() == ResultCode.OK && commit.state() == OperationState.COMMITTED) {
                        return repository.completeClaim(draft.operationId()).thenApply(done -> claimed(draft.operationId(), done));
                    }
                    return repository.markClaimCommitPending(draft.operationId()).thenApply(ignored ->
                            new CityClaimResult(draft.operationId(), CityClaimCode.UNKNOWN_OUTCOME, Optional.empty(), false));
                });
            }
            if (price == 0 || applied.code() == CityClaimCode.OPERATION_IN_PROGRESS
                    || applied.code() == CityClaimCode.OPERATION_CONFLICT) return CompletableFuture.completedFuture(applied);
            return release(draft, applied.code());
        });
    }

    private CompletionStage<CityClaimResult> release(CityClaimDraft draft, CityClaimCode failure) {
        if (price == 0) return repository.markClaimReleasePending(draft.operationId(), failure)
                .thenCompose(ignored -> repository.markClaimReleased(draft.operationId()))
                .thenApply(ignored -> new CityClaimResult(draft.operationId(), failure, Optional.empty(), false));
        return repository.markClaimReleasePending(draft.operationId(), failure).thenCompose(ignored -> economy.release(draft.operationId()))
                .thenCompose(release -> {
                    if (release.code() == ResultCode.OK && release.state() == OperationState.RELEASED) {
                        return repository.markClaimReleased(draft.operationId()).thenApply(done ->
                                new CityClaimResult(draft.operationId(), failure, Optional.empty(), false));
                    }
                    return result(draft.operationId(), CityClaimCode.UNKNOWN_OUTCOME);
                });
    }

    private CityClaimResult claimed(UUID operationId, CityClaimResult completed) {
        return completed.city().isPresent()
                ? new CityClaimResult(operationId, CityClaimCode.CLAIMED, completed.city(), completed.idempotentReplay())
                : completed;
    }

    private CompletionStage<CityClaimResult> result(UUID operationId, CityClaimCode code) {
        return CompletableFuture.completedFuture(new CityClaimResult(operationId, code, Optional.empty(), false));
    }

    private Set<ChunkPosition> frontier(Set<ChunkPosition> chunks) {
        Set<ChunkPosition> result = new HashSet<>();
        for (ChunkPosition chunk : chunks) {
            add(result, chunk, 1, 0); add(result, chunk, -1, 0);
            add(result, chunk, 0, 1); add(result, chunk, 0, -1);
        }
        result.removeAll(chunks);
        return Set.copyOf(result);
    }

    private void add(Set<ChunkPosition> target, ChunkPosition source, int dx, int dz) {
        long x = (long) source.x() + dx;
        long z = (long) source.z() + dz;
        if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE && z >= Integer.MIN_VALUE && z <= Integer.MAX_VALUE) {
            target.add(new ChunkPosition(source.worldId(), (int) x, (int) z));
        }
    }
}
