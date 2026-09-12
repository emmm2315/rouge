package com.phantomcorridor.model.entity;

/**
 * 可生成物种索引。id 与素材包目录保持一致：
 * 旧包 {@code monster_pack_v1}（n01～n04 / e01～e02 / b01）与
 * 新包 {@code monster_expansion_v2}（n05～n07 / b02～b06）。
 *
 * <p>三档分类（{@link Tier}）决定身位、受击框、渲染尺寸与默认行为：
 * 普通怪 27 / 35，精英 34 / 45，首领 46 / 62（脚下半径 / 胸腹受击半径）。
 *
 * <p>速度用「玩家基础移速 200px/s 的倍率」表示，数值取自素材包的
 * {@code movementPixelsPerSecond}：甲虫 72、孢子 55、翼蝠 125、炮蟹 54、
 * 螳爵 125、蛛后 66、古树 42、术士 82。
 */
public enum EnemyKind {
    // 旧包：以玩家基础移速 200px/s 为参照——狼压迫最快，傀儡与鸣钟则明显笨重。
    // 生命值一列是「第 1 层 · 标准难度」的基础值；刻度见文件末尾的说明。
    LANTERN("n01_lantern", Tier.NORMAL, 30, 0.56, "seeker_orb"),
    WOLF("n02_wolf", Tier.NORMAL, 45, 1.08, "bite_arc"),
    GOLEM("n03_golem", Tier.NORMAL, 75, 0.38, "ground_crack"),
    MAGE("n04_mage", Tier.NORMAL, 45, 0.58, "fan_pellet"),
    EXECUTIONER("e01_executioner", Tier.ELITE, 140, 0.72, "spear_projectile"),
    BELL("e02_bell", Tier.ELITE, 130, 0.32, "bell_pellet"),
    WATCHER("b01_watcher", Tier.BOSS, 500, 0.50, "rift_spear"),

    // 新包 v2：三种小怪。
    /** 铲角甲虫：近身顶撞 / 掘进伏击。光「曜甲虫」影「掘影虫」。 */
    BEETLE("n05_beetle", Tier.NORMAL, 75, 72.0 / 200.0, "horn_arc"),
    /** 行囊孢子：延迟区域 / 慢速弹。光「晨露孢囊」影「暮毒孢囊」。 */
    SPORE("n06_spore", Tier.NORMAL, 60, 55.0 / 200.0, "spore_projectile"),
    /** 钩尾翼蝠：侧翼骚扰 / 俯冲。光「辉翼蝠」影「夜钩蝠」。 */
    RAYBAT("n07_raybat", Tier.NORMAL, 45, 125.0 / 200.0, "feather_projectile"),

    // 新包 v2：五个 Boss。基础生命按「第一层 · 标准难度」的起点给定，
    // 层数成长与难度倍率仍按 GameConfig 的公式叠加（不再次额外叠乘）。
    /** 棱镜炮蟹：远程炮击与路线封锁。光「昼铸炮垒」影「蚀潮炮垒」。 */
    PRISM_CRAB("b02_prismcrab", Tier.BOSS, 440, 54.0 / 200.0, "prism_shell"),
    /** 双镰螳爵：高速近战、二连斩与反击。光「曦刃螳爵」影「夜刃螳爵」。 */
    MANTIS("b03_mantis", Tier.BOSS, 380, 125.0 / 200.0, "scythe_arc"),
    /** 织巢蛛后：召唤管理、丝线牵制。光「圣绡织母」影「噬梦织母」。 */
    WEAVER("b04_weaver", Tier.BOSS, 420, 66.0 / 200.0, "silk_projectile"),
    /** 根冠古树：地形分割与安全区轮换。光「日冕根王」影「枯夜根王」。 */
    ROOTKING("b05_rootking", Tier.BOSS, 500, 42.0 / 200.0, "root_spike"),
    /** 镜砂术士：幻象辨认、延迟攻击与换位。光「映昼司砂」影「逆影司砂」。 */
    HOURGLASS("b06_hourglass", Tier.BOSS, 400, 82.0 / 200.0, "mirror_shard");

    /** 物种档位：决定身位、受击框、渲染倍数与战斗行为。 */
    public enum Tier { NORMAL, ELITE, BOSS }

