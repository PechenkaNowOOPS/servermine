package ru.servermine.economy.internal;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrencyCodecTest {
    @TempDir Path directory;
    CurrencySigner signer;
    CurrencyCodec codec;
    ItemStack item;
    Map<String,String> data;
    PersistentDataContainer pdc;
    @BeforeEach void setup() throws Exception {
        signer = CurrencySigner.loadOrCreate(directory.resolve("currency.secret"));
        // Bukkit's material registry is server-owned. Mock only configuration lookup,
        // while exercising the real PDC inspection and HMAC validation below.
        var registry = mock(CurrencyRegistry.class);
        when(registry.currencyType()).thenReturn("servermine_coin");
        when(registry.schemaVersion()).thenReturn(1);
        Material material = mock(Material.class);
        when(material.name()).thenReturn("GOLD_NUGGET");
        var denomination = new Denomination("small", 1, material, "Small", List.of());
        when(registry.byId(anyString())).thenAnswer(inv -> "small".equals(inv.getArgument(0))
                ? Optional.of(denomination) : Optional.empty());
        var plugin = mock(ServerMineEconomyPlugin.class);
        when(plugin.namespace()).thenReturn("servermineeconomy");
        codec = new CurrencyCodec(plugin, registry, signer);
        item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(material);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        data = new HashMap<>(Map.of("currency_type", "servermine_coin", "denomination", "small",
                "signature", signer.sign("servermine_coin|1|small|1|GOLD_NUGGET")));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(inv -> data.get(((NamespacedKey) inv.getArgument(0)).getKey()));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenReturn(1);
    }
    @Test void validCurrencyIsAccepted() { assertTrue(codec.inspect(item).authentic()); }
    @Test void forgedSignatureIsRejected() {
        data.put("signature", "forged");
        assertEquals("bad_signature", codec.inspect(item).reason());
        assertFalse(codec.inspect(item).authentic());
    }
    @Test void missingSignatureIsRejected() { data.remove("signature"); assertFalse(codec.inspect(item).authentic()); }
    @Test void wrongMaterialIsRejected() {
        when(item.getType()).thenReturn(mock(Material.class));
        assertEquals("wrong_material", codec.inspect(item).reason());
    }
    @Test void wrongSchemaIsRejected() {
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenReturn(2);
        assertEquals("wrong_schema", codec.inspect(item).reason());
    }
    @Test void unknownDenominationIsRejected() {
        data.put("denomination", "counterfeit");
        assertEquals("unknown_denomination", codec.inspect(item).reason());
    }
    @Test void renamedVanillaItemIsNotCurrency() {
        data.clear();
        assertFalse(codec.inspect(item).authentic());
        assertFalse(codec.inspect(null).authentic());
    }
    @Test void persistedSecretValidatesExistingCoins() throws Exception {
        String payload = "servermine_coin|1|small|1|GOLD_NUGGET";
        var loaded = CurrencySigner.loadOrCreate(directory.resolve("currency.secret"));
        assertTrue(loaded.verify(payload, signer.sign(payload)));
        assertFalse(loaded.verify(payload + "tampered", signer.sign(payload)));
    }
}
