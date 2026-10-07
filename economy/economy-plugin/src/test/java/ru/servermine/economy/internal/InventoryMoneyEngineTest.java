package ru.servermine.economy.internal;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryMoneyEngineTest {
    @Test void balanceOverflowFailsBeforeChangingInventory() {
        var registry = mock(CurrencyRegistry.class);
        var codec = mock(CurrencyCodec.class);
        var player = mock(Player.class);
        var inventory = mock(PlayerInventory.class);
        var coin = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{coin});
        when(coin.clone()).thenReturn(coin);
        when(coin.getAmount()).thenReturn(1);
        when(codec.inspect(coin)).thenReturn(new CurrencyCodec.Inspection(true,
                new Denomination("huge", Long.MAX_VALUE, Material.GOLD_NUGGET, "Huge", List.of()), "ok"));
        var engine = new InventoryMoneyEngine(registry, codec);
        assertThrows(ArithmeticException.class, () -> engine.payout(player, 1));
        verify(inventory, never()).setStorageContents(any());
    }
}
