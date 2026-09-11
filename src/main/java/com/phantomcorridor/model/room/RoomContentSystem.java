package com.phantomcorridor.model.room;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.ItemType;
import com.phantomcorridor.model.Pickup;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.entity.Player;

/**
 * 房间内容（奖励、商店、宝箱、事件）的生成与交互规则。
 *
 * <p>三条硬性约束：
 * <ul>
 *   <li><b>只生成一次</b>：内容在房间第一次进入时按种子生成并保存在 {@link RoomLoot} 上，
 *       之后反复进出既不会重刷，也不会丢失——旧实现把地面物品存在会话层并在换房时清表，
 *       于是“进商店看一眼、出门再回来”货架就空了；</li>
 *   <li><b>怪物房不复活</b>：清空过的战斗/首领房再次进入时 {@code EnemySystem} 直接跳过生成；</li>
 *   <li><b>商店二次确认</b>：第一次按 E 只选中并显示价格，第二次才扣款，
 *       玩家走出 {@link GameConfig#SHOP_CONFIRM_RESET_RADIUS} 后选择自动取消，避免误触。</li>
 * </ul>
 */
public final class RoomContentSystem {

    /** 一次交互的结果：会话层据此接管需要其他系统的分支。 */
    public enum Outcome {
        /** 范围内没有可交互的内容。 */
        NONE,
        /** 已在本系统内处理完（拾取、装备、选中商品、完成购买、开箱、事件结算）。 */
        HANDLED,
        /** 事件房抽到伏击：需要会话层在当前房间刷出敌人。 */
        EVENT_AMBUSH,
        /** 走进首领房清空后出现的传送门：需要会话层推进层数或结算通关。 */
        PORTAL
    }

    private long dungeonSeed;
    private int floor = 1;
    private Pickup selectedOffer;

    public void reset(long dungeonSeed, int floor) {
        this.dungeonSeed = dungeonSeed;
        this.floor = Math.max(1, floor);
        this.selectedOffer = null;
    }

    /**
     * 进入房间：第一次进入时按种子生成内容，之后原样保留。
     *
     * <p>换房一定会清掉“待确认商品”，所以离开商店再回来不会直接停在确认状态。
     */
    public void enterRoom(Room room, Player player) {
        selectedOffer = null;
        RoomLoot loot = room.loot();
        if (loot.isRolled()) return;
        loot.markRolled();
        switch (room.type()) {
            case REWARD -> {
                // 奖励不再跟随玩家刚进门的位置：以房间几何中心为原点摆放成一个小组，
                // 不规则房间也能稳定落在主区域中央。
                double x = centerX(room);
                double y = centerY(room);
                loot.addPickup(new Pickup(Pickup.Type.COIN, x + 28, y, 5));
                loot.addPickup(new Pickup(Pickup.Type.EQUIPMENT, x - 28, y,
                        equipmentIndex(room, 1)));
            }
            case SHOP -> {
                for (int i = 0; i < GameConfig.SHOP_OFFER_COUNT; i++) {
                    double offset = (i - (GameConfig.SHOP_OFFER_COUNT - 1) / 2.0) * GameConfig.SHOP_OFFER_SPACING;
                    loot.addPickup(new Pickup(Pickup.Type.EQUIPMENT,
                            centerX(room) + offset, centerY(room), equipmentIndex(room, i)));
                }
            }
            case EVENT -> loot.setEventPending(true);
            default -> { }
        }
    }

    /** 每帧调用：玩家走出商品范围后取消二次确认，下次靠近要重新按 E 选中。 */
    public void update(Room room, Player player) {
        if (selectedOffer == null) return;
        boolean stillOffered = room.type() == RoomType.SHOP
                && room.loot().pickups().contains(selectedOffer)
                && distance(player, selectedOffer) <= GameConfig.SHOP_CONFIRM_RESET_RADIUS;
        if (!stillOffered) selectedOffer = null;
    }

