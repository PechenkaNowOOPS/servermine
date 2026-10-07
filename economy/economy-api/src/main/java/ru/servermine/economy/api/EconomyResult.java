package ru.servermine.economy.api;

import java.util.UUID;

/** Результат мутации денег. */
public record EconomyResult(
        UUID operationId,
        ResultCode code,
        OperationState state,
        long amount,
        long balanceBefore,
        long balanceAfter,
        boolean idempotentReplay,
        String message
) {
    public boolean successful() {
        return code == ResultCode.OK &&
                (state == OperationState.RESERVED || state == OperationState.COMMITTED || state == OperationState.RELEASED);
    }
}
