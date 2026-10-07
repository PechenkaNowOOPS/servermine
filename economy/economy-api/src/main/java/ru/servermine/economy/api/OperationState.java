package ru.servermine.economy.api;

/** Устойчивое состояние денежной операции. */
public enum OperationState {
    PREPARED,
    RESERVED,
    RELEASING,
    COMMITTED,
    RELEASED,
    FAILED,
    UNKNOWN
}
