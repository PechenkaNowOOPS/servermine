package ru.servermine.gradostroygui;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class GradostroyGuiPlugin extends JavaPlugin {
    private DemoCityRepository repository;
    private CityBookService bookService;
    private MenuManager menuManager;
    private AuditLog auditLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        auditLog = new AuditLog(Path.of(getDataFolder().getAbsolutePath(), "audit.log"));

        repository = new DemoCityRepository(this);
        repository.load();
        bookService = new CityBookService(this);
        menuManager = new MenuManager(this, repository);

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        PluginCommand command = getCommand("gradostroygui");
        if (command == null) {
            throw new IllegalStateException("Команда gradostroygui отсутствует в plugin.yml");
        }
        AdminCommand adminCommand = new AdminCommand(this);
        command.setExecutor(adminCommand);
        command.setTabCompleter(adminCommand);

        getLogger().info("GradostroyGUI MVP включён. Игровой интерфейс открывается Книгой управления городом.");
    }

    public DemoCityRepository getRepository() {
        return repository;
    }

    public CityBookService getBookService() {
        return bookService;
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }
}
