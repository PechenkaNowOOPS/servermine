package ru.servermine.economy.api;

import java.util.UUID;

/** Результат чтения физического баланса инвентаря. */
public record BalanceResult(
        UUID playerId,
        ResultCode code,
        long balance,
        String message
) {
    public boolean successful() {
        return code == ResultCode.OK;
    }
}