    /** 处理一次 E 交互。 */
    public Outcome interact(Room room, Player player) {
        RoomLoot loot = room.loot();
        if (room.type() == RoomType.EVENT && loot.isEventPending()) return resolveEvent(room);
        if (isPortalTarget(room, player)) return Outcome.PORTAL;
        if (room.hasUnopenedChest() && nearChest(room, player)) return openChest(room);
        Pickup target = nearestPickup(room, player);
        if (target == null) return Outcome.NONE;
        if (room.type() == RoomType.SHOP && priceOf(target) >= 0) return trade(room, player, target);
        collect(player, target);
        loot.removePickup(target);
        return Outcome.HANDLED;
    }

    /** 当前应当显示的交互提示；没有可交互内容时返回空串。 */
    public String prompt(Room room, Player player) {
        RoomLoot loot = room.loot();
        if (room.type() == RoomType.EVENT && loot.isEventPending()) return "E  触发事件";
        if (isPortalTarget(room, player)) {
            return floor >= GameConfig.TOTAL_FLOORS
                    ? "E  穿过裂隙（通关）"
                    : "E  进入传送门（第 " + (floor + 1) + " 层）";
        }
        if (room.hasUnopenedChest() && nearChest(room, player)) return "E  打开宝箱";
        Pickup target = nearestPickup(room, player);
        if (target == null) return "";
        int price = room.type() == RoomType.SHOP ? priceOf(target) : -1;
        if (price >= 0) {
            if (target != selectedOffer) return "E  购买 " + target.displayName() + " " + price + " 金币";
            return player.getCoins() >= price
                    ? "E  确认购买 " + price + " 金币（走开取消）"
                    : "金币不足：需 " + price + "，当前 " + player.getCoins();
        }
        return target.type() == Pickup.Type.EQUIPMENT
                ? "E  装备 " + target.displayName()
                : "E  拾取 " + target.displayName();
    }

    /**
     * 当前交互目标（地面上离玩家最近、且在交互范围内的拾取物）；宝箱、事件或传送门是目标时返回 null。
     *
     * <p>提示文本与地面名称标签都取自这里，保证两处判断永远一致。
     */
    public Pickup currentTarget(Room room, Player player) {
        if (room.type() == RoomType.EVENT && room.loot().isEventPending()) return null;
        if (isPortalTarget(room, player)) return null;
        if (room.hasUnopenedChest() && nearChest(room, player)) return null;
        return nearestPickup(room, player);
    }

    /** 传送门是否就是当前该交互的目标：和宝箱同时在房间里时按距离决出。 */
    private static boolean isPortalTarget(Room room, Player player) {
        if (!room.hasPortal()) return false;
        double toPortal = Math.hypot(player.getX() - centerX(room), player.getY() - centerY(room));
        if (toPortal > GameConfig.INTERACT_RADIUS) return false;
        if (!room.hasUnopenedChest()) return true;
        return toPortal < Math.hypot(player.getX() - room.doorCenter(Direction.NORTH),
                player.getY() - (room.minY() + RoomConfig.CHEST_OFFSET_Y));
    }

    /** 商品售价（金币）；不是可售装备时返回 -1。 */
    public static int priceOf(Pickup pickup) {
        if (pickup.type() != Pickup.Type.EQUIPMENT) return -1;
        EquipmentType[] values = EquipmentType.values();
        if (pickup.amount() < 0 || pickup.amount() >= values.length) return -1;
        return values[pickup.amount()].price();
    }

    /** 该商品是否已经被玩家选中、正等待二次确认。 */
    public boolean isOfferSelected(Pickup pickup) { return pickup == selectedOffer; }

    /** 玩家金币是否够买这件商品。 */
    public static boolean canAfford(Player player, Pickup pickup) {
        int price = priceOf(pickup);
        return price >= 0 && player.getCoins() >= price;
    }

    /** 第一次按 E 只选中；再按一次才真正扣款装备。 */
    private Outcome trade(Room room, Player player, Pickup offer) {
        if (selectedOffer != offer) {
            selectedOffer = offer;
            return Outcome.HANDLED;
        }
        int price = priceOf(offer);
        if (!player.spendCoins(price)) return Outcome.HANDLED;   // 金币不足：保持选中，提示写明所缺金额
        player.equip(EquipmentType.values()[offer.amount()]);
        room.loot().removePickup(offer);
        selectedOffer = null;
        return Outcome.HANDLED;
    }

