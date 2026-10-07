package ru.servermine.economy.internal;

import ru.servermine.economy.api.OperationState;
import ru.servermine.economy.api.ResultCode;

import java.time.Instant;
import java.util.UUID;

record OperationRecord(
        UUID operationId,
        String type,
        UUID playerId,
        long amount,
        String sourcePlugin,
        String purpose,
        OperationState state,
        ResultCode resultCode,
        long balanceBefore,
        long balanceAfter,
        Instant createdAt,
        Instant updatedAt
) {}
