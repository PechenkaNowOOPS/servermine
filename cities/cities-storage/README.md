# cities-storage

Cities-owned SQLite adapter. The v1 schema contains:

- `cities` — progression stage, treasury amount, revision and founder identity.
- `city_members` — one current city per player, role id and last-known display name.
- `city_chunks` — a unique `(world_uuid, chunk_x, chunk_z)` key so a chunk cannot belong to two cities.
- `city_operations` — stable operation UUID and payload fingerprint; city founding is journaled transactionally, while purchase saga recovery remains future work.
- `city_treasury_entries` — idempotent journal rows owned by Cities.

Initialization uses a transactional, numbered SQLite migration (`PRAGMA user_version`). The connection enables WAL, FULL synchronous durability,
foreign keys and a bounded busy timeout. A database from a future schema version fails closed; the plugin must not overwrite or silently downgrade it.

`CityRepository` lives in `cities-core`. `SqliteCityRepository` owns JDBC and maps each read to an immutable `CityView` inside one consistent read transaction.
Queries run on the single Cities I/O executor, not a Bukkit event handler. Connections are short-lived; the executor belongs to the plugin lifecycle.
No SQLite connection, row object or repository is published through a public API.

This first slice implements the persistent schema and read path. City creation, aggregate writes, optimistic revision updates, journal idempotency and recovery are still disabled.
Do not write directly to these tables from GUI, admin commands or another plugin. Add domain transactions through core ports before enabling any mutation.
