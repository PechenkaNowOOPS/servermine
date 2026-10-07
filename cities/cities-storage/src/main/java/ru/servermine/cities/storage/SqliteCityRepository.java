package ru.servermine.cities.storage;

import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.core.CityRepository;

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
}
