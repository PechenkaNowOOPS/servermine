package example;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.economy.api.EconomyService;
import ru.servermine.economy.api.MoneyRequest;

import java.util.UUID;

/** Выплата награды охоты существующей физической валютой. */
public final class HuntingGroundsPayoutExample {
    private final JavaPlugin plugin;
    private final EconomyService economy;

    public HuntingGroundsPayoutExample(JavaPlugin plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider<EconomyService> registration = Bukkit.getServicesManager().getRegistration(EconomyService.class);
        if (registration == null) throw new IllegalStateException("ServerMineEconomy service is unavailable");
        this.economy = registration.getProvider();
    }

    public void payContract(Player player, UUID contractId, long reward) {
        // Важно: детерминированный operationId. Повторная сдача того же договора не создаст вторую выплату.
        UUID operationId = UUID.nameUUIDFromBytes(("hunting-contract:" + contractId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MoneyRequest request = new MoneyRequest(operationId, player.getUniqueId(), reward, plugin.getName(), "hunting_contract:" + contractId);

        economy.payout(request).thenAccept(result -> {
            if (result.successful()) {
                plugin.getLogger().info("Hunting payout committed: " + result.operationId() + " replay=" + result.idempotentReplay());
            } else {
                plugin.getLogger().warning("Hunting payout failed: " + result.code() + " state=" + result.state());
            }
        });
    }
}
