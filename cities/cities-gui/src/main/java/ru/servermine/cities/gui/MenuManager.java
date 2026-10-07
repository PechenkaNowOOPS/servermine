package ru.servermine.cities.gui;

import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.CitiesService;
import ru.servermine.cities.api.CityFoundationCode;
import ru.servermine.cities.api.CityFoundationRequest;
import ru.servermine.cities.api.CityFoundingService;
import ru.servermine.cities.api.CityProgressionService;
import ru.servermine.cities.api.CityTerritoryService;
import ru.servermine.cities.api.CityClaimCode;
import ru.servermine.cities.api.CityClaimRequest;
import ru.servermine.cities.api.CityPromotionCode;
import ru.servermine.cities.api.CityPromotionRequest;
import ru.servermine.cities.api.CityPromotionResult;
import ru.servermine.cities.api.ChunkPosition;
import ru.servermine.cities.api.CityStage;
import ru.servermine.cities.api.CityView;
import ru.servermine.cities.api.CityTreasuryEntry;

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
import java.time.Instant;
import java.util.UUID;

public final class MenuManager {
    private static final Key GUI_FONT = Key.key("gradostroy", "gui");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");
    private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"));

    private final JavaPlugin plugin;
    private final CitiesService cities;
    private final java.util.Map<java.util.UUID, java.util.UUID> requests = new java.util.HashMap<>();
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, PendingFounding> pendingFounding = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, PendingPromotion> pendingPromotions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, PendingClaim> pendingClaims = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, PendingMemberKick> pendingMemberKicks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, java.util.UUID> cityIds = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, CitySnapshot> cityViews = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, List<CityTreasuryEntry>> treasuryHistory = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, List<ChunkPosition>> purchaseTargets = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> purchasePages = new java.util.HashMap<>();

    public MenuManager(JavaPlugin plugin, CitiesService cities) {
        this.plugin = plugin;
        this.cities = cities;
    }

    public void forget(Player player) { requests.remove(player.getUniqueId()); }

    public void clearPlayer(Player player) {
        forget(player);
        pendingFounding.remove(player.getUniqueId());
        pendingPromotions.remove(player.getUniqueId());
        pendingClaims.remove(player.getUniqueId());
        pendingMemberKicks.remove(player.getUniqueId());
        cityIds.remove(player.getUniqueId());
        cityViews.remove(player.getUniqueId());
        treasuryHistory.remove(player.getUniqueId());
        purchaseTargets.remove(player.getUniqueId());
        purchasePages.remove(player.getUniqueId());
    }

    public boolean isAwaitingCityName(java.util.UUID playerId) {
        PendingFounding pending = pendingFounding.get(playerId);
        if (pending == null) return false;
        if (pending.createdAt.plusSeconds(300).isBefore(Instant.now())) {
            pendingFounding.remove(playerId, pending);
            return false;
        }
        return pending.cityName == null;
    }

