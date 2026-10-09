# Примеры потребителей Economy API

Этот каталог компилируется в экономическом проекте, но не является серверным плагином.

- `CitiesPurchaseExample` показывает только денежную часть межплагинной покупки чанка через `reserve → domain commit → commit/release`. Сам Градострой разрабатывается отдельно. Внешний плагин обязан хранить operationId и собственный журнал результата.
- `HuntingGroundsPayoutExample` иллюстрирует выплату физической валюты по детерминированному UUID награды. При необходимости любые Bukkit-вызовы из async callbacks выполняются на main thread.

Потребитель добавляет `depend: [ServerMineEconomy]` и подключает `economy-api` как `compileOnly`, не встраивая API классы в собственный JAR. Внутри проекта: `compileOnly(project(":economy:economy-api"))`.

Примеры не являются готовыми gameplay-системами.
