# ServerMineEconomy

Самостоятельный плагин физической экономики для Minecraft Java Edition (Elementra), Purpur/Paper 26.2 и Java 25.

Репозиторий содержит **только экономическую систему**: валюту, API, операции, тесты, документацию и примеры интеграции. Градострой разрабатывается отдельно в [servermine-cities](https://github.com/PechenkaNowOOPS/servermine-cities).

## Реализовано в исходниках

- Физические монеты (`ItemStack`) с номиналами из конфигурации, проверкой PDC/HMAC и ключом `currency.secret`.
- `EconomyService` через Bukkit `ServicesManager`: баланс, `charge`, `payout`, `reserve`, `commit`, `release`, получение операции.
- SQLite-журнал, идемпотентность по UUID, `PREPARED/RESERVED/RELEASING/COMMITTED/RELEASED/FAILED/UNKNOWN`.
- Защита от повторной выплаты резерва и переполнения денежной суммы, регрессионные unit-тесты.
- Административные команды `/smeconomy`; обязательных игровых команд нет.

**Статус:** имеются исходники и автономные тесты. Runtime-проверки на настоящем сервере, аварийное завершение JVM и сверка playerdata с SQLite ещё требуют подтверждения. Не объявлять плагин release-ready по одному исходному коду.

## Сборка

Нужен JDK 25. Linux/macOS: `./gradlew clean build`; Windows PowerShell: `.\gradlew.bat clean build`.

| Результат | Назначение |
|---|---|
| `economy/economy-plugin/build/libs/ServerMineEconomy-0.1.0-SNAPSHOT.jar` | Единственный серверный JAR для `plugins/` |
| `economy/economy-api/build/libs/economy-api-0.1.0-SNAPSHOT.jar` | Compile-only API для разработчиков, не устанавливать в `plugins/` |

`examples` — исходники примеров обращения к Economy API, а не рабочие серверные плагины.

## Установка и безопасность

1. Положить Economy JAR в `plugins/` тестового Purpur 26.2 на Java 25.
2. Перезапустить сервер; проверить `/smeconomy status`, `balance`, `give`, `take`, `verify` и `op`.
3. Сохранить резервную копию `plugins/ServerMineEconomy/currency.secret` вместе с базой и данными сервера. **Смена или потеря ключа сделает существующие монеты недействительными.**

База, ключ, playerdata и миры в Git не добавляются.

## Границы модулей

**Economy отвечает** за подлинность/номиналы физических денег, выдачу/списание, резервирование, журнал операций и Economy API.

**Внешние системы отвечают** за городскую казну, налоги, территории, городские рынки, охотничьи контракты и награды данжей. Они получают API через `ServicesManager`, подключают `economy-api` как `compileOnly`, указывают `depend: [ServerMineEconomy]` и не читают `economy.db`. Междоменная покупка: сохранить operationId, вызвать `reserve`, провести собственную транзакцию и затем `commit` либо `release` при доказанном отказе. `UNKNOWN` сначала сверяется.

## Документация

- [Архитектура](docs/architecture/overview.md), [границы модулей](docs/architecture/module-boundaries.md), [транзакции](docs/architecture/transactions.md).
- [Технический обзор экономики](docs/reference/economy-readme.md), [схема движка](docs/reference/economy-architecture.md).
- [Решение о физической валюте](docs/decisions/ADR-001-physical-currency.md), [граница городской казны](docs/decisions/ADR-002-city-treasury-owned-by-cities.md), [сервисы](docs/decisions/ADR-003-servicesmanager-apis.md).
- [Roadmap](ROADMAP.md), [проверки](docs/development/server-smoke-test.md).

Предыдущая версия репозитория с Градостроем сохранена в ветке `backup/pre-economy-split-2026-10-09`. Git-история не переписана.
