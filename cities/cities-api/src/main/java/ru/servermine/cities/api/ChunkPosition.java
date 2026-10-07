package ru.servermine.cities.api;

import java.util.Objects;
import java.util.UUID;

public record ChunkPosition(UUID worldId, int x, int z) {
    public ChunkPosition { Objects.requireNonNull(worldId, "worldId"); }
    public boolean adjacentTo(ChunkPosition other) {
        return worldId.equals(other.worldId) && Math.abs((long) x - other.x) + Math.abs((long) z - other.z) == 1;
    }
}
