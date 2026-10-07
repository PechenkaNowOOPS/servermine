package ru.servermine.economy.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Запрос на денежную операцию.
 * operationId обязан быть устойчивым: повтор того же бизнес-действия использует тот же UUID.
 */
public record MoneyRequest(
        UUID operationId,
        UUID playerId,
        long amount,
        String sourcePlugin,
        String purpose
) {
    public MoneyRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        sourcePlugin = normalize(sourcePlugin, "unknown");
        purpose = normalize(purpose, "unspecified");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        value = value.trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
