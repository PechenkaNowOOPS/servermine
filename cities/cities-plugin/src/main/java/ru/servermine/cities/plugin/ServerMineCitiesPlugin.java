package ru.servermine.cities.plugin;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityFoundingService;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.core.PersistedCitiesService;
import ru.servermine.cities.storage.SqliteCityRepository;
import ru.servermine.cities.creation.CityFoundingServiceImpl;
import ru.servermine.cities.protection.BukkitFoundationPolicy;
import ru.servermine.cities.progression.CityProgressionServiceImpl;
import ru.servermine.cities.progression.StagePromotionPolicy;
import ru.servermine.cities.territory.CityTerritoryServiceImpl;
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
    private CityFoundingServiceImpl cityFoundingService;
    private CityProgressionServiceImpl cityProgressionService;
    private CityTerritoryServiceImpl cityTerritoryService;

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

            BukkitFoundationPolicy protection = new BukkitFoundationPolicy(this, loadSystemZones());
            cityFoundingService = new CityFoundingServiceImpl(repository, protection);
            EconomyService economy = getServer().getServicesManager().load(EconomyService.class);
            cityProgressionService = new CityProgressionServiceImpl(repository, economy, loadPromotionPolicies());
            cityTerritoryService = new CityTerritoryServiceImpl(repository, economy,
                    getConfig().getLong("territory.chunk-price", 750),
                    target -> protection.checkTarget(target).thenApply(decision -> decision == ru.servermine.cities.protection.FoundationDecision.ALLOWED),
                    protection::checkTargets);
            citiesService = new PersistedCitiesService(repository, cityFoundingService, cityProgressionService,
                    cityTerritoryService);
            getServer().getServicesManager().register(CitiesService.class, citiesService, this, ServicePriority.Normal);
            CityBookService books = new CityBookService(this);
            MenuManager menus = new MenuManager(this, citiesService);
            getServer().getPluginManager().registerEvents(new GuiListener(this, books, menus), this);
            Objects.requireNonNull(getCommand("smcities"), "smcities command is missing")
                    .setExecutor(new CitiesAdminCommand(citiesService, books, menus));

            getLogger().info("Cities storage is ready. Economy ready=" + (economy != null && economy.isReady()));
            getLogger().info("City founding, configured progression, and territory purchase services are ready.");
        } catch (Exception error) {
            getLogger().severe("ServerMineCities could not initialize its database: " + error.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Cities startup failed", error);
            closeDatabaseExecutor();
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (cityProgressionService != null) cityProgressionService.deactivate();
        if (cityTerritoryService != null) cityTerritoryService.deactivate();
        if (cityFoundingService != null) cityFoundingService.deactivate();
        if (citiesService != null) citiesService.deactivate();
        getServer().getServicesManager().unregisterAll(this);
        getServer().getOnlinePlayers().stream()
            .filter(p -> p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
            .forEach(org.bukkit.entity.Player::closeInventory);
        closeDatabaseExecutor();
    }

    private java.util.Map<CityStage, StagePromotionPolicy> loadPromotionPolicies() {
        java.util.Map<CityStage, StagePromotionPolicy> policies = new java.util.EnumMap<>(CityStage.class);
        policies.put(CityStage.SETTLEMENT, new StagePromotionPolicy(
                getConfig().getBoolean("progression.settlement-to-village.enabled", true),
                getConfig().getLong("progression.settlement-to-village.price", 0),
                getConfig().getInt("progression.settlement-to-village.minimum-residents", 1),
                getConfig().getInt("progression.settlement-to-village.minimum-chunks", 4)));
        return java.util.Map.copyOf(policies);
    }

    private java.util.List<BukkitFoundationPolicy.SystemZone> loadSystemZones() {
        java.util.List<BukkitFoundationPolicy.SystemZone> zones = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> row : getConfig().getMapList("protection.system-zones")) {
            Object world = row.get("world");
            if (world == null) throw new IllegalArgumentException("protection.system-zones entry is missing world UUID");
            try {
                zones.add(new BukkitFoundationPolicy.SystemZone(java.util.UUID.fromString(world.toString()),
                        integer(row, "min-x"), integer(row, "max-x"), integer(row, "min-z"), integer(row, "max-z")));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid protection.system-zones entry: " + row, invalid);
            }
        }
        return java.util.List.copyOf(zones);
    }

    private int integer(java.util.Map<?, ?> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing integer " + key);
        return number.intValue();
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
