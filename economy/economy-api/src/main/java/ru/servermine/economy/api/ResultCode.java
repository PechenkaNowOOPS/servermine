package ru.servermine.economy.api;

/** Машиночитаемый результат вызова Economy API. */
public enum ResultCode {
    OK,
    INVALID_AMOUNT,
    PLAYER_OFFLINE,
    INSUFFICIENT_FUNDS,
    INVENTORY_FULL,
    OPERATION_CONFLICT,
    OPERATION_NOT_FOUND,
    INVALID_OPERATION_STATE,
    SERVICE_UNAVAILABLE,
    UNKNOWN_OUTCOME,
    INTERNAL_ERROR
}
