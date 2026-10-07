# Проверка bootstrap — 2026-10-07

Среда: Windows, Microsoft OpenJDK 25.0.4.1, Gradle Wrapper 9.7.1, Purpur API 26.2.build.2633-stable.

Выполнено из корня репозитория:

```powershell
.\gradlew.bat projects clean build --console=plain --warning-mode all
git diff --check
```

Результат: BUILD SUCCESSFUL; **25 тестов**, 0 ошибок, 0 пропусков.

| Набор | Тестов | Что проверено |
|---|---:|---|
| OperationRepositoryTest | 5 | Повтор и конфликт UUID, конкурирующий prepare, единственный владелец release, restart UNKNOWN/RESERVED |
| CurrencyCodecTest | 8 | Подлинный HMAC, подделка/отсутствие подписи, материал, schema, номинал, обычный предмет, сохранённый ключ |
| ReservationRaceTest | 2 | Повторный payout не планируется, проигравший commit не объявляет успех |
| InventoryMoneyEngineTest | 1 | Overflow обнаруживается до записи инвентаря |
| CityStageTest | 1 | Caps 4/12/24/40/64 |
| FoundingFootprintTest | 2 | Четыре соединённых чанка и отсутствие переполнения координат |
| ClaimRulesTest | 5 | Пятый чанк Поселения, соседство/мир/диагональ, cap, занятость, защита и revision |
| StageProgressionTest | 1 | Порядок этапов и конечный KINGDOM |

SQLite-тесты используют настоящую временную базу. CurrencyCodec использует настоящую логику проверки PDC/HMAC и тестовый ключ;
Bukkit-предметы, material registry, PDC и scheduling представлены Mockito-объектами, так как сервер не запущен.
Это не клиентские или полные серверные интеграционные тесты.

Дополнительно:

- `verifyPackaging` включён в Gradle check: проверяет наличие plugin.yml и API, состав двух JAR, отсутствие Economy-классов в Cities и отсутствие старого DemoCityRepository.
- Resource-pack ZIP содержит корневой pack.mcmeta, gui.json и восемь экранов.
- Все 11 файлов исходного ресурспака совпали с приложенным ZIP по SHA-256.
- Все 73 оригинальных приложения проверены по манифесту в source-materials.zip.
- `.gitignore` проверен на secret, .env, DB/WAL, build и тестовые миры.
- Gradle projects и сборка подтверждают разрешимый граф модулей.

Невыполненное: запуск Purpur/Minecraft, ручные GUI-эксплойт сценарии, crash/playerdata-интеграция, GitHub Actions на remote.
Сценарии следующей проверки описаны в [server-smoke-test.md](server-smoke-test.md).

Источники версий: [совместимость Gradle/Java](https://docs.gradle.org/current/userguide/compatibility.html),
[метаданные Purpur API](https://repo.purpurmc.org/snapshots/org/purpurmc/purpur/purpur-api/maven-metadata.xml).
