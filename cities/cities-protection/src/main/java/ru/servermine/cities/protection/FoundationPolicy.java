package ru.servermine.cities.protection;

import ru.servermine.cities.api.ChunkPosition;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Checks if a starter footprint is available under server-owned protection rules. */
public interface FoundationPolicy {
    CompletionStage<FoundationDecision> check(UUID founderId, Set<ChunkPosition> initialChunks);
}
