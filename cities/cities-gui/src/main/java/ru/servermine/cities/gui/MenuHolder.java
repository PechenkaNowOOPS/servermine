package ru.servermine.cities.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MenuHolder implements InventoryHolder {
    private final MenuSession session;
    private Inventory inventory;

    public MenuHolder(MenuSession session) {
        this.session = session;
    }

    public MenuSession session() {
        return session;
    }

    public void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory is not bound yet");
        return inventory;
    }
}
