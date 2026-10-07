# Потребители API

Примеры компилируются в общей сборке, но не являются отдельным серверным плагином.
Потребитель объявляет `depend: [ServerMineEconomy]` в plugin.yml и compileOnly-зависимость на economy-api.

В монорепозитории: `compileOnly(project(":economy:economy-api"))`.
Внешний проект может использовать `compileOnly(files("libs/economy-api-0.1.0-SNAPSHOT.jar"))`.
Economy API не нужно включать в JAR потребителя.

CitiesPurchaseExample принимает заранее сохранённый UUID и callback реальной доменной транзакции.
Он не создаёт фиктивный чанк и не генерирует новый UUID при повторе.
Callback должен атомарно сохранить domain outcome, проверять повтор и конфликт, а вызывающий код — журналировать фазы и восстанавливать их после сбоя.
UNKNOWN/exception не приводит к автоматическому release. COMMITTED/RELEASED не принимаются за новый RESERVED.

HuntingGroundsPayoutExample показывает детерминированный UUID награды контракта.
В реальном домене сумма и параметры контракта должны быть устойчивы; Bukkit-вызовы из async callbacks требуют переноса на серверный поток.
