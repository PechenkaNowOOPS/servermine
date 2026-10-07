package ru.servermine.cities.storage;

import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityFoundationCode;
import ru.servermine.cities.api.CityFoundationDraft;
import ru.servermine.cities.api.CityFoundationResult;
import ru.servermine.cities.api.CityPromotionCode;
import ru.servermine.cities.api.CityPromotionResult;
import ru.servermine.cities.api.CityClaimCode;
import ru.servermine.cities.api.CityClaimResult;
import ru.servermine.cities.core.CityRepository;
import ru.servermine.cities.core.CityPromotionDraft;
import ru.servermine.cities.core.CityClaimDraft;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.HashSet;

/**
 * SQLite adapter owned by Cities. Database calls run on the injected I/O executor;
 * world and inventory access are intentionally absent from this module.
 */
public final class SqliteCityRepository implements CityRepository {
    private static final int SCHEMA_VERSION = 1;

    private final String jdbcUrl;
    private final int busyTimeoutMillis;
    private final Executor ioExecutor;

    public SqliteCityRepository(Path databaseFile, int busyTimeoutMillis, Executor ioExecutor) {
        if (busyTimeoutMillis < 1 || busyTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("busyTimeoutMillis must be between 1 and 60000");
        }
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.ioExecutor = ioExecutor;
    }

