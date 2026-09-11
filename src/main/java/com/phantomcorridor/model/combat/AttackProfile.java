package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;

/**
 * 武器攻击方案：一件武器如何出手、打出几个伤害包、每个包的系数是多少。
 *
 * <p>对应《新增 15 件装备与攻击特效设计》§三「统一数值与特效规则」：单个伤害包的
 * 防御前伤害 = 对应基础伤害 × 该段系数 ×（1 + 当前生效的同类伤害加成之和）。
 * 倍率一律相对 {@link GameConfig} 的基础值表示，所以基础攻击就是系数 1.0、间隔 1.0。
 *
 * <p>形态类武器（三叉杖、长杖、环月镰……）是**替换攻击方案**而不是叠加属性：
 * 装上贯日长杖就不再是“三叉杖 + 长矛”，而是「这次出手改用长矛的方案」。
 * 同界同时带多件改变攻击方式的武器时，按 {@link PlayerAttackSystem} 里的固定优先级取一件，
 * 保证手感稳定、可预期。
 *
 * <p>纯数据：不引用 JavaFX，也不持有任何会变的状态（会变的状态在 {@link Projectile} 上）。
 */
public record AttackProfile(
        /** 这一轮攻击属于哪一界：决定伤害加成取哪一套、以及弹体与敌人的世界匹配。 */
        WorldType world,
        /** 弹体形态：渲染层据此选贴图与特效，命中逻辑不关心它。 */
        ProjectileShape shape,
        /** 伤害包数量（三发散射、二连发为多包；近战震波等另有自己的包）。 */
        int pellets,
        /** 相邻伤害包之间的夹角（度）；单发时为 0。 */
        double spreadDegrees,
        /** 每个伤害包的系数：相对该界基础伤害。 */
        double[] damageCoefficients,
        /**
         * 同一发弹体最多还能额外命中几个敌人（穿透）。
         * 0 表示命中首个目标即消失；长杖为 2（合计三个敌人）。
         */
        int extraHits,
        /** 弹速相对基础光弹的倍率。 */
        double speedScale,
        /** 寿命相对基础光弹的倍率；与速度成反比即可保持最大飞行距离。 */
        double lifetimeScale,
        /** 弹体半径倍率。 */
        double radiusScale,
        /** 攻击间隔相对基础间隔的倍率。 */
        double cooldownScale,
        /** 攻击间隔的绝对下限（秒）。 */
        double minCooldown,
        /** 近战扇形角度（度）；远程方案忽略。 */
        double meleeArcDegrees,
        /** 近战距离倍率（相对 {@link GameConfig#SHADOW_MELEE_RANGE}）；远程方案忽略。 */
        double meleeRangeScale,
        /** 出手前摇（秒）：0 表示立即结算，重剑为 0.18。 */
        double windup,
        /** 多包之间的发射间隔（秒）：蚀界仪的两颗光弹相差 0.10。 */
        double pelletInterval) {

    /** 弹体形态：只影响渲染，命中一律按圆形判定。 */
    public enum ProjectileShape { ORB, LANCE, CORE, FANG }

    public AttackProfile {
        damageCoefficients = damageCoefficients.clone();
    }

    @Override
    public double[] damageCoefficients() {
        return damageCoefficients.clone();
    }

    /** 第 {@code index} 个伤害包的系数；越界时回落到最后一个。 */
    public double coefficient(int index) {
        if (damageCoefficients.length == 0) return 1.0;
        return damageCoefficients[Math.min(index, damageCoefficients.length - 1)];
    }

    /** 是否有出手前摇。 */
    public boolean hasWindup() { return windup > 0.0; }

    /** 一轮攻击是否由多颗弹体组成（三叉杖、二连发）。 */
    public boolean isMultiPellet() { return pellets > 1; }

    // ---- 各武器的具体方案 ----

    /** 基础光弹：单发、系数 1.0、间隔 1.0。 */
    public static AttackProfile baseLight() {
        return light(ProjectileShape.ORB, 1, 0.0, new double[]{1.0}, 0,
                1.0, 1.0, 1.0, 1.0, 0.0);
    }

    /** 基础影斩：前方扇形、系数 1.0、间隔 1.0。 */
    public static AttackProfile baseShadow() {
        return shadow(ProjectileShape.FANG, 1.0, GameConfig.SHADOW_MELEE_ARC_DEGREES, 1.0, 1.0, 0.0, 0.0);
    }

    /**
     * 棱光三叉杖：-14° / 0° / +14° 三发同步光弹，每发 0.45，间隔 1.10。
     *
     * <p>三发可以分别命中同一个敌人；三发全中防御前合计 1.35。
     */
    public static AttackProfile prismFanWand() {
        return light(ProjectileShape.ORB, 3, 14.0, new double[]{0.45, 0.45, 0.45}, 0,
                1.0, 1.0, 1.0, 1.10, 0.0);
    }

    /**
     * 贯日长杖：细长光矛，最多穿透三个敌人，依次 1.00 / 0.75 / 0.50，间隔 1.20。
     *
     * <p>速度 ×1.35、寿命 ÷1.35（保持原最大飞行距离）；弹体更细，所以半径收窄。
     */
    public static AttackProfile sunlance() {
        return light(ProjectileShape.LANCE, 1, 0.0, new double[]{1.00, 0.75, 0.50}, 2,
                1.35, 1.0 / 1.35, 0.6, 1.20, 0.0);
    }

    /**
     * 折镜法球：首发 0.85，命中后最多弹射两次，后两段 0.55 / 0.35，间隔 1.15。
     *
     * <p>三段伤害落在三个**不同**敌人身上，由 {@link Projectile#consumeBounce} 在命中时逐段推进；
     * 弹体本身只带第一段的系数，后续两段由弹射系统另行结算。
     */
    public static AttackProfile mirrorOrb() {
        return light(ProjectileShape.ORB, 1, 0.0, new double[]{0.85, 0.55, 0.35}, 0,
                1.0, 1.0, 1.0, 1.15, 0.0);
    }

    /**
     * 炽核权杖：慢速光核（速度 0.70、寿命 ÷0.70），命中或耗尽时半径 80 爆裂造成 1.10。
     *
     * <p>光核没有单独的接触伤害——直接命中的敌人也只吃一次爆炸，所以它的伤害系数由
     * 爆炸那一份承担，弹体自身系数为 0。
     */
    public static AttackProfile solarBurstStaff() {
        return light(ProjectileShape.CORE, 1, 0.0, new double[]{0.0, 1.10}, 0,
                0.70, 1.0 / 0.70, 1.6, 1.45, 0.0);
    }

    /** 环月镰：以出手位置为中心的 360° 环斩，半径 0.80、伤害 0.80，间隔 1.20。 */
    public static AttackProfile crescentReaper() {
        return shadow(ProjectileShape.FANG, 0.80, 360.0, 0.80, 1.20, 0.0, 0.0);
    }

    /** 夜坠重剑：40° 窄扇形重劈，距离 1.35、伤害 1.60、0.18 秒前摇，间隔 max(1.65, 0.30)。 */
    public static AttackProfile nightfallGreatsword() {
        return shadow(ProjectileShape.FANG, 1.60, 40.0, 1.35, 1.65, 0.30, 0.18);
    }

    /** 蚀界仪（光界）：沿同一方向两颗光弹，各 0.55，间隔 0.10 秒。 */
    public static AttackProfile eclipseRelayLight() {
        return light(ProjectileShape.ORB, 2, 0.0, new double[]{0.55, 0.55}, 0,
                1.0, 1.0, 1.0, 1.20, 0.20, 0.10);
    }

    /**
     * 蚀界仪（影界）：先 0.75 斩击、间隔 ×1.30，0.22 秒后原方向复斩 0.45。
     *
     * <p>{@code pelletInterval} 在这里表达「主斩与复斩之间的 0.22 秒」。
     */
    public static AttackProfile eclipseRelayShadow() {
        return new AttackProfile(WorldType.SHADOW, ProjectileShape.FANG, 1, 0.0,
                new double[]{0.75}, 0, 1.0, 1.0, 1.0, 1.30, 0.30,
                GameConfig.SHADOW_MELEE_ARC_DEGREES, 1.00, 0.0, 0.22);
    }

    /**
     * 蚀界仪的复斩：不跟随玩家、不重新瞄准，所以它是一份**独立**的方案。
     *
     * <p>它只承担第二段的 0.45，间隔不叠加（那一轮的间隔由主斩负责）。
     */
    public static AttackProfile eclipseRelayReslash() {
        return shadow(ProjectileShape.FANG, 0.45, GameConfig.SHADOW_MELEE_ARC_DEGREES, 1.00,
                1.00, 0.0, 0.0);
    }

    private static AttackProfile light(ProjectileShape shape, int pellets, double spreadDegrees,
                                       double[] coefficients, int extraHits, double speedScale,
                                       double lifetimeScale, double radiusScale, double cooldownScale,
                                       double minCooldown) {
        return light(shape, pellets, spreadDegrees, coefficients, extraHits, speedScale,
                lifetimeScale, radiusScale, cooldownScale, minCooldown, 0.0);
    }

    private static AttackProfile light(ProjectileShape shape, int pellets, double spreadDegrees,
                                       double[] coefficients, int extraHits, double speedScale,
                                       double lifetimeScale, double radiusScale, double cooldownScale,
                                       double minCooldown, double pelletInterval) {
        return new AttackProfile(WorldType.LIGHT, shape, pellets, spreadDegrees, coefficients, extraHits,
                speedScale, lifetimeScale, radiusScale, cooldownScale, minCooldown,
                0.0, 1.0, 0.0, pelletInterval);
    }

    /**
     * 近战方案的构造入口。
     *
     * @param damageCoefficient 伤害系数（相对该界基础伤害）
     * @param cooldownScale     攻击间隔倍率（相对该界基础间隔）
     */
    private static AttackProfile shadow(ProjectileShape shape, double damageCoefficient, double arcDegrees,
                                        double rangeScale, double cooldownScale, double minCooldown,
                                        double windup) {
        return new AttackProfile(WorldType.SHADOW, shape, 1, 0.0, new double[]{damageCoefficient}, 0,
                1.0, 1.0, 1.0, cooldownScale, minCooldown, arcDegrees, rangeScale, windup, 0.0);
    }

    /**
     * 这件武器在**当前世界**下是否改变攻击方式。
     *
     * <p>单界武器只在它自己那一界生效；蚀界仪是双界武器，两界都换方案。
     */
    public static boolean changesAttack(EquipmentType weapon, WorldType world) {
        if (weapon == null) return false;
        return switch (weapon) {
            case PRISM_FAN_WAND, SUNLANCE, MIRROR_ORB, SOLAR_BURST_STAFF -> world == WorldType.LIGHT;
            case CRESCENT_REAPER, RETURNING_FANG, NIGHTFALL_GREATSWORD -> world == WorldType.SHADOW;
            case ECLIPSE_RELAY -> true;
            default -> false;
        };
    }

    /** 该武器在那个世界里的攻击方案；不改变攻击方式时返回 {@code null}。 */
    public static AttackProfile forWeapon(EquipmentType weapon, WorldType world) {
        if (!changesAttack(weapon, world)) return null;
        return switch (weapon) {
            case PRISM_FAN_WAND -> prismFanWand();
            case SUNLANCE -> sunlance();
            case MIRROR_ORB -> mirrorOrb();
            case SOLAR_BURST_STAFF -> solarBurstStaff();
            case CRESCENT_REAPER -> crescentReaper();
            case RETURNING_FANG -> null;   // 归影双刃走独立弹体，见 PlayerAttackSystem
            case NIGHTFALL_GREATSWORD -> nightfallGreatsword();
            case ECLIPSE_RELAY -> world == WorldType.LIGHT ? eclipseRelayLight() : eclipseRelayShadow();
            default -> null;
        };
    }

    /** 便捷入口：按玩家当前世界与装备栏取攻击方案。 */
    public static AttackProfile forPlayer(Player player) {
        return forWeapon(PlayerAttackSystem.activeWeapon(player), player.getCurrentWorld());
    }
}
