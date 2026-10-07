# AGENTS.md — ServerMine repository instructions

## Mission

Create and maintain the ServerMine Minecraft server codebase as a modular Java 25 / Purpur 26.2 project.

The immediate goal is to turn this bootstrap folder into a clean Git repository and establish two major domains:

1. `ServerMineEconomy` — an independent plugin and public API for physical currency and monetary transactions.
2. `ServerMineCities` (Gradostroy) — a modular city-building plugin whose internal modules are developed independently but are packaged as one plugin JAR.

Do not collapse the domains into one monolith. Do not let one domain read or mutate another domain's database.

## Target runtime

- Minecraft server: Purpur 26.2, compatible Paper API.
- Java: 25.
- Build: Gradle Kotlin DSL, Gradle Wrapper committed.
- No NMS/CraftBukkit internals unless a future ADR explicitly approves them.
- Bukkit/Paper/Purpur APIs only for normal server interaction.
- SQLite is acceptable for current persistence.
- SQL/file I/O must not run in hot Bukkit event handlers.
- Bukkit world/inventory mutations happen on the server thread.

## Repository layout to create

```text
servermine/
├─ AGENTS.md
├─ README.md
├─ ROADMAP.md
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ .gitignore
├─ docs/
│  ├─ architecture/
│  │  ├─ overview.md
│  │  ├─ module-boundaries.md
│  │  └─ transactions.md
│  ├─ decisions/
│  │  ├─ ADR-001-physical-currency.md
│  │  ├─ ADR-002-city-treasury-owned-by-cities.md
│  │  ├─ ADR-003-servicesmanager-apis.md
│  │  └─ ADR-004-city-modules-one-plugin.md
│  └─ gradostroy/
│     ├─ concept.md
│     ├─ module-plan.md
│     └─ progression.md
├─ economy/
│  ├─ economy-api/
│  └─ economy-plugin/
├─ cities/
│  ├─ cities-api/
│  ├─ cities-core/
│  ├─ cities-storage/
│  ├─ cities-creation/
│  ├─ cities-territory/
│  ├─ cities-protection/
│  ├─ cities-members/
│  ├─ cities-roles/
│  ├─ cities-treasury/
│  ├─ cities-progression/
│  ├─ cities-upgrades/
│  ├─ cities-market/
│  ├─ cities-diplomacy/
│  ├─ cities-gui/
│  ├─ cities-admin/
│  └─ cities-plugin/
├─ resource-pack/
└─ examples/
```

`cities-plugin` is the composition/bootstrap module that produces the final `ServerMineCities.jar`.
The internal Cities modules must not each become separate runtime plugins unless explicitly decided later.

## Domain ownership

### Economy owns

- physical currency authenticity;
- denominations;
- currency item schema/version;
- HMAC/PDC validation;
- money issue/payout;
- physical money charge;
- reservations;
- commit/release of reservations;
- operation idempotency;
- monetary transaction journal;
- Economy public API.

Economy does NOT own:
- city treasury balances;
- city roles/permissions;
- city territory;
- hunting contracts;
- dungeon rewards.

### Cities owns

- cities;
- members;
- roles and city permissions;
- city chunks/territory;
- city treasury state;
- city progression stages;
- city upgrades;
- city diplomacy;
- city market-space definitions;
- management book;
- Cities public API.

### Protection responsibility

For the first implementation it may live as an internal Cities module, but keep its interfaces isolated so it can later become `ServerMineProtection` without rewriting city domain logic.

## Critical Gradostroy decisions

Player-facing city actions use the City Management Book GUI. Normal players do not use city commands.

Commands are administrative/diagnostic only.

A new city starts with exactly 4 chunks.

Progression and current territory caps:

- Settlement / Поселение: 4
- Village / Деревня: 12
- City / Город: 24
- Capital / Столица: 40
- Kingdom / Королевство: 64

At Settlement stage the city cannot buy additional chunks. It must progress to Village first.

New chunks:
- are purchased with currency;
- must be adjacent to existing city territory;
- may not create disconnected enclaves;
- must respect protected/system zones;
- must respect the current stage cap.

Upgrades do NOT directly grant `+N chunks`. Territory capacity is primarily unlocked by progression stage.

No teleport-to-city-square feature.

City progression:
`SETTLEMENT -> VILLAGE -> CITY -> CAPITAL -> KINGDOM`

The exact prices and promotion requirements are configuration, not hard-coded balance unless needed as defaults.

## GUI rules

The City Management Book is the main player interface.

Sections:
- Overview
- Territory
- Upgrades
- Treasury
- Residents
- Management
- Diplomacy
- Market

GUI is presentation, never source of truth.

On every state-changing click re-check:
- player identity;
- city membership;
- permission;
- object revision/version;
- current price;
- current stage;
- current territory cap;
- target availability;
- integration readiness.