    /** 两界共享敌人，弱点由物种决定，不随玩家切界重置。 */
    public enum Affinity {
        LIGHT_WEAK("弱光"), SHADOW_WEAK("弱影"), COUPLED("光影破防");
        private final String label;
        Affinity(String label) { this.label = label; }
        public String label() { return label; }
        public double damageMultiplier(com.phantomcorridor.model.WorldType attackWorld, boolean detonating) {
            if (this == COUPLED) return detonating ? 1.5 : 0.5;
            boolean light = attackWorld == com.phantomcorridor.model.WorldType.LIGHT;
            return light == (this == LIGHT_WEAK) ? 1.35 : 0.85;
        }
    }

    public Affinity affinity() {
        return switch (this) {
            case WOLF, SPORE, RAYBAT, WEAVER -> Affinity.LIGHT_WEAK;
            case LANTERN, MAGE, EXECUTIONER, MANTIS -> Affinity.SHADOW_WEAK;
            case GOLEM, BEETLE, BELL, WATCHER, PRISM_CRAB, ROOTKING, HOURGLASS -> Affinity.COUPLED;
        };
    }


    private final String assetId;
    private final Tier tier;
    private final int hitPoints;
    private final double speedMultiplier;
    private final String lightEffect;

    EnemyKind(String assetId, Tier tier, int hitPoints, double speedMultiplier, String lightEffect) {
        this.assetId = assetId;
        this.tier = tier;
        this.hitPoints = hitPoints;
        this.speedMultiplier = speedMultiplier;
        this.lightEffect = lightEffect;
    }

    public String assetId() { return assetId; }
    public Tier tier() { return tier; }
    public int hitPoints() { return hitPoints; }
    public double speedMultiplier() { return speedMultiplier; }
    public String lightEffect() { return lightEffect; }

    /** 精英怪：后段战斗房里会替换掉一只普通怪。首领不算精英。 */
    public boolean elite() { return tier == Tier.ELITE; }

    /** 首领：Boss 房只生成一名，半血时跨界换形。 */
    public boolean boss() { return tier == Tier.BOSS; }

    /** 远程物种保持距离并发射弹体；其余物种贴近到短距离后再攻击。 */
    public boolean ranged() {
        return switch (this) {
            case LANTERN, MAGE, BELL, WATCHER, SPORE, PRISM_CRAB, WEAVER, ROOTKING, HOURGLASS -> true;
            case WOLF, GOLEM, EXECUTIONER, BEETLE, RAYBAT, MANTIS -> false;
        };
    }

    /** 脚下身位半径（像素）：移动、贴墙与出生点校验都用它。 */
    public double footRadius() { return switch (tier) {
        case BOSS -> 46.0;
        case ELITE -> 34.0;
        case NORMAL -> 27.0;
    }; }

    /** 胸腹受击框半径（像素）：比模型轮廓小一圈，避免「擦到披风就受伤」。 */
    public double hurtRadius() { return switch (tier) {
        case BOSS -> 62.0;
        case ELITE -> 45.0;
        case NORMAL -> 35.0;
    }; }

    /** 受击框中心相对脚下落点的上移量（像素）。 */
    public double hitboxCenterOffset() { return switch (tier) {
        case BOSS -> 92.0;
        case ELITE -> 66.0;
        case NORMAL -> 51.0;
    }; }

    /**
     * 本体帧画布的边长（像素）。
     *
     * <p>旧包普通/精英/首领分别是 256 / 320 / 448，新包 Boss 用更大的 640 画布给树冠、
     * 镰臂与炮管留空间；画布越大不代表物理身位越大，身位一律看 {@link #footRadius()}。
     */
    public int canvasSize() {
        if (this == WATCHER) return 448;
        return switch (tier) {
            case BOSS -> 640;
            case ELITE -> 320;
            case NORMAL -> 256;
        };
    }

    /**
     * 是否同时绘制了正面与背面。
     *
     * <p>v1 包有 front/back/right/left 四个朝向；v2 包只画了右向三分之四视角并镜像出左向，
     * 没有独立正面与背面（素材包明确要求「不能把 left/right 谎标为 front/back」）。
     * 缺少正背面的物种只按左右翻转来表现朝向。
     */
    public boolean hasFrontBackArt() { return switch (this) {
        case BEETLE, SPORE, RAYBAT, PRISM_CRAB, MANTIS, WEAVER, ROOTKING, HOURGLASS -> false;
        default -> true;
    }; }

