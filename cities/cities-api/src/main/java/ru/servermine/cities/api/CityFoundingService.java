package ru.servermine.cities.api;

import java.util.concurrent.CompletionStage;

/** Founding includes four connected starting chunks and has no physical-currency fee. */
public interface CityFoundingService {
    boolean isReady();

    CompletionStage<CityFoundationResult> found(CityFoundationRequest request);
}
