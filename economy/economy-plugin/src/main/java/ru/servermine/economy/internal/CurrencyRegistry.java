package ru.servermine.economy.internal;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.servermine.economy.api.DenominationView;

import java.util.*;

final class CurrencyRegistry {
    private final String currencyType;
    private final int schemaVersion;
    private final List<Denomination> descending;
    private final Map<String, Denomination> byId;

    private CurrencyRegistry(String currencyType, int schemaVersion, List<Denomination> denominations) {
        this.currencyType = currencyType;
        this.schemaVersion = schemaVersion;
        this.descending = denominations.stream()
                .sorted(Comparator.comparingLong(Denomination::value).reversed())
                .toList();
        Map<String, Denomination> map = new LinkedHashMap<>();
        for (Denomination denomination : denominations) map.put(denomination.id(), denomination);
        this.byId = Map.copyOf(map);
    }

    static CurrencyRegistry from(FileConfiguration config) {
        String type = config.getString("currency.type", "servermine_coin").trim();
        int schema = config.getInt("currency.schema-version", 1);
        ConfigurationSection section = Objects.requireNonNull(
                config.getConfigurationSection("currency.denominations"),
                "currency.denominations is missing"
        );

        List<Denomination> values = new ArrayList<>();
        Set<Long> usedValues = new HashSet<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection denomination = Objects.requireNonNull(section.getConfigurationSection(id));
            long value = denomination.getLong("value");
            if (value <= 0) throw new IllegalArgumentException("Denomination " + id + " has non-positive value");
            if (!usedValues.add(value)) throw new IllegalArgumentException("Duplicate denomination value: " + value);
            Material material = Material.matchMaterial(denomination.getString("material", "GOLD_NUGGET"));
            if (material == null || !material.isItem()) throw new IllegalArgumentException("Invalid material for " + id);
            String displayName = denomination.getString("display-name", id);
            List<String> lore = List.copyOf(denomination.getStringList("lore"));
            values.add(new Denomination(id, value, material, displayName, lore));
        }
        if (values.isEmpty()) throw new IllegalArgumentException("At least one denomination is required");
        if (values.stream().noneMatch(d -> d.value() == 1L)) {
            throw new IllegalArgumentException("A denomination with value=1 is required to guarantee exact change");
        }
        return new CurrencyRegistry(type, schema, values);
    }

    String currencyType() { return currencyType; }
    int schemaVersion() { return schemaVersion; }
    List<Denomination> descending() { return descending; }
    Optional<Denomination> byId(String id) { return Optional.ofNullable(byId.get(id)); }

    List<DenominationView> publicView() {
        return descending.stream()
                .map(d -> new DenominationView(d.id(), d.value(), d.displayName()))
                .toList();
    }

    Map<Denomination, Integer> makeChange(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        Map<Denomination, Integer> result = new LinkedHashMap<>();
        long remaining = amount;
        for (Denomination denomination : descending) {
            if (remaining <= 0) break;
            long count = remaining / denomination.value();
            if (count > 0) {
                if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("amount too large");
                result.put(denomination, (int) count);
                remaining %= denomination.value();
            }
        }
        if (remaining != 0) throw new IllegalStateException("Cannot make exact change");
        return result;
    }
}
