package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

import java.util.Arrays;
import java.util.List;

/**
 * 敌人技能的游戏内索引。字段与 monster_pack_v1/combat_profiles.json 一一对应到动作和独立特效，
 * 逻辑数值保留为易于试玩调整的首轮实现，而不是把不同怪物全压成同一颗圆形弹。
 */
public enum EnemySkill {
    LANTERN_SEEKER(EnemyKind.LANTERN, WorldType.LIGHT, "attack", "seeker_orb", Pattern.PROJECTILE, .70, .85, 450, 14, 1, 175, 2.35),
    LANTERN_NEEDLES(EnemyKind.LANTERN, WorldType.SHADOW, "attack", "dusk_needle", Pattern.SPREAD, .82, 1.05, 410, 10, 3, 275, 2.65),
    WOLF_POUNCE(EnemyKind.WOLF, WorldType.LIGHT, "attack", "bite_flash", Pattern.DASH, .72, .78, 290, 54, 1, 0, 2.25),
    WOLF_DASH_BITE(EnemyKind.WOLF, WorldType.SHADOW, "attack", "bite_arc", Pattern.DASH, .55, .88, 340, 58, 1, 0, 2.05),
    GOLEM_CRACK(EnemyKind.GOLEM, WorldType.LIGHT, "attack", "ground_crack", Pattern.CRACK, 1.15, 1.25, 195, 48, 3, 0, 3.45),
    GOLEM_SLAM(EnemyKind.GOLEM, WorldType.SHADOW, "attack", "slam_sector", Pattern.ARC, 1.05, 1.35, 180, 125, 1, 0, 3.35),
    MAGE_FAN(EnemyKind.MAGE, WorldType.LIGHT, "attack", "fan_pellet", Pattern.FAN, .75, .82, 500, 11, 5, 230, 2.45),
    MAGE_MIRROR(EnemyKind.MAGE, WorldType.SHADOW, "attack", "mirror_arc", Pattern.ARC, .90, 1.15, 205, 130, 1, 0, 2.85),
    EXECUTIONER_SPEAR(EnemyKind.EXECUTIONER, WorldType.LIGHT, "attack", "spear_projectile", Pattern.PROJECTILE, 1.00, .90, 500, 16, 1, 360, 2.85),
    EXECUTIONER_BASH(EnemyKind.EXECUTIONER, WorldType.LIGHT, "shield_bash", "shield_sector", Pattern.ARC, .78, .90, 165, 120, 1, 0, 2.55),
    EXECUTIONER_COMBO(EnemyKind.EXECUTIONER, WorldType.SHADOW, "attack", "cleave_arc", Pattern.DOUBLE_ARC, .78, 1.15, 205, 130, 1, 0, 2.95),
    EXECUTIONER_APPROACH(EnemyKind.EXECUTIONER, WorldType.SHADOW, "approach", "dash_dust", Pattern.DASH_NO_DAMAGE, .65, .45, 370, 0, 1, 0, 2.15),
    BELL_RING(EnemyKind.BELL, WorldType.LIGHT, "attack", "bell_pellet", Pattern.RING, 1.30, 1.15, 400, 10, 9, 175, 3.85),
    BELL_LIGHT_MARK(EnemyKind.BELL, WorldType.LIGHT, "ground_mark", "pillar_impact", Pattern.MARK, 1.05, .95, 520, 92, 1, 0, 3.35),
    BELL_ANNULAR(EnemyKind.BELL, WorldType.SHADOW, "attack", "annular_burst", Pattern.RING_AREA, 1.20, 1.40, 245, 135, 1, 0, 3.75),
    BELL_SHADOW_MARK(EnemyKind.BELL, WorldType.SHADOW, "ground_mark", "pillar_impact", Pattern.MARK, 1.05, .95, 500, 92, 1, 0, 3.35),
    WATCHER_BARRAGE(EnemyKind.WATCHER, WorldType.LIGHT, "attack", "sun_pellet", Pattern.DOUBLE_RING, .90, 1.05, 540, 13, 16, 205, 3.10),
    WATCHER_JUDGMENT(EnemyKind.WATCHER, WorldType.LIGHT, "judgment", "pillar_impact", Pattern.TRIPLE_MARK, 1.05, 1.10, 580, 82, 3, 0, 3.35),
    WATCHER_SPEAR(EnemyKind.WATCHER, WorldType.LIGHT, "rift_spear", "rift_spear", Pattern.PROJECTILE, 1.10, 1.15, 610, 20, 1, 350, 3.55),
    /**
     * 光界的增援召唤：素材包里守望者的光形态没有 summon_* 本体动作，
     * 这里用它的 transform（相位收拢）配合两侧都有的 summon_portal 特效，
     * 读起来就是“把光界的造物从裂隙里拉出来”。
     */
    WATCHER_CALL(EnemyKind.WATCHER, WorldType.LIGHT, "transform", "summon_portal", Pattern.SUMMON, 1.15, 1.40, 620, 0, 2, 0, 12.0),
    WATCHER_DOUBLE_SLASH(EnemyKind.WATCHER, WorldType.SHADOW, "attack", "slash_arc", Pattern.DOUBLE_ARC, .85, 1.20, 235, 155, 1, 0, 3.15),
    WATCHER_DASH(EnemyKind.WATCHER, WorldType.SHADOW, "dash", "dash_trail", Pattern.DASH, .85, 1.20, 470, 92, 1, 0, 3.05),
    WATCHER_SUMMON(EnemyKind.WATCHER, WorldType.SHADOW, "summon", "summon_portal", Pattern.SUMMON, 1.20, 1.50, 540, 0, 2, 0, 12.0);

