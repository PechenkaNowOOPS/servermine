package ru.servermine.cities.creation;

import ru.servermine.cities.api.CityFoundationCode;
import ru.servermine.cities.api.CityFoundationDraft;
import ru.servermine.cities.api.CityFoundationRequest;
import ru.servermine.cities.api.CityFoundationResult;
import ru.servermine.cities.api.CityFoundingService;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.core.CityRepository;
import ru.servermine.cities.protection.FoundationDecision;
import ru.servermine.cities.protection.FoundationPolicy;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Idempotent policy + persistence orchestration for free Settlement founding. */
public final class CityFoundingServiceImpl implements CityFoundingService {
    private final CityRepository repository;
    private final FoundationPolicy policy;
    private final Consumer<CityView> cityChanged;
    private volatile boolean ready = true;

    public CityFoundingServiceImpl(CityRepository repository, FoundationPolicy policy) {
        this(repository, policy, ignored -> { });
    }

    public CityFoundingServiceImpl(CityRepository repository, FoundationPolicy policy, Consumer<CityView> cityChanged) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.cityChanged = Objects.requireNonNull(cityChanged, "cityChanged");
    }

    public void deactivate() {
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public CompletionStage<CityFoundationResult> found(CityFoundationRequest request) {
        if (!ready) return completed(request.operationId(), CityFoundationCode.SERVICE_UNAVAILABLE, false);
        if (!validFounderName(request.founderName())) return completed(request.operationId(), CityFoundationCode.INVALID_NAME, false);
        String name = normalizeName(request.cityName());
        if (name == null) return completed(request.operationId(), CityFoundationCode.INVALID_NAME, false);

        final Set<ChunkPosition> footprint;
        try {
            footprint = FoundingFootprint.squareAt(request.anchor());
        } catch (ArithmeticException invalidCoordinate) {
            return completed(request.operationId(), CityFoundationCode.INVALID_NAME, false);
        }

        String nameKey = name.toLowerCase(Locale.ROOT);
        UUID cityId = UUID.nameUUIDFromBytes(("servermine:city:" + request.operationId())
                .getBytes(StandardCharsets.UTF_8));
        CityFoundationDraft draft = new CityFoundationDraft(request.operationId(), cityId,
                request.founderId(), request.founderName().trim(), name, nameKey, footprint);
        return repository.findFoundationReplay(draft).thenCompose(replay -> {
            if (replay.isPresent()) return CompletableFuture.completedFuture(replay.get());
            return policy.check(request.founderId(), footprint).thenCompose(decision -> {
                if (decision == FoundationDecision.WORLD_UNAVAILABLE) {
                    return completed(request.operationId(), CityFoundationCode.WORLD_UNAVAILABLE, false);
                }
                if (decision == FoundationDecision.PROTECTED_ZONE) {
                    return completed(request.operationId(), CityFoundationCode.PROTECTED_ZONE, false);
                }
                return repository.found(draft);
            });
        }).thenApply(result -> {
            if ((result.code() == CityFoundationCode.CREATED || result.code() == CityFoundationCode.REPLAYED)
                    && result.city().isPresent()) cityChanged.accept(result.city().get());
            return result;
        }).exceptionally(error -> new CityFoundationResult(request.operationId(), CityFoundationCode.INTERNAL_ERROR,
                Optional.empty(), false));
    }

    private boolean validFounderName(String name) {
        return !name.isBlank() && name.codePointCount(0, name.length()) <= 64
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    private String normalizeName(String input) {
        String name = Normalizer.normalize(input, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        int characters = name.codePointCount(0, name.length());
        if (characters < 3 || characters > 32) return null;
        if (name.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c) && c != ' ' && c != '_' && c != '-')) return null;
        return name;
    }

    private CompletionStage<CityFoundationResult> completed(UUID id, CityFoundationCode code, boolean replay) {
        return CompletableFuture.completedFuture(new CityFoundationResult(id, code, Optional.empty(), replay));
    }
}
