package example;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.economy.api.EconomyResult;
import ru.servermine.economy.api.EconomyService;
import ru.servermine.economy.api.MoneyRequest;

import java.util.UUID;

/**
 * Пример межплагинной саги для Cities.
 * В plugin.yml потребителя: depend: [ServerMineEconomy]
 */
public final class CitiesPurchaseExample {
    private final JavaPlugin plugin;
    private final EconomyService economy;

    public CitiesPurchaseExample(JavaPlugin plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider<EconomyService> registration =
                Bukkit.getServicesManager().getRegistration(EconomyService.class);
        if (registration == null) throw new IllegalStateException("ServerMineEconomy service is unavailable");
        this.economy = registration.getProvider();
    }

    public void buyChunk(Player player, long price) {
        UUID operationId = UUID.randomUUID(); // Cities должен хранить этот UUID вместе со своей операцией покупки.
        MoneyRequest request = new MoneyRequest(
                operationId,
                player.getUniqueId(),
                price,
                plugin.getName(),
                "city_chunk_purchase"
        );

        economy.reserve(request).thenAccept(reserve -> {
            if (!reserve.successful()) {
                // Покупка не началась: денег нет / инвентарь не позволяет выдать сдачу / сервис недоступен.
                return;
            }

            // В реальном Cities ниже должна быть его собственная устойчивая операция/БД.
            boolean cityCommitSucceeded = persistChunkPurchase();

            if (cityCommitSucceeded) {
                economy.commit(operationId).thenAccept(this::auditEconomyResult);
            } else {
                economy.release(operationId).thenAccept(this::auditEconomyResult);
            }
        });
    }

    private boolean persistChunkPurchase() {
        return true;
    }

    private void auditEconomyResult(EconomyResult result) {
        plugin.getLogger().info("Economy op=" + result.operationId() + " state=" + result.state() + " code=" + result.code());
    }
}
