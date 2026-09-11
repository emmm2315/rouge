package com.phantomcorridor.model;

/**
 * 可装备武器/饰品及其首轮属性。每个槽位只保留一件。
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

    PRISM_FAN_WAND("棱光三叉杖", "光", "光弹变为 -14°/0°/+14° 三向散射", 6, 34, 0, "PRISM_FAN_WAND"),
    SUNLANCE("贯日长杖", "光", "细长光矛，最多穿透三个敌人", 7, 38, 0, "SUNLANCE"),
    MIRROR_ORB("折镜法球", "光", "命中后向附近敌人弹射两次", 8, 46, 0, "MIRROR_ORB"),
    SOLAR_BURST_STAFF("炽核权杖", "光", "慢速光核命中后范围爆裂", 9, 48, 0, "SOLAR_BURST_STAFF"),
    CRESCENT_REAPER("环月镰", "影", "近身攻击改为周身环斩", 10, 34, 0, "CRESCENT_REAPER"),
    RETURNING_FANG("归影双刃", "影", "短距影刃飞出并回旋", 11, 38, 0, "RETURNING_FANG"),
    NIGHTFALL_GREATSWORD("夜坠重剑", "影", "窄角度、长距离重劈", 12, 42, 0, "NIGHTFALL_GREATSWORD"),
    ECLIPSE_RELAY("蚀界仪", "双界", "光界二连发，影界延迟复斩", 13, 52, 0, "ECLIPSE_RELAY"),
    SUNWEAVE_MANTLE("曜纹披肩", "光", "光界受伤减免 30%，冷却 6 秒", 14, 28, 0, "SUNWEAVE_MANTLE"),
    NIGHTSTEP_CLOAK("夜行披风", "影", "影斩后短暂加速", 15, 32, 0, "NIGHTSTEP_CLOAK"),
    FOCUS_LENS("凝光透镜", "光", "光界伤害 +25%，攻击间隔 ×1.15", 16, 30, 0, "FOCUS_LENS"),
    HUNTERS_FANG("猎影牙饰", "影", "对高生命敌人的影界伤害 +25%", 17, 34, 0, "HUNTERS_FANG"),
    RESONANCE_RING("余震指环", "双界", "每第四轮攻击追加一次震波", 18, 44, 0, "RESONANCE_RING"),
    PHASE_GYROSCOPE("相位陀螺", "通用", "切界后攻击间隔 ×0.85，持续 2 秒", 19, 36, 0, "PHASE_GYROSCOPE"),
    WAYFARER_HEART("行者心核", "通用", "最大生命 +20%", 20, 28, 0, "WAYFARER_HEART");

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
