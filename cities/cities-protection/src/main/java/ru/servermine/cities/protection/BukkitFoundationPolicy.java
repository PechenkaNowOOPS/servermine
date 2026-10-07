package ru.servermine.cities.protection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermine.cities.api.ChunkPosition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Main-thread Bukkit checks for loaded worlds, world borders and configured system zones. */
public final class BukkitFoundationPolicy implements FoundationPolicy {
    private final JavaPlugin plugin;
    private final List<SystemZone> zones;

    public BukkitFoundationPolicy(JavaPlugin plugin, List<SystemZone> zones) {
        this.plugin = plugin;
        this.zones = List.copyOf(zones);
    }

    @Override
    public CompletionStage<FoundationDecision> check(UUID founderId, Set<ChunkPosition> initialChunks) {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(safeCheck(initialChunks));
        CompletableFuture<FoundationDecision> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> result.complete(safeCheck(initialChunks)));
        } catch (RuntimeException unavailable) {
            result.complete(FoundationDecision.WORLD_UNAVAILABLE);
        }
        return result;
    }

    private FoundationDecision safeCheck(Set<ChunkPosition> chunks) {
        try {
            return checkOnMain(chunks);
        } catch (RuntimeException unavailable) {
            return FoundationDecision.WORLD_UNAVAILABLE;
        }
    }

    private FoundationDecision checkOnMain(Set<ChunkPosition> chunks) {
        for (ChunkPosition chunk : chunks) {
            World world = Bukkit.getWorld(chunk.worldId());
            if (world == null) return FoundationDecision.WORLD_UNAVAILABLE;
            if (!world.getWorldBorder().isInside(new Location(world, chunk.x() * 16.0 + 8.0, 0.0, chunk.z() * 16.0 + 8.0))) {
                return FoundationDecision.PROTECTED_ZONE;
            }
            for (SystemZone zone : zones) {
                if (zone.contains(chunk)) return FoundationDecision.PROTECTED_ZONE;
            }
        }
        return FoundationDecision.ALLOWED;
    }

    public record SystemZone(UUID worldId, int minX, int maxX, int minZ, int maxZ) {
        public SystemZone {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid protection system zone bounds");
        }
        boolean contains(ChunkPosition chunk) {
            return worldId.equals(chunk.worldId()) && chunk.x() >= minX && chunk.x() <= maxX
                    && chunk.z() >= minZ && chunk.z() <= maxZ;
        }
    }
}
