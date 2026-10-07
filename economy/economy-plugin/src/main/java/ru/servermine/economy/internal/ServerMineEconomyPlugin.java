package ru.servermine.economy.internal;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.economy.api.EconomyService;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ServerMineEconomyPlugin extends JavaPlugin {
    private ExecutorService databaseExecutor;
    private OperationRepository operationRepository;
    private EconomyServiceImpl economyService;
    private CurrencyCodec currencyCodec;
    private MainThread mainThread;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            getDataFolder().mkdirs();

            CurrencyRegistry registry = CurrencyRegistry.from(getConfig());
            Path secretPath = getDataFolder().toPath().resolve(getConfig().getString("security.secret-file", "currency.secret"));
            CurrencySigner signer = CurrencySigner.loadOrCreate(secretPath);
            currencyCodec = new CurrencyCodec(this, registry, signer);
            InventoryMoneyEngine moneyEngine = new InventoryMoneyEngine(registry, currencyCodec);
            mainThread = new MainThread(this);

            databaseExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ServerMineEconomy-DB");
                thread.setDaemon(true);
                return thread;
            });
            Path dbPath = getDataFolder().toPath().resolve(getConfig().getString("storage.sqlite-file", "economy.db"));
            int timeout = getConfig().getInt("storage.busy-timeout-ms", 5000);
            operationRepository = new OperationRepository(dbPath, timeout, databaseExecutor);
            operationRepository.initialize(getConfig().getBoolean("operations.mark-prepared-as-unknown-on-startup", true));

            economyService = new EconomyServiceImpl(registry, moneyEngine, operationRepository, mainThread);
            getServer().getServicesManager().register(EconomyService.class, economyService, this, ServicePriority.Normal);

            PluginCommand command = Objects.requireNonNull(getCommand("smeconomy"), "smeconomy command missing");
            EconomyAdminCommand admin = new EconomyAdminCommand(this, economyService, currencyCodec);
            command.setExecutor(admin);
            command.setTabCompleter(admin);

            economyService.setReady(true);
            getLogger().info("ServerMineEconomy READY. EconomyService API v" + economyService.apiVersion() + " registered.");
        } catch (Exception e) {
            getLogger().severe("ServerMineEconomy failed to start: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (economyService != null) economyService.setReady(false);
        getServer().getServicesManager().unregisterAll(this);
        if (operationRepository != null) operationRepository.close();
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) databaseExecutor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                databaseExecutor.shutdownNow();
            }
        }
    }

    MainThread mainThread() {
        return mainThread;
    }

    void reloadEconomyConfig() {
        reloadConfig();
        getLogger().warning("Config reloaded. Currency denomination/PDC changes require a full restart and planned migration; live registry is unchanged.");
    }
}
