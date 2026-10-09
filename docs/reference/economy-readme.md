# ServerMineEconomy

Отдельный доменный плагин физической экономики для Server Mine.

## Целевая среда

- Purpur/Paper API: `26.2.build.+`
- Java runtime/toolchain: 25
- Bukkit plugin (`plugin.yml`)
- SQLite: `org.xerial:sqlite-jdbc:3.53.4.0`, загружается Paper через `libraries`
- Игровых команд нет. `/smeconomy` — только административная диагностика.

## Что уже реализовано в v0.1

### Физическая валюта

Монеты являются реальными `ItemStack` и могут храниться/передаваться обычным способом.
Подлинность определяется PDC и HMAC-подписью, а не названием или lore.

На первом запуске создаётся `plugins/ServerMineEconomy/currency.secret`.
**Этот файл нельзя терять или заменять:** он является ключом проверки существующих монет.

Номиналы задаются в `config.yml`. По умолчанию используются значения `1 / 10 / 100`; названия намеренно нейтральные, потому что финальные названия валюты ещё не утверждены.

### Публичный API

Другие плагины получают `EconomyService` через Bukkit `ServicesManager`.

Доступны:

- чтение физического баланса онлайн-игрока;
- окончательное списание (`charge`);
- выплата (`payout`);
- резервирование (`reserve`);
- подтверждение резерва (`commit`);
- возврат резерва (`release`);
- поиск операции по UUID;
- список номиналов и capabilities.

### Идемпотентность

Каждая мутация содержит `operationId`.

Один и тот же `operationId` с теми же параметрами не выполняет денежную операцию второй раз. Если тот же UUID пришёл с другими параметрами, возвращается `OPERATION_CONFLICT`.

Это особенно важно для:

- наград за охоту;
- покупки чанков;
- городских улучшений;
- наград событий;
- магазинов;
- повторного клика/лага/разрыва соединения.

### Резервы для межплагинных операций

Для действий, которые должны изменить и Economy, и другой домен:

1. внешний плагин создаёт свой устойчивый `operationId`;
2. вызывает `economy.reserve(request)`;
3. выполняет собственную мутацию;
4. при успехе вызывает `economy.commit(operationId)`;
5. при отказе вызывает `economy.release(operationId)`.

Пример находится в `examples/CitiesPurchaseExample.java`.

### SQLite и восстановление

Таблица `economy_operations` хранит состояние операций.

Основные состояния:

- `PREPARED` — операция записана перед изменением инвентаря;
- `RESERVED` — деньги изъяты и ждут решения внешнего домена;
- `RELEASING` — начат возврат;
- `COMMITTED` — окончательно завершено;
- `RELEASED` — резерв возвращён;
- `FAILED` — безопасный отказ до денежного результата;
- `UNKNOWN` — после сбоя нельзя доказать, была ли последняя физическая мутация завершена.

При запуске незавершённые `PREPARED/RELEASING` переводятся в `UNKNOWN`. Они **не повторяются автоматически**, чтобы не создать дюп. Такие операции диагностируются администратором.

## Разделение ответственности

`ServerMineEconomy` владеет:

- подлинностью физической валюты;
- номиналами;
- выдачей и списанием монет;
- резервами;
- операциями и их состояниями;
- журналом денежных операций;
- Economy API.

Он **не владеет**:

- балансом городской казны — это `Cities`;
- городскими правами — `Cities`;
- территорией — `Cities`;
- охотничьими договорами — `HuntingGrounds`;
- наградами данжей — `Dungeons`.

Внешний модуль никогда не читает `economy.db` напрямую.

## Подключение API в другой плагин

В `plugin.yml`:

```yaml
depend: [ServerMineEconomy]
```

При локальной разработке положить `economy-api-0.1.0-SNAPSHOT.jar` в `libs/` потребителя:

```kotlin
dependencies {
    compileOnly(files("libs/servermine-economy-api-0.1.0-SNAPSHOT.jar"))
}
```

Получение сервиса:

```java
RegisteredServiceProvider<EconomyService> registration =
        Bukkit.getServicesManager().getRegistration(EconomyService.class);

if (registration == null) {
    throw new IllegalStateException("ServerMineEconomy is unavailable");
}

EconomyService economy = registration.getProvider();
```

Пример списания:

```java
UUID operationId = UUID.randomUUID();
MoneyRequest request = new MoneyRequest(
        operationId,
        player.getUniqueId(),
        750,
        getName(),
        "city_chunk_purchase"
);

economy.charge(request).thenAccept(result -> {
    if (!result.successful()) {
        getLogger().warning(result.code().name());
    }
});
```

Для междоменной покупки вместо `charge` использовать `reserve -> commit/release`.

## Административные команды

- `/smeconomy status`
- `/smeconomy balance [player]`
- `/smeconomy give <player> <amount>`
- `/smeconomy take <player> <amount>`
- `/smeconomy verify`
- `/smeconomy op <operation-uuid>`
- `/smeconomy reload`

Permission: `servermine.economy.admin`, по умолчанию OP.

## Сборка

```bash
./gradlew clean build
```

Финальный серверный JAR будет в:

`economy/economy-plugin/build/libs/ServerMineEconomy-0.1.0-SNAPSHOT.jar`

Проект намеренно настроен на Java 25 и настоящий Purpur 26.2 API.

## Следующие подмодули Economy

Ядро v0.1 специально не смешивает в один класс все экономические механики. Следующими слоями можно добавить поверх текущего API:

1. `ResourceExchange` — серверный обменник ресурсов на физическую валюту;
2. `PlayerShops` — физические магазины игроков;
3. `SafeTrade` — атомарный обмен игрок ↔ игрок;
4. `EconomyStats` — эмиссия, уничтожение, оборот и статистика рынка;
5. `CurrencyExchangeGUI` — размен номиналов;
6. административное восстановление `UNKNOWN` операций.
