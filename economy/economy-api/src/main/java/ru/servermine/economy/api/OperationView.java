package ru.servermine.economy.api;

import java.time.Instant;
import java.util.UUID;

/** Неизменяемое представление записанной операции. */
public record OperationView(
        UUID operationId,
        String type,
        UUID playerId,
        long amount,
        String sourcePlugin,
        String purpose,
        OperationState state,
        ResultCode resultCode,
        Instant createdAt,
        Instant updatedAt
) {}
