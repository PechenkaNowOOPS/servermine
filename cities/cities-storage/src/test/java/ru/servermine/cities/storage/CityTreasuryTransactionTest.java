package ru.servermine.cities.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityClaimCode;
import ru.servermine.cities.api.CityFoundationDraft;
import ru.servermine.cities.api.CityPromotionCode;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityTreasuryCode;
import ru.servermine.cities.core.CityClaimDraft;
import ru.servermine.cities.core.CityPromotionDraft;
import ru.servermine.cities.core.CityTreasuryDraft;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTreasuryTransactionTest {
    @TempDir Path directory;

    @Test
    void depositsAndCityExpensesKeepBalanceAndLedgerAtomic() throws Exception {
        Path database = directory.resolve("cities.db");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SqliteCityRepository repository = new SqliteCityRepository(database, 5_000, executor);
            repository.initialize();

            UUID cityId = UUID.randomUUID();
            UUID rulerId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            CityFoundationDraft foundation = new CityFoundationDraft(UUID.randomUUID(), cityId, rulerId,
                    "Ruler", "Test City", "test city", Set.of(
                    new ChunkPosition(worldId, 0, 0), new ChunkPosition(worldId, 0, 1),
                    new ChunkPosition(worldId, 1, 0), new ChunkPosition(worldId, 1, 1)));
            repository.found(foundation).toCompletableFuture().join();

            CityPromotionDraft promotion = new CityPromotionDraft(UUID.randomUUID(), cityId, rulerId, 1,
                    CityStage.SETTLEMENT, CityStage.VILLAGE, 0, 1, 4);
            assertEquals(CityPromotionCode.OPERATION_IN_PROGRESS,
                    repository.preparePromotion(promotion).toCompletableFuture().join().code());
            assertTrue(repository.reservePromotion(promotion.operationId()).toCompletableFuture().join());
            assertEquals(CityPromotionCode.PROMOTED,
                    repository.applyPromotion(promotion).toCompletableFuture().join().code());
            repository.completePromotion(promotion.operationId()).toCompletableFuture().join();

            CityTreasuryDraft deposit = new CityTreasuryDraft(UUID.randomUUID(), cityId, rulerId, 250);
            assertEquals(CityTreasuryCode.OPERATION_IN_PROGRESS,
                    repository.prepareTreasuryDeposit(deposit).toCompletableFuture().join().code());
            assertTrue(repository.reserveTreasuryDeposit(deposit.operationId()).toCompletableFuture().join());
            assertEquals(CityTreasuryCode.DEPOSITED,
                    repository.applyTreasuryDeposit(deposit).toCompletableFuture().join().code());
            repository.completeTreasuryDeposit(deposit.operationId()).toCompletableFuture().join();

            CityClaimDraft claim = new CityClaimDraft(UUID.randomUUID(), cityId, rulerId, 3,
                    new ChunkPosition(worldId, 2, 1), 200);
            assertEquals(CityClaimCode.OPERATION_IN_PROGRESS,
                    repository.prepareClaim(claim).toCompletableFuture().join().code());
            assertTrue(repository.reserveClaim(claim.operationId()).toCompletableFuture().join());
            assertEquals(CityClaimCode.CLAIMED,
                    repository.applyClaim(claim).toCompletableFuture().join().code());
            repository.completeClaim(claim.operationId()).toCompletableFuture().join();

            assertEquals(50L, repository.find(cityId).toCompletableFuture().join().orElseThrow().treasury());
            var history = repository.treasuryHistory(cityId, 10).toCompletableFuture().join();
            assertEquals(2, history.size());
            assertEquals(-200L, history.get(0).amount());
            assertEquals(50L, history.get(0).balanceAfter());
            assertEquals(250L, history.get(1).amount());

            CityClaimDraft unaffordableClaim = new CityClaimDraft(UUID.randomUUID(), cityId, rulerId, 4,
                    new ChunkPosition(worldId, 2, 2), 100);
            assertEquals(CityClaimCode.INSUFFICIENT_TREASURY,
                    repository.prepareClaim(unaffordableClaim).toCompletableFuture().join().code());
            assertEquals(50L, repository.find(cityId).toCompletableFuture().join().orElseThrow().treasury());
            assertEquals(2, repository.treasuryHistory(cityId, 10).toCompletableFuture().join().size());

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM city_chunks WHERE city_uuid='" + cityId + "'")) {
                assertTrue(result.next());
                assertEquals(5, result.getInt(1));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
