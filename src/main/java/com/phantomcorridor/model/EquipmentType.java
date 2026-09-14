package com.phantomcorridor.model;

/**
 * 可装备武器/饰品及其首轮属性。
 *
 * <p>每个枚举常量描述的是**一件**装备：装备栏固定三格，同名装备允许重复占用多个槽位，
 * 数值增益按件数叠加（两把「晨曦法杖」= 光界攻击 +2），所以凡是数值型效果都要用
 * {@link com.phantomcorridor.model.entity.Player#equipmentCount(EquipmentType)} 结算，
 * 只有“形态切换”类的开关型效果（例如三叉杖把光弹改成三向散射）才看“有没有”。
 *
 * <p>{@code price} 是商店售价（金币）：定位在“打两三个房间刚好买得起一件”，
 * 让商店成为需要攒钱的目标而不是随手就买。
 */
public enum EquipmentType {
    DAWN_WAND("晨曦法杖", "光", "光弹伤害 +20%，速度 +10%", 0, 24, 0),
    SHADOW_FANG("影牙短刃", "影", "影斩伤害 +20%，冷却 -10%", 1, 24, 0),
    RIFT_TWINBLADE("裂界双端刃", "双界", "两种形态伤害 +10%", 2, 32, 0),
    DAWN_SEAL("晨曦圣印", "光", "光弹伤害 +10%", 3, 16, 0),
    DUSK_CLOAK("暮色斗篷", "影", "影界移速额外 +10%", 4, 18, 0),
    /** 唯一撑护盾的饰品：换层重置护盾时，容量整体抬高，等于每层多一条更厚的临时血。 */
    PHASE_VESSEL("相位容器", "通用", "护盾上限 +12，进层时重置为一整条", 5, 20, 12),

    PRISM_FAN_WAND("棱光三叉杖", "光", "光弹三向散射（±14°），每发 0.45×伤害，间隔 ×1.10", 6, 34, 0, "PRISM_FAN_WAND"),
    SUNLANCE("贯日长杖", "光", "光矛穿透 3 敌：1.00/0.75/0.50×伤害，速度 ×1.35", 7, 38, 0, "SUNLANCE"),
    MIRROR_ORB("折镜法球", "光", "首发 0.85×，命中后弹射 0.55×/0.35×", 8, 46, 0, "MIRROR_ORB"),
    SOLAR_BURST_STAFF("炽核权杖", "光", "光核速度 ×0.70，命中爆裂半径 80，爆裂 1.10×", 9, 48, 0, "SOLAR_BURST_STAFF"),
    CRESCENT_REAPER("环月镰", "影", "360°环斩，范围 ×0.80、伤害 ×0.80，间隔 ×1.20", 10, 34, 0, "CRESCENT_REAPER"),
    RETURNING_FANG("归影双刃", "影", "影刃往返距离 ×1.60，出/返程各 0.55×伤害", 11, 38, 0, "RETURNING_FANG"),
    NIGHTFALL_GREATSWORD("夜坠重剑", "影", "40°重劈，距离 ×1.35、伤害 ×1.60，前摇 0.18 秒", 12, 42, 0, "NIGHTFALL_GREATSWORD"),
    ECLIPSE_RELAY("蚀界仪", "双界", "光界双发 0.55×（间隔 0.10 秒）；影界 0.75×+0.45×复斩", 13, 52, 0, "ECLIPSE_RELAY"),
    SUNWEAVE_MANTLE("曜纹披肩", "光", "光界受击减伤 30%，冷却 6 秒", 14, 28, 0, "SUNWEAVE_MANTLE"),
    NIGHTSTEP_CLOAK("夜行披风", "影", "影界攻击后移速 +18%，持续 0.45 秒", 15, 32, 0, "NIGHTSTEP_CLOAK"),
    FOCUS_LENS("凝光透镜", "光", "光界伤害 +25%，攻击间隔 ×1.15", 16, 30, 0, "FOCUS_LENS"),
    HUNTERS_FANG("猎影牙饰", "影", "对生命 >70% 的敌人影伤 +25%", 17, 34, 0, "HUNTERS_FANG"),
    RESONANCE_RING("余震指环", "双界", "每第 4 轮攻击追加震波（光 0.30×/影 0.25×）", 18, 44, 0, "RESONANCE_RING"),
    PHASE_GYROSCOPE("相位陀螺", "通用", "切界后攻击间隔 ×0.85，持续 2 秒", 19, 36, 0, "PHASE_GYROSCOPE"),
    WAYFARER_HEART("行者心核", "通用", "最大生命 +20%（装备不补血）", 20, 28, 0, "WAYFARER_HEART");

    // 新增装备：攻击方案与说明来自“新增15件装备与攻击特效设计”。
    // iconIndex 保留给旧图集兼容；assetId 用于加载独立的高清贴图。

    private final String displayName;
    private final String affinity;
    private final String description;
    private final int iconIndex;
    private final int price;
    private final double shieldCapacityBonus;
    private final String assetId;

    EquipmentType(String displayName, String affinity, String description, int iconIndex, int price,
                  double shieldCapacityBonus) {
        this(displayName, affinity, description, iconIndex, price, shieldCapacityBonus, null);
    }

    EquipmentType(String displayName, String affinity, String description, int iconIndex, int price,
                  double shieldCapacityBonus, String assetId) {
        this.displayName = displayName; this.affinity = affinity;
        this.description = description; this.iconIndex = iconIndex; this.price = price;
        this.shieldCapacityBonus = shieldCapacityBonus;
        this.assetId = assetId;
    }
    public String displayName() { return displayName; }
    public String affinity() { return affinity; }
    public String description() { return description; }
    public int iconIndex() { return iconIndex; }
    /** 商店售价（金币）。 */
    public int price() { return price; }
    /** 该装备提供的护盾上限加成（未提供时为 0）。 */
    public double shieldCapacityBonus() { return shieldCapacityBonus; }
    /** 独立贴图资源 ID；旧装备没有时回退到旧图集。 */
    public String assetId() { return assetId; }
}
