# cities-core

`CityRepository` is the Cities-owned persistence port. `PersistedCitiesService` adapts immutable read models to the public Cities API.
`cities-plugin` injects the SQLite implementation at startup; no core or feature module opens the database or reaches into another module's tables.

Readiness becomes true only after schema initialization succeeds. It means the read path is available and says nothing about city-creation,
territory, treasury or progression mutations. Current writes and the recovery coordinator must be implemented before those features are advertised as ready.