    private Outcome openChest(Room room) {
        RoomLoot loot = room.loot();
        loot.openChest();
        if (loot.isChestRewardGranted()) return Outcome.HANDLED;
        loot.grantChestReward();
        double x = room.doorCenter(Direction.NORTH);
        double y = room.minY() + RoomConfig.CHEST_OFFSET_Y;
        int reward = Math.floorMod((int) (dungeonSeed + room.id() * 31L), 4);
        if (reward == 0) loot.addPickup(new Pickup(Pickup.Type.COIN, x, y, 8 + Math.floorMod(room.id(), 13)));
        else if (reward == 1) loot.addPickup(new Pickup(Pickup.Type.HEALTH, x, y, 2));
        else if (reward == 2) loot.addPickup(new Pickup(Pickup.Type.PHASE_FRAGMENT, x, y, 2));
        else loot.addPickup(new Pickup(Pickup.Type.EQUIPMENT, x, y, equipmentIndex(room, 2)));
        return Outcome.HANDLED;
    }

    /** 事件房：一次性的祝福/诅咒，触发后不再重复出现。 */
    private Outcome resolveEvent(Room room) {
        room.loot().setEventPending(false);
        double x = centerX(room);
        double y = centerY(room);
        int roll = Math.floorMod((int) (dungeonSeed + room.id() * 17L), 4);
        if (roll == 0) room.loot().addPickup(new Pickup(Pickup.Type.HEALTH, x, y, 2));
        else if (roll == 1) room.loot().addPickup(new Pickup(Pickup.Type.PHASE_FRAGMENT, x, y, 3));
        else if (roll == 2) room.loot().addPickup(new Pickup(Pickup.Type.EQUIPMENT, x, y, equipmentIndex(room, 3)));
        else return Outcome.EVENT_AMBUSH;
        return Outcome.HANDLED;
    }

    private static void collect(Player player, Pickup pickup) {
        switch (pickup.type()) {
            case COIN -> player.addCoins(pickup.amount());
            case PHASE_FRAGMENT -> player.restorePhaseEnergy(
                    GameConfig.PHASE_ENERGY_PER_FRAGMENT * Math.max(1, pickup.amount()));
            // 药剂按“瓶”算：一瓶回复 PLAYER_HEAL_PER_PICKUP 点生命，
            // 拾取物里的 amount 是瓶数，不随玩家最大生命（100 点）的刻度一起变形。
            case HEALTH -> player.restoreHealthByPickups(Math.max(1, pickup.amount()));
            case ITEM -> player.addItem(ItemType.values()[Math.floorMod(pickup.amount(), ItemType.values().length)]);
            case EQUIPMENT -> player.equip(
                    EquipmentType.values()[Math.floorMod(pickup.amount(), EquipmentType.values().length)]);
        }
    }

    private static Pickup nearestPickup(Room room, Player player) {
        Pickup nearest = null;
        double nearestDistance = GameConfig.INTERACT_RADIUS;
        for (Pickup pickup : room.loot().pickups()) {
            double distance = distance(player, pickup);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = pickup;
            }
        }
        return nearest;
    }

    private static boolean nearChest(Room room, Player player) {
        return Math.hypot(player.getX() - room.doorCenter(Direction.NORTH),
                player.getY() - (room.minY() + RoomConfig.CHEST_OFFSET_Y)) <= GameConfig.INTERACT_RADIUS;
    }

    private static double distance(Player player, Pickup pickup) {
        return Math.hypot(player.getX() - pickup.x(), player.getY() - pickup.y());
    }

    private static double centerX(Room room) { return (room.minX() + room.maxX()) / 2.0; }

    private static double centerY(Room room) { return (room.minY() + room.maxY()) / 2.0; }

    /** 按种子与该房间固定下来的装备下标；salt 用来让同房多件商品互不相同。 */
    private int equipmentIndex(Room room, int salt) {
        int count = EquipmentType.values().length;
        return Math.floorMod((int) (dungeonSeed + room.id() * 31L) + salt * 5, count);
    }
}
