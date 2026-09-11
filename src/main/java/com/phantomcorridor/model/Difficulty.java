package com.phantomcorridor.model;

/**
 * 难度：只改敌人的**基础属性**（生命与防御），与层数成长相乘叠加。
 *
 * <p>玩家的生命、金币与装备完全不变——难度是给同一套数值换一个起点，
 * 而不是换一套规则，这样不同难度下的地图、房间内容与掉落都还能横向对比。
 *
 * <p>倍率作用在物种基础值上：第 N 层的敌人生命 = {@code 基础 × 倍率 × (1 + (N-1) × 0.25)}，
 * 防御同理；简单难度下防御会被压到更低的档位，困难与屌炸天则更早堆起来。
 */
public enum Difficulty {

    /** 简单：敌人基础属性 50% */
    EASY("简单", 0.5, "敌人生命与防御 50%，先摸清机制"),

    /** 标准：基准难度 */
    NORMAL("标准", 1.0, "敌人属性基准值，推荐首次通关"),

    /** 困难：敌人基础属性 150% */
    HARD("困难", 1.5, "敌人生命与防御 150%，容错更低"),

    /** 屌炸天：敌人基础属性 200% */
    INSANE("屌炸天", 2.0, "敌人生命与防御 200%，走错一步就重来");

    private final String displayName;
    private final double enemyStatMultiplier;
    private final String description;

    Difficulty(String displayName, double enemyStatMultiplier, String description) {
        this.displayName = displayName;
        this.enemyStatMultiplier = enemyStatMultiplier;
        this.description = description;
    }

    public String displayName() { return displayName; }

    /** 敌人基础属性（生命、防御）的倍率。 */
    public double enemyStatMultiplier() { return enemyStatMultiplier; }

    /**
     * 玩家攻击伤害的倍率。
     *
     * <p>与 {@link #enemyStatMultiplier()} 完全一致：难度改的是“双方伤害与血量的整体刻度”，
     * 而不是单方面把敌人堆厚。旧实现里玩家伤害恒为 1～4 点、而敌人生命会随难度涨到 200%，
     * 于是「屌炸天」下同一只小怪要打两倍次数——那不是变难，只是变磨。
     * 现在玩家的每一击也乘同一个倍率，敌人更厚、玩家打得更疼，节奏保持不变。
     */
    public double playerDamageMultiplier() { return enemyStatMultiplier; }

    /** 一句难度说明，菜单与文档共用。 */
    public String description() { return description; }

    /** 百分比文本，例如「150%」。 */
    public String percentText() { return Math.round(enemyStatMultiplier * 100) + "%"; }

    /** 按显示名找难度；找不到时回落到 {@link #NORMAL}。 */
    public static Difficulty fromName(String name) {
        if (name == null) return NORMAL;
        for (Difficulty difficulty : values()) {
            if (difficulty.displayName.equals(name) || difficulty.name().equalsIgnoreCase(name.trim())) {
                return difficulty;
            }
        }
        return NORMAL;
    }
}
