package ru.servermine.economy.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Публичный контракт ServerMineEconomy.
 * Внешние плагины получают этот сервис через Bukkit ServicesManager.
 */
public interface EconomyService {
    String apiVersion();

    boolean isReady();

    Set<EconomyCapability> capabilities();

    List<DenominationView> denominations();

    /** Сумма подлинной физической валюты в инвентаре онлайн-игрока. */
    CompletionStage<BalanceResult> physicalBalance(UUID playerId);

    /** Списать деньги окончательно. Удобный вариант reserve + commit. */
    CompletionStage<EconomyResult> charge(MoneyRequest request);

    /** Выдать физическую валюту игроку. */
    CompletionStage<EconomyResult> payout(MoneyRequest request);

    /**
     * Зарезервировать сумму: монеты изымаются из инвентаря, но операция ещё не считается завершённой.
     * Используется межплагинными сагами (например, Cities: покупка чанка).
     */
    CompletionStage<EconomyResult> reserve(MoneyRequest request);

    /** Зафиксировать ранее созданный резерв. */
    CompletionStage<EconomyResult> commit(UUID operationId);

    /** Отменить резерв и вернуть эквивалентную сумму игроку. */
    CompletionStage<EconomyResult> release(UUID operationId);

    CompletionStage<Optional<OperationView>> operation(UUID operationId);
}
