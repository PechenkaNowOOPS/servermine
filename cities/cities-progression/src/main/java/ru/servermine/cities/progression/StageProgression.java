package ru.servermine.cities.progression;

import java.util.Optional;
import ru.servermine.cities.api.CityStage;

/** Stage order only. Prices and requirements belong to future configuration-backed policy. */
public final class StageProgression {
    private StageProgression() {}
    public static Optional<CityStage> next(CityStage stage) {
        return stage == CityStage.KINGDOM ? Optional.empty() : Optional.of(CityStage.values()[stage.ordinal() + 1]);
    }
}
