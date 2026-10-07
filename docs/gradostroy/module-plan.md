# Gradostroy module plan

| Module | Responsibility | Runtime |
|---|---|---|
| cities-api | Public immutable contracts/services/events | library |
| cities-core | City aggregate, IDs, shared domain rules | library |
| cities-storage | SQLite repositories/migrations | library |
| cities-creation | City founding workflow | library |
| cities-territory | Chunk ownership, adjacency, caps, pricing hooks | library |
| cities-protection | Enforcement adapters/listeners | library |
| cities-members | Invitations/membership lifecycle | library |
| cities-roles | Roles and atomic permissions | library |
| cities-treasury | City-owned treasury state + Economy integration | library |
| cities-progression | SETTLEMENT→VILLAGE→CITY→CAPITAL→KINGDOM | library |
| cities-upgrades | Upgrade definitions/dependencies/effects | library |
| cities-market | City market-space governance | library |
| cities-diplomacy | Inter-city relations | library |
| cities-gui | Management Book menus/session validation | library |
| cities-admin | Admin diagnostics/recovery commands | library |
| cities-plugin | Composition root / plugin.yml / service publication | ServerMineCities.jar |

## Territory caps

- SETTLEMENT: 4
- VILLAGE: 12
- CITY: 24
- CAPITAL: 40
- KINGDOM: 64

A new city starts with 4 chunks. At SETTLEMENT it cannot buy additional chunks.
