# ADR-003: Economy API через ServicesManager

**Статус:** принято.

`ServerMineEconomy` регистрирует `EconomyService` через Bukkit `ServicesManager`. Публичный `economy-api` поставляется внутри серверного Economy JAR; потребители указывают `compileOnly(economy-api)` и `depend: [ServerMineEconomy]` и не включают вторую копию API в свой JAR.

API не раскрывает SQLite, внутренние репозитории или изменяемые сущности. Если сервис отсутствует либо `isReady() == false`, денежная операция не допускается. На отключении сервиса регистрация снимается.

`CitiesService` и прочие игровые домены реализуются отдельно в соответствующих репозиториях.