    public void acceptCityName(Player player, String input) {
        PendingFounding pending = pendingFounding.get(player.getUniqueId());
        if (pending == null || pending.cityName != null) return;
        String name = input.strip();
        if (name.equalsIgnoreCase("отмена") || name.equalsIgnoreCase("cancel")) {
            pendingFounding.remove(player.getUniqueId(), pending);
            player.sendMessage(Component.text("Основание города отменено.", NamedTextColor.YELLOW));
            return;
        }
        int length = name.codePointCount(0, name.length());
        if (length < 3 || length > 32) {
            player.sendMessage(Component.text("Название должно содержать от 3 до 32 символов. Напишите название в чат.", NamedTextColor.RED));
            return;
        }
        PendingFounding named = pending.withName(name);
        pendingFounding.put(player.getUniqueId(), named);
        showFounding(player, MenuType.FOUNDING_CONFIRM, named);
    }

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
                    showFounding(player, MenuType.FOUNDING, null);
                } else if (type == MenuType.TREASURY) {
                    openTreasury(player, city.get());
                } else show(player, type, snapshot(city.get()), false, city.get().id());
            });
        });
    }

    public void openPreview(Player player, MenuType type) {
        // Explicit admin preview only; never publishes fixture data through CitiesService.
        requests.remove(player.getUniqueId());
        CitySnapshot city = new CitySnapshot("Камнедолбинск · макет", CityStage.CITY, 17, 24580, 0,
            List.of(new CitySnapshot.ResidentEntry(UUID.randomUUID(), "PechenkaNow", "Правитель"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Neronius", "Заместитель"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Artemis", "Казначей"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Luna", "Строитель"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Kray", "Житель"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Velmira", "Житель"),
                    new CitySnapshot.ResidentEntry(UUID.randomUUID(), "Witch", "Временный заместитель")));
        cityIds.remove(player.getUniqueId());
        cityViews.remove(player.getUniqueId());
        treasuryHistory.remove(player.getUniqueId());
        purchaseTargets.remove(player.getUniqueId());
        purchasePages.remove(player.getUniqueId());
        show(player, type, city, true, null);
    }

    private void openTreasury(Player player, CityView city) {
        var treasury = cities.treasuryService().orElse(null);
        if (treasury == null || !treasury.isReady()) {
            treasuryHistory.put(player.getUniqueId(), List.of());
            show(player, MenuType.TREASURY, snapshot(city), false, city.id());
            player.sendMessage(Component.text("История казны временно недоступна.", NamedTextColor.YELLOW));
            return;
        }
        UUID requestId = UUID.randomUUID();
        requests.put(player.getUniqueId(), requestId);
        treasury.history(player.getUniqueId(), 5).whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !requestId.equals(requests.get(player.getUniqueId()))) return;
                requests.remove(player.getUniqueId());
                treasuryHistory.put(player.getUniqueId(), error == null && entries != null ? entries : List.of());
                show(player, MenuType.TREASURY, snapshot(city), false, city.id());
                if (error != null) plugin.getLogger().warning("Cannot load treasury history: " + error);
            });
        });
    }

    private CitySnapshot snapshot(CityView city) {
        return new CitySnapshot(city.name(), city.stage(), city.chunks().size(), city.treasury(), city.revision(),
            city.residents().stream().map(r -> new CitySnapshot.ResidentEntry(r.playerId(), r.name(), roleName(r.role()))).toList());
    }

    private String roleName(String role) {
        return switch (role) {
            case "RULER" -> "Правитель";
            case "RESIDENT" -> "Житель";
            default -> role;
        };
    }

    private void show(Player player, MenuType type, CitySnapshot city, boolean preview, java.util.UUID cityId) {
        if (cityId != null) {
            cityIds.put(player.getUniqueId(), cityId);
            cityViews.put(player.getUniqueId(), city);
        }
        MenuHolder holder = new MenuHolder(MenuSession.create(player.getUniqueId(), type, city.revision(), preview));
        Inventory inventory = Bukkit.createInventory(holder, 54, title(type, preview));
        holder.bind(inventory);
        render(inventory, type, city, player.getUniqueId(), preview);
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

    private void render(Inventory inv, MenuType type, CitySnapshot city, UUID playerId, boolean preview) {
        switch (type) {
            case MAIN -> renderMain(inv, city);
            case TERRITORY -> renderTerritory(inv, city);
            case PURCHASE -> renderPurchase(inv, city, playerId, preview);
            case PURCHASE_CONFIRM -> renderClaimConfirm(inv, city, playerId, preview);
            case UPGRADES -> renderUpgrades(inv, city);
            case TREASURY -> renderTreasury(inv, city, playerId);
            case RESIDENTS -> renderResidents(inv, city);
            case MEMBER_KICK_CONFIRM -> renderMemberKickConfirm(inv, playerId, preview);
            case MANAGEMENT -> renderManagement(inv, city);
            case DIPLOMACY -> renderDiplomacy(inv, city);
            case MARKET -> { inv.setItem(22, button(Material.CHEST, "Городской рынок", "Модуль подготовлен к разработке.")); inv.setItem(45, back()); }
            case FOUNDING, FOUNDING_CONFIRM -> { }
            case PROGRESSION -> renderProgression(inv, city, false);
            case PROMOTION_CONFIRM -> renderProgression(inv, city, true);
        }
    }

    private void renderProgression(Inventory inv, CitySnapshot city, boolean confirm) {
        CityProgressionService service = cities.progressionService().orElse(null);
        java.util.Optional<CityStage> next = service == null ? java.util.Optional.empty() : service.nextStage(city.stage());
        if (next.isEmpty()) {
            inv.setItem(13, button(Material.BOOK, "Развитие города", "Переход для этапа " + city.stage().displayName() + " не настроен."));
            inv.setItem(45, back());
            return;
        }
        long price = service.promotionPrice(city.stage());
        int minResidents = service.minimumResidents(city.stage());
        int minChunks = service.minimumChunks(city.stage());
        if (confirm) {
            inv.setItem(13, button(Material.EXPERIENCE_BOTTLE, "Переход в «" + next.get().displayName() + "»",
                    "Текущий этап: " + city.stage().displayName(), "Стоимость: " + money(price),
                    "Жителей: " + city.residents().size() + " / " + minResidents,
                    "Чанков: " + city.territory() + " / " + minChunks,
                    "Ревизия города: " + city.revision()));
            inv.setItem(29, button(Material.LIME_CONCRETE, "Подтвердить переход", "Стоимость спишется не более одного раза."));
            inv.setItem(33, button(Material.RED_CONCRETE, "Отмена", "Вернуться к развитию города."));
            return;
        }
        boolean eligible = city.residents().size() >= minResidents && city.territory() >= minChunks;
        inv.setItem(13, button(Material.BOOK, "Развитие: " + city.name(),
                "Этап: " + city.stage().displayName() + " → " + next.get().displayName(),
                "Жители: " + city.residents().size() + " / " + minResidents,
                "Территория: " + city.territory() + " / " + minChunks + " чанков",
                "Стоимость: " + money(price)));
        inv.setItem(31, button(eligible ? Material.LIME_CONCRETE : Material.BARRIER,
                eligible ? "Перейти в «" + next.get().displayName() + "»" : "Требования не выполнены",
                "Открыть подтверждение."));
        inv.setItem(45, back());
    }

    private void showFounding(Player player, MenuType type, PendingFounding pending) {
        MenuHolder holder = new MenuHolder(MenuSession.create(player.getUniqueId(), type, 0, false));
        Inventory inventory = Bukkit.createInventory(holder, 45, Component.text("Градострой — " + type.fallbackTitle(), NamedTextColor.DARK_GRAY));
        holder.bind(inventory);
        if (type == MenuType.FOUNDING) {
            boolean available = cities.foundingService().filter(CityFoundingService::isReady).isPresent();
            inventory.setItem(13, button(Material.BOOK, "Основание города",
                    "Город получит 4 соседних чанка 2×2.", "Опорным будет чанк под вами.", "",
                    available ? "Создание бесплатное." : "Сервис основания сейчас недоступен."));
            inventory.setItem(31, button(available ? Material.LIME_CONCRETE : Material.BARRIER,
                    available ? "Начать основание" : "Недоступно", "Выберите название города в чате."));
        } else {
            inventory.setItem(13, button(Material.GRASS_BLOCK, pending.cityName,
                    "Мир: " + pending.anchor.worldId(), "Опорный чанк: X " + pending.anchor.x() + ", Z " + pending.anchor.z(),
                    "Будут закреплены 4 чанка в квадрате 2×2.", "Стоимость: бесплатно"));
            inventory.setItem(29, button(Material.LIME_CONCRETE, "Основать город", "Подтвердить создание и занять стартовые чанки."));
            inventory.setItem(33, button(Material.RED_CONCRETE, "Отмена", "Создание города не начнётся."));
        }
        player.openInventory(inventory);
    }

    private void beginFounding(Player player) {
        CityFoundingService service = cities.foundingService().orElse(null);
        if (service == null || !service.isReady()) {
            player.sendMessage(Component.text("Сервис основания города сейчас недоступен.", NamedTextColor.RED));
            return;
        }
        ChunkPosition anchor = new ChunkPosition(player.getWorld().getUID(), player.getLocation().getChunk().getX(),
                player.getLocation().getChunk().getZ());
        pendingFounding.put(player.getUniqueId(), new PendingFounding(java.util.UUID.randomUUID(), anchor, null, Instant.now()));
        player.closeInventory();
        player.sendMessage(Component.text("Напишите название города в чат (3–32 символа). Ваш стартовый участок будет 2×2 чанка вокруг текущего.", NamedTextColor.YELLOW));
    }

    private void confirmFounding(Player player) {
        PendingFounding pending = pendingFounding.remove(player.getUniqueId());
        if (pending == null || pending.cityName == null) return;
        CityFoundingService service = cities.foundingService().orElse(null);
        if (service == null || !service.isReady()) {
            player.sendMessage(Component.text("Сервис основания города сейчас недоступен.", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        player.sendMessage(Component.text("Проверяю участок и создаю город…", NamedTextColor.YELLOW));
        CityFoundationRequest request = new CityFoundationRequest(pending.operationId, player.getUniqueId(),
                player.getName(), pending.cityName, pending.anchor);
        service.found(request).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    player.sendMessage(Component.text("Не удалось создать город. Попробуйте начать заново.", NamedTextColor.RED));
                    plugin.getLogger().warning("City founding failed for operation " + pending.operationId + ": " + error);
                } else if (result.code() == CityFoundationCode.CREATED || result.code() == CityFoundationCode.REPLAYED) {
                    player.sendMessage(Component.text("Город «" + result.city().orElseThrow().name() + "» основан!", NamedTextColor.GREEN));
                    open(player, MenuType.MAIN);
                } else {
                    player.sendMessage(Component.text(foundingMessage(result.code()), NamedTextColor.RED));
                    open(player, MenuType.FOUNDING);
                }
            });
        });
    }

    private String foundingMessage(CityFoundationCode code) {
        return switch (code) {
            case INVALID_NAME -> "Название города не подходит. Используйте 3–32 буквы, цифры, пробел, _ или - .";
            case ALREADY_IN_CITY -> "Вы уже состоите в городе.";
            case NAME_ALREADY_USED -> "Город с таким названием уже существует.";
            case CHUNK_ALREADY_CLAIMED -> "Часть стартовой территории уже занята.";
            case WORLD_UNAVAILABLE -> "Мир для основания сейчас недоступен.";
            case PROTECTED_ZONE -> "Стартовая территория находится в защищённой зоне или за границей мира.";
            case OPERATION_CONFLICT -> "Запрос основания конфликтует с предыдущей операцией.";
            case SERVICE_UNAVAILABLE -> "Сервис основания города сейчас недоступен.";
            case INTERNAL_ERROR -> "Не удалось создать город из-за внутренней ошибки.";
            case CREATED, REPLAYED -> "Город создан.";
        };
    }

    private void beginPromotion(Player player, MenuSession session) {
        CityProgressionService progression = cities.progressionService().orElse(null);
        java.util.UUID cityId = cityIds.get(player.getUniqueId());
        CitySnapshot city = cityViews.get(player.getUniqueId());
        if (progression == null || !progression.isReady() || cityId == null || city == null) {
            player.sendMessage(Component.text("Развитие города сейчас недоступно.", NamedTextColor.RED));
            return;
        }
        if (progression.nextStage(city.stage()).isEmpty()) {
            player.sendMessage(Component.text("Для этого этапа переход пока не настроен.", NamedTextColor.RED));
            return;
        }
        if (city.residents().size() < progression.minimumResidents(city.stage())
                || city.territory() < progression.minimumChunks(city.stage())) {
            player.sendMessage(Component.text("Город пока не выполняет требования для перехода.", NamedTextColor.RED));
            return;
        }
        pendingPromotions.put(player.getUniqueId(), new PendingPromotion(UUID.randomUUID(), cityId,
                city.stage(), session.cityRevision()));
        open(player, MenuType.PROMOTION_CONFIRM);
    }

    private void confirmPromotion(Player player) {
        PendingPromotion pending = pendingPromotions.remove(player.getUniqueId());
        CityProgressionService progression = cities.progressionService().orElse(null);
        if (pending == null || progression == null || !progression.isReady()) {
            player.sendMessage(Component.text("Развитие города сейчас недоступно.", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        player.sendMessage(Component.text("Проверяю требования и выполняю переход…", NamedTextColor.YELLOW));
        CityPromotionRequest request = new CityPromotionRequest(pending.operationId, pending.cityId,
                player.getUniqueId(), pending.expectedStage, pending.revision);
        progression.promote(request).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    player.sendMessage(Component.text("Не удалось подтвердить результат перехода. Не повторяйте оплату; обратитесь к администратору.", NamedTextColor.RED));
                    plugin.getLogger().warning("City promotion failed for operation " + pending.operationId + ": " + error);
                } else if (result.code() == CityPromotionCode.PROMOTED || result.code() == CityPromotionCode.REPLAYED) {
                    player.sendMessage(Component.text("Город перешёл на этап «" + result.city().orElseThrow().stage().displayName() + "»!", NamedTextColor.GREEN));
                    open(player, MenuType.MAIN);
                } else {
                    player.sendMessage(Component.text(promotionMessage(result.code()), NamedTextColor.RED));
                    open(player, MenuType.PROGRESSION);
                }
            });
        });
    }

    private String promotionMessage(CityPromotionCode code) {
        return switch (code) {
            case NOT_FOUND -> "Город не найден.";
            case NOT_MEMBER -> "Вы не состоите в этом городе.";
            case PERMISSION_DENIED -> "Повышать этап может только правитель города.";
            case INVALID_STAGE -> "Такой переход этапа сейчас недоступен.";
            case STALE_REVISION -> "Состояние города изменилось. Откройте книгу заново.";
            case REQUIREMENTS_NOT_MET -> "Город пока не выполняет требования для перехода.";
            case INSUFFICIENT_FUNDS -> "Недостаточно монет для перехода.";
            case INSUFFICIENT_TREASURY -> "В казне города недостаточно монет.";
            case ECONOMY_UNAVAILABLE -> "Экономика сейчас недоступна.";
            case OPERATION_CONFLICT -> "Операция конфликтует с предыдущим запросом.";
            case OPERATION_IN_PROGRESS, UNKNOWN_OUTCOME -> "Операция ещё не подтверждена. Не повторяйте оплату; обратитесь к администратору.";
            case SERVICE_UNAVAILABLE -> "Сервис развития города сейчас недоступен.";
            case INTERNAL_ERROR -> "Не удалось выполнить переход из-за внутренней ошибки.";
            case PROMOTED, REPLAYED -> "Переход выполнен.";
        };
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

    private void renderPurchase(Inventory inv, CitySnapshot c, UUID playerId, boolean preview) {
        CityTerritoryService service = cities.territoryService().orElse(null);
        long price = service == null ? chunkPrice() : service.chunkPrice();
        inv.setItem(4, button(Material.GOLD_INGOT, "Цена за чанк: " + money(price),
                "Город: " + c.territory() + " / " + c.territoryLimit() + " чанков"));
        List<ChunkPosition> targets = preview ? List.of() : purchaseTargets.getOrDefault(playerId, List.of());
        int page = purchasePages.getOrDefault(playerId, 0);
        int start = page * purchaseSlots().length;
        int end = Math.min(targets.size(), start + purchaseSlots().length);
        int[] slots = purchaseSlots();
        for (int index = start; index < end; index++) {
            ChunkPosition target = targets.get(index);
            org.bukkit.World world = Bukkit.getWorld(target.worldId());
            String worldName = world == null ? target.worldId().toString().substring(0, 8) : world.getName();
            inv.setItem(slots[index - start], button(Material.GRASS_BLOCK,
                    "Чанк X " + target.x() + ", Z " + target.z(), worldName,
                    "Свободен и соседствует с городом.", "Нажмите, чтобы проверить и подтвердить покупку."));
        }
        if (targets.isEmpty()) {
            inv.setItem(22, button(Material.BARRIER, "Нет доступных чанков",
                    "Проверьте этап, лимит территории, границу мира и системные зоны."));
        }
        if (page > 0) inv.setItem(48, button(Material.ARROW, "Предыдущая страница"));
        if (end < targets.size()) inv.setItem(50, button(Material.ARROW, "Следующая страница"));
        inv.setItem(45, button(Material.ARROW, "Назад", "Вернуться к территории"));
    }

    private void renderClaimConfirm(Inventory inv, CitySnapshot city, UUID playerId, boolean preview) {
        PendingClaim pending = preview ? null : pendingClaims.get(playerId);
        if (pending == null) {
            inv.setItem(22, button(Material.BARRIER, "Покупка недоступна", "Выберите чанк на предыдущем экране."));
            inv.setItem(45, back());
            return;
        }
        CityTerritoryService service = cities.territoryService().orElse(null);
        long price = service == null ? chunkPrice() : service.chunkPrice();
        org.bukkit.World world = Bukkit.getWorld(pending.target.worldId());
        String worldName = world == null ? pending.target.worldId().toString() : world.getName();
        inv.setItem(13, button(Material.GRASS_BLOCK, "Покупка соседнего чанка",
                "Мир: " + worldName, "Координаты: X " + pending.target.x() + ", Z " + pending.target.z(),
                "Текущая территория: " + city.territory() + " / " + city.territoryLimit(),
                "После покупки: " + (city.territory() + 1) + " / " + city.territoryLimit(),
                "Стоимость: " + money(price), "Казна города: " + money(city.treasury()),
                "Оплата: из казны города."));
        inv.setItem(29, button(Material.LIME_CONCRETE, "Подтвердить покупку", "Средства спишутся из казны города."));
        inv.setItem(33, button(Material.RED_CONCRETE, "Отмена", "Вернуться к выбору чанка."));
        inv.setItem(45, button(Material.ARROW, "Назад", "Вернуться к выбору чанка."));
    }

    private int[] purchaseSlots() {
        return new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
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

    private void renderTreasury(Inventory inv, CitySnapshot c, UUID playerId) {
        inv.setItem(13, button(Material.GOLD_BLOCK, "Баланс города", money(c.treasury())));
        List<CityTreasuryEntry> entries = treasuryHistory.getOrDefault(playerId, List.of());
        int[] slots = {19, 20, 21, 22, 23};
        for (int i = 0; i < Math.min(slots.length, entries.size()); i++) {
            CityTreasuryEntry entry = entries.get(i);
            String title = entry.amount() > 0 ? "+" + money(entry.amount()) : "−" + money(Math.abs(entry.amount()));
            inv.setItem(slots[i], button(entry.amount() > 0 ? Material.EMERALD : Material.GOLD_NUGGET,
                    title, treasuryReason(entry.reason()), "Баланс после: " + money(entry.balanceAfter()),
                    entry.occurredAt().atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))));
        }
        if (entries.isEmpty()) inv.setItem(21, button(Material.BOOK, "История операций", "Операций пока нет."));
        inv.setItem(29, button(Material.EMERALD, "Пополнить казну", "Команда: /smcities treasury deposit <сумма>"));
        inv.setItem(38, button(Material.BOOK, "История операций", "Показать последние записи: /smcities treasury history"));
        inv.setItem(45, back());
    }

    private String treasuryReason(String reason) {
        if (reason.equals("DEPOSIT")) return "Пополнение казны";
        if (reason.startsWith("CHUNK_CLAIM:")) return "Покупка городского чанка";
        if (reason.startsWith("STAGE_PROMOTION:")) return "Развитие города";
        return reason;
    }

    private void renderResidents(Inventory inv, CitySnapshot c) {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (CitySnapshot.ResidentEntry resident : c.residents()) {
            if (i >= slots.length) break;
            inv.setItem(slots[i++], button(Material.PLAYER_HEAD, resident.name(), resident.role(), "ЛКМ: управление участником"));
        }
        inv.setItem(40, button(Material.LIME_DYE, "Пригласить игрока", "Команда: /smcities invite <игрок>"));
        inv.setItem(41, button(Material.PAPER, "Принять приглашение", "Команда: /smcities accept"));
        inv.setItem(45, back());
    }

    private void renderMemberKickConfirm(Inventory inv, UUID playerId, boolean preview) {
        PendingMemberKick pending = pendingMemberKicks.get(playerId);
        String name = pending == null ? "Участник" : pending.targetName;
        inv.setItem(13, button(Material.PLAYER_HEAD, name, "Исключить из города?"));
        inv.setItem(29, button(Material.REDSTONE_BLOCK, "Исключить", "Участник потеряет доступ к территории."));
        inv.setItem(33, button(Material.BARRIER, "Отмена", "Вернуться к составу города."));
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
        if (rawSlot == 45) target = switch (session.menuType()) {
            case PURCHASE -> MenuType.TERRITORY;
            case PURCHASE_CONFIRM -> MenuType.PURCHASE;
            default -> MenuType.MAIN;
        };
        else if (session.menuType() == MenuType.MAIN) target = switch (rawSlot) {
            case 20 -> MenuType.TERRITORY; case 24 -> MenuType.RESIDENTS;
            case 29 -> MenuType.UPGRADES; case 33 -> MenuType.MANAGEMENT;
            case 38 -> MenuType.TREASURY; case 42 -> MenuType.DIPLOMACY;
            case 49 -> MenuType.MARKET; default -> null;
        };
        else if (session.menuType() == MenuType.TERRITORY && rawSlot == 40) {
            if (session.preview()) openPreview(player, MenuType.PURCHASE); else openPurchaseTargets(player);
            return;
        }
        else if (session.menuType() == MenuType.PURCHASE && rawSlot == 42) target = MenuType.TERRITORY;
        else if (session.menuType() == MenuType.MANAGEMENT && rawSlot == 41) target = MenuType.PROGRESSION;
        else if (session.menuType() == MenuType.RESIDENTS && residentSlot(rawSlot)) {
            beginMemberKick(player, session, rawSlot);
            return;
        } else if (session.menuType() == MenuType.MEMBER_KICK_CONFIRM && rawSlot == 29) {
            confirmMemberKick(player);
            return;
        } else if (session.menuType() == MenuType.MEMBER_KICK_CONFIRM && rawSlot == 33) {
            pendingMemberKicks.remove(player.getUniqueId());
            open(player, MenuType.RESIDENTS);
            return;
        }
        if (target != null) {
            if (session.preview()) openPreview(player, target); else open(player, target);
        } else if (session.menuType() == MenuType.FOUNDING && rawSlot == 31) {
            beginFounding(player);
        } else if (session.menuType() == MenuType.FOUNDING_CONFIRM && rawSlot == 29) {
            confirmFounding(player);
        } else if (session.menuType() == MenuType.FOUNDING_CONFIRM && rawSlot == 33) {
            pendingFounding.remove(player.getUniqueId());
            showFounding(player, MenuType.FOUNDING, null);
        } else if (session.menuType() == MenuType.PROGRESSION && rawSlot == 31) {
            beginPromotion(player, session);
        } else if (session.menuType() == MenuType.PROMOTION_CONFIRM && rawSlot == 29) {
            confirmPromotion(player);
        } else if (session.menuType() == MenuType.PROMOTION_CONFIRM && rawSlot == 33) {
            pendingPromotions.remove(player.getUniqueId());
            open(player, MenuType.PROGRESSION);
        } else if (session.menuType() == MenuType.PURCHASE && (rawSlot == 48 || rawSlot == 50)) {
            int page = purchasePages.getOrDefault(player.getUniqueId(), 0);
            int maximum = Math.max(0, (purchaseTargets.getOrDefault(player.getUniqueId(), List.of()).size() - 1)
                    / purchaseSlots().length);
            purchasePages.put(player.getUniqueId(), Math.max(0, Math.min(maximum, page + (rawSlot == 50 ? 1 : -1))));
            open(player, MenuType.PURCHASE);
        } else if (session.menuType() == MenuType.PURCHASE && candidateSlot(rawSlot)) {
            beginClaim(player, session, rawSlot);
        } else if (session.menuType() == MenuType.PURCHASE_CONFIRM && rawSlot == 29) {
            confirmClaim(player);
        } else if (session.menuType() == MenuType.PURCHASE_CONFIRM && rawSlot == 33) {
            pendingClaims.remove(player.getUniqueId());
            open(player, MenuType.PURCHASE);
        } else if (session.menuType() == MenuType.RESIDENTS && rawSlot == 40) {
            player.sendMessage(Component.text("Пригласить онлайн-игрока: /smcities invite <игрок>", NamedTextColor.YELLOW));
        } else if (session.menuType() == MenuType.RESIDENTS && rawSlot == 41) {
            player.sendMessage(Component.text("Принять приглашение: /smcities accept", NamedTextColor.YELLOW));
        } else if (session.menuType() == MenuType.TREASURY && rawSlot == 29) {
            player.sendMessage(Component.text("Пополнить казну: /smcities treasury deposit <сумма>", NamedTextColor.YELLOW));
        } else if (session.menuType() == MenuType.TREASURY && rawSlot == 38) {
            List<CityTreasuryEntry> entries = treasuryHistory.getOrDefault(player.getUniqueId(), List.of());
            if (entries.isEmpty()) player.sendMessage(Component.text("Операций в казне пока нет.", NamedTextColor.YELLOW));
            else entries.forEach(entry -> player.sendMessage(Component.text(
                    (entry.amount() > 0 ? "+" : "−") + money(Math.abs(entry.amount()))
                            + " · " + treasuryReason(entry.reason()) + " · баланс " + money(entry.balanceAfter()),
                    entry.amount() > 0 ? NamedTextColor.GREEN : NamedTextColor.GOLD)));
        } else {
            player.sendMessage(Component.text("Этот раздел пока доступен только для просмотра.", NamedTextColor.YELLOW));
        }
    }

    private boolean residentSlot(int rawSlot) {
        return rawSlot == 10 || rawSlot == 11 || rawSlot == 12 || rawSlot == 13 || rawSlot == 14
                || rawSlot == 15 || rawSlot == 16 || rawSlot == 19 || rawSlot == 20 || rawSlot == 21
                || rawSlot == 22 || rawSlot == 23 || rawSlot == 24 || rawSlot == 25;
    }

    private void beginMemberKick(Player player, MenuSession session, int rawSlot) {
        CitySnapshot current = cityViews.get(player.getUniqueId());
        if (session.preview() || current == null) {
            player.sendMessage(Component.text("Управление составом недоступно в макете.", NamedTextColor.YELLOW));
            return;
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int index = -1;
        for (int i = 0; i < slots.length; i++) if (slots[i] == rawSlot) { index = i; break; }
        if (index < 0 || index >= current.residents().size()) return;
        CitySnapshot.ResidentEntry target = current.residents().get(index);
        if (target.playerId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Чтобы покинуть город, используйте /smcities leave.", NamedTextColor.YELLOW));
            return;
        }
        if (target.role().equals("Правитель")) {
            player.sendMessage(Component.text("Нельзя исключить правителя города.", NamedTextColor.RED));
            return;
        }
        pendingMemberKicks.put(player.getUniqueId(), new PendingMemberKick(target.playerId(), target.name(), session.cityRevision()));
        open(player, MenuType.MEMBER_KICK_CONFIRM);
    }

    private void confirmMemberKick(Player player) {
        PendingMemberKick pending = pendingMemberKicks.remove(player.getUniqueId());
        var membership = cities.membershipService().orElse(null);
        if (pending == null || membership == null || !membership.isReady()) {
            player.sendMessage(Component.text("Управление составом сейчас недоступно.", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        membership.kick(player.getUniqueId(), pending.targetId, pending.revision).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null || result == null) {
                    player.sendMessage(Component.text("Не удалось исключить участника.", NamedTextColor.RED));
                    return;
                }
                switch (result.code()) {
                    case KICKED -> {
                        player.sendMessage(Component.text(pending.targetName + " исключён из города.", NamedTextColor.GREEN));
                        Player target = Bukkit.getPlayer(pending.targetId);
                        if (target != null) target.sendMessage(Component.text("Вас исключили из города "
                                + result.city().map(CityView::name).orElse("") + ".", NamedTextColor.RED));
                        open(player, MenuType.RESIDENTS);
                    }
                    case PERMISSION_DENIED -> player.sendMessage(Component.text("Исключать участников может только правитель.", NamedTextColor.RED));
                    case STALE_CITY -> player.sendMessage(Component.text("Состав изменился. Откройте список жителей и попробуйте снова.", NamedTextColor.YELLOW));
                    case TARGET_NOT_IN_CITY -> player.sendMessage(Component.text("Этот игрок больше не состоит в городе.", NamedTextColor.YELLOW));
                    case CANNOT_KICK_RULER -> player.sendMessage(Component.text("Нельзя исключить правителя города.", NamedTextColor.RED));
                    case NO_CITY -> player.sendMessage(Component.text("Вы не состоите в городе.", NamedTextColor.RED));
                    default -> player.sendMessage(Component.text("Не удалось исключить участника.", NamedTextColor.RED));
                }
            });
        });
    }

    private void openPurchaseTargets(Player player) {
        CityTerritoryService service = cities.territoryService().orElse(null);
        UUID cityId = cityIds.get(player.getUniqueId());
        if (service == null || !service.isReady() || cityId == null) {
            player.sendMessage(Component.text("Покупка территории сейчас недоступна.", NamedTextColor.RED));
            return;
        }
        UUID requestId = UUID.randomUUID();
        requests.put(player.getUniqueId(), requestId);
        player.sendMessage(Component.text("Ищу свободные соседние чанки…", NamedTextColor.YELLOW));
        service.availableTargets(cityId).whenComplete((targets, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !requestId.equals(requests.get(player.getUniqueId()))) return;
                requests.remove(player.getUniqueId());
                if (error != null) {
                    plugin.getLogger().warning("Cannot list city claim targets: " + error);
                    player.sendMessage(Component.text("Не удалось загрузить соседние чанки.", NamedTextColor.RED));
                    return;
                }
                purchaseTargets.put(player.getUniqueId(), targets);
                purchasePages.put(player.getUniqueId(), 0);
                open(player, MenuType.PURCHASE);
            });
        });
    }

    private boolean candidateSlot(int rawSlot) {
        for (int slot : purchaseSlots()) if (slot == rawSlot) return true;
        return false;
    }

    private void beginClaim(Player player, MenuSession session, int rawSlot) {
        List<ChunkPosition> targets = purchaseTargets.getOrDefault(player.getUniqueId(), List.of());
        int index = purchasePages.getOrDefault(player.getUniqueId(), 0) * purchaseSlots().length;
        int[] slots = purchaseSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot) { index += i; break; }
        }
        if (index >= targets.size()) return;
        UUID cityId = cityIds.get(player.getUniqueId());
        if (cityId == null) {
            player.sendMessage(Component.text("Город не найден. Откройте книгу заново.", NamedTextColor.RED));
            return;
        }
        pendingClaims.put(player.getUniqueId(), new PendingClaim(UUID.randomUUID(), cityId, targets.get(index), session.cityRevision()));
        open(player, MenuType.PURCHASE_CONFIRM);
    }

    private void confirmClaim(Player player) {
        PendingClaim pending = pendingClaims.remove(player.getUniqueId());
        CityTerritoryService service = cities.territoryService().orElse(null);
        if (pending == null || service == null || !service.isReady()) {
            player.sendMessage(Component.text("Покупка территории сейчас недоступна.", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        player.sendMessage(Component.text("Проверяю участок и резервирую оплату…", NamedTextColor.YELLOW));
        CityClaimRequest request = new CityClaimRequest(pending.operationId, pending.cityId,
                player.getUniqueId(), pending.revision, pending.target);
        service.purchase(request).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    player.sendMessage(Component.text("Не удалось подтвердить результат покупки. Не повторяйте оплату; обратитесь к администратору.", NamedTextColor.RED));
                    plugin.getLogger().warning("City chunk claim failed for operation " + pending.operationId + ": " + error);
                } else if (result.code() == CityClaimCode.CLAIMED || result.code() == CityClaimCode.REPLAYED) {
                    String paid = service.chunkPrice() == 0 ? "Бесплатно." : "Из казны списано: " + money(service.chunkPrice()) + ".";
                    player.sendMessage(Component.text("Чанк закреплён за городом. " + paid, NamedTextColor.GREEN));
                    open(player, MenuType.TERRITORY);
                } else {
                    player.sendMessage(Component.text(claimMessage(result.code()), NamedTextColor.RED));
                    open(player, MenuType.TERRITORY);
                }
            });
        });
    }

    private String claimMessage(CityClaimCode code) {
        return switch (code) {
            case NOT_FOUND -> "Город не найден.";
            case NOT_MEMBER -> "Вы не состоите в этом городе.";
            case PERMISSION_DENIED -> "Покупать территорию может только правитель города.";
            case PROMOTION_REQUIRED -> "Сначала повысьте этап города до Деревни.";
            case STALE_REVISION -> "Город изменился. Откройте книгу и выберите чанк заново.";
            case CAP_REACHED -> "Лимит территории этого этапа достигнут.";
            case ALREADY_OWNED -> "Этот чанк уже принадлежит вашему городу.";
            case TARGET_UNAVAILABLE -> "Чанк недоступен или уже занят другим городом.";
            case PROTECTED_ZONE -> "Этот чанк находится в защищённой зоне.";
            case DISCONNECTED -> "Выберите чанк рядом с существующей территорией города.";
            case INSUFFICIENT_FUNDS -> "Недостаточно монет.";
            case INSUFFICIENT_TREASURY -> "В казне города недостаточно монет. Пополните её через меню казны.";
            case ECONOMY_UNAVAILABLE -> "Экономика сейчас недоступна.";
            case OPERATION_CONFLICT -> "Операция конфликтует с предыдущим запросом.";
            case OPERATION_IN_PROGRESS, UNKNOWN_OUTCOME -> "Покупка ещё не подтверждена. Не повторяйте оплату; обратитесь к администратору.";
            case SERVICE_UNAVAILABLE -> "Сервис территории сейчас недоступен.";
            case INTERNAL_ERROR -> "Не удалось купить чанк из-за внутренней ошибки.";
            case CLAIMED, REPLAYED -> "Чанк куплен.";
        };
    }

    private long chunkPrice() {
        return Math.max(0, plugin.getConfig().getLong("territory.chunk-price", 750));
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

    private record PendingFounding(java.util.UUID operationId, ChunkPosition anchor, String cityName, Instant createdAt) {
        PendingFounding withName(String name) { return new PendingFounding(operationId, anchor, name, createdAt); }
    }

    private record PendingPromotion(UUID operationId, UUID cityId, CityStage expectedStage, long revision) { }

    private record PendingClaim(UUID operationId, UUID cityId, ChunkPosition target, long revision) { }
    private record PendingMemberKick(UUID targetId, String targetName, long revision) { }
}
