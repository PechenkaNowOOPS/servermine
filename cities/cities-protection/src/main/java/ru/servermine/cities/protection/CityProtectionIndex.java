package ru.servermine.cities.protection;

import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityView;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread event index; it contains only immutable claim and resident snapshots. */
public final class CityProtectionIndex {
    private final Map<ChunkPosition, Claim> claims = new HashMap<>();

    public synchronized void replaceAll(Collection<CityView> cities) {
        claims.clear();
        for (CityView city : cities) add(city);
    }

    public synchronized void replaceCity(CityView city) {
        claims.entrySet().removeIf(entry -> entry.getValue().cityId.equals(city.id()));
        add(city);
    }

    public synchronized boolean isClaimed(ChunkPosition chunk) {
        return claims.containsKey(chunk);
    }

    public synchronized boolean allowsTransfer(ChunkPosition source, ChunkPosition destination) {
        Claim from = claims.get(source);
        Claim to = claims.get(destination);
        if (from == null || to == null) return from == to;
        return from.cityId.equals(to.cityId);
    }

    public synchronized int claimCount() { return claims.size(); }

    public synchronized boolean canModify(UUID playerId, ChunkPosition chunk) {
        Claim claim = claims.get(chunk);
        return claim == null || claim.residents.contains(playerId);
    }

    private void add(CityView city) {
        Set<UUID> residents = new HashSet<>();
        city.residents().forEach(resident -> residents.add(resident.playerId()));
        Claim claim = new Claim(city.id(), Set.copyOf(residents));
        city.chunks().forEach(chunk -> claims.put(chunk, claim));
    }

    private record Claim(UUID cityId, Set<UUID> residents) { }
}
