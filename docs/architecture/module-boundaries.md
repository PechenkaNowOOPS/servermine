# Границы модулей

| Модуль | Владелец данных / назначение | Текущее состояние |
|---|---|---|
| economy-api | Контракты физических денежных операций | Импортирован |
| economy-plugin | Монеты, HMAC/PDC, номиналы, операции, SQLite | Импортирован, исправлены гонка возврата и overflow |
| cities-api | Неизменяемые CityView, ChunkPosition, CityStage, CitiesService | Реализован начальный read API |
| cities-core | Контракт репозитория и read-only CitiesService | Реализован |
| cities-storage | SQLite schema v1, транзакционные миграции, асинхронные immutable read models | Реализован; write workflows пока отсутствуют |
| cities-creation | Основание города | Чистый расчёт четырёх чанков 2×2; сохранение не реализовано |
| cities-territory | Владение, соседство, caps, покупка | Чистая валидация; saga не реализована |
| cities-protection | Изолированные политики и адаптеры защиты | Каркас |
| cities-members | Состав города и приглашения | Каркас |
| cities-roles | Роли и атомарные права | Каркас |
| cities-treasury | Городская казна и интеграция с Economy | Каркас |
| cities-progression | Последовательность этапов | Порядок переходов; требования пока не реализованы |
| cities-upgrades | Улучшения, зависимости и эффекты | Каркас |
| cities-market | Торговые места города | Каркас |
| cities-diplomacy | Межгородские отношения | Каркас |
| cities-gui | Книга, отрисовка, сессии и навигация | Просмотр/предпросмотр, без денежных мутаций |
| cities-admin | Административные команды | status, givebook, open, preview |
| cities-plugin | Композиция, конфигурация, ServicesManager и lifecycle I/O executor | Один серверный JAR; проверка DB-схемы идёт до регистрации сервиса |

Каждая реализация зависит от своего API/core. Admin использует публичные адаптеры GUI для выдачи книги и предпросмотра.
Feature-модули не читают репозитории соседей. Экономические зависимости Cities — только compileOnly на economy-api.
Казна Cities никогда не размещается в БД Economy.

Структуру зависимостей можно получить `./gradlew projects` и `./gradlew :cities:cities-plugin:dependencies`.
Группирующие Gradle-проекты `cities` и `economy` не являются библиотеками/плагинами.

Интерфейсы репозитория принадлежат core; SQLite-реализация — storage; создание и инъекция выполняются в cities-plugin.
Не добавлять Bukkit в публичный Cities API. Protection должен оставаться заменяемым адаптером для будущего выделения в отдельный плагин.
