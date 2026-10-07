package ru.servermine.cities.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** City-owned territorial claims; payments use the actor's physical Economy balance. */
public interface CityTerritoryService {
    boolean isReady();
    long chunkPrice();
    CompletionStage<List<ChunkPosition>> availableTargets(UUID cityId);
    CompletionStage<CityClaimResult> purchase(CityClaimRequest request);
}
