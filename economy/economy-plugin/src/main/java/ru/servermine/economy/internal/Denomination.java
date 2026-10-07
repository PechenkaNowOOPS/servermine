package ru.servermine.economy.internal;

import org.bukkit.Material;

import java.util.List;

record Denomination(String id, long value, Material material, String displayName, List<String> lore) {}
