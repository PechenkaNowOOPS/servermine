package ru.servermine.cities.core;

import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityFoundingService;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/** Read-only service adapter. Readiness is enabled only after the repository finishes startup recovery. */
public final class PersistedCitiesService implements CitiesService {
    private final CityRepository repository;
    private final CityFoundingService foundingService;
    private volatile boolean ready = true;

    public PersistedCitiesService(CityRepository repository) {
        this(repository, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.foundingService = foundingService;
    }

    public String apiVersion() {
        return "1";
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public Optional<CityFoundingService> foundingService() {
        return ready ? Optional.ofNullable(foundingService) : Optional.empty();
    }

    public void deactivate() {
        ready = false;
    }

    public CompletionStage<Optional<CityView>> city(UUID cityId) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Cities service is unavailable"));
        return repository.find(Objects.requireNonNull(cityId, "cityId"));
    }

    public CompletionStage<Optional<CityView>> cityForPlayer(UUID playerId) {
        if (!ready) return CompletableFuture.failedFuture(new IllegalStateException("Cities service is unavailable"));
        return repository.findForPlayer(Objects.requireNonNull(playerId, "playerId"));
    }
}
