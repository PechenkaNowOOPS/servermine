package ru.servermine.gradostroygui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class AuditLog {
    private final Path path;

    public AuditLog(Path path) {
        this.path = path;
    }

    public synchronized void append(String operationId, String action, String details) {
        String row = "%s\t%s\t%s\t%s%n".formatted(Instant.now(), operationId, action, details);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Для GUI-MVP сбой технического аудита не должен падать весь сервер.
        }
    }
}
