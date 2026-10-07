package ru.servermine.economy.internal;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.servermine.economy.api.*;

import java.util.*;

final class EconomyAdminCommand implements CommandExecutor, TabCompleter {
    private final ServerMineEconomyPlugin plugin;
    private final EconomyService service;
    private final CurrencyCodec codec;

    EconomyAdminCommand(ServerMineEconomyPlugin plugin, EconomyService service, CurrencyCodec codec) {
        this.plugin = plugin;
        this.service = service;
        this.codec = codec;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("servermine.economy.admin")) {
            sender.sendMessage("§cНет права servermine.economy.admin.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "balance" -> balance(sender, args);
            case "give" -> mutate(sender, args, true);
            case "take" -> mutate(sender, args, false);
            case "verify" -> verify(sender);
            case "op" -> operation(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage("§6ServerMineEconomy §7API v" + service.apiVersion() + " §8| §fREADY: " + service.isReady());
        sender.sendMessage("§7Capabilities: §f" + service.capabilities());
        sender.sendMessage("§7Номиналы:");
        service.denominations().forEach(d -> sender.sendMessage(" §8- §f" + d.id() + " §7= §e" + d.value() + " §7(" + d.displayName() + ")"));
    }

    private void balance(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) return;
        service.physicalBalance(target.getUniqueId()).thenAccept(result -> plugin.mainThread().run(() -> {
            if (result.successful()) sender.sendMessage("§e" + target.getName() + "§7: §6" + result.balance() + " §7малых единиц.");
            else sender.sendMessage("§cБаланс недоступен: " + result.code() + " — " + result.message());
        }));
    }

    private void mutate(CommandSender sender, String[] args, boolean give) {
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /smeconomy " + (give ? "give" : "take") + " <игрок> <сумма>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cИгрок должен быть онлайн.");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cСумма должна быть целым числом.");
            return;
        }
        UUID operationId = UUID.randomUUID();
        MoneyRequest request = new MoneyRequest(operationId, target.getUniqueId(), amount, "ServerMineEconomy:admin",
                (give ? "admin_give:" : "admin_take:") + sender.getName());
        var future = give ? service.payout(request) : service.charge(request);
        future.thenAccept(result -> plugin.mainThread().run(() -> {
            String prefix = result.successful() ? "§a" : "§c";
            sender.sendMessage(prefix + result.code() + " §7state=" + result.state() + " op=" + result.operationId());
            if (result.balanceAfter() >= 0) sender.sendMessage("§7Баланс: §f" + result.balanceBefore() + " §8→ §f" + result.balanceAfter());
        }));
    }

    private void verify(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда требует игрока с предметом в основной руке.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        CurrencyCodec.Inspection inspection = codec.inspect(item);
        if (!inspection.authentic()) {
            sender.sendMessage("§cПредмет НЕ является подлинной валютой. Причина: " + inspection.reason());
            return;
        }
        sender.sendMessage("§aПодлинная валюта: §f" + inspection.denomination().displayName()
                + " §7× " + item.getAmount() + " §8(номинал " + inspection.denomination().value() + ")");
    }

    private void operation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /smeconomy op <operation-uuid>");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cНеверный UUID операции.");
            return;
        }
        service.operation(id).thenAccept(optional -> plugin.mainThread().run(() -> {
            if (optional.isEmpty()) {
                sender.sendMessage("§cОперация не найдена.");
                return;
            }
            OperationView op = optional.get();
            sender.sendMessage("§6Operation §f" + op.operationId());
            sender.sendMessage("§7type=§f" + op.type() + " §7state=§f" + op.state() + " §7result=§f" + op.resultCode());
            sender.sendMessage("§7player=§f" + op.playerId() + " §7amount=§e" + op.amount());
            sender.sendMessage("§7source=§f" + op.sourcePlugin() + " §7purpose=§f" + op.purpose());
            sender.sendMessage("§7created=§f" + op.createdAt() + " §7updated=§f" + op.updatedAt());
        }));
    }

    private void reload(CommandSender sender) {
        plugin.reloadEconomyConfig();
        sender.sendMessage("§eТекстовая конфигурация перечитана. Изменение валюты/номиналов применяется только после полного рестарта.");
    }

    private Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player player = Bukkit.getPlayerExact(args[index]);
            if (player == null) sender.sendMessage("§cИгрок должен быть онлайн.");
            return player;
        }
        if (sender instanceof Player player) return player;
        sender.sendMessage("§cУкажите игрока.");
        return null;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6/smeconomy status");
        sender.sendMessage("§6/smeconomy balance [игрок]");
        sender.sendMessage("§6/smeconomy give <игрок> <сумма>");
        sender.sendMessage("§6/smeconomy take <игрок> <сумма>");
        sender.sendMessage("§6/smeconomy verify");
        sender.sendMessage("§6/smeconomy op <uuid>");
        sender.sendMessage("§6/smeconomy reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("status", "balance", "give", "take", "verify", "op", "reload"), args[0]);
        if (args.length == 2 && Set.of("balance", "give", "take").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
