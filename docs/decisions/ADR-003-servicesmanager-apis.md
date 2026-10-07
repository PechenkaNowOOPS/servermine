# ADR-003: публикация API через ServicesManager

Статус: принято из исходного задания.

Плагины получают EconomyService и CitiesService через Bukkit ServicesManager. Контракты размещены в маленьких отдельных API-модулях.
Потребители используют compileOnly и plugin.yml depend; API-классы поставляет ровно один runtime-владелец.

Следствие: Cities JAR не содержит копию Economy API. API не раскрывает SQLite, репозитории, обработчики событий и изменяемые внутренние объекты.
Отсутствие либо readiness=false сервиса должно запрещать зависимые мутации. На disable регистрации снимаются.
