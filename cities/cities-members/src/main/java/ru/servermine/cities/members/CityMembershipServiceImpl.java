package ru.servermine.cities.members;

import ru.servermine.cities.api.CityLeaveCode;
import ru.servermine.cities.api.CityLeaveResult;
import ru.servermine.cities.api.CityMembershipService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Transactional self-departure workflow; a departing ruler hands leadership to the oldest resident. */
public final class CityMembershipServiceImpl implements CityMembershipService {
    private final CityRepository repository;
    private final Consumer<CityView> cityChanged;
    private volatile boolean ready = true;

    public CityMembershipServiceImpl(CityRepository repository, Consumer<CityView> cityChanged) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cityChanged = Objects.requireNonNull(cityChanged, "cityChanged");
    }

    @Override public boolean isReady() { return ready; }

    public void deactivate() { ready = false; }

    @Override
    public CompletionStage<CityLeaveResult> leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!ready) return completed(CityLeaveCode.SERVICE_UNAVAILABLE);
        return repository.leaveCity(playerId).thenApply(result -> {
            if (result.code() == CityLeaveCode.LEFT) result.city().ifPresent(cityChanged);
            return result;
        }).exceptionally(error -> completed(CityLeaveCode.INTERNAL_ERROR).toCompletableFuture().join());
    }

    private CompletionStage<CityLeaveResult> completed(CityLeaveCode code) {
        return CompletableFuture.completedFuture(new CityLeaveResult(code, Optional.empty(), Optional.empty()));
    }
}
