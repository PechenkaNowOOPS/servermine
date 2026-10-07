package ru.servermine.cities.progression;
import org.junit.jupiter.api.Test;
import ru.servermine.cities.api.CityStage;
import static org.junit.jupiter.api.Assertions.*;
class StageProgressionTest {
    @Test void progressionIsOrderedAndKingdomIsTerminal() {
        var stages = CityStage.values();
        for (int i=0; i<stages.length-1; i++) assertEquals(stages[i+1], StageProgression.next(stages[i]).orElseThrow());
        assertTrue(StageProgression.next(CityStage.KINGDOM).isEmpty());
    }
}