Prevent inventory GUI exploits: shift-click, drag, number-key swap, double-click, offhand swap, drop, creative middle-click and extraction of service items.

The resource pack is cosmetic. Without it the GUI must remain understandable and functional through vanilla items, names and lore.

Do not implement city-square teleportation.

## Economy integration rules

Other plugins obtain `EconomyService` through Bukkit `ServicesManager`.

For cross-domain purchases use:
`reserve -> domain mutation -> commit`
or
`reserve -> failed domain mutation -> release`.

Example: chunk purchase:
1. Cities creates stable operation ID.
2. Cities validates city permission and target chunk.
3. Economy reserves required physical money.
4. Cities commits the chunk purchase transaction.
5. Economy commits reservation.
6. If Cities cannot commit, Economy releases reservation.

Never use a fresh operation ID to blindly retry an operation with unknown outcome.

## Transaction invariants

- Money amounts use integer minimal units (`long`).
- Every critical mutation has a stable operation ID.
- Repeating the same operation ID with identical normalized parameters is idempotent.
- Same operation ID with different parameters is a conflict.
- Unknown outcome is not automatically retried.
- Persist enough state to recover or diagnose after restart.
- GUI closure/disconnect/repeated clicks must not duplicate payment or reward.
- No negative money values.
- No direct cross-plugin SQL access.

## API design

Keep public API modules small and implementation-independent.

Use immutable records/value objects where practical.

Do not expose:
- SQLite connection;
- repository implementation;
- Bukkit listener implementation;
- internal mutable entity instances.

Prefer services such as:
- `EconomyService`
- `CitiesService`
- `TerritoryService`
- `CityMembershipService`
- `CityPermissionService`
- `CityTreasuryService`
- `CityProgressionService`

Publish APIs through Bukkit `ServicesManager`.

## Gradle dependency direction

Allowed:
- implementation modules -> their own API/core abstractions
- `cities-plugin` -> all Cities implementation modules
- Cities treasury/creation/territory where needed -> `economy-api` only
- other plugins -> `cities-api` / `economy-api`

Forbidden:
- `economy-plugin` -> Cities implementation
- `economy-api` -> Bukkit implementation classes beyond what is necessary for API types
- one Cities feature module directly accessing another module's repositories
- any module -> another module's SQLite tables

Avoid cyclic Gradle dependencies.

## Development sequence

1. Normalize/import existing Economy source.
2. Make root Gradle multi-project build compile.
3. Add tests around Economy idempotency and currency validation.
4. Create Cities API/core/storage.
5. Create city creation.
6. Create territory.
7. Create protection.
8. Create members and roles.
9. Integrate treasury with Economy.
10. Add progression.
11. Add upgrades.
12. Replace Gradostroy GUI demo data with real services.
13. Add market and diplomacy.
14. Add admin diagnostics/recovery.
15. Integration and restart/failure tests.

Do not jump to diplomacy/market polish while core territory and transaction invariants are untested.

## Existing material in bootstrap

`existing/ServerMineEconomy` contains the current Economy implementation baseline.
Import/refactor it; do not discard working behavior without reason.

`existing/GradostroyGUI` contains a GUI prototype and resource-pack reference.
Treat it as visual/prototyping material. Do not treat demo city data as canonical domain state.

## Git requirements

Initialize a Git repository on branch `main`.

Create logical commits rather than one giant dump. Suggested initial commits:

1. `chore: initialize ServerMine multi-project build`
2. `feat(economy): import economy API and plugin baseline`
3. `docs(cities): define Gradostroy module boundaries`
4. `feat(cities): scaffold Cities API core and plugin composition`
5. `feat(gui): import management book resource-pack prototype`

Do not commit:
- `.gradle/`
- `build/`
- IDE metadata;
- SQLite runtime databases;
- `currency.secret`;
- server logs;
- server world folders;
- generated test servers.

If GitHub CLI is authenticated, create a GitHub repository only after local build/tests pass.
Default requested repository name: `servermine`.
Prefer PRIVATE visibility unless the user explicitly asks for public.
Do not force-push or rewrite remote history.

## Definition of done for repository bootstrap

Before declaring bootstrap complete:

- Gradle Wrapper exists.
- `./gradlew projects` works.
- `./gradlew build` succeeds or any unavoidable external dependency failure is clearly documented.
- Economy API and implementation are represented as separate modules.
- Cities module graph exists without dependency cycles.
- `ServerMineCities` plugin composition module exists.
- README explains local build and server installation.
- AGENTS.md remains at repository root.
- `.gitignore` protects secrets/runtime data.
- Git history contains meaningful commits.
- If remote creation was requested and authentication is available, `origin` points to the created GitHub repository and `main` is pushed.
