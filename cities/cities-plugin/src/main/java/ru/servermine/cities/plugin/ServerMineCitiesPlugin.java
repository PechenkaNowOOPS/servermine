package ru.servermine.cities.plugin;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.core.PersistedCitiesService;
import ru.servermine.cities.storage.SqliteCityRepository;
import ru.servermine.cities.admin.CitiesAdminCommand;
import ru.servermine.cities.gui.*;
import ru.servermine.economy.api.EconomyService;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ServerMineCitiesPlugin extends JavaPlugin {
    private ExecutorService databaseExecutor;
    private PersistedCitiesService citiesService;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            Files.createDirectories(getDataFolder().toPath());
            databaseExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "ServerMineCities-DB");
                thread.setDaemon(true);
                return thread;
            });

            Path database = getDataFolder().toPath().resolve(getConfig().getString("storage.sqlite-file", "cities.db"));
            int busyTimeout = getConfig().getInt("storage.busy-timeout-ms", 5000);
            SqliteCityRepository repository = new SqliteCityRepository(database, busyTimeout, databaseExecutor);
            repository.initialize();

            citiesService = new PersistedCitiesService(repository);
            getServer().getServicesManager().register(CitiesService.class, citiesService, this, ServicePriority.Normal);
            CityBookService books = new CityBookService(this);
            MenuManager menus = new MenuManager(this, citiesService);
            getServer().getPluginManager().registerEvents(new GuiListener(this, books, menus), this);
            Objects.requireNonNull(getCommand("smcities"), "smcities command is missing")
                    .setExecutor(new CitiesAdminCommand(citiesService, books, menus));

            EconomyService economy = getServer().getServicesManager().load(EconomyService.class);
            getLogger().info("Cities storage is ready. Economy ready=" + (economy != null && economy.isReady()));
            getLogger().warning("City creation and territory mutations are not implemented yet. /smcities preview opens read-only mockups.");
        } catch (Exception error) {
            getLogger().severe("ServerMineCities could not initialize its database: " + error.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Cities startup failed", error);
            closeDatabaseExecutor();
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (citiesService != null) citiesService.deactivate();
        getServer().getServicesManager().unregisterAll(this);
        getServer().getOnlinePlayers().stream()
            .filter(p -> p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
            .forEach(org.bukkit.entity.Player::closeInventory);
        closeDatabaseExecutor();
    }

    private void closeDatabaseExecutor() {
        if (databaseExecutor == null) return;
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) databaseExecutor.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            databaseExecutor.shutdownNow();
        }
    }
}
