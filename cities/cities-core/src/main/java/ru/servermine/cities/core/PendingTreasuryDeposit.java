package ru.servermine.cities.core;

import java.util.UUID;

public record PendingTreasuryDeposit(UUID operationId, UUID cityId, UUID actorId, long amount, String state) { }
