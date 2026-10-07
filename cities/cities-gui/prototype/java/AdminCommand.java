package ru.servermine.gradostroygui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final GradostroyGuiPlugin plugin;

    public AdminCommand(GradostroyGuiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("gradostroy.admin")) {
            sender.sendMessage(Component.text("Нет административного права gradostroy.admin.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "givebook" -> giveBook(sender, args);
            case "open" -> open(sender, args);
            case "reload" -> reload(sender);
            case "setdemo" -> setDemo(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void giveBook(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        if (target == null) {
            sender.sendMessage(Component.text("Использование: /gradostroygui givebook <игрок>", NamedTextColor.RED));
            return;
        }
        target.getInventory().addItem(plugin.getBookService().createBook());
        sender.sendMessage(Component.text("Книга выдана игроку " + target.getName() + ".", NamedTextColor.GREEN));
    }

    private void open(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /gradostroygui open <игрок> [menu]", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        MenuType type = MenuType.MAIN;
        if (args.length >= 3) {
            try {
                type = MenuType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                sender.sendMessage(Component.text("Неизвестное меню.", NamedTextColor.RED));
                return;
            }
        }
        plugin.getMenuManager().open(target, type);
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getRepository().load();
        sender.sendMessage(Component.text("GradostroyGUI перезагружен.", NamedTextColor.GREEN));
    }

    private void setDemo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Использование: /gradostroygui setdemo <name|stage|chunks|treasury> <значение>", NamedTextColor.RED));
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        try {
            plugin.getRepository().setDemo(args[1], value);
            sender.sendMessage(Component.text("Демонстрационный город обновлён.", NamedTextColor.GREEN));
        } catch (RuntimeException ex) {
            sender.sendMessage(Component.text("Ошибка: " + ex.getMessage(), NamedTextColor.RED));
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("GradostroyGUI — административные команды:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/gradostroygui givebook <игрок>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/gradostroygui open <игрок> [MAIN|TERRITORY|PURCHASE|UPGRADES|TREASURY|RESIDENTS|MANAGEMENT|DIPLOMACY]", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/gradostroygui setdemo <name|stage|chunks|treasury> <значение>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/gradostroygui reload", NamedTextColor.GRAY));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("gradostroy.admin")) return List.of();
        if (args.length == 1) return prefix(args[0], List.of("givebook", "open", "reload", "setdemo"));
        if ((args[0].equalsIgnoreCase("givebook") || args[0].equalsIgnoreCase("open")) && args.length == 2) {
            return prefix(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args[0].equalsIgnoreCase("open") && args.length == 3) {
            return prefix(args[2], Arrays.stream(MenuType.values()).map(Enum::name).toList());
        }
        if (args[0].equalsIgnoreCase("setdemo") && args.length == 2) {
            return prefix(args[1], List.of("name", "stage", "chunks", "treasury"));
        }
        if (args[0].equalsIgnoreCase("setdemo") && args.length == 3 && args[1].equalsIgnoreCase("stage")) {
            return prefix(args[2], Arrays.stream(CityStage.values()).map(Enum::name).toList());
        }
        return List.of();
    }

    private static List<String> prefix(String input, List<String> options) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(option);
        }
        return result;
    }
}
