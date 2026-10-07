package ru.servermine.cities.members;

import ru.servermine.cities.api.CityLeaveCode;
import ru.servermine.cities.api.CityLeaveResult;
import ru.servermine.cities.api.CityMembershipCode;
import ru.servermine.cities.api.CityMembershipResult;
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

    @Override
    public CompletionStage<CityMembershipResult> invite(UUID inviterId, UUID inviteeId, String inviteeName) {
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(inviteeName, "inviteeName");
        if (!ready) return membershipResult(CityMembershipCode.SERVICE_UNAVAILABLE);
        if (inviteeName.isBlank() || inviteeName.length() > 64) return membershipResult(CityMembershipCode.INTERNAL_ERROR);
        return repository.inviteToCity(inviterId, inviteeId, inviteeName).exceptionally(
                error -> new CityMembershipResult(CityMembershipCode.INTERNAL_ERROR, Optional.empty()));
    }

    @Override
    public CompletionStage<CityMembershipResult> accept(UUID playerId, String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        if (!ready) return membershipResult(CityMembershipCode.SERVICE_UNAVAILABLE);
        if (playerName.isBlank() || playerName.length() > 64) return membershipResult(CityMembershipCode.INTERNAL_ERROR);
        return repository.acceptCityInvite(playerId, playerName).thenApply(result -> {
            if (result.code() == CityMembershipCode.ACCEPTED) result.city().ifPresent(cityChanged);
            return result;
        }).exceptionally(error -> new CityMembershipResult(CityMembershipCode.INTERNAL_ERROR, Optional.empty()));
    }

    @Override
    public CompletionStage<CityMembershipResult> kick(UUID actorId, UUID targetId, long expectedRevision) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        if (!ready) return membershipResult(CityMembershipCode.SERVICE_UNAVAILABLE);
        return repository.kickCityMember(actorId, targetId, expectedRevision).thenApply(result -> {
            if (result.code() == CityMembershipCode.KICKED) result.city().ifPresent(cityChanged);
            return result;
        }).exceptionally(error -> new CityMembershipResult(CityMembershipCode.INTERNAL_ERROR, Optional.empty()));
    }

    private CompletionStage<CityLeaveResult> completed(CityLeaveCode code) {
        return CompletableFuture.completedFuture(new CityLeaveResult(code, Optional.empty(), Optional.empty()));
    }

    private CompletionStage<CityMembershipResult> membershipResult(CityMembershipCode code) {
        return CompletableFuture.completedFuture(new CityMembershipResult(code, Optional.empty()));
    }
}
