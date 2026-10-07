package ru.servermine.gradostroygui;

import java.time.Instant;
import java.util.UUID;

public record MenuSession(
        UUID sessionId,
        UUID playerId,
        MenuType menuType,
        long cityRevision,
        Instant openedAt
) {
    public static MenuSession create(UUID playerId, MenuType menuType, long cityRevision) {
        return new MenuSession(UUID.randomUUID(), playerId, menuType, cityRevision, Instant.now());
    }
}
