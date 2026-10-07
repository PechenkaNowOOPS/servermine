package ru.servermine.cities.treasury;

import ru.servermine.cities.api.CityTreasuryCode;
import ru.servermine.cities.api.CityTreasuryEntry;
import ru.servermine.cities.api.CityTreasuryResult;
import ru.servermine.cities.api.CityTreasuryService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityRepository;
import ru.servermine.cities.core.CityTreasuryDraft;
import ru.servermine.cities.core.PendingTreasuryDeposit;
import ru.servermine.economy.api.EconomyResult;
import ru.servermine.economy.api.EconomyService;
import ru.servermine.economy.api.MoneyRequest;
import ru.servermine.economy.api.OperationState;
import ru.servermine.economy.api.ResultCode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Physical-coin deposits are a durable saga; city spending is committed with its domain action. */
public final class CityTreasuryServiceImpl implements CityTreasuryService {
    private final CityRepository repository;
    private final EconomyService economy;
    private final Consumer<CityView> cityChanged;
    private volatile boolean ready;

    public CityTreasuryServiceImpl(CityRepository repository, EconomyService economy, Consumer<CityView> cityChanged) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.economy = economy;
        this.cityChanged = Objects.requireNonNull(cityChanged, "cityChanged");
    }

    @Override public boolean isReady() { return ready; }
    public void deactivate() { ready = false; }

    /** Reconcile deposits interrupted at any Economy/SQLite boundary before accepting new deposits. */
    public CompletionStage<Void> recoverPending() {
        ready = false;
        return repository.pendingTreasuryDeposits().thenCompose(pending -> {
            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (PendingTreasuryDeposit operation : pending) {
                chain = chain.thenCompose(ignored -> recover(operation));
            }
            return chain;
        }).thenRun(() -> ready = true);
    }

    @Override
    public CompletionStage<CityTreasuryResult> deposit(UUID playerId, long amount) {
        UUID operationId = UUID.randomUUID();
        if (!ready) return result(operationId, CityTreasuryCode.SERVICE_UNAVAILABLE);
        if (amount <= 0) return result(operationId, CityTreasuryCode.INVALID_AMOUNT);
        if (economy == null || !economy.isReady()) return result(operationId, CityTreasuryCode.ECONOMY_UNAVAILABLE);
        return repository.findForPlayer(playerId).thenCompose(found -> {
            if (found.isEmpty()) return result(operationId, CityTreasuryCode.NO_CITY);
            CityTreasuryDraft draft = new CityTreasuryDraft(operationId, found.get().id(), playerId, amount);
            return repository.prepareTreasuryDeposit(draft).thenCompose(prepared -> {
                if (prepared.code() != CityTreasuryCode.OPERATION_IN_PROGRESS) return CompletableFuture.completedFuture(prepared);
                MoneyRequest money = new MoneyRequest(operationId, playerId, amount, "ServerMineCities", "city_treasury_deposit");
                return economy.reserve(money).thenCompose(reservation -> handleReservation(draft, reservation));
            });
        }).exceptionally(error -> resultNow(operationId, CityTreasuryCode.INTERNAL_ERROR));
    }

    @Override
    public CompletionStage<List<CityTreasuryEntry>> history(UUID playerId, int limit) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("City treasury is recovering"));
        return repository.findForPlayer(playerId).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture(List.of())
                : repository.treasuryHistory(found.get().id(), limit));
    }

    private CompletionStage<CityTreasuryResult> handleReservation(CityTreasuryDraft draft, EconomyResult reservation) {
        if (reservation.code() != ResultCode.OK || reservation.state() != OperationState.RESERVED) {
            if (reservation.state() == OperationState.UNKNOWN || reservation.state() == OperationState.PREPARED
                    || reservation.code() == ResultCode.UNKNOWN_OUTCOME || reservation.code() == ResultCode.INTERNAL_ERROR) {
                return result(draft.operationId(), CityTreasuryCode.UNKNOWN_OUTCOME);
            }
            return repository.failTreasuryDeposit(draft.operationId(), false).thenApply(ignored -> resultNow(
                    draft.operationId(), reservation.code() == ResultCode.INSUFFICIENT_FUNDS
                            ? CityTreasuryCode.INSUFFICIENT_FUNDS : CityTreasuryCode.INTERNAL_ERROR));
        }
        return repository.reserveTreasuryDeposit(draft.operationId()).thenCompose(reserved -> reserved
                ? applyAndCommit(draft) : release(draft, CityTreasuryCode.OPERATION_IN_PROGRESS));
    }

    private CompletionStage<CityTreasuryResult> applyAndCommit(CityTreasuryDraft draft) {
        return repository.applyTreasuryDeposit(draft).thenCompose(applied -> {
            if (applied.code() != CityTreasuryCode.DEPOSITED) return release(draft, applied.code());
            return economy.commit(draft.operationId()).thenCompose(committed -> {
                if (committed.code() == ResultCode.OK && committed.state() == OperationState.COMMITTED) {
                    return repository.completeTreasuryDeposit(draft.operationId()).thenApply(done -> publish(done));
                }
                return repository.markTreasuryCommitPending(draft.operationId())
                        .thenApply(ignored -> resultNow(draft.operationId(), CityTreasuryCode.UNKNOWN_OUTCOME));
            });
        });
    }

    private CompletionStage<CityTreasuryResult> release(CityTreasuryDraft draft, CityTreasuryCode failure) {
        return repository.failTreasuryDeposit(draft.operationId(), true).thenCompose(ignored -> economy.release(draft.operationId()))
                .thenCompose(released -> {
                    if (released.code() == ResultCode.OK && released.state() == OperationState.RELEASED) {
                        return repository.markTreasuryDepositReleased(draft.operationId())
                                .thenApply(done -> resultNow(draft.operationId(), failure));
                    }
                    return result(draft.operationId(), CityTreasuryCode.UNKNOWN_OUTCOME);
                });
    }

    private CompletionStage<Void> recover(PendingTreasuryDeposit pending) {
        if (economy == null || !economy.isReady()) return CompletableFuture.failedFuture(
                new IllegalStateException("Economy is unavailable while recovering city deposits"));
        CityTreasuryDraft draft = new CityTreasuryDraft(pending.operationId(), pending.cityId(), pending.actorId(), pending.amount());
        return economy.operation(pending.operationId()).thenCompose(found -> {
            if (found.isEmpty()) {
                if (pending.state().equals("PREPARED")) return repository.failTreasuryDeposit(pending.operationId(), false);
                if (pending.state().equals("RELEASE_PENDING")) return repository.markTreasuryDepositReleased(pending.operationId());
                return CompletableFuture.failedFuture(new IllegalStateException("Treasury operation is pending without an Economy record"));
            }
            OperationState economyState = found.get().state();
            if (pending.state().equals("RELEASE_PENDING")) {
                if (economyState == OperationState.RELEASED) return repository.markTreasuryDepositReleased(pending.operationId());
                if (economyState == OperationState.RESERVED || economyState == OperationState.RELEASING) {
                    return economy.release(pending.operationId()).thenCompose(result -> result.state() == OperationState.RELEASED
                            ? repository.markTreasuryDepositReleased(pending.operationId())
                            : CompletableFuture.failedFuture(new IllegalStateException("Could not recover treasury release")));
                }
                return CompletableFuture.failedFuture(new IllegalStateException("Treasury release outcome needs manual reconciliation"));
            }
            if (pending.state().equals("PREPARED")) {
                if (economyState == OperationState.RESERVED) {
                    return repository.reserveTreasuryDeposit(pending.operationId()).thenCompose(ok -> ok
                            ? applyAndCommit(draft).thenApply(ignored -> null)
                            : CompletableFuture.failedFuture(new IllegalStateException("Could not restore treasury reservation")));
                }
                if (economyState == OperationState.FAILED || economyState == OperationState.RELEASED) {
                    return repository.failTreasuryDeposit(pending.operationId(), false);
                }
                return CompletableFuture.failedFuture(new IllegalStateException("Prepared treasury operation needs reconciliation"));
            }
            if (pending.state().equals("RESERVED")) return applyAndCommit(draft).thenApply(ignored -> null);
            if (pending.state().equals("DOMAIN_COMMITTED") || pending.state().equals("COMMIT_PENDING")) {
                if (economyState == OperationState.COMMITTED) return repository.completeTreasuryDeposit(pending.operationId()).thenAccept(this::publish);
                if (economyState == OperationState.RESERVED) return economy.commit(pending.operationId()).thenCompose(result ->
                        result.state() == OperationState.COMMITTED
                                ? repository.completeTreasuryDeposit(pending.operationId()).thenAccept(this::publish)
                                : CompletableFuture.failedFuture(new IllegalStateException("Could not recover treasury commit")));
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Treasury operation needs manual reconciliation: " + pending.operationId()));
        });
    }

    private CityTreasuryResult publish(CityTreasuryResult result) {
        if (result.code() == CityTreasuryCode.DEPOSITED) result.city().ifPresent(cityChanged);
        return result;
    }

    private CompletionStage<CityTreasuryResult> result(UUID id, CityTreasuryCode code) {
        return CompletableFuture.completedFuture(resultNow(id, code));
    }

    private CityTreasuryResult resultNow(UUID id, CityTreasuryCode code) {
        return new CityTreasuryResult(id, code, Optional.empty());
    }
}