    /** Called once during plugin startup, before publishing the public service. */
    public void initialize() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                if (!result.next()) throw new SQLException("SQLite did not return a schema version");
                version = result.getInt(1);
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("Cities database schema " + version + " is newer than supported " + SCHEMA_VERSION);
            }
            if (version == 0) createVersionOne(connection);
        }
    }

    private void createVersionOne(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE cities (
                        city_uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        name_key TEXT NOT NULL UNIQUE,
                        stage TEXT NOT NULL CHECK (stage IN ('SETTLEMENT','VILLAGE','CITY','CAPITAL','KINGDOM')),
                        treasury INTEGER NOT NULL DEFAULT 0 CHECK (treasury >= 0),
                        revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
                        founder_uuid TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE city_members (
                        city_uuid TEXT NOT NULL REFERENCES cities(city_uuid) ON DELETE CASCADE,
                        player_uuid TEXT NOT NULL UNIQUE,
                        last_known_name TEXT NOT NULL,
                        role_id TEXT NOT NULL,
                        joined_at TEXT NOT NULL,
                        PRIMARY KEY (city_uuid, player_uuid)
                    )
                    """);
            statement.execute("CREATE INDEX idx_city_members_player ON city_members(player_uuid)");
            statement.execute("""
                    CREATE TABLE city_chunks (
                        world_uuid TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        city_uuid TEXT NOT NULL REFERENCES cities(city_uuid) ON DELETE CASCADE,
                        claimed_at TEXT NOT NULL,
                        PRIMARY KEY (world_uuid, chunk_x, chunk_z)
                    )
                    """);
            statement.execute("CREATE INDEX idx_city_chunks_owner ON city_chunks(city_uuid)");
            statement.execute("""
                    CREATE TABLE city_operations (
                        operation_uuid TEXT PRIMARY KEY,
                        operation_type TEXT NOT NULL,
                        request_hash TEXT NOT NULL,
                        request_payload TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN ('PREPARED','RESERVED','DOMAIN_COMMITTED',
                            'COMMIT_PENDING','RELEASE_PENDING','COMPLETED','RELEASED','FAILED','UNKNOWN')),
                        result_code TEXT NOT NULL,
                        city_uuid TEXT,
                        actor_uuid TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_city_operations_state ON city_operations(state, updated_at)");
            statement.execute("""
                    CREATE TABLE city_treasury_entries (
                        operation_uuid TEXT PRIMARY KEY,
                        city_uuid TEXT NOT NULL REFERENCES cities(city_uuid),
                        amount INTEGER NOT NULL CHECK (amount <> 0),
                        balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_city_treasury_history ON city_treasury_entries(city_uuid, created_at)");
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public CompletionStage<Optional<CityView>> find(UUID cityId) {
        return async(() -> readCity(cityId));
    }

    @Override
    public CompletionStage<Optional<CityView>> findForPlayer(UUID playerId) {
        return async(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT city_uuid FROM city_members WHERE player_uuid = ?")) {
                    query.setString(1, playerId.toString());
                    UUID cityId;
                    try (ResultSet result = query.executeQuery()) {
                        cityId = result.next() ? UUID.fromString(result.getString("city_uuid")) : null;
                    }
                    Optional<CityView> city = cityId == null ? Optional.empty() : readCity(connection, cityId);
                    connection.commit();
                    return city;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to load a player's city", e);
            }
        });
    }

    @Override
    public CompletionStage<Optional<CityFoundationResult>> findFoundationReplay(CityFoundationDraft draft) {
        return async(() -> {
            String payload = foundationPayload(draft);
            String hash = sha256(payload);
            try (Connection connection = connection()) {
                ExistingOperation existing = findOperation(connection, draft.operationId());
                if (existing == null) return Optional.empty();
                if (!existing.type.equals("FOUND_CITY") || !existing.requestHash.equals(hash)) {
                    return Optional.of(foundationResult(draft.operationId(), CityFoundationCode.OPERATION_CONFLICT, null, true));
                }
                if (existing.state.equals("COMPLETED")) {
                    CityView city = readCity(connection, UUID.fromString(existing.cityId)).orElse(null);
                    return Optional.of(foundationResult(draft.operationId(), city == null
                            ? CityFoundationCode.INTERNAL_ERROR : CityFoundationCode.REPLAYED, city, true));
                }
                return Optional.of(foundationResult(draft.operationId(), parseFoundationCode(existing.resultCode), null, true));
            }
        });
    }

    @Override
    public CompletionStage<CityFoundationResult> found(CityFoundationDraft draft) {
        return async(() -> foundTransaction(draft));
    }

    private CityFoundationResult foundTransaction(CityFoundationDraft draft) throws SQLException {
        validateFootprint(draft);
        String payload = foundationPayload(draft);
        String hash = sha256(payload);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                ExistingOperation existing = findOperation(connection, draft.operationId());
                if (existing != null) {
                    connection.commit();
                    if (!existing.type.equals("FOUND_CITY") || !existing.requestHash.equals(hash)) {
                        return foundationResult(draft.operationId(), CityFoundationCode.OPERATION_CONFLICT, null, true);
                    }
                    if (existing.state.equals("COMPLETED")) {
                        CityView city = readCity(connection, UUID.fromString(existing.cityId)).orElse(null);
                        return foundationResult(draft.operationId(), city == null
                                ? CityFoundationCode.INTERNAL_ERROR : CityFoundationCode.REPLAYED, city, true);
                    }
                    return foundationResult(draft.operationId(), parseFoundationCode(existing.resultCode), null, true);
                }

                CityFoundationCode failure = foundingConflict(connection, draft);
                if (failure != null) {
                    recordFoundationFailure(connection, draft, hash, payload, failure);
                    connection.commit();
                    return foundationResult(draft.operationId(), failure, null, false);
                }

                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO cities(city_uuid, name, name_key, stage, treasury, revision, founder_uuid, created_at)
                        VALUES (?, ?, ?, 'SETTLEMENT', 0, 1, ?, ?)
                        """)) {
                    insert.setString(1, draft.cityId().toString());
                    insert.setString(2, draft.name());
                    insert.setString(3, draft.nameKey());
                    insert.setString(4, draft.founderId().toString());
                    insert.setString(5, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO city_members(city_uuid, player_uuid, last_known_name, role_id, joined_at)
                        VALUES (?, ?, ?, 'RULER', ?)
                        """)) {
                    insert.setString(1, draft.cityId().toString());
                    insert.setString(2, draft.founderId().toString());
                    insert.setString(3, draft.founderName());
                    insert.setString(4, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO city_chunks(world_uuid, chunk_x, chunk_z, city_uuid, claimed_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (ChunkPosition chunk : sortedChunks(draft)) {
                        insert.setString(1, chunk.worldId().toString());
                        insert.setInt(2, chunk.x());
                        insert.setInt(3, chunk.z());
                        insert.setString(4, draft.cityId().toString());
                        insert.setString(5, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                recordFoundationOperation(connection, draft, hash, payload, "COMPLETED", CityFoundationCode.CREATED, now);
                CityView city = readCity(connection, draft.cityId()).orElseThrow(
                        () -> new SQLException("New city could not be read inside its transaction"));
                connection.commit();
                return foundationResult(draft.operationId(), CityFoundationCode.CREATED, city, false);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private CityFoundationCode foundingConflict(Connection connection, CityFoundationDraft draft) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM city_members WHERE player_uuid = ?")) {
            query.setString(1, draft.founderId().toString());
            try (ResultSet result = query.executeQuery()) { if (result.next()) return CityFoundationCode.ALREADY_IN_CITY; }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM cities WHERE name_key = ?")) {
            query.setString(1, draft.nameKey());
            try (ResultSet result = query.executeQuery()) { if (result.next()) return CityFoundationCode.NAME_ALREADY_USED; }
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM city_chunks WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?
                """)) {
            for (ChunkPosition chunk : sortedChunks(draft)) {
                query.setString(1, chunk.worldId().toString());
                query.setInt(2, chunk.x());
                query.setInt(3, chunk.z());
                try (ResultSet result = query.executeQuery()) { if (result.next()) return CityFoundationCode.CHUNK_ALREADY_CLAIMED; }
            }
        }
        return null;
    }

    private void validateFootprint(CityFoundationDraft draft) throws SQLException {
        if (draft.initialChunks().size() != 4) throw new SQLException("A city must start with four chunks");
        UUID world = draft.initialChunks().iterator().next().worldId();
        int minX = draft.initialChunks().stream().mapToInt(ChunkPosition::x).min().orElseThrow();
        int maxX = draft.initialChunks().stream().mapToInt(ChunkPosition::x).max().orElseThrow();
        int minZ = draft.initialChunks().stream().mapToInt(ChunkPosition::z).min().orElseThrow();
        int maxZ = draft.initialChunks().stream().mapToInt(ChunkPosition::z).max().orElseThrow();
        if (draft.initialChunks().stream().anyMatch(chunk -> !chunk.worldId().equals(world))
                || (long) maxX - minX != 1 || (long) maxZ - minZ != 1) {
            throw new SQLException("Initial city chunks must form a 2x2 footprint in one world");
        }
    }

    private ExistingOperation findOperation(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT operation_type, request_hash, state, result_code, city_uuid
                FROM city_operations WHERE operation_uuid = ?
                """)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ExistingOperation(result.getString("operation_type"),
                        result.getString("request_hash"), result.getString("state"),
                        result.getString("result_code"), result.getString("city_uuid")) : null;
            }
        }
    }

    private void recordFoundationFailure(Connection connection, CityFoundationDraft draft, String hash,
                                         String payload, CityFoundationCode code) throws SQLException {
        recordFoundationOperation(connection, draft, hash, payload, "FAILED", code, Instant.now().toString());
    }

    private void recordFoundationOperation(Connection connection, CityFoundationDraft draft, String hash,
                                           String payload, String state, CityFoundationCode code, String now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO city_operations(operation_uuid, operation_type, request_hash, request_payload, state,
                    result_code, city_uuid, actor_uuid, created_at, updated_at)
                VALUES (?, 'FOUND_CITY', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, draft.operationId().toString());
            insert.setString(2, hash);
            insert.setString(3, payload);
            insert.setString(4, state);
            insert.setString(5, code.name());
            insert.setString(6, state.equals("COMPLETED") ? draft.cityId().toString() : null);
            insert.setString(7, draft.founderId().toString());
            insert.setString(8, now);
            insert.setString(9, now);
            insert.executeUpdate();
        }
    }

    private List<ChunkPosition> sortedChunks(CityFoundationDraft draft) {
        return draft.initialChunks().stream().sorted(Comparator.comparing((ChunkPosition c) -> c.worldId().toString())
                .thenComparingInt(ChunkPosition::x).thenComparingInt(ChunkPosition::z)).toList();
    }

    private String foundationPayload(CityFoundationDraft draft) {
        StringBuilder value = new StringBuilder().append(draft.founderId()).append('\n').append(draft.founderName())
                .append('\n').append(draft.cityId()).append('\n').append(draft.name()).append('\n').append(draft.nameKey());
        for (ChunkPosition chunk : sortedChunks(draft)) value.append('\n').append(chunk.worldId()).append(':').append(chunk.x()).append(':').append(chunk.z());
        return value.toString();
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private CityFoundationCode parseFoundationCode(String value) {
        try { return CityFoundationCode.valueOf(value); }
        catch (IllegalArgumentException invalid) { return CityFoundationCode.INTERNAL_ERROR; }
    }

    private CityFoundationResult foundationResult(UUID operationId, CityFoundationCode code, CityView city, boolean replay) {
        Optional<CityView> view = (code == CityFoundationCode.CREATED || code == CityFoundationCode.REPLAYED)
                ? Optional.ofNullable(city) : Optional.empty();
        if (view.isEmpty() && (code == CityFoundationCode.CREATED || code == CityFoundationCode.REPLAYED)) {
            code = CityFoundationCode.INTERNAL_ERROR;
        }
        return new CityFoundationResult(operationId, code, view, replay);
    }

    private record ExistingOperation(String type, String requestHash, String state, String resultCode, String cityId) { }

    @Override
    public CompletionStage<CityPromotionResult> preparePromotion(CityPromotionDraft draft) {
        return async(() -> promotionTransaction(connection -> {
            String payload = promotionPayload(draft);
            String hash = sha256(payload);
            ExistingOperation operation = findOperation(connection, draft.operationId());
            if (operation != null) return promotionReplay(connection, draft, operation, hash);

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT operation_uuid FROM city_operations WHERE city_uuid=? AND operation_type='PROMOTE_CITY'
                      AND state IN ('PREPARED','RESERVED','DOMAIN_COMMITTED','COMMIT_PENDING','RELEASE_PENDING')
                    LIMIT 1
                    """)) {
                query.setString(1, draft.cityId().toString());
                try (ResultSet pending = query.executeQuery()) {
                    if (pending.next()) {
                        connection.commit();
                        return promotionResult(draft.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS, null, true);
                    }
                }
            }

            CityPromotionCode failure = promotionFailure(connection, draft);
            String now = Instant.now().toString();
            if (failure != null) {
                writePromotionOperation(connection, draft, hash, payload, "FAILED", failure, now);
                connection.commit();
                return promotionResult(draft.operationId(), failure, null, false);
            }
            writePromotionOperation(connection, draft, hash, payload, "PREPARED", CityPromotionCode.OPERATION_IN_PROGRESS, now);
            connection.commit();
            return promotionResult(draft.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS, null, false);
        }));
    }

    @Override
    public CompletionStage<Boolean> reservePromotion(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                    UPDATE city_operations SET state='RESERVED', updated_at=?
                    WHERE operation_uuid=? AND operation_type='PROMOTE_CITY' AND state='PREPARED'
                    """)) {
                update.setString(1, Instant.now().toString());
                update.setString(2, operationId.toString());
                int changed = update.executeUpdate();
                if (changed == 1) return true;
                ExistingOperation operation = findOperation(connection, operationId);
                return operation != null && operation.type.equals("PROMOTE_CITY") && operation.state.equals("RESERVED");
            }
        });
    }

    @Override
    public CompletionStage<CityPromotionResult> applyPromotion(CityPromotionDraft draft) {
        return async(() -> promotionTransaction(connection -> {
            String payload = promotionPayload(draft);
            String hash = sha256(payload);
            ExistingOperation operation = findOperation(connection, draft.operationId());
            if (operation == null || !operation.type.equals("PROMOTE_CITY") || !operation.requestHash.equals(hash)) {
                connection.rollback();
                return promotionResult(draft.operationId(), CityPromotionCode.OPERATION_CONFLICT, null, true);
            }
            if (operation.state.equals("DOMAIN_COMMITTED") || operation.state.equals("COMMIT_PENDING") || operation.state.equals("COMPLETED")) {
                CityView current = readCity(connection, draft.cityId()).orElse(null);
                connection.commit();
                return promotionResult(draft.operationId(), current == null ? CityPromotionCode.INTERNAL_ERROR
                        : CityPromotionCode.REPLAYED, current, true);
            }
            if (!operation.state.equals("RESERVED")) {
                connection.commit();
                return promotionResult(draft.operationId(), operation.state.equals("FAILED") || operation.state.equals("RELEASED")
                        ? parsePromotionCode(operation.resultCode) : CityPromotionCode.OPERATION_IN_PROGRESS, null, true);
            }

            CityPromotionCode failure = promotionFailure(connection, draft);
            if (failure != null) {
                setOperationState(connection, draft.operationId(), "RELEASE_PENDING", failure);
                connection.commit();
                return promotionResult(draft.operationId(), failure, null, false);
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE cities SET stage=?, revision=revision+1
                    WHERE city_uuid=? AND stage=? AND revision=?
                    """)) {
                update.setString(1, draft.toStage().name());
                update.setString(2, draft.cityId().toString());
                update.setString(3, draft.fromStage().name());
                update.setLong(4, draft.expectedRevision());
                if (update.executeUpdate() != 1) {
                    setOperationState(connection, draft.operationId(), "RELEASE_PENDING", CityPromotionCode.STALE_REVISION);
                    connection.commit();
                    return promotionResult(draft.operationId(), CityPromotionCode.STALE_REVISION, null, false);
                }
            }
            setOperationState(connection, draft.operationId(), "DOMAIN_COMMITTED", CityPromotionCode.PROMOTED);
            CityView updated = readCity(connection, draft.cityId()).orElseThrow(() -> new SQLException("Promoted city disappeared"));
            connection.commit();
            return promotionResult(draft.operationId(), CityPromotionCode.PROMOTED, updated, false);
        }));
    }

    @Override
    public CompletionStage<Void> markPromotionCommitPending(UUID operationId) {
        return async(() -> { updatePromotionState(operationId, "DOMAIN_COMMITTED", "COMMIT_PENDING", CityPromotionCode.UNKNOWN_OUTCOME); return null; });
    }

    @Override
    public CompletionStage<CityPromotionResult> completePromotion(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                ExistingOperation operation = findOperation(connection, operationId);
                if (operation == null || !operation.type.equals("PROMOTE_CITY")) {
                    connection.commit();
                    return promotionResult(operationId, CityPromotionCode.OPERATION_CONFLICT, null, true);
                }
                  if (operation.state.equals("DOMAIN_COMMITTED") || operation.state.equals("COMMIT_PENDING")) {
                      setOperationState(connection, operationId, "COMPLETED", CityPromotionCode.PROMOTED);
                      operation = findOperation(connection, operationId);
                  }
                  if (!operation.state.equals("COMPLETED")) {
                      connection.commit();
                      return promotionResult(operationId, CityPromotionCode.OPERATION_IN_PROGRESS, null, true);
                  }
                  CityView city = operation.cityId == null ? null : readCity(connection, UUID.fromString(operation.cityId)).orElse(null);
                  connection.commit();
                  return promotionResult(operationId, city == null ? CityPromotionCode.INTERNAL_ERROR
                          : CityPromotionCode.REPLAYED, city, true);
            }
        });
    }

    @Override
    public CompletionStage<Void> failPromotion(UUID operationId, CityPromotionCode code, boolean releasePending) {
        return async(() -> {
            String expected = releasePending ? "RESERVED" : "PREPARED";
            String target = releasePending ? "RELEASE_PENDING" : "FAILED";
            updatePromotionState(operationId, expected, target, code);
            return null;
        });
    }

    @Override
    public CompletionStage<Void> markPromotionReleased(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                    UPDATE city_operations SET state='RELEASED', updated_at=?
                    WHERE operation_uuid=? AND operation_type='PROMOTE_CITY' AND state='RELEASE_PENDING'
                    """)) {
                update.setString(1, Instant.now().toString());
                update.setString(2, operationId.toString());
                update.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Set<ChunkPosition>> claimedChunks(Set<ChunkPosition> candidates) {
        Set<ChunkPosition> copy = Set.copyOf(candidates);
        return async(() -> {
            Set<ChunkPosition> claimed = new HashSet<>();
            try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM city_chunks WHERE world_uuid=? AND chunk_x=? AND chunk_z=?")) {
                for (ChunkPosition chunk : copy) {
                    query.setString(1, chunk.worldId().toString());
                    query.setInt(2, chunk.x());
                    query.setInt(3, chunk.z());
                    try (ResultSet result = query.executeQuery()) { if (result.next()) claimed.add(chunk); }
                }
            }
            return Set.copyOf(claimed);
        });
    }

    @Override
    public CompletionStage<CityClaimResult> prepareClaim(CityClaimDraft draft) {
        return async(() -> promotionTransaction(connection -> {
            String payload = claimPayload(draft);
            String hash = sha256(payload);
            ExistingOperation operation = findOperation(connection, draft.operationId());
            if (operation != null) return claimReplay(connection, draft, operation, hash);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT operation_uuid FROM city_operations WHERE city_uuid=? AND operation_type='CLAIM_CHUNK'
                      AND state IN ('PREPARED','RESERVED','DOMAIN_COMMITTED','COMMIT_PENDING','RELEASE_PENDING')
                    LIMIT 1
                    """)) {
                query.setString(1, draft.cityId().toString());
                try (ResultSet pending = query.executeQuery()) {
                    if (pending.next()) {
                        connection.commit();
                        return claimResult(draft.operationId(), CityClaimCode.OPERATION_IN_PROGRESS, null, true);
                    }
                }
            }
            CityClaimCode failure = claimFailure(connection, draft);
            String now = Instant.now().toString();
            if (failure != null) {
                writeClaimOperation(connection, draft, hash, payload, "FAILED", failure, now);
                connection.commit();
                return claimResult(draft.operationId(), failure, null, false);
            }
            writeClaimOperation(connection, draft, hash, payload, "PREPARED", CityClaimCode.OPERATION_IN_PROGRESS, now);
            connection.commit();
            return claimResult(draft.operationId(), CityClaimCode.OPERATION_IN_PROGRESS, null, false);
        }));
    }

    @Override
    public CompletionStage<Boolean> reserveClaim(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                    UPDATE city_operations SET state='RESERVED', updated_at=?
                    WHERE operation_uuid=? AND operation_type='CLAIM_CHUNK' AND state='PREPARED'
                    """)) {
                update.setString(1, Instant.now().toString());
                update.setString(2, operationId.toString());
                if (update.executeUpdate() == 1) return true;
                ExistingOperation operation = findOperation(connection, operationId);
                return operation != null && operation.type.equals("CLAIM_CHUNK") && operation.state.equals("RESERVED");
            }
        });
    }

    @Override
    public CompletionStage<CityClaimResult> applyClaim(CityClaimDraft draft) {
        return async(() -> promotionTransaction(connection -> {
            String hash = sha256(claimPayload(draft));
            ExistingOperation operation = findOperation(connection, draft.operationId());
            if (operation == null || !operation.type.equals("CLAIM_CHUNK") || !operation.requestHash.equals(hash)) {
                connection.rollback();
                return claimResult(draft.operationId(), CityClaimCode.OPERATION_CONFLICT, null, true);
            }
            if (operation.state.equals("DOMAIN_COMMITTED") || operation.state.equals("COMMIT_PENDING")
                    || operation.state.equals("COMPLETED")) {
                CityView current = readCity(connection, draft.cityId()).orElse(null);
                connection.commit();
                return claimResult(draft.operationId(), current == null ? CityClaimCode.INTERNAL_ERROR
                        : CityClaimCode.REPLAYED, current, true);
            }
            if (!operation.state.equals("RESERVED")) {
                connection.commit();
                CityClaimCode code = operation.state.equals("FAILED") || operation.state.equals("RELEASED")
                        ? parseClaimCode(operation.resultCode) : CityClaimCode.OPERATION_IN_PROGRESS;
                return claimResult(draft.operationId(), code, null, true);
            }
            CityClaimCode failure = claimFailure(connection, draft);
            if (failure != null) {
                setClaimState(connection, draft.operationId(), "RELEASE_PENDING", failure);
                connection.commit();
                return claimResult(draft.operationId(), failure, null, false);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO city_chunks(world_uuid, chunk_x, chunk_z, city_uuid, claimed_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, draft.target().worldId().toString());
                insert.setInt(2, draft.target().x());
                insert.setInt(3, draft.target().z());
                insert.setString(4, draft.cityId().toString());
                insert.setString(5, Instant.now().toString());
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE cities SET revision=revision+1 WHERE city_uuid=? AND revision=? AND stage<>'SETTLEMENT'
                    """)) {
                update.setString(1, draft.cityId().toString());
                update.setLong(2, draft.expectedRevision());
                if (update.executeUpdate() != 1) throw new SQLException("City changed while claiming chunk");
            }
            setClaimState(connection, draft.operationId(), "DOMAIN_COMMITTED", CityClaimCode.CLAIMED);
            CityView updated = readCity(connection, draft.cityId()).orElseThrow(() -> new SQLException("Claimed city disappeared"));
            connection.commit();
            return claimResult(draft.operationId(), CityClaimCode.CLAIMED, updated, false);
        }));
    }

    @Override
    public CompletionStage<Void> markClaimCommitPending(UUID operationId) {
        return async(() -> { updateClaimState(operationId, "DOMAIN_COMMITTED", "COMMIT_PENDING", CityClaimCode.UNKNOWN_OUTCOME); return null; });
    }

    @Override
    public CompletionStage<CityClaimResult> completeClaim(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                ExistingOperation operation = findOperation(connection, operationId);
                if (operation == null || !operation.type.equals("CLAIM_CHUNK")) {
                    connection.commit();
                    return claimResult(operationId, CityClaimCode.OPERATION_CONFLICT, null, true);
                }
                if (operation.state.equals("DOMAIN_COMMITTED") || operation.state.equals("COMMIT_PENDING")) {
                    setClaimState(connection, operationId, "COMPLETED", CityClaimCode.CLAIMED);
                    operation = findOperation(connection, operationId);
                }
                if (!operation.state.equals("COMPLETED")) {
                    connection.commit();
                    return claimResult(operationId, CityClaimCode.OPERATION_IN_PROGRESS, null, true);
                }
                CityView city = operation.cityId == null ? null : readCity(connection, UUID.fromString(operation.cityId)).orElse(null);
                connection.commit();
                return claimResult(operationId, city == null ? CityClaimCode.INTERNAL_ERROR : CityClaimCode.REPLAYED, city, true);
            }
        });
    }

    @Override
    public CompletionStage<Void> failClaim(UUID operationId, CityClaimCode code) {
        return async(() -> { updateClaimState(operationId, "PREPARED", "FAILED", code); return null; });
    }

    @Override
    public CompletionStage<Void> markClaimReleasePending(UUID operationId, CityClaimCode code) {
        return async(() -> {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                    UPDATE city_operations SET state='RELEASE_PENDING', result_code=?, updated_at=?
                    WHERE operation_uuid=? AND operation_type='CLAIM_CHUNK' AND state IN ('PREPARED','RESERVED')
                    """)) {
                update.setString(1, code.name());
                update.setString(2, Instant.now().toString());
                update.setString(3, operationId.toString());
                update.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Void> markClaimReleased(UUID operationId) {
        return async(() -> {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                    UPDATE city_operations SET state='RELEASED', updated_at=?
                    WHERE operation_uuid=? AND operation_type='CLAIM_CHUNK' AND state='RELEASE_PENDING'
                    """)) {
                update.setString(1, Instant.now().toString());
                update.setString(2, operationId.toString());
                update.executeUpdate();
                return null;
            }
        });
    }

    private CityClaimCode claimFailure(Connection connection, CityClaimDraft draft) throws SQLException {
        CityView city = readCity(connection, draft.cityId()).orElse(null);
        if (city == null) return CityClaimCode.NOT_FOUND;
        try (PreparedStatement query = connection.prepareStatement("SELECT role_id FROM city_members WHERE city_uuid=? AND player_uuid=?")) {
            query.setString(1, draft.cityId().toString());
            query.setString(2, draft.actorId().toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return CityClaimCode.NOT_MEMBER;
                if (!result.getString("role_id").equals("RULER")) return CityClaimCode.PERMISSION_DENIED;
            }
        }
        if (city.revision() != draft.expectedRevision()) return CityClaimCode.STALE_REVISION;
        if (city.stage() == CityStage.SETTLEMENT) return CityClaimCode.PROMOTION_REQUIRED;
        if (city.chunks().size() >= city.stage().chunkLimit()) return CityClaimCode.CAP_REACHED;
        if (city.chunks().contains(draft.target())) return CityClaimCode.ALREADY_OWNED;
        if (city.chunks().stream().noneMatch(draft.target()::adjacentTo)) return CityClaimCode.DISCONNECTED;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM city_chunks WHERE world_uuid=? AND chunk_x=? AND chunk_z=?
                """)) {
            query.setString(1, draft.target().worldId().toString());
            query.setInt(2, draft.target().x());
            query.setInt(3, draft.target().z());
            try (ResultSet result = query.executeQuery()) { if (result.next()) return CityClaimCode.TARGET_UNAVAILABLE; }
        }
        return null;
    }

    private CityClaimResult claimReplay(Connection connection, CityClaimDraft draft, ExistingOperation operation,
                                        String hash) throws SQLException {
        if (!operation.type.equals("CLAIM_CHUNK") || !operation.requestHash.equals(hash)) {
            connection.commit();
            return claimResult(draft.operationId(), CityClaimCode.OPERATION_CONFLICT, null, true);
        }
        if (operation.state.equals("COMPLETED")) {
            CityView city = operation.cityId == null ? null : readCity(connection, UUID.fromString(operation.cityId)).orElse(null);
            connection.commit();
            return claimResult(draft.operationId(), city == null ? CityClaimCode.INTERNAL_ERROR : CityClaimCode.REPLAYED, city, true);
        }
        if (operation.state.equals("FAILED") || operation.state.equals("RELEASED")) {
            connection.commit();
            return claimResult(draft.operationId(), parseClaimCode(operation.resultCode), null, true);
        }
        connection.commit();
        return claimResult(draft.operationId(), CityClaimCode.OPERATION_IN_PROGRESS, null, true);
    }

    private void writeClaimOperation(Connection connection, CityClaimDraft draft, String hash, String payload,
                                     String state, CityClaimCode code, String now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO city_operations(operation_uuid, operation_type, request_hash, request_payload, state,
                    result_code, city_uuid, actor_uuid, created_at, updated_at)
                VALUES (?, 'CLAIM_CHUNK', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, draft.operationId().toString()); insert.setString(2, hash); insert.setString(3, payload);
            insert.setString(4, state); insert.setString(5, code.name()); insert.setString(6, draft.cityId().toString());
            insert.setString(7, draft.actorId().toString()); insert.setString(8, now); insert.setString(9, now);
            insert.executeUpdate();
        }
    }

    private String claimPayload(CityClaimDraft draft) {
        return draft.cityId() + "\n" + draft.actorId() + "\n" + draft.expectedRevision() + "\n"
                + draft.target().worldId() + "\n" + draft.target().x() + "\n" + draft.target().z() + "\n" + draft.price();
    }

    private CityClaimCode parseClaimCode(String value) {
        try { return CityClaimCode.valueOf(value); }
        catch (IllegalArgumentException invalid) { return CityClaimCode.INTERNAL_ERROR; }
    }

    private CityClaimResult claimResult(UUID operationId, CityClaimCode code, CityView city, boolean replay) {
        Optional<CityView> view = code == CityClaimCode.CLAIMED || code == CityClaimCode.REPLAYED
                ? Optional.ofNullable(city) : Optional.empty();
        if (view.isEmpty() && (code == CityClaimCode.CLAIMED || code == CityClaimCode.REPLAYED)) code = CityClaimCode.INTERNAL_ERROR;
        return new CityClaimResult(operationId, code, view, replay);
    }

    private void setClaimState(Connection connection, UUID operationId, String state, CityClaimCode code) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE city_operations SET state=?, result_code=?, updated_at=?
                WHERE operation_uuid=? AND operation_type='CLAIM_CHUNK'
                """)) {
            update.setString(1, state); update.setString(2, code.name()); update.setString(3, Instant.now().toString());
            update.setString(4, operationId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Claim journal entry disappeared");
        }
    }

    private void updateClaimState(UUID operationId, String expected, String state, CityClaimCode code) throws SQLException {
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                UPDATE city_operations SET state=?, result_code=?, updated_at=?
                WHERE operation_uuid=? AND operation_type='CLAIM_CHUNK' AND state=?
                """)) {
            update.setString(1, state); update.setString(2, code.name()); update.setString(3, Instant.now().toString());
            update.setString(4, operationId.toString()); update.setString(5, expected); update.executeUpdate();
        }
    }

    private CityPromotionCode promotionFailure(Connection connection, CityPromotionDraft draft) throws SQLException {
        CityView city = readCity(connection, draft.cityId()).orElse(null);
        if (city == null) return CityPromotionCode.NOT_FOUND;
        if (city.revision() != draft.expectedRevision()) return CityPromotionCode.STALE_REVISION;
        if (city.stage() != draft.fromStage() || city.stage().ordinal() + 1 != draft.toStage().ordinal()) {
            return CityPromotionCode.INVALID_STAGE;
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT role_id FROM city_members WHERE city_uuid=? AND player_uuid=?
                """)) {
            query.setString(1, draft.cityId().toString());
            query.setString(2, draft.actorId().toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return CityPromotionCode.NOT_MEMBER;
                if (!result.getString("role_id").equals("RULER")) return CityPromotionCode.PERMISSION_DENIED;
            }
        }
        if (city.residents().size() < draft.minimumResidents() || city.chunks().size() < draft.minimumChunks()) {
            return CityPromotionCode.REQUIREMENTS_NOT_MET;
        }
        return null;
    }

    private CityPromotionResult promotionReplay(Connection connection, CityPromotionDraft draft,
                                                 ExistingOperation operation, String hash) throws SQLException {
        if (!operation.type.equals("PROMOTE_CITY") || !operation.requestHash.equals(hash)) {
            connection.commit();
            return promotionResult(draft.operationId(), CityPromotionCode.OPERATION_CONFLICT, null, true);
        }
        if (operation.state.equals("COMPLETED")) {
            CityView city = operation.cityId == null ? null : readCity(connection, UUID.fromString(operation.cityId)).orElse(null);
            connection.commit();
            return promotionResult(draft.operationId(), city == null ? CityPromotionCode.INTERNAL_ERROR
                    : CityPromotionCode.REPLAYED, city, true);
        }
        if (operation.state.equals("FAILED") || operation.state.equals("RELEASED")) {
            connection.commit();
            return promotionResult(draft.operationId(), parsePromotionCode(operation.resultCode), null, true);
        }
        connection.commit();
        return promotionResult(draft.operationId(), CityPromotionCode.OPERATION_IN_PROGRESS, null, true);
    }

    private void writePromotionOperation(Connection connection, CityPromotionDraft draft, String hash,
                                         String payload, String state, CityPromotionCode code, String now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO city_operations(operation_uuid, operation_type, request_hash, request_payload, state,
                    result_code, city_uuid, actor_uuid, created_at, updated_at)
                VALUES (?, 'PROMOTE_CITY', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, draft.operationId().toString());
            insert.setString(2, hash);
            insert.setString(3, payload);
            insert.setString(4, state);
            insert.setString(5, code.name());
            insert.setString(6, draft.cityId().toString());
            insert.setString(7, draft.actorId().toString());
            insert.setString(8, now);
            insert.setString(9, now);
            insert.executeUpdate();
        }
    }

    private String promotionPayload(CityPromotionDraft draft) {
        return draft.cityId() + "\n" + draft.actorId() + "\n" + draft.expectedRevision() + "\n"
                + draft.fromStage() + "\n" + draft.toStage() + "\n" + draft.price() + "\n"
                + draft.minimumResidents() + "\n" + draft.minimumChunks();
    }

    private CityPromotionCode parsePromotionCode(String value) {
        try { return CityPromotionCode.valueOf(value); }
        catch (IllegalArgumentException invalid) { return CityPromotionCode.INTERNAL_ERROR; }
    }

    private CityPromotionResult promotionResult(UUID operationId, CityPromotionCode code, CityView city, boolean replay) {
        Optional<CityView> view = code == CityPromotionCode.PROMOTED || code == CityPromotionCode.REPLAYED
                ? Optional.ofNullable(city) : Optional.empty();
        if (view.isEmpty() && (code == CityPromotionCode.PROMOTED || code == CityPromotionCode.REPLAYED)) {
            code = CityPromotionCode.INTERNAL_ERROR;
        }
        return new CityPromotionResult(operationId, code, view, replay);
    }

    private <T> T promotionTransaction(SqlTransaction<T> action) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try { return action.run(connection); }
            catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    private void setOperationState(Connection connection, UUID operationId, String state, CityPromotionCode code) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE city_operations SET state=?, result_code=?, updated_at=?
                WHERE operation_uuid=? AND operation_type='PROMOTE_CITY'
                """)) {
            update.setString(1, state);
            update.setString(2, code.name());
            update.setString(3, Instant.now().toString());
            update.setString(4, operationId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Promotion journal entry disappeared");
        }
    }

    private void updatePromotionState(UUID operationId, String expected, String target, CityPromotionCode code) throws SQLException {
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                UPDATE city_operations SET state=?, result_code=?, updated_at=?
                WHERE operation_uuid=? AND operation_type='PROMOTE_CITY' AND state=?
                """)) {
            update.setString(1, target);
            update.setString(2, code.name());
            update.setString(3, Instant.now().toString());
            update.setString(4, operationId.toString());
            update.setString(5, expected);
            update.executeUpdate();
        }
    }

    private Optional<CityView> readCity(UUID cityId) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            Optional<CityView> city = readCity(connection, cityId);
            connection.commit();
            return city;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to load city " + cityId, e);
        }
    }

    private Optional<CityView> readCity(Connection connection, UUID cityId) throws SQLException {
        String name;
        CityStage stage;
        long treasury;
        long revision;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT name, stage, treasury, revision FROM cities WHERE city_uuid = ?
                """)) {
            query.setString(1, cityId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                name = result.getString("name");
                stage = CityStage.valueOf(result.getString("stage"));
                treasury = result.getLong("treasury");
                revision = result.getLong("revision");
            }
        }

        var chunks = new java.util.LinkedHashSet<ChunkPosition>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT world_uuid, chunk_x, chunk_z FROM city_chunks WHERE city_uuid = ? ORDER BY world_uuid, chunk_x, chunk_z")) {
            query.setString(1, cityId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    chunks.add(new ChunkPosition(UUID.fromString(result.getString("world_uuid")),
                            result.getInt("chunk_x"), result.getInt("chunk_z")));
                }
            }
        }

        List<CityView.ResidentView> residents = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, last_known_name, role_id FROM city_members
                WHERE city_uuid = ? ORDER BY joined_at, player_uuid
                """)) {
            query.setString(1, cityId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    residents.add(new CityView.ResidentView(UUID.fromString(result.getString("player_uuid")),
                            result.getString("last_known_name"), result.getString("role_id")));
                }
            }
        }
        return Optional.of(new CityView(cityId, name, stage, Set.copyOf(chunks), treasury, revision, List.copyOf(residents)));
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private <T> CompletableFuture<T> async(SqlOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (SQLException e) {
                throw new IllegalStateException("Cities database operation failed", e);
            }
        }, ioExecutor);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTransaction<T> {
        T run(Connection connection) throws SQLException;
    }
}
