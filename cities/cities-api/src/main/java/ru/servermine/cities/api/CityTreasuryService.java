package ru.servermine.cities.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** City balance, audited history, and physical-coin deposits. City development costs debit this treasury. */
public interface CityTreasuryService {
    boolean isReady();

    CompletionStage<CityTreasuryResult> deposit(UUID playerId, long amount);

    CompletionStage<List<CityTreasuryEntry>> history(UUID playerId, int limit);
}
