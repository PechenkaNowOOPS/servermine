package ru.servermine.gradostroygui;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DemoCityRepository {
    public enum PurchaseStatus {
        SUCCESS,
        STALE_REVISION,
        LIMIT_REACHED,
        NOT_ENOUGH_MONEY
    }

    public record PurchaseResult(PurchaseStatus status, CitySnapshot snapshot, String operationId) {}

    private final GradostroyGuiPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    public DemoCityRepository(GradostroyGuiPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "demo-city.yml");
    }

    public synchronized void load() {
        if (!file.exists()) {
            plugin.saveResource("demo-city.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        normalize();
        save();
    }

    private void normalize() {
        yaml.addDefault("name", "Камнедолбинск");
        yaml.addDefault("stage", "CITY");
        yaml.addDefault("territory", 17);
        yaml.addDefault("treasury", 24580L);
        yaml.addDefault("revision", 1L);
        yaml.options().copyDefaults(true);
    }

    public synchronized CitySnapshot snapshot() {
        String name = yaml.getString("name", "Камнедолбинск");
        CityStage stage = CityStage.parse(yaml.getString("stage", "CITY"));
        int territory = Math.max(0, yaml.getInt("territory", 0));
        long treasury = Math.max(0L, yaml.getLong("treasury", 0L));
        long revision = Math.max(1L, yaml.getLong("revision", 1L));

        List<CitySnapshot.ResidentEntry> residents = new ArrayList<>();
        for (String row : yaml.getStringList("residents")) {
            String[] split = row.split("\\|", 2);
            residents.add(new CitySnapshot.ResidentEntry(
                    split.length > 0 ? split[0] : "Игрок",
                    split.length > 1 ? split[1] : "Житель"
            ));
        }
        return new CitySnapshot(name, stage, territory, treasury, revision, List.copyOf(residents));
    }

    public synchronized PurchaseResult purchaseChunk(long expectedRevision, int price) {
        CitySnapshot before = snapshot();
        String operationId = java.util.UUID.randomUUID().toString();

        if (before.revision() != expectedRevision) {
            return new PurchaseResult(PurchaseStatus.STALE_REVISION, before, operationId);
        }
        if (before.territoryFull()) {
            return new PurchaseResult(PurchaseStatus.LIMIT_REACHED, before, operationId);
        }
        if (before.treasury() < price) {
            return new PurchaseResult(PurchaseStatus.NOT_ENOUGH_MONEY, before, operationId);
        }

        yaml.set("territory", before.territory() + 1);
        yaml.set("treasury", before.treasury() - price);
        yaml.set("revision", before.revision() + 1);
        save();
        plugin.getAuditLog().append(operationId,
                "PURCHASE_CHUNK",
                "territory=" + before.territory() + "->" + (before.territory() + 1)
                        + ", treasury=" + before.treasury() + "->" + (before.treasury() - price));
        return new PurchaseResult(PurchaseStatus.SUCCESS, snapshot(), operationId);
    }

    public synchronized void setDemo(String field, String value) {
        switch (field.toLowerCase(Locale.ROOT)) {
            case "name" -> yaml.set("name", value);
            case "stage" -> yaml.set("stage", CityStage.parse(value).name());
            case "chunks", "territory" -> yaml.set("territory", Math.max(0, Integer.parseInt(value)));
            case "treasury" -> yaml.set("treasury", Math.max(0L, Long.parseLong(value)));
            default -> throw new IllegalArgumentException("Неизвестное поле: " + field);
        }
        yaml.set("revision", yaml.getLong("revision", 1L) + 1L);
        save();
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить demo-city.yml", e);
        }
    }
}
