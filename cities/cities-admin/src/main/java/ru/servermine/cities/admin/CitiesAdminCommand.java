package ru.servermine.cities.admin;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CityLeaveCode;
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
                    + "; members ready=" + cities.membershipService().filter(ru.servermine.cities.api.CityMembershipService::isReady).isPresent());
            sender.sendMessage("/smcities <givebook|open|preview> <игрок> [menu]; /smcities leave — покинуть город");
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
