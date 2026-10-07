# CODEX_TASK.md

You are responsible for turning this bootstrap directory into the initial ServerMine Git repository.

## Required procedure

1. Read `AGENTS.md` completely.
2. Inspect `existing/ServerMineEconomy` and `existing/GradostroyGUI`.
3. Create the final repository layout specified in `AGENTS.md`.
4. Use a Gradle Kotlin DSL multi-project build and commit the Gradle Wrapper.
5. Import/refactor the existing Economy source into:
   - `economy/economy-api`
   - `economy/economy-plugin`
6. Scaffold the Cities modules and establish compile-safe dependency directions.
7. Move the GUI/resource-pack prototype into the appropriate `cities/cities-gui` and `resource-pack` locations, preserving its role as presentation only.
8. Create the architecture/ADR documentation requested in `AGENTS.md`.
9. Add `.gitignore` rules for secrets, DBs, builds, test servers, logs and worlds.
10. Add tests for at least:
    - Economy duplicate operation ID;
    - Economy conflicting operation ID;
    - invalid/fake currency rejected;
    - Cities stage cap values;
    - Settlement cannot purchase a fifth chunk;
    - disconnected chunk claim rejected at domain-validation level.
11. Run the build/tests and fix failures.
12. Initialize Git with branch `main`.
13. Make logical commits as described in `AGENTS.md`.

## GitHub

After the local repository builds successfully:
- check whether GitHub CLI (`gh`) is installed and authenticated;
- if authenticated, create a PRIVATE GitHub repository named `servermine`;
- add it as `origin`;
- push `main`;
- if authentication or GitHub CLI is unavailable, do not invent credentials and do not block the local repository. Print the exact command the user should run after authentication.

Suggested command when authenticated:

`gh repo create servermine --private --source=. --remote=origin --push`

Do not make the repository public unless explicitly instructed.

## Important product rules

- No normal player city commands.
- City player UX is the City Management Book GUI.
- Economy remains a separate plugin.
- Cities is modular internally but produces one runtime plugin.
- City treasury belongs to Cities.
- Physical currency authenticity and monetary operations belong to Economy.
- City starts with 4 chunks.
- Caps: 4 / 12 / 24 / 40 / 64.
- Settlement cannot expand until promoted to Village.
- Claims must be adjacent.
- Upgrades do not directly add chunk capacity.
- No city-square teleport.
- Cross-domain purchases use Economy reservation semantics.
