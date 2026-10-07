package ru.servermine.economy.api;

/** Возможности, которые внешние плагины могут проверять до вызова операций. */
public enum EconomyCapability {
    PHYSICAL_CURRENCY,
    BALANCE_QUERY,
    CHARGE,
    PAYOUT,
    RESERVATIONS,
    OPERATION_LOOKUP
}
