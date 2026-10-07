package ru.servermine.cities.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Player-facing city membership operations. */
public interface CityMembershipService {
    boolean isReady();

    CompletionStage<CityLeaveResult> leave(UUID playerId);
}
