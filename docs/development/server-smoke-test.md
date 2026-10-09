# ServerMineEconomy — проверка на сервере

**Статус:** ручной тест на реальном сервере не подтверждён.

Использовать отдельный тестовый Purpur 26.2 / Java 25 и **только Economy JAR**.

1. Проверить загрузку, регистрацию `EconomyService`, создание `economy.db` и `currency.secret`.
2. `/smeconomy status`, `balance`, `give`, `take`, `verify`, `op`, `reload`; проверить админ-права.
3. Проверить достаточность денег, сдачу, переполненный инвентарь, offline, большие суммы/overflow.
4. Проверить подделки name/lore/PDC/HMAC, материал, номинал и схему монеты.
5. Повтор с тем же UUID/параметрами не списывает повторно; с иными параметрами вызывает конфликт.
6. Проверить конкурирующие `reserve/commit/release` и два `release` одного резерва.
7. Проверить disconnect/reconnect и сохранение RESERVED через штатный restart.
8. Провести crash/restart в окнах PREPARED, записи инвентаря/playerdata, RESERVED, RELEASING и COMMITTED/RELEASED; сверить журнал.
9. UNKNOWN не должен автоматически повторяться или компенсироваться.
10. Проверить внешний тестовый плагин с `compileOnly(economy-api)` и `depend: [ServerMineEconomy]`.

Для каждого теста фиксировать commit SHA, версии среды и фактический результат.
