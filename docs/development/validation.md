# Историческая проверка Economy

В исходном смешанном bootstrap 7 октября 2026 года был задокументирован `BUILD SUCCESSFUL`, всего 25 unit-тестов, из которых **16 относятся к экономике**:

| Набор | Тестов | Проверено |
|---|---:|---|
| OperationRepositoryTest | 5 | SQLite/UUID/idempotency, конкурентный prepare/release, restart UNKNOWN/RESERVED |
| CurrencyCodecTest | 8 | Проверка HMAC/PDC, подделки, схема, материал, номинал, ключ |
| ReservationRaceTest | 2 | Гонки возврата и commit |
| InventoryMoneyEngineTest | 1 | Overflow до изменения инвентаря |

SQLite тестировалась на временной базе, а Bukkit — через Mockito. Результат относится к исторической сборке, а не является подтверждением текущего коммита или реального Purpur.

После разделения нужно отдельно подтвердить `./gradlew clean build`, `verifyPackaging` и выполнить [server-smoke-test.md](server-smoke-test.md). Crash/playerdata-надёжность unit-тестами не доказана.
