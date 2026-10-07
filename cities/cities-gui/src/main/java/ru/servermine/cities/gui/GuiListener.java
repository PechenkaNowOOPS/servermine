package ru.servermine.cities.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuiListener implements Listener {
    private final JavaPlugin plugin;
    private final CityBookService books;
    private final MenuManager menus;
    public GuiListener(JavaPlugin plugin, CityBookService books, MenuManager menus) {
        this.plugin = plugin; this.books = books; this.menus = menus;
    }
    @EventHandler public void onBookUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!books.isCityBook(event.getItem())) return;
        event.setCancelled(true);
        menus.open(event.getPlayer(), MenuType.MAIN);
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Cancel every transfer variant; only plain left clicks can navigate.
        if (event.getClick() != ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        // Bukkit requires opening/closing a different inventory after the click event.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) menus.handleClick(player, holder, slot);
        });
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) menus.forget(player);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { menus.forget(event.getPlayer()); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
}
