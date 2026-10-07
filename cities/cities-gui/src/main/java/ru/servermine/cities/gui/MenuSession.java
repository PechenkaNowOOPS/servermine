package ru.servermine.cities.gui;
import java.time.Instant;
import java.util.UUID;
public record MenuSession(UUID sessionId, UUID playerId, MenuType menuType, long cityRevision,
                          Instant openedAt, boolean preview) {
    public static MenuSession create(UUID playerId, MenuType type, long revision, boolean preview) {
        return new MenuSession(UUID.randomUUID(), playerId, type, revision, Instant.now(), preview);
    }
}
