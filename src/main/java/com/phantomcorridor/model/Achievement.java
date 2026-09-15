package com.phantomcorridor.model;

/** 可由本地档案永久记录的成就。 */
public enum Achievement {
    BOSS_SLAYER("首领终结者", "首次击败任意首领", "boss_slayer"),
    ALL_BOSSES("诸界征服者", "击败图鉴中的全部六名首领", "all_bosses"),
    ALL_EQUIPMENT("万物皆藏", "发现道具图鉴中的全部装备", "all_equipment"),
    EASY_CLEAR("小试身手", "通关简单难度", "easy_clear"),
    NORMAL_CLEAR("通关了？", "通关标准难度", "normal_clear"),
    HARD_CLEAR("无惧者", "通关困难难度", "hard_clear"),
    INSANE_CLEAR("如有神助", "通关屌炸天难度", "insane_clear");

    private final String title;
    private final String description;
    private final String iconId;
    Achievement(String title, String description, String iconId) {
        this.title = title; this.description = description; this.iconId = iconId;
    }
    public String title() { return title; }
    public String description() { return description; }
    public String iconId() { return iconId; }
}
