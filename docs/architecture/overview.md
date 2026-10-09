# Архитектура ServerMineEconomy

В репозитории находятся только `economy-api` (контракты) и `economy-plugin` (монеты, HMAC/PDC, операции, SQLite), документация и примеры использования Economy API.

Сервис `EconomyService` регистрируется в Bukkit `ServicesManager`. Внешние плагины используют `compileOnly(economy-api)` и `depend: [ServerMineEconomy]`; сами классы API включены только в JAR Economy.

Инвентарь изменяется только на серверном потоке, SQL выполняется в выделенном executor. Внешние домены (Cities, охота, данжи) самостоятельно хранят казну, территорию, правила наград и другие свои данные и не читают `economy.db`.

SQLite и Minecraft playerdata не образуют общей ACID-транзакции: нужны реальные crash/restart-тесты.
