package ru.servermine.cities.gui;

public enum MenuType {
    MAIN('\uE001', "Главное меню"),
    TERRITORY('\uE002', "Территория"),
    PURCHASE('\uE003', "Покупка территории"),
    PURCHASE_CONFIRM('\u0000', "Подтверждение покупки чанка"),
    UPGRADES('\uE004', "Улучшения"),
    TREASURY('\uE005', "Казна"),
    RESIDENTS('\uE006', "Жители"),
    MEMBER_KICK_CONFIRM('\u0000', "Исключение участника"),
    MANAGEMENT('\uE007', "Управление"),
    DIPLOMACY('\uE008', "Дипломатия"),
    MARKET('\u0000', "Рынок"),
    FOUNDING('\u0000', "Основание города"),
    FOUNDING_CONFIRM('\u0000', "Подтверждение основания"),
    PROGRESSION('\u0000', "Развитие города"),
    PROMOTION_CONFIRM('\u0000', "Подтверждение перехода");

    private final char glyph;
    private final String fallbackTitle;

    MenuType(char glyph, String fallbackTitle) {
        this.glyph = glyph;
        this.fallbackTitle = fallbackTitle;
    }

    public char glyph() {
        return glyph;
    }

    public String fallbackTitle() {
        return fallbackTitle;
    }
}
