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
import java.util.function.Consumer;

/** Durable claim workflow; paid claims debit the city treasury in the same SQLite transaction as the claim. */
public final class CityTerritoryServiceImpl implements CityTerritoryService {
    private static final Comparator<ChunkPosition> POSITION_ORDER = Comparator
            .comparing((ChunkPosition p) -> p.worldId().toString()).thenComparingInt(ChunkPosition::x)
            .thenComparingInt(ChunkPosition::z);

    private final CityRepository repository;
    private final long price;
    private final Function<ChunkPosition, CompletionStage<Boolean>> checkTarget;
    private final Function<Set<ChunkPosition>, CompletionStage<List<ChunkPosition>>> checkTargets;
    private final Consumer<CityView> cityChanged;
    private volatile boolean ready = true;

    public CityTerritoryServiceImpl(CityRepository repository, long price,
                                    Function<ChunkPosition, CompletionStage<Boolean>> checkTarget,
                                    Function<Set<ChunkPosition>, CompletionStage<List<ChunkPosition>>> checkTargets,
                                    Consumer<CityView> cityChanged) {
        this.repository = Objects.requireNonNull(repository, "repository");
        if (price < 0) throw new IllegalArgumentException("territory chunk price cannot be negative");
        this.price = price;
        this.checkTarget = Objects.requireNonNull(checkTarget, "checkTarget");
        this.checkTargets = Objects.requireNonNull(checkTargets, "checkTargets");
        this.cityChanged = Objects.requireNonNull(cityChanged, "cityChanged");
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
                return reserveAndApply(draft);
            });
        }).exceptionally(error -> new CityClaimResult(request.operationId(), CityClaimCode.INTERNAL_ERROR,
                Optional.empty(), false));
    }

    private CompletionStage<CityClaimResult> reserveAndApply(CityClaimDraft draft) {
        return repository.reserveClaim(draft.operationId()).thenCompose(reserved -> {
            if (!reserved) return result(draft.operationId(), CityClaimCode.OPERATION_IN_PROGRESS);
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
                applied.city().ifPresent(cityChanged);
                return repository.completeClaim(draft.operationId()).thenApply(done -> claimed(draft.operationId(), done));
            }
            if (applied.code() == CityClaimCode.OPERATION_IN_PROGRESS
                    || applied.code() == CityClaimCode.OPERATION_CONFLICT) return CompletableFuture.completedFuture(applied);
            return release(draft, applied.code());
        });
    }

    private CompletionStage<CityClaimResult> release(CityClaimDraft draft, CityClaimCode failure) {
        return repository.markClaimReleasePending(draft.operationId(), failure)
                .thenCompose(ignored -> repository.markClaimReleased(draft.operationId()))
                .thenApply(ignored -> new CityClaimResult(draft.operationId(), failure, Optional.empty(), false));
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