    public enum Pattern { PROJECTILE, SPREAD, FAN, RING, DOUBLE_RING, ARC, DOUBLE_ARC, DASH, DASH_NO_DAMAGE, CRACK, MARK, TRIPLE_MARK, RING_AREA, SUMMON }

    private final EnemyKind kind;
    private final WorldType world;
    private final String actionBase;
    private final String effect;
    private final Pattern pattern;
    private final double windup, recovery, range, radius, speed, cooldown;
    private final int count;

    EnemySkill(EnemyKind kind, WorldType world, String actionBase, String effect, Pattern pattern,
               double windup, double recovery, double range, double radius, int count, double speed, double cooldown) {
        this.kind = kind; this.world = world; this.actionBase = actionBase; this.effect = effect; this.pattern = pattern;
        this.windup = windup; this.recovery = recovery; this.range = range; this.radius = radius;
        this.count = count; this.speed = speed; this.cooldown = cooldown;
    }
    public EnemyKind kind() { return kind; }
    public WorldType world() { return world; }
    public String actionBase() { return actionBase; }
    public String effect() { return effect; }
    public Pattern pattern() { return pattern; }
    public double windup() { return windup; }
    public double recovery() { return recovery; }
    public double range() { return range; }
    public double radius() { return radius; }
    public int count() { return count; }
    public double speed() { return speed; }
    public double cooldown() { return cooldown; }
    public static List<EnemySkill> forEnemy(EnemyKind kind, WorldType world) {
        return Arrays.stream(values()).filter(skill -> skill.kind == kind && skill.world == world).toList();
    }

    /** 该攻击造成的伤害类型：按招式所在的世界归类。 */
    public DamageType damageType() { return DamageType.ofWorld(world); }

    /**
     * 该招式打中玩家一次造成的伤害（玩家 100 点生命刻度）。
     *
     * <p><b>为什么逐招配数值</b>：旧实现里所有伤害都在结算处写死成 1 点，
     * 于是傀儡的践踏和灯魇的一发小弹打掉的血一模一样——怪物的压迫感完全没有差别，
     * 玩家也读不出“这一下很疼、那一下可以硬吃”。现在伤害跟着招式走：
     * 起手长、范围大、看得见的重招打得更疼，廉价的小弹只削一层皮。
     *
     * <p>数值分档（玩家满血 100）：
     * <ul>
     *   <li>4 —— 低威胁小弹（灯魇的暗针、法师的扇面弹、鸣钟者的钟波）；</li>
     *   <li>5～6 —— 普通单发/近身（灯魇追踪光球、法师镜面斩、傀儡地裂、影狼扑咬）；</li>
     *   <li>8～10 —— 精英与首领的正经招式（处刑者长矛/盾击、鸣钟者光柱、守望者长矛）；</li>
     *   <li>11～13 —— 精英/首领的压轴重招（守望者的裂隙斩、日审判、冲锋）；</li>
     *   <li>3 —— 例外：守望者的日光弹幕一次 16 发环形散射，单发必须便宜，
     *       否则被弹幕擦到就直接融血。</li>
     * </ul>
     */
    public double damage() {
        return switch (this) {
            case LANTERN_SEEKER -> 5.0;
            case LANTERN_NEEDLES -> 4.0;
            case WOLF_POUNCE -> 5.0;
            case WOLF_DASH_BITE -> 6.0;
            case GOLEM_CRACK -> 6.0;
            case GOLEM_SLAM -> 7.5;
            case MAGE_FAN -> 4.0;
            case MAGE_MIRROR -> 6.5;
            case EXECUTIONER_SPEAR -> 9.0;
            case EXECUTIONER_BASH -> 8.0;
            case EXECUTIONER_COMBO -> 10.0;
            // 纯位移招：撞到人不疼，它的价值在贴身，不在伤害。
            case EXECUTIONER_APPROACH -> 2.0;
            case BELL_RING -> 4.0;
            case BELL_LIGHT_MARK -> 10.0;
            case BELL_ANNULAR -> 8.5;
            case BELL_SHADOW_MARK -> 10.0;
            case WATCHER_BARRAGE -> 3.0;
            case WATCHER_JUDGMENT -> 12.0;
            case WATCHER_SPEAR -> 11.0;
            // 召唤本身不造成伤害。
            case WATCHER_CALL, WATCHER_SUMMON -> 0.0;
            case WATCHER_DOUBLE_SLASH -> 13.0;
            case WATCHER_DASH -> 11.0;
        };
    }
}
