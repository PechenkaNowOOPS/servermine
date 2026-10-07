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
}
