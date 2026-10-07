package ru.servermine.economy.internal;

import ru.servermine.economy.api.MoneyRequest;
import ru.servermine.economy.api.OperationState;
import ru.servermine.economy.api.ResultCode;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

final class OperationRepository implements AutoCloseable {
    enum PrepareStatus { CREATED, EXISTING_SAME, CONFLICT }
    record PrepareResult(PrepareStatus status, OperationRecord record) {}

    private final String jdbcUrl;
    private final int busyTimeoutMs;
    private final ExecutorService executor;

    OperationRepository(Path dbFile, int busyTimeoutMs, ExecutorService executor) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        this.busyTimeoutMs = busyTimeoutMs;
        this.executor = executor;
    }

    void initialize(boolean markPreparedUnknown) throws SQLException {
        try (Connection connection = connection(); Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS economy_operations (
                      operation_id TEXT PRIMARY KEY,
                      type TEXT NOT NULL,
                      player_uuid TEXT NOT NULL,
                      amount INTEGER NOT NULL,
                      source_plugin TEXT NOT NULL,
                      purpose TEXT NOT NULL,
                      state TEXT NOT NULL,
                      result_code TEXT NOT NULL,
                      balance_before INTEGER NOT NULL DEFAULT -1,
                      balance_after INTEGER NOT NULL DEFAULT -1,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_economy_operations_player ON economy_operations(player_uuid, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_economy_operations_state ON economy_operations(state)");
            if (markPreparedUnknown) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE economy_operations
                           SET state = ?, result_code = ?, updated_at = ?
                         WHERE state IN (?, ?)
                        """)) {
                    ps.setString(1, OperationState.UNKNOWN.name());
                    ps.setString(2, ResultCode.UNKNOWN_OUTCOME.name());
                    ps.setString(3, Instant.now().toString());
                    ps.setString(4, OperationState.PREPARED.name());
                    ps.setString(5, OperationState.RELEASING.name());
                    ps.executeUpdate();
                }
            }
        }
    }

    CompletableFuture<PrepareResult> prepare(String type, MoneyRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    Optional<OperationRecord> existing = find(connection, request.operationId());
                    if (existing.isPresent()) {
                        connection.commit();
                        OperationRecord row = existing.get();
                        return new PrepareResult(sameRequest(row, type, request) ? PrepareStatus.EXISTING_SAME : PrepareStatus.CONFLICT, row);
                    }
                    Instant now = Instant.now();
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO economy_operations
                            (operation_id, type, player_uuid, amount, source_plugin, purpose, state, result_code,
                             balance_before, balance_after, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, -1, -1, ?, ?)
                            """)) {
                        ps.setString(1, request.operationId().toString());
                        ps.setString(2, type);
                        ps.setString(3, request.playerId().toString());
                        ps.setLong(4, request.amount());
                        ps.setString(5, request.sourcePlugin());
                        ps.setString(6, request.purpose());
                        ps.setString(7, OperationState.PREPARED.name());
                        ps.setString(8, ResultCode.OK.name());
                        ps.setString(9, now.toString());
                        ps.setString(10, now.toString());
                        ps.executeUpdate();
                    }
                    OperationRecord created = find(connection, request.operationId()).orElseThrow();
                    connection.commit();
                    return new PrepareResult(PrepareStatus.CREATED, created);
                } catch (Throwable t) {
                    connection.rollback();
                    throw t;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    CompletableFuture<Optional<OperationRecord>> find(UUID operationId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connection()) {
                return find(connection, operationId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    CompletableFuture<OperationRecord> transition(
            UUID operationId,
            OperationState expected,
            OperationState next,
            ResultCode code,
            long balanceBefore,
            long balanceAfter
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connection()) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE economy_operations
                           SET state = ?, result_code = ?, balance_before = ?, balance_after = ?, updated_at = ?
                         WHERE operation_id = ? AND state = ?
                        """)) {
                    ps.setString(1, next.name());
                    ps.setString(2, code.name());
                    ps.setLong(3, balanceBefore);
                    ps.setLong(4, balanceAfter);
                    ps.setString(5, Instant.now().toString());
                    ps.setString(6, operationId.toString());
                    ps.setString(7, expected.name());
                    int changed = ps.executeUpdate();
                    if (changed != 1) {
                        return find(connection, operationId).orElseThrow(() -> new IllegalStateException("Operation disappeared: " + operationId));
                    }
                }
                return find(connection, operationId).orElseThrow();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    CompletableFuture<OperationRecord> markUnknown(UUID operationId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connection(); PreparedStatement ps = connection.prepareStatement("""
                    UPDATE economy_operations
                       SET state = ?, result_code = ?, updated_at = ?
                     WHERE operation_id = ? AND state = ?
                    """)) {
                ps.setString(1, OperationState.UNKNOWN.name());
                ps.setString(2, ResultCode.UNKNOWN_OUTCOME.name());
                ps.setString(3, Instant.now().toString());
                ps.setString(4, operationId.toString());
                ps.setString(5, OperationState.PREPARED.name());
                ps.executeUpdate();
                return find(connection, operationId).orElseThrow();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            st.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private Optional<OperationRecord> find(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM economy_operations WHERE operation_id = ?")) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        }
    }

    private OperationRecord read(ResultSet rs) throws SQLException {
        return new OperationRecord(
                UUID.fromString(rs.getString("operation_id")),
                rs.getString("type"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("amount"),
                rs.getString("source_plugin"),
                rs.getString("purpose"),
                OperationState.valueOf(rs.getString("state")),
                ResultCode.valueOf(rs.getString("result_code")),
                rs.getLong("balance_before"),
                rs.getLong("balance_after"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private boolean sameRequest(OperationRecord row, String type, MoneyRequest request) {
        return row.type().equals(type)
                && row.playerId().equals(request.playerId())
                && row.amount() == request.amount()
                && row.sourcePlugin().equals(request.sourcePlugin())
                && row.purpose().equals(request.purpose());
    }

    @Override
    public void close() {
        // Connections are short-lived. Executor lifecycle is owned by the plugin.
    }
}
