package ru.servermine.gradostroygui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

public final class CityBookService {
    private final GradostroyGuiPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey instanceKey;

    public CityBookService(GradostroyGuiPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "system_item");
        this.instanceKey = new NamespacedKey(plugin, "book_instance");
    }

    public ItemStack createBook() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();

        meta.displayName(Component.text(plugin.getConfig().getString("book.name", "Книга управления городом"), NamedTextColor.GOLD));
        meta.lore(plugin.getConfig().getStringList("book.lore").stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY))
                .toList());
        meta.setTitle("Книга управления городом");
        meta.setAuthor("Server Mine");
        meta.addPages("ПКМ по книге открывает интерфейс управления городом.");
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "city_management_book");
        meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCityBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) return false;
        String type = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return "city_management_book".equals(type);
    }
}
