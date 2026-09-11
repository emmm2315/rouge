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
    PHASE_VESSEL("相位容器", "通用", "护盾上限 +12，进层时重置为一整条", 5, 20, 12);

    private final String displayName;
    private final String affinity;
    private final String description;
    private final int iconIndex;
    private final int price;
    private final double shieldCapacityBonus;

    EquipmentType(String displayName, String affinity, String description, int iconIndex, int price,
                  double shieldCapacityBonus) {
        this.displayName = displayName; this.affinity = affinity;
        this.description = description; this.iconIndex = iconIndex; this.price = price;
        this.shieldCapacityBonus = shieldCapacityBonus;
    }
    public String displayName() { return displayName; }
    public String affinity() { return affinity; }
    public String description() { return description; }
    public int iconIndex() { return iconIndex; }
    /** 商店售价（金币）。 */
    public int price() { return price; }
    /** 该装备提供的护盾上限加成（未提供时为 0）。 */
    public double shieldCapacityBonus() { return shieldCapacityBonus; }
}
