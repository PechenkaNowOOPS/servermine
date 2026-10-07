package ru.servermine.cities.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Obtain through Bukkit ServicesManager; never access another plugin's storage. */
public interface CitiesService {
    String apiVersion();
    boolean isReady();
    CompletionStage<Optional<CityView>> city(UUID cityId);
    CompletionStage<Optional<CityView>> cityForPlayer(UUID playerId);

    /** A service can be used to query cities before optional mutation services become available. */
    default Optional<CityFoundingService> foundingService() {
        return Optional.empty();
    }

    /** Optional stage promotion workflow, backed by Cities and Economy. */
    default Optional<CityProgressionService> progressionService() {
        return Optional.empty();
    }

    /** Optional paid city-territory claim workflow. */
    default Optional<CityTerritoryService> territoryService() {
        return Optional.empty();
    }

    /** Optional membership lifecycle, including a player's own departure from a city. */
    default Optional<CityMembershipService> membershipService() {
        return Optional.empty();
    }

    default Optional<CityTreasuryService> treasuryService() {
        return Optional.empty();
    }
}
