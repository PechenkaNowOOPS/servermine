package ru.servermine.cities.creation;
import org.junit.jupiter.api.Test;
import ru.servermine.cities.api.ChunkPosition;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class FoundingFootprintTest {
    @Test void cityStartsWithFourConnectedChunks() {
        var footprint = FoundingFootprint.squareAt(new ChunkPosition(UUID.randomUUID(), -2, -3));
        assertEquals(4, footprint.size());
        footprint.forEach(c -> assertEquals(2, footprint.stream().filter(c::adjacentTo).count()));
    }
    @Test void integerBoundaryCannotWrapIntoAnotherRegion() {
        assertThrows(ArithmeticException.class, () -> FoundingFootprint.squareAt(new ChunkPosition(UUID.randomUUID(), Integer.MAX_VALUE, 0)));
    }
}
