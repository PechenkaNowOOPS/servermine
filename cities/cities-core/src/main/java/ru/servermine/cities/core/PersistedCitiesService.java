package ru.servermine.cities.core;

import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityFoundingService;
import ru.servermine.cities.api.CityProgressionService;
import ru.servermine.cities.api.CityTerritoryService;
import ru.servermine.cities.api.CityMembershipService;
import ru.servermine.cities.api.CityTreasuryService;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/** Read-only service adapter. Readiness is enabled only after the repository finishes startup recovery. */
public final class PersistedCitiesService implements CitiesService {
    private final CityRepository repository;
    private final CityFoundingService foundingService;
    private final CityProgressionService progressionService;
    private final CityTerritoryService territoryService;
    private final CityMembershipService membershipService;
    private final CityTreasuryService treasuryService;
    private volatile boolean ready = true;

    public PersistedCitiesService(CityRepository repository) {
        this(repository, null, null, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService) {
        this(repository, foundingService, null, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService,
                                  CityProgressionService progressionService) {
        this(repository, foundingService, progressionService, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService,
                                  CityProgressionService progressionService, CityTerritoryService territoryService) {
        this(repository, foundingService, progressionService, territoryService, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService,
                                  CityProgressionService progressionService, CityTerritoryService territoryService,
                                  CityMembershipService membershipService) {
        this(repository, foundingService, progressionService, territoryService, membershipService, null);
    }

    public PersistedCitiesService(CityRepository repository, CityFoundingService foundingService,
                                  CityProgressionService progressionService, CityTerritoryService territoryService,
                                  CityMembershipService membershipService, CityTreasuryService treasuryService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.foundingService = foundingService;
        this.progressionService = progressionService;
        this.territoryService = territoryService;
        this.membershipService = membershipService;
        this.treasuryService = treasuryService;
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

    @Override
    public Optional<CityProgressionService> progressionService() {
        return ready ? Optional.ofNullable(progressionService) : Optional.empty();
    }

    @Override
    public Optional<CityTerritoryService> territoryService() {
        return ready ? Optional.ofNullable(territoryService) : Optional.empty();
    }

    @Override
    public Optional<CityMembershipService> membershipService() {
        return ready ? Optional.ofNullable(membershipService) : Optional.empty();
    }

    @Override
    public Optional<CityTreasuryService> treasuryService() {
        return ready ? Optional.ofNullable(treasuryService) : Optional.empty();
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
