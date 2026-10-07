package ru.servermine.cities.creation;

import ru.servermine.cities.api.ChunkPosition;
import java.util.Set;

/** Proposed 2x2 footprint. Availability and protected zones must be checked atomically before founding. */
public final class FoundingFootprint {
    private FoundingFootprint() {}
    public static Set<ChunkPosition> squareAt(ChunkPosition anchor) {
        int east = Math.addExact(anchor.x(), 1);
        int south = Math.addExact(anchor.z(), 1);
        return Set.of(anchor, new ChunkPosition(anchor.worldId(), east, anchor.z()),
                new ChunkPosition(anchor.worldId(), anchor.x(), south), new ChunkPosition(anchor.worldId(), east, south));
    }
}
