package ru.servermine.cities.api;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class CityStageTest {
    @Test void stageCapsMatchProductRules() {
        assertArrayEquals(new int[]{4, 12, 24, 40, 64}, Arrays.stream(CityStage.values()).mapToInt(CityStage::chunkLimit).toArray());
    }
}
