package example;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.economy.api.*;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Integration sketch, not a complete persisted saga. The caller must persist the intent/operation ID,
 * deduplicate the domain transaction and reconcile outstanding intents after restart.
 * plugin.yml of the consumer must declare depend: [ServerMineEconomy].
 */
public final class CitiesPurchaseExample {
    public enum DomainOutcome { COMMITTED, DEFINITELY_NOT_APPLIED }
    private final JavaPlugin plugin;
    private final EconomyService economy;

    public CitiesPurchaseExample(JavaPlugin plugin) {
        this.plugin = plugin;
        this.economy = Objects.requireNonNull(Bukkit.getServicesManager().load(EconomyService.class),
                "ServerMineEconomy service is unavailable");
    }

    /**
     * Call only for a persisted, unfinished intent. Reuse its original normalized parameters.
     * Domain callback must revalidate and atomically commit the claim + intent outcome in Cities.
     * Exceptional/unknown domain outcomes deliberately propagate WITHOUT an automatic refund.
     * The caller must record commit/release responses and reconcile any incomplete operation.
     */
    public CompletionStage<EconomyResult> buyChunk(
            UUID persistedOperationId, UUID playerId, long price,
            Supplier<CompletionStage<DomainOutcome>> durableDomainTransaction) {
        MoneyRequest request = new MoneyRequest(persistedOperationId, playerId, price,
                plugin.getName(), "city_chunk_purchase");
        return economy.reserve(request).thenCompose(reservation -> {
            if (reservation.code() != ResultCode.OK) return CompletableFuture.completedFuture(reservation);
            if (reservation.state() != OperationState.RESERVED) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Reconcile existing saga before continuing: " + reservation.state()));
            }
            return durableDomainTransaction.get().thenCompose(outcome -> switch (Objects.requireNonNull(outcome)) {
                case COMMITTED -> economy.commit(persistedOperationId);
                case DEFINITELY_NOT_APPLIED -> economy.release(persistedOperationId);
            });
        });
    }
}
