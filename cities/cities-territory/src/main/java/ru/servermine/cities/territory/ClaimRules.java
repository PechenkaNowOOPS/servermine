package ru.servermine.cities.territory;

import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;

/** Pure validation. The eventual transaction must also recheck actor permissions and price. */
public final class ClaimRules {
    public enum Result { ALLOWED, STALE_REVISION, PROMOTION_REQUIRED, CAP_REACHED, ALREADY_OWNED,
        TARGET_UNAVAILABLE, PROTECTED_ZONE, DISCONNECTED }
    private ClaimRules() {}
    public static Result validate(CityView city, long expectedRevision, ChunkPosition target,
                                  boolean targetAvailable, boolean systemProtected) {
        if (city.revision() != expectedRevision) return Result.STALE_REVISION;
        if (city.chunks().contains(target)) return Result.ALREADY_OWNED;
        if (city.stage() == CityStage.SETTLEMENT) return Result.PROMOTION_REQUIRED;
        if (city.chunks().size() >= city.stage().chunkLimit()) return Result.CAP_REACHED;
        if (systemProtected) return Result.PROTECTED_ZONE;
        if (!targetAvailable) return Result.TARGET_UNAVAILABLE;
        if (city.chunks().stream().noneMatch(target::adjacentTo)) return Result.DISCONNECTED;
        return Result.ALLOWED;
    }
}
