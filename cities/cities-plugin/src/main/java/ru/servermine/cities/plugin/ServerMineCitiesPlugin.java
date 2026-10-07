package ru.servermine.cities.plugin;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.core.UnconfiguredCitiesService;
import ru.servermine.cities.admin.CitiesAdminCommand;
import ru.servermine.cities.gui.*;
import ru.servermine.economy.api.EconomyService;
import java.util.Objects;

public final class ServerMineCitiesPlugin extends JavaPlugin {
    @Override public void onEnable() {
        saveDefaultConfig();
        CitiesService cities = new UnconfiguredCitiesService();
        getServer().getServicesManager().register(CitiesService.class, cities, this, ServicePriority.Normal);
        CityBookService books = new CityBookService(this);
        MenuManager menus = new MenuManager(this, cities);
        getServer().getPluginManager().registerEvents(new GuiListener(this, books, menus), this);
        Objects.requireNonNull(getCommand("smcities")).setExecutor(new CitiesAdminCommand(cities, books, menus));
        EconomyService economy = getServer().getServicesManager().load(EconomyService.class);
        getLogger().info("Bootstrap enabled. Economy ready=" + (economy != null && economy.isReady()));
        getLogger().warning("City persistence and mutations are not implemented yet. /smcities preview opens read-only mockups.");
    }
    @Override public void onDisable() {
        getServer().getOnlinePlayers().stream()
            .filter(p -> p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
            .forEach(org.bukkit.entity.Player::closeInventory);
        getServer().getServicesManager().unregisterAll(this);
    }
}
