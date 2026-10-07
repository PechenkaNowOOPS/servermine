package ru.servermine.cities.territory;
import org.junit.jupiter.api.Test;
import ru.servermine.cities.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaimRulesTest {
    final UUID world = UUID.randomUUID();
    ChunkPosition chunk(int x, int z) { return new ChunkPosition(world, x, z); }
    CityView city(CityStage stage) {
        return new CityView(UUID.randomUUID(), "Test", stage, Set.of(chunk(0,0), chunk(1,0), chunk(0,1), chunk(1,1)), 0, 1, List.of());
    }
    @Test void settlementCannotBuyFifthChunk() {
        assertEquals(ClaimRules.Result.PROMOTION_REQUIRED, ClaimRules.validate(city(CityStage.SETTLEMENT), 1, chunk(2,0), true, false));
    }
    @Test void disconnectedAndDiagonalClaimsAreRejected() {
        for (var target : List.of(chunk(8,8), chunk(2,2), new ChunkPosition(UUID.randomUUID(), 2, 0)))
            assertEquals(ClaimRules.Result.DISCONNECTED, ClaimRules.validate(city(CityStage.VILLAGE), 1, target, true, false));
    }
    @Test void adjacentVillageClaimIsAllowed() {
        assertEquals(ClaimRules.Result.ALLOWED, ClaimRules.validate(city(CityStage.VILLAGE), 1, chunk(2,0), true, false));
    }
    @Test void unavailableProtectedAndStaleClaimsAreRejected() {
        var city = city(CityStage.VILLAGE);
        assertEquals(ClaimRules.Result.TARGET_UNAVAILABLE, ClaimRules.validate(city, 1, chunk(2,0), false, false));
        assertEquals(ClaimRules.Result.PROTECTED_ZONE, ClaimRules.validate(city, 1, chunk(2,0), true, true));
        assertEquals(ClaimRules.Result.STALE_REVISION, ClaimRules.validate(city, 0, chunk(2,0), true, false));
        assertEquals(ClaimRules.Result.ALREADY_OWNED, ClaimRules.validate(city, 1, chunk(0,0), true, false));
    }
    @Test void capCannotBeExceeded() {
        Set<ChunkPosition> chunks = new HashSet<>();
        for (int x=0; x<12; x++) chunks.add(chunk(x,0));
        var city = new CityView(UUID.randomUUID(), "Full", CityStage.VILLAGE, chunks, 0, 1, List.of());
        assertEquals(ClaimRules.Result.CAP_REACHED, ClaimRules.validate(city, 1, chunk(12,0), true, false));
    }
}
