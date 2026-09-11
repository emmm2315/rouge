package com.phantomcorridor.model.entity;

/** 素材包中的七个可生成物种；id 与 monster_pack_v1 目录保持一致。 */
public enum EnemyKind {
    // 以玩家基础移速 200px/s 为参照：狼压迫最快，傀儡与鸣钟则明显笨重。
    LANTERN("n01_lantern", false, 2, 0.56, "seeker_orb"),
    WOLF("n02_wolf", false, 3, 1.08, "bite_arc"),
    GOLEM("n03_golem", false, 5, 0.38, "ground_crack"),
    MAGE("n04_mage", false, 3, 0.58, "fan_pellet"),
    EXECUTIONER("e01_executioner", true, 10, 0.72, "spear_projectile"),
    BELL("e02_bell", true, 9, 0.32, "bell_pellet"),
    WATCHER("b01_watcher", true, 50, 0.50, "rift_spear");

    private final String assetId;
    private final boolean elite;
    private final int hitPoints;
    private final double speedMultiplier;
    private final String lightEffect;

    EnemyKind(String assetId, boolean elite, int hitPoints, double speedMultiplier, String lightEffect) {
        this.assetId = assetId;
        this.elite = elite;
        this.hitPoints = hitPoints;
        this.speedMultiplier = speedMultiplier;
        this.lightEffect = lightEffect;
    }

    public String assetId() { return assetId; }
    public boolean elite() { return elite; }
    public int hitPoints() { return hitPoints; }
    public double speedMultiplier() { return speedMultiplier; }
    public String lightEffect() { return lightEffect; }

    /** 远程物种保持距离并发射弹体；其余物种贴近到短距离后再攻击。 */
    public boolean ranged() {
        return switch (this) {
            case LANTERN, MAGE, BELL, WATCHER -> true;
            case WOLF, GOLEM, EXECUTIONER -> false;
        };
    }

    /**
     * 该物种的**基础**撞击伤害（玩家 100 点生命下的取值）。
     *
     * <p>这是“这只怪撞到你就掉多少血”的物种底价，也是它没有单独配数值的招式的默认伤害。
     * 每个招式自己的伤害另在 {@link com.phantomcorridor.model.combat.EnemySkill} 上逐条配置：
     * 同一只怪的重招与小招不该打掉同样多的血。
     *
     * <p>参考刻度：玩家 100 点生命，普通小怪一发 4～6 点、精英 8～10 点、首领 9～12 点。
     */
    public double attackDamage() {
        return switch (this) {
            case LANTERN -> 4.0;
            case WOLF -> 5.0;
            case GOLEM -> 8.0;
            case MAGE -> 5.0;
            case EXECUTIONER -> 10.0;
            case BELL -> 8.0;
            case WATCHER -> 12.0;
        };
    }
}
