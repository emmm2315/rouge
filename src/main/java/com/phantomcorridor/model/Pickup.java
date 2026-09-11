package com.phantomcorridor.model;

/**
 * 房间奖励、敌人掉落或玩家丢弃的轻量拾取物。
 *
 * <p>装备的 {@code amount} 是 {@link EquipmentType} 的下标，所以「是哪一件」这件事跟着物品一起
 * 存在 {@code Pickup} 里：换装与丢弃只要把同一个下标重新放进地面，捡回来就还是原来那一件。
 *
 * @param shopGoods 是不是商店货架上的商品。**玩家丢下的装备一律是 {@code false}**：
 *                  换装/丢弃产生的装备落在商店房间里时，它只是掉在地上，不能再被当成商品
 *                  挂上价签——否则玩家把自己换下来的装备“买”回去等于白花一次钱。
 */
public record Pickup(Type type, double x, double y, int amount, boolean shopGoods) {
    public enum Type { COIN, PHASE_FRAGMENT, HEALTH, ITEM, EQUIPMENT }

    /** 非商店商品的拾取物（奖励、宝箱、事件、玩家丢弃）。 */
    public Pickup(Type type, double x, double y, int amount) {
        this(type, x, y, amount, false);
    }

    /** 商店货架上的商品。 */
    public static Pickup shopGoods(Type type, double x, double y, int amount) {
        return new Pickup(type, x, y, amount, true);
    }

    /**
     * 拾取物显示名：地面名称标签与交互提示共用。
     *
     * <p>回血道具不能只画成一个红包就了事，统一叫「生命恢复药剂」，玩家一眼能认出是什么。
     */
    public String displayName() {
        return switch (type) {
            case COIN -> "金币";
            case PHASE_FRAGMENT -> "相位碎片";
            case HEALTH -> "生命恢复药剂";
            case ITEM -> "道具";
            case EQUIPMENT -> EquipmentType.values()[
                    Math.floorMod(amount, EquipmentType.values().length)].displayName();
        };
    }
}