    /** 是否为 v2 扩展包物种（用于文档、调试与备用池）。 */
    public boolean expansion() {
        return switch (this) {
            case BEETLE, SPORE, RAYBAT, PRISM_CRAB, MANTIS, WEAVER, ROOTKING, HOURGLASS -> true;
            default -> false;
        };
    }

    /**
     * 物种的目标显示身高（像素，素材包「中立姿态高度」实测值）。
     *
     * <p>两套素材包都按这个高度 1:1 导出到画布上，所以它既是美术基准，也是渲染层
     * 摆放血条与飘字的依据：血条必须贴在**模型头顶**而不是画布顶上——树冠与镰臂的留白
     * 有十几像素，用画布高度会把血条顶到天花板。
     */
    public double displayHeight() {
        return switch (this) {
            case LANTERN -> 84.0;
            case WOLF -> 90.0;
            case GOLEM -> 148.0;
            case MAGE -> 116.0;
            case EXECUTIONER -> 174.0;
            case BELL -> 168.0;
            case WATCHER -> 268.0;
            case BEETLE -> 86.0;
            case SPORE -> 102.0;
            case RAYBAT -> 92.0;
            case PRISM_CRAB -> 209.0;
            case MANTIS -> 252.0;
            case WEAVER -> 234.0;
            case ROOTKING -> 267.0;
            case HOURGLASS -> 244.0;
        };
    }

    /** 血条 / 受击飘字相对脚下的基准高度（像素，已按渲染缩放换算到屏幕尺度）。 */
    public double overheadHeight() {
        return displayHeight() * com.phantomcorridor.config.GameConfig.MONSTER_RENDER_SCALE;
    }

    /** 物种名（中文）：公告、Boss 血条与调试输出用。 */
    public String displayName() {
        return switch (this) {
            case LANTERN -> "灯魇";
            case WOLF -> "晶狼";
            case GOLEM -> "傀儡";
            case MAGE -> "法师";
            case EXECUTIONER -> "执刑者";
            case BELL -> "鸣钟者";
            case WATCHER -> "裂隙守望者";
            case BEETLE -> "铲角甲虫";
            case SPORE -> "行囊孢子";
            case RAYBAT -> "钩尾翼蝠";
            case PRISM_CRAB -> "棱镜炮蟹";
            case MANTIS -> "双镰螳爵";
            case WEAVER -> "织巢蛛后";
            case ROOTKING -> "根冠古树";
            case HOURGLASS -> "镜砂术士";
        };
    }

    /**
     * 该物种的**基础**撞击伤害（玩家 100 点生命下的取值）。
     *
     * <p><b>两个刻度不要混淆</b>：
     * <ul>
     *   <li>{@link #hitPoints()} 是敌人自己的血量，走「玩家攻击刻度」——玩家基础攻击 10 点，
     *       小怪几十、精英上百、首领数百，所以打一只小怪要好几发；</li>
     *   <li>{@code attackDamage()} 是敌人打到**玩家**身上的伤害，走「玩家生命刻度」——
     *       玩家满血 100 点，小怪 4～5、精英 8～10、首领 9～12，所以挨十几下才会倒下。</li>
     * </ul>
     * 两套刻度各自内部自洽，不要拿一边的数值去衡量另一边。
     *
     * <p>这是“这只怪撞到你就掉多少血”的物种底价，也是它没有单独配数值的招式的默认伤害。
     * 每个招式自己的伤害另在 {@link com.phantomcorridor.model.combat.EnemySkill} 上逐条配置：
     * 同一只怪的重招与小招不该打掉同样多的血。
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
            // v2 小怪
            case BEETLE -> 5.0;
            case SPORE -> 4.0;
            case RAYBAT -> 4.0;
            // v2 首领
            case PRISM_CRAB, ROOTKING, HOURGLASS -> 10.0;
            case MANTIS -> 11.0;
            case WEAVER -> 9.0;
        };
    }
}
