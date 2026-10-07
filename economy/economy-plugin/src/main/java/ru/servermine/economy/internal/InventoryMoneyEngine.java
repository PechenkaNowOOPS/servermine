package ru.servermine.economy.internal;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;

final class InventoryMoneyEngine {
    enum MutationCode { OK, INSUFFICIENT_FUNDS, INVENTORY_FULL }
    record Mutation(MutationCode code, long balanceBefore, long balanceAfter) {}

    private final CurrencyRegistry registry;
    private final CurrencyCodec codec;

    InventoryMoneyEngine(CurrencyRegistry registry, CurrencyCodec codec) {
        this.registry = registry;
        this.codec = codec;
    }

    long balance(Player player) {
        long total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            CurrencyCodec.Inspection inspection = codec.inspect(item);
            if (inspection.authentic()) {
                total = Math.addExact(total, Math.multiplyExact(inspection.denomination().value(), item.getAmount()));
            }
        }
        return total;
    }

    Mutation charge(Player player, long amount) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] working = cloneContents(inventory.getStorageContents());
        long before = balance(working);
        if (before < amount) return new Mutation(MutationCode.INSUFFICIENT_FUNDS, before, before);

        long removed = 0;
        List<Integer> currencySlots = new ArrayList<>();
        for (int i = 0; i < working.length; i++) {
            CurrencyCodec.Inspection inspection = codec.inspect(working[i]);
            if (inspection.authentic()) currencySlots.add(i);
        }
        currencySlots.sort((a, b) -> Long.compare(valueOf(working[b]), valueOf(working[a])));

        for (int slot : currencySlots) {
            if (removed >= amount) break;
            ItemStack item = working[slot];
            CurrencyCodec.Inspection inspection = codec.inspect(item);
            long value = inspection.denomination().value();
            long missing = amount - removed;
            int needed = (int) Math.min(item.getAmount(), 1 + (missing - 1) / value);
            removed = Math.addExact(removed, Math.multiplyExact(value, needed));
            int left = item.getAmount() - needed;
            if (left == 0) working[slot] = null;
            else item.setAmount(left);
        }

        long change = removed - amount;
        if (!addMoney(working, change)) return new Mutation(MutationCode.INVENTORY_FULL, before, before);
        inventory.setStorageContents(working);
        return new Mutation(MutationCode.OK, before, before - amount);
    }

    Mutation payout(Player player, long amount) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] working = cloneContents(inventory.getStorageContents());
        long before = balance(working);
        long after = Math.addExact(before, amount);
        if (!addMoney(working, amount)) return new Mutation(MutationCode.INVENTORY_FULL, before, before);
        inventory.setStorageContents(working);
        return new Mutation(MutationCode.OK, before, after);
    }

    private long balance(ItemStack[] contents) {
        long total = 0;
        for (ItemStack item : contents) {
            CurrencyCodec.Inspection inspection = codec.inspect(item);
            if (inspection.authentic()) total = Math.addExact(total, Math.multiplyExact(inspection.denomination().value(), item.getAmount()));
        }
        return total;
    }

    private long valueOf(ItemStack item) {
        CurrencyCodec.Inspection inspection = codec.inspect(item);
        return inspection.authentic() ? inspection.denomination().value() : 0;
    }

    private boolean addMoney(ItemStack[] contents, long amount) {
        if (amount == 0) return true;
        Map<Denomination, Integer> breakdown = registry.makeChange(amount);
        for (Map.Entry<Denomination, Integer> entry : breakdown.entrySet()) {
            int remaining = entry.getValue();
            Denomination denomination = entry.getKey();

            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack current = contents[i];
                CurrencyCodec.Inspection inspection = codec.inspect(current);
                if (!inspection.authentic() || !inspection.denomination().id().equals(denomination.id())) continue;
                int free = current.getMaxStackSize() - current.getAmount();
                if (free <= 0) continue;
                int add = Math.min(free, remaining);
                current.setAmount(current.getAmount() + add);
                remaining -= add;
            }

            while (remaining > 0) {
                int empty = firstEmpty(contents);
                if (empty < 0) return false;
                ItemStack stack = codec.create(denomination, Math.min(remaining, denomination.material().getMaxStackSize()));
                contents[empty] = stack;
                remaining -= stack.getAmount();
            }
        }
        return true;
    }

    private int firstEmpty(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) return i;
        }
        return -1;
    }

    private ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }
}
