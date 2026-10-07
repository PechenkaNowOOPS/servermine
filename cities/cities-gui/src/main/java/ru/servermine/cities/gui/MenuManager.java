package ru.servermine.cities.gui;

import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MenuManager {
    private static final Key GUI_FONT = Key.key("gradostroy", "gui");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");
    private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"));

    private final JavaPlugin plugin;
    private final CitiesService cities;
    private final java.util.Map<java.util.UUID, java.util.UUID> requests = new java.util.HashMap<>();

    public MenuManager(JavaPlugin plugin, CitiesService cities) {
        this.plugin = plugin;
        this.cities = cities;
    }

    public void forget(Player player) { requests.remove(player.getUniqueId()); }

    public void open(Player player, MenuType type) {
        if (!cities.isReady()) {
            player.sendMessage(Component.text("Городские механики ещё не подключены. Книга готова к подключению Cities.", NamedTextColor.YELLOW));
            return;
        }
        java.util.UUID requestId = java.util.UUID.randomUUID();
        requests.put(player.getUniqueId(), requestId);
        cities.cityForPlayer(player.getUniqueId()).whenComplete((city, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !requestId.equals(requests.get(player.getUniqueId()))) return;
                requests.remove(player.getUniqueId());
                if (error != null) {
                    plugin.getLogger().warning("Cannot read city: " + error);
                    player.sendMessage(Component.text("Не удалось загрузить город.", NamedTextColor.RED));
                } else if (city.isEmpty()) {
                    player.sendMessage(Component.text("Вы пока не состоите в городе.", NamedTextColor.YELLOW));
                } else show(player, type, snapshot(city.get()), false);
            });
        });
    }

    public void openPreview(Player player, MenuType type) {
        // Explicit admin preview only; never publishes fixture data through CitiesService.
        requests.remove(player.getUniqueId());
        CitySnapshot city = new CitySnapshot("Камнедолбинск · макет", CityStage.CITY, 17, 24580, 0,
            List.of(new CitySnapshot.ResidentEntry("PechenkaNow", "Правитель"),
                    new CitySnapshot.ResidentEntry("Neronius", "Заместитель"),
                    new CitySnapshot.ResidentEntry("Artemis", "Казначей"),
                    new CitySnapshot.ResidentEntry("Luna", "Строитель"),
                    new CitySnapshot.ResidentEntry("Kray", "Житель"),
                    new CitySnapshot.ResidentEntry("Velmira", "Житель"),
                    new CitySnapshot.ResidentEntry("Witch", "Временный заместитель")));
        show(player, type, city, true);
    }

    private CitySnapshot snapshot(CityView city) {
        return new CitySnapshot(city.name(), city.stage(), city.chunks().size(), city.treasury(), city.revision(),
            city.residents().stream().map(r -> new CitySnapshot.ResidentEntry(r.name(), r.role())).toList());
    }

    private void show(Player player, MenuType type, CitySnapshot city, boolean preview) {
        MenuHolder holder = new MenuHolder(MenuSession.create(player.getUniqueId(), type, city.revision(), preview));
        Inventory inventory = Bukkit.createInventory(holder, 54, title(type, preview));
        holder.bind(inventory);
        render(inventory, type, city);
        if (preview) inventory.setItem(0, button(Material.BARRIER, "Макет интерфейса", "Все данные демонстрационные. Покупки отключены."));
        player.openInventory(inventory);
    }

    private Component title(MenuType type, boolean preview) {
        boolean backgrounds = preview && plugin.getConfig().getBoolean("visual.use-pixel-backgrounds", false);
        if (!backgrounds || type == MenuType.MARKET) {
            return Component.text("Градострой — " + type.fallbackTitle(), NamedTextColor.DARK_GRAY);
        }

        // Если ресурспак установлен, glyph растягивается поверх всего GUI.
        // Если нет — остаётся обычный читаемый текст после неизвестного символа.
        return Component.text(String.valueOf(type.glyph()))
                .font(GUI_FONT)
                .color(NamedTextColor.WHITE)
                .append(Component.text("  Градострой — " + type.fallbackTitle(), NamedTextColor.DARK_GRAY).font(DEFAULT_FONT));
    }

    private void render(Inventory inv, MenuType type, CitySnapshot city) {
        switch (type) {
            case MAIN -> renderMain(inv, city);
            case TERRITORY -> renderTerritory(inv, city);
            case PURCHASE -> renderPurchase(inv, city);
            case UPGRADES -> renderUpgrades(inv, city);
            case TREASURY -> renderTreasury(inv, city);
            case RESIDENTS -> renderResidents(inv, city);
            case MANAGEMENT -> renderManagement(inv, city);
            case DIPLOMACY -> renderDiplomacy(inv, city);
            case MARKET -> { inv.setItem(22, button(Material.CHEST, "Городской рынок", "Модуль подготовлен к разработке.")); inv.setItem(45, back()); }
        }
    }

    private void renderMain(Inventory inv, CitySnapshot c) {
        inv.setItem(4, button(Material.BOOK, c.name(),
                "Этап: " + c.stage().displayName(),
                "Территория: " + c.territory() + " / " + c.territoryLimit(),
                "Казна: " + money(c.treasury()),
                "Жители: " + c.residents().size()));
        inv.setItem(49, button(Material.CHEST, "Рынок", "Торговые места города"));
        inv.setItem(20, button(Material.GRASS_BLOCK, "Территория", "Управление землями города"));
        inv.setItem(24, button(Material.PLAYER_HEAD, "Жители", "Состав города и должности"));
        inv.setItem(29, button(Material.COMPARATOR, "Улучшения", "Дерево развития города"));
        inv.setItem(33, button(Material.WRITABLE_BOOK, "Управление", "Права, законы и развитие"));
        inv.setItem(38, button(Material.GOLD_INGOT, "Казна", "Баланс и история операций"));
        inv.setItem(42, button(Material.BLUE_BANNER, "Дипломатия", "Отношения с другими городами"));
    }

    private void renderTerritory(Inventory inv, CitySnapshot c) {
        inv.setItem(4, button(Material.MAP, "Территория города",
                c.territory() + " / " + c.territoryLimit() + " чанков",
                c.territoryFull() ? "Лимит этапа достигнут" : "Можно расширяться"));
        inv.setItem(22, button(Material.FILLED_MAP, "Карта чанков",
                "MVP: показывает состояние территории.",
                "Полноценная карта соседних чанков будет подключена к Cities."));
        inv.setItem(40, button(c.territoryFull() ? Material.BARRIER : Material.LIME_CONCRETE,
                c.territoryFull() ? "Лимит территории" : "Выбрать соседний чанк",
                c.territoryFull()
                        ? "Для расширения нужен следующий этап развития."
                        : "Открыть подтверждение покупки соседнего чанка."));
        inv.setItem(45, back());
    }

    private void renderPurchase(Inventory inv, CitySnapshot c) {
        int price = chunkPrice();
        inv.setItem(13, button(Material.GRASS_BLOCK, "Соседний чанк",
                "Координаты выбираются территориальным модулем.",
                "Предпросмотр. Покупки ещё не подключены."));
        inv.setItem(22, button(Material.GOLD_INGOT, "Стоимость: " + money(price),
                "Казна: " + money(c.treasury()),
                "После покупки: " + Math.min(c.territory() + 1, c.territoryLimit()) + " / " + c.territoryLimit()));
        inv.setItem(38, button(Material.LIME_CONCRETE, "Покупка недоступна", "Ожидает подключения доменных сервисов"));
        inv.setItem(42, button(Material.RED_CONCRETE, "Отмена", "Вернуться к территории"));
        inv.setItem(45, back());
    }

    private void renderUpgrades(Inventory inv, CitySnapshot c) {
        inv.setItem(13, button(Material.BELL, "Ратуша", "Этап: " + c.stage().displayName()));
        inv.setItem(20, button(Material.MAP, "Территория", "Улучшения территориального управления"));
        inv.setItem(22, button(Material.WRITABLE_BOOK, "Управление", "Должности и административные функции"));
        inv.setItem(24, button(Material.SHIELD, "Защита", "Настройки защиты города"));
        inv.setItem(31, button(Material.GOLD_INGOT, "Экономика", "Улучшения городской экономики"));
        inv.setItem(33, button(Material.LANTERN, "Инфраструктура", "Городские функции и инфраструктура"));
        inv.setItem(45, back());
    }

    private void renderTreasury(Inventory inv, CitySnapshot c) {
        inv.setItem(13, button(Material.GOLD_BLOCK, "Баланс города", money(c.treasury())));
        inv.setItem(29, button(Material.EMERALD, "Пополнить казну", "Подключение к Economy — следующий этап."));
        inv.setItem(38, button(Material.BOOK, "История операций", "Журнал казны ещё не подключён."));
        inv.setItem(42, button(Material.PAPER, "Расходы города", "Просмотр городских расходов."));
        inv.setItem(45, back());
    }

    private void renderResidents(Inventory inv, CitySnapshot c) {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (CitySnapshot.ResidentEntry resident : c.residents()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], button(Material.PLAYER_HEAD, resident.name(), resident.role()));
        }
        inv.setItem(40, button(Material.LIME_DYE, "Пригласить игрока", "Будет связано с системой жителей Cities."));
        inv.setItem(45, back());
    }

    private void renderManagement(Inventory inv, CitySnapshot c) {
        inv.setItem(10, button(Material.BOOK, "Информация о городе", c.name(), "Этап: " + c.stage().displayName()));
        inv.setItem(19, button(Material.MAP, "Настройки территории", "Параметры городских земель"));
        inv.setItem(28, button(Material.TRIPWIRE_HOOK, "Должности и права", "Роли и разрешения жителей"));
        inv.setItem(37, button(Material.GOLD_INGOT, "Налоги и сборы", "Экономическая политика города"));
        inv.setItem(46, button(Material.PAPER, "Законы города", "Локальные правила города"));
        inv.setItem(32, button(Material.BLUE_BANNER, "Внешний вид", "Герб и цвет города"));
        inv.setItem(41, button(Material.EXPERIENCE_BOTTLE, "Развитие города",
                "Текущий этап: " + c.stage().displayName(),
                "Лимит территории: " + c.territoryLimit() + " чанков"));
        inv.setItem(45, back());
    }

    private void renderDiplomacy(Inventory inv, CitySnapshot c) {
        inv.setItem(10, button(Material.LIME_BANNER, "Союзы", "Дружественные города"));
        inv.setItem(19, button(Material.PAPER, "Договоры", "Заключённые соглашения"));
        inv.setItem(28, button(Material.HONEYCOMB, "Отношения", "Текущие отношения"));
        inv.setItem(37, button(Material.BLUE_BANNER, "Вассальные города", "Функция позднего этапа"));
        inv.setItem(40, button(Material.BOOK, "Журнал дипломатии", "История дипломатических событий"));
        inv.setItem(45, back());
    }

    public void handleClick(Player player, MenuHolder holder, int rawSlot) {
        MenuSession session = holder.session();
        if (!session.playerId().equals(player.getUniqueId()) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
        if (session.openedAt().plusSeconds(300).isBefore(java.time.Instant.now())) {
            player.closeInventory();
            return;
        }
        MenuType target = null;
        if (rawSlot == 45) target = MenuType.MAIN;
        else if (session.menuType() == MenuType.MAIN) target = switch (rawSlot) {
            case 20 -> MenuType.TERRITORY; case 24 -> MenuType.RESIDENTS;
            case 29 -> MenuType.UPGRADES; case 33 -> MenuType.MANAGEMENT;
            case 38 -> MenuType.TREASURY; case 42 -> MenuType.DIPLOMACY;
            case 49 -> MenuType.MARKET; default -> null;
        };
        else if (session.menuType() == MenuType.TERRITORY && rawSlot == 40) target = MenuType.PURCHASE;
        else if (session.menuType() == MenuType.PURCHASE && rawSlot == 42) target = MenuType.TERRITORY;
        if (target != null) {
            if (session.preview()) openPreview(player, target); else open(player, target);
        } else {
            player.sendMessage(Component.text("Этот раздел пока доступен только для просмотра.", NamedTextColor.YELLOW));
        }
    }

    private int chunkPrice() {
        return Math.max(0, plugin.getConfig().getInt("territory.chunk-price", 750));
    }

    private static String money(long value) {
        return NUMBER.format(value) + " монет";
    }

    private static ItemStack back() {
        return button(Material.ARROW, "Назад", "Вернуться в главное меню");
    }

    private static ItemStack button(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
