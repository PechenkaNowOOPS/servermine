package ru.servermine.economy.api;

/** Публичная информация о номинале без Bukkit-классов. */
public record DenominationView(
        String id,
        long value,
        String displayName
) {}
