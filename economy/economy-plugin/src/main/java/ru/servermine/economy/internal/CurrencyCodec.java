package ru.servermine.economy.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

final class CurrencyCodec {
    record Inspection(boolean authentic, Denomination denomination, String reason) {}

    private final CurrencyRegistry registry;
    private final CurrencySigner signer;
    private final NamespacedKey typeKey;
    private final NamespacedKey denominationKey;
    private final NamespacedKey schemaKey;
    private final NamespacedKey signatureKey;

    CurrencyCodec(ServerMineEconomyPlugin plugin, CurrencyRegistry registry, CurrencySigner signer) {
        this.registry = registry;
        this.signer = signer;
        this.typeKey = new NamespacedKey(plugin, "currency_type");
        this.denominationKey = new NamespacedKey(plugin, "denomination");
        this.schemaKey = new NamespacedKey(plugin, "schema_version");
        this.signatureKey = new NamespacedKey(plugin, "signature");
    }

    ItemStack create(Denomination denomination, int amount) {
        ItemStack item = new ItemStack(denomination.material(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(denomination.displayName(), NamedTextColor.GOLD));
        if (!denomination.lore().isEmpty()) {
            meta.lore(denomination.lore().stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, registry.currencyType());
        pdc.set(denominationKey, PersistentDataType.STRING, denomination.id());
        pdc.set(schemaKey, PersistentDataType.INTEGER, registry.schemaVersion());
        pdc.set(signatureKey, PersistentDataType.STRING, signaturePayload(denomination));
        item.setItemMeta(meta);
        return item;
    }

    Inspection inspect(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return new Inspection(false, null, "not_currency");
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        String id = pdc.get(denominationKey, PersistentDataType.STRING);
        Integer schema = pdc.get(schemaKey, PersistentDataType.INTEGER);
        String signature = pdc.get(signatureKey, PersistentDataType.STRING);
        if (!registry.currencyType().equals(type)) return new Inspection(false, null, "wrong_type");
        if (id == null || schema == null || schema != registry.schemaVersion()) return new Inspection(false, null, "wrong_schema");
        Optional<Denomination> optional = registry.byId(id);
        if (optional.isEmpty()) return new Inspection(false, null, "unknown_denomination");
        Denomination denomination = optional.get();
        if (item.getType() != denomination.material()) return new Inspection(false, null, "wrong_material");
        String payload = rawPayload(denomination);
        if (!signer.verify(payload, signature)) return new Inspection(false, null, "bad_signature");
        return new Inspection(true, denomination, "ok");
    }

    private String signaturePayload(Denomination denomination) {
        return signer.sign(rawPayload(denomination));
    }

    private String rawPayload(Denomination denomination) {
        return registry.currencyType() + "|" + registry.schemaVersion() + "|" + denomination.id() + "|" + denomination.value() + "|" + denomination.material().name();
    }
}
