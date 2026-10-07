package ru.servermine.economy.internal;

import org.bukkit.Bukkit;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

final class MainThread {
    private final ServerMineEconomyPlugin plugin;

    MainThread(ServerMineEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    <T> CompletableFuture<T> call(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (Bukkit.isPrimaryThread()) run.run();
        else Bukkit.getScheduler().runTask(plugin, run);
        return future;
    }

    void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }
}
