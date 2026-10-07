package ru.servermine.cities.admin;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.gui.*;
import java.util.List;
import java.util.Locale;

public final class CitiesAdminCommand implements CommandExecutor {
    private final CitiesService cities;
    private final CityBookService books;
    private final MenuManager menus;
    public CitiesAdminCommand(CitiesService cities, CityBookService books, MenuManager menus) {
        this.cities = cities; this.books = books; this.menus = menus;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("servermine.cities.admin")) { sender.sendMessage("Нет административного права."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("ServerMineCities: storage ready=" + cities.isReady()
                    + "; founding ready=" + cities.foundingService().filter(ru.servermine.cities.api.CityFoundingService::isReady).isPresent()
                    + "; progression ready=" + cities.progressionService().filter(ru.servermine.cities.api.CityProgressionService::isReady).isPresent()
                    + "; territory ready=" + cities.territoryService().filter(ru.servermine.cities.api.CityTerritoryService::isReady).isPresent());
            sender.sendMessage("/smcities <givebook|open|preview> <игрок> [menu]");
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
