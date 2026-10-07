package ru.servermine.economy.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.servermine.economy.api.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class EconomyServiceImpl implements EconomyService {
    private static final String API_VERSION = "1";

    private final CurrencyRegistry registry;
    private final InventoryMoneyEngine moneyEngine;
    private final OperationRepository operations;
    private final MainThread mainThread;
    private volatile boolean ready;

    EconomyServiceImpl(
            CurrencyRegistry registry,
            InventoryMoneyEngine moneyEngine,
            OperationRepository operations,
            MainThread mainThread
    ) {
        this.registry = registry;
        this.moneyEngine = moneyEngine;
        this.operations = operations;
        this.mainThread = mainThread;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public Set<EconomyCapability> capabilities() {
        return EnumSet.allOf(EconomyCapability.class);
    }

    @Override
    public List<DenominationView> denominations() {
        return registry.publicView();
    }

    @Override
    public CompletionStage<BalanceResult> physicalBalance(UUID playerId) {
        if (!ready) return CompletableFuture.completedFuture(new BalanceResult(playerId, ResultCode.SERVICE_UNAVAILABLE, -1, "Economy is not ready"));
        return mainThread.call(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return new BalanceResult(playerId, ResultCode.PLAYER_OFFLINE, -1, "Player is offline");
            }
            return new BalanceResult(playerId, ResultCode.OK, moneyEngine.balance(player), "ok");
        });
    }

    @Override
    public CompletionStage<EconomyResult> charge(MoneyRequest request) {
        return debit(request, "CHARGE", OperationState.COMMITTED);
    }

    @Override
    public CompletionStage<EconomyResult> payout(MoneyRequest request) {
        if (!valid(request)) return CompletableFuture.completedFuture(invalid(request));
        if (!ready) return CompletableFuture.completedFuture(unavailable(request));

        return operations.prepare("PAYOUT", request).thenCompose(prepared -> {
            EconomyResult replay = replayOrConflict(request, prepared);
            if (replay != null) return CompletableFuture.completedFuture(replay);

            return mainThread.call(() -> {
                Player player = Bukkit.getPlayer(request.playerId());
                if (player == null || !player.isOnline()) return new PlayerMutation(null, ResultCode.PLAYER_OFFLINE);
                InventoryMoneyEngine.Mutation mutation = moneyEngine.payout(player, request.amount());
                return switch (mutation.code()) {
                    case OK -> new PlayerMutation(mutation, ResultCode.OK);
                    case INVENTORY_FULL -> new PlayerMutation(mutation, ResultCode.INVENTORY_FULL);
                    case INSUFFICIENT_FUNDS -> throw new IllegalStateException("Impossible payout result");
                };
            }).thenCompose(pm -> finishPrepared(request, pm, OperationState.COMMITTED));
        }).exceptionally(ex -> internalFailure(request, ex));
    }

    @Override
    public CompletionStage<EconomyResult> reserve(MoneyRequest request) {
        return debit(request, "RESERVE", OperationState.RESERVED);
    }

    private CompletionStage<EconomyResult> debit(MoneyRequest request, String type, OperationState successState) {
        if (!valid(request)) return CompletableFuture.completedFuture(invalid(request));
        if (!ready) return CompletableFuture.completedFuture(unavailable(request));

        return operations.prepare(type, request).thenCompose(prepared -> {
            EconomyResult replay = replayOrConflict(request, prepared);
            if (replay != null) return CompletableFuture.completedFuture(replay);

            return mainThread.call(() -> {
                Player player = Bukkit.getPlayer(request.playerId());
                if (player == null || !player.isOnline()) return new PlayerMutation(null, ResultCode.PLAYER_OFFLINE);
                InventoryMoneyEngine.Mutation mutation = moneyEngine.charge(player, request.amount());
                return switch (mutation.code()) {
                    case OK -> new PlayerMutation(mutation, ResultCode.OK);
                    case INSUFFICIENT_FUNDS -> new PlayerMutation(mutation, ResultCode.INSUFFICIENT_FUNDS);
                    case INVENTORY_FULL -> new PlayerMutation(mutation, ResultCode.INVENTORY_FULL);
                };
            }).thenCompose(pm -> finishPrepared(request, pm, successState));
        }).exceptionally(ex -> internalFailure(request, ex));
    }

    private CompletionStage<EconomyResult> finishPrepared(MoneyRequest request, PlayerMutation pm, OperationState successState) {
        long before = pm.mutation == null ? -1 : pm.mutation.balanceBefore();
        long after = pm.mutation == null ? -1 : pm.mutation.balanceAfter();
        OperationState target = pm.code == ResultCode.OK ? successState : OperationState.FAILED;
        return operations.transition(request.operationId(), OperationState.PREPARED, target, pm.code, before, after)
                .handle((row, error) -> {
                    if (error != null) {
                        operations.markUnknown(request.operationId());
                        return new EconomyResult(request.operationId(), ResultCode.UNKNOWN_OUTCOME, OperationState.UNKNOWN,
                                request.amount(), before, after, false, "Outcome is unknown; admin reconciliation required");
                    }
                    return result(row, false);
                });
    }

    @Override
    public CompletionStage<EconomyResult> commit(UUID operationId) {
        if (!ready) return CompletableFuture.completedFuture(simple(operationId, ResultCode.SERVICE_UNAVAILABLE, OperationState.UNKNOWN, 0, "Economy is not ready"));
        return operations.find(operationId).thenCompose(optional -> {
            if (optional.isEmpty()) return CompletableFuture.completedFuture(simple(operationId, ResultCode.OPERATION_NOT_FOUND, OperationState.FAILED, 0, "Operation not found"));
            OperationRecord row = optional.get();
            if (row.state() == OperationState.COMMITTED) return CompletableFuture.completedFuture(result(row, true));
            if (row.state() != OperationState.RESERVED) {
                return CompletableFuture.completedFuture(simple(operationId, ResultCode.INVALID_OPERATION_STATE, row.state(), row.amount(), "Expected RESERVED"));
            }
            return operations.transition(operationId, OperationState.RESERVED, OperationState.COMMITTED, ResultCode.OK,
                    row.balanceBefore(), row.balanceAfter()).thenApply(updated -> result(updated, false));
        }).exceptionally(ex -> simple(operationId, ResultCode.INTERNAL_ERROR, OperationState.UNKNOWN, 0, rootMessage(ex)));
    }

    @Override
    public CompletionStage<EconomyResult> release(UUID operationId) {
        if (!ready) return CompletableFuture.completedFuture(simple(operationId, ResultCode.SERVICE_UNAVAILABLE, OperationState.UNKNOWN, 0, "Economy is not ready"));

        return operations.find(operationId).thenCompose(optional -> {
            if (optional.isEmpty()) return CompletableFuture.completedFuture(simple(operationId, ResultCode.OPERATION_NOT_FOUND, OperationState.FAILED, 0, "Operation not found"));
            OperationRecord row = optional.get();
            if (row.state() == OperationState.RELEASED) return CompletableFuture.completedFuture(result(row, true));
            if (row.state() != OperationState.RESERVED) {
                return CompletableFuture.completedFuture(simple(operationId, ResultCode.INVALID_OPERATION_STATE, row.state(), row.amount(), "Expected RESERVED"));
            }

            return operations.transition(operationId, OperationState.RESERVED, OperationState.RELEASING, ResultCode.OK,
                    row.balanceBefore(), row.balanceAfter()).thenCompose(releasing ->
                    mainThread.call(() -> {
                        Player player = Bukkit.getPlayer(releasing.playerId());
                        if (player == null || !player.isOnline()) return new PlayerMutation(null, ResultCode.PLAYER_OFFLINE);
                        InventoryMoneyEngine.Mutation mutation = moneyEngine.payout(player, releasing.amount());
                        return switch (mutation.code()) {
                            case OK -> new PlayerMutation(mutation, ResultCode.OK);
                            case INVENTORY_FULL -> new PlayerMutation(mutation, ResultCode.INVENTORY_FULL);
                            case INSUFFICIENT_FUNDS -> throw new IllegalStateException("Impossible release result");
                        };
                    }).thenCompose(pm -> {
                        if (pm.code != ResultCode.OK) {
                            long before = pm.mutation == null ? releasing.balanceAfter() : pm.mutation.balanceBefore();
                            long after = pm.mutation == null ? releasing.balanceAfter() : pm.mutation.balanceAfter();
                            return operations.transition(operationId, OperationState.RELEASING, OperationState.RESERVED, pm.code, before, after)
                                    .thenApply(updated -> result(updated, false));
                        }
                        return operations.transition(operationId, OperationState.RELEASING, OperationState.RELEASED, ResultCode.OK,
                                        pm.mutation.balanceBefore(), pm.mutation.balanceAfter())
                                .handle((updated, error) -> {
                                    if (error != null) {
                                        return simple(operationId, ResultCode.UNKNOWN_OUTCOME, OperationState.UNKNOWN, releasing.amount(),
                                                "Refund may have been delivered; admin reconciliation required");
                                    }
                                    return result(updated, false);
                                });
                    })
            );
        }).exceptionally(ex -> simple(operationId, ResultCode.INTERNAL_ERROR, OperationState.UNKNOWN, 0, rootMessage(ex)));
    }

    @Override
    public CompletionStage<Optional<OperationView>> operation(UUID operationId) {
        return operations.find(operationId).thenApply(optional -> optional.map(this::view));
    }

    private EconomyResult replayOrConflict(MoneyRequest request, OperationRepository.PrepareResult prepared) {
        if (prepared.status() == OperationRepository.PrepareStatus.CONFLICT) {
            return new EconomyResult(request.operationId(), ResultCode.OPERATION_CONFLICT, prepared.record().state(), request.amount(),
                    prepared.record().balanceBefore(), prepared.record().balanceAfter(), true,
                    "The same operationId was already used with different parameters");
        }
        if (prepared.status() == OperationRepository.PrepareStatus.EXISTING_SAME) {
            OperationRecord row = prepared.record();
            if (row.state() == OperationState.PREPARED || row.state() == OperationState.RELEASING) {
                return new EconomyResult(request.operationId(), ResultCode.OPERATION_CONFLICT, row.state(), row.amount(),
                        row.balanceBefore(), row.balanceAfter(), true, "Operation is already in progress");
            }
            return result(row, true);
        }
        return null;
    }

    private boolean valid(MoneyRequest request) {
        return request.amount() > 0;
    }

    private EconomyResult invalid(MoneyRequest request) {
        return new EconomyResult(request.operationId(), ResultCode.INVALID_AMOUNT, OperationState.FAILED, request.amount(), -1, -1, false,
                "Amount must be positive");
    }

    private EconomyResult unavailable(MoneyRequest request) {
        return new EconomyResult(request.operationId(), ResultCode.SERVICE_UNAVAILABLE, OperationState.FAILED, request.amount(), -1, -1, false,
                "Economy is not ready");
    }

    private EconomyResult internalFailure(MoneyRequest request, Throwable error) {
        return new EconomyResult(request.operationId(), ResultCode.INTERNAL_ERROR, OperationState.UNKNOWN, request.amount(), -1, -1, false, rootMessage(error));
    }

    private EconomyResult result(OperationRecord row, boolean replay) {
        return new EconomyResult(row.operationId(), row.resultCode(), row.state(), row.amount(), row.balanceBefore(), row.balanceAfter(), replay,
                row.resultCode() == ResultCode.OK ? "ok" : row.resultCode().name());
    }

    private OperationView view(OperationRecord row) {
        return new OperationView(row.operationId(), row.type(), row.playerId(), row.amount(), row.sourcePlugin(), row.purpose(),
                row.state(), row.resultCode(), row.createdAt(), row.updatedAt());
    }

    private EconomyResult simple(UUID operationId, ResultCode code, OperationState state, long amount, String message) {
        return new EconomyResult(operationId, code, state, amount, -1, -1, false, message);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private record PlayerMutation(InventoryMoneyEngine.Mutation mutation, ResultCode code) {}
}
