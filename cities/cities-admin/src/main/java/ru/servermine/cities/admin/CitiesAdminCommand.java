package ru.servermine.cities.admin;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CityMembershipCode;
import ru.servermine.cities.api.CityTreasuryCode;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.gui.*;
import java.util.List;
import java.util.Locale;

public final class CitiesAdminCommand implements CommandExecutor {
    private final CitiesService cities;
    private final CityBookService books;
    private final MenuManager menus;
    private final JavaPlugin plugin;
    public CitiesAdminCommand(JavaPlugin plugin, CitiesService cities, CityBookService books, MenuManager menus) {
        this.plugin = plugin; this.cities = cities; this.books = books; this.menus = menus;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("treasury")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            var treasury = cities.treasuryService().orElse(null);
            if (treasury == null || !treasury.isReady()) { player.sendMessage("Казна города восстанавливает операции или временно недоступна."); return true; }
            if (args.length == 3 && args[1].equalsIgnoreCase("deposit")) {
                long amount;
                try { amount = Long.parseLong(args[2]); }
                catch (NumberFormatException invalid) { player.sendMessage("Укажите целую сумму монет."); return true; }
                if (amount <= 0) { player.sendMessage("Сумма должна быть больше нуля."); return true; }
                treasury.deposit(player.getUniqueId(), amount).whenComplete((result, error) ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            if (error != null || result == null) { player.sendMessage("Не удалось пополнить казну."); return; }
                            switch (result.code()) {
                                case DEPOSITED -> player.sendMessage("В казну внесено " + amount + " монет. Новый баланс: "
                                        + result.city().orElseThrow().treasury() + " монет.");
                                case NO_CITY -> player.sendMessage("Вы не состоите в городе.");
                                case INVALID_AMOUNT -> player.sendMessage("Сумма должна быть больше нуля.");
                                case INSUFFICIENT_FUNDS -> player.sendMessage("У вас недостаточно физических монет.");
                                case ECONOMY_UNAVAILABLE -> player.sendMessage("Экономика временно недоступна.");
                                case OPERATION_IN_PROGRESS, UNKNOWN_OUTCOME -> player.sendMessage("Операция ещё сверяется. Не повторяйте платёж; после перезапуска она будет восстановлена.");
                                case SERVICE_UNAVAILABLE -> player.sendMessage("Казна города временно недоступна.");
                                case INTERNAL_ERROR -> player.sendMessage("Не удалось пополнить казну.");
                            }
                        }));
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("history")) {
                treasury.history(player.getUniqueId(), 10).whenComplete((entries, error) ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            if (error != null) { player.sendMessage("Не удалось загрузить историю казны."); return; }
                            if (entries.isEmpty()) { player.sendMessage("Операций в казне пока нет."); return; }
                            player.sendMessage("Последние операции казны:");
                            entries.forEach(entry -> player.sendMessage((entry.amount() > 0 ? "+" : "−")
                                    + Math.abs(entry.amount()) + " монет; баланс " + entry.balanceAfter()
                                    + "; " + entry.reason() + "; " + entry.occurredAt()));
                        }));
                return true;
            }
            player.sendMessage("Использование: /smcities treasury deposit <сумма> | /smcities treasury history");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("invite")) {
            if (args.length != 2) { sender.sendMessage("Использование: /smcities invite <игрок>"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { player.sendMessage("Игрок должен быть онлайн."); return true; }
            var membership = cities.membershipService().orElse(null);
            if (membership == null || !membership.isReady()) { player.sendMessage("Управление составом города временно недоступно."); return true; }
            membership.invite(player.getUniqueId(), target.getUniqueId(), target.getName()).whenComplete((result, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null || result == null) { player.sendMessage("Не удалось отправить приглашение."); return; }
                        switch (result.code()) {
                            case INVITED -> {
                                String cityName = result.city().map(ru.servermine.cities.api.CityView::name).orElse("город");
                                player.sendMessage("Приглашение в город " + cityName + " отправлено игроку " + target.getName() + ". Оно действует 48 часов.");
                                if (target.isOnline()) target.sendMessage("Вас пригласили в город " + cityName + ". Примите приглашение командой /smcities accept.");
                            }
                            case NO_CITY -> player.sendMessage("Вы не состоите в городе.");
                            case PERMISSION_DENIED -> player.sendMessage("Приглашать игроков может только правитель города.");
                            case TARGET_ALREADY_IN_CITY -> player.sendMessage("Этот игрок уже состоит в городе.");
                            case NOT_INVITED, ALREADY_IN_CITY, ACCEPTED -> player.sendMessage("Не удалось отправить приглашение.");
                            case SERVICE_UNAVAILABLE -> player.sendMessage("Управление составом города временно недоступно.");
                            case INTERNAL_ERROR -> player.sendMessage("Не удалось отправить приглашение.");
                        }
                    }));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("accept")) {
            if (args.length != 1) { sender.sendMessage("Использование: /smcities accept"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            var membership = cities.membershipService().orElse(null);
            if (membership == null || !membership.isReady()) { player.sendMessage("Управление составом города временно недоступно."); return true; }
            membership.accept(player.getUniqueId(), player.getName()).whenComplete((result, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null || result == null) { player.sendMessage("Не удалось принять приглашение."); return; }
                        switch (result.code()) {
                            case ACCEPTED -> player.sendMessage("Вы вступили в город " + result.city().map(ru.servermine.cities.api.CityView::name).orElse("") + ".");
                            case NOT_INVITED -> player.sendMessage("У вас нет действующего приглашения.");
                            case ALREADY_IN_CITY -> player.sendMessage("Вы уже состоите в городе.");
                            case SERVICE_UNAVAILABLE -> player.sendMessage("Управление составом города временно недоступно.");
                            case NO_CITY, PERMISSION_DENIED, TARGET_ALREADY_IN_CITY, INVITED, INTERNAL_ERROR -> player.sendMessage("Не удалось принять приглашение.");
                        }
                    }));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("kick")) {
            if (args.length != 2) { sender.sendMessage("Использование: /smcities kick <игрок>"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { player.sendMessage("Игрок должен быть онлайн."); return true; }
            var membership = cities.membershipService().orElse(null);
            if (membership == null || !membership.isReady()) { player.sendMessage("Управление составом города временно недоступно."); return true; }
            cities.cityForPlayer(player.getUniqueId()).whenComplete((city, lookupError) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (lookupError != null || city == null || city.isEmpty()) { player.sendMessage("Вы не состоите в городе."); return; }
                        membership.kick(player.getUniqueId(), target.getUniqueId(), city.get().revision()).whenComplete((result, error) ->
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (!player.isOnline()) return;
                                    if (error != null || result == null) { player.sendMessage("Не удалось исключить игрока."); return; }
                                    switch (result.code()) {
                                        case KICKED -> {
                                            player.sendMessage(target.getName() + " исключён из города.");
                                            if (target.isOnline()) target.sendMessage("Вас исключили из города " + city.get().name() + ".");
                                        }
                                        case PERMISSION_DENIED -> player.sendMessage("Исключать участников может только правитель.");
                                        case TARGET_NOT_IN_CITY -> player.sendMessage("Игрок не состоит в вашем городе.");
                                        case SELF_TARGET -> player.sendMessage("Нельзя исключить себя. Используйте /smcities leave.");
                                        case CANNOT_KICK_RULER -> player.sendMessage("Нельзя исключить правителя города.");
                                        case STALE_CITY -> player.sendMessage("Состав изменился. Повторите команду.");
                                        default -> player.sendMessage("Не удалось исключить игрока.");
                                    }
                                }));
                    }));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            if (args.length != 1) { sender.sendMessage("Использование: /smcities leave"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
            var membership = cities.membershipService().orElse(null);
            if (membership == null || !membership.isReady()) { player.sendMessage("Управление составом города пока недоступно."); return true; }
            membership.leave(player.getUniqueId()).whenComplete((result, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null || result == null) { player.sendMessage("Не удалось выйти из города. Попробуйте позже."); return; }
                        switch (result.code()) {
                            case LEFT -> {
                                player.sendMessage("Вы покинули город.");
                                result.newRulerId().ifPresent(newRuler -> result.city().ifPresent(city -> city.residents().stream()
                                        .filter(resident -> resident.playerId().equals(newRuler)).findFirst()
                                        .ifPresent(resident -> player.sendMessage("Правителем автоматически стал " + resident.name() + "."))));
                            }
                            case NOT_IN_CITY -> player.sendMessage("Вы не состоите в городе.");
                            case LAST_RULER -> player.sendMessage("Нельзя покинуть город, если вы его последний правитель. Сначала пригласите преемника.");
                            case SERVICE_UNAVAILABLE -> player.sendMessage("Управление составом города временно недоступно.");
                            case INTERNAL_ERROR -> player.sendMessage("Не удалось выйти из города. Попробуйте позже.");
                        }
                    }));
            return true;
        }
        if (!sender.hasPermission("servermine.cities.admin")) { sender.sendMessage("Нет административного права."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("ServerMineCities: storage ready=" + cities.isReady()
                    + "; founding ready=" + cities.foundingService().filter(ru.servermine.cities.api.CityFoundingService::isReady).isPresent()
                    + "; progression ready=" + cities.progressionService().filter(ru.servermine.cities.api.CityProgressionService::isReady).isPresent()
                    + "; territory ready=" + cities.territoryService().filter(ru.servermine.cities.api.CityTerritoryService::isReady).isPresent()
                    + "; members ready=" + cities.membershipService().filter(ru.servermine.cities.api.CityMembershipService::isReady).isPresent()
                    + "; treasury ready=" + cities.treasuryService().filter(ru.servermine.cities.api.CityTreasuryService::isReady).isPresent());
            sender.sendMessage("/smcities <givebook|open|preview> <игрок> [menu]; /smcities invite <игрок>; /smcities accept; /smcities kick <игрок>; /smcities leave");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("givebook", "open", "preview").contains(action)) return false;
        Player player = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        if (player == null) { sender.sendMessage("Укажите онлайн-игрока."); return true; }
        if (action.equals("givebook")) {
            if (player.getInventory().firstEmpty() < 0) { sender.sendMessage("Инвентарь заполнен."); return true; }
            player.getInventory().addItem(books.createBook());
            sender.sendMessage("Книга выдана.");
            return true;
        }
        MenuType menu = MenuType.MAIN;
        if (args.length > 2) {
            try { menu = MenuType.valueOf(args[2].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { sender.sendMessage("Неизвестное меню."); return true; }
        }
        if (action.equals("preview")) {
            menus.openPreview(player, menu);
            sender.sendMessage("Открыт демонстрационный макет. Покупки и изменения отключены.");
        } else menus.open(player, menu);
        return true;
    }
}
