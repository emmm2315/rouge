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
        /** 装备栏已满，等待玩家选择要替换的槽位或取消。 */
        EQUIPMENT_SELECTION,
        /** 事件房抽到伏击：需要会话层在当前房间刷出敌人。 */
        EVENT_AMBUSH,
        /** 走进首领房清空后出现的传送门：需要会话层推进层数或结算通关。 */
        PORTAL
    }

    private long dungeonSeed;
    private int floor = 1;
    private Pickup selectedOffer;
    private Pickup pendingEquipment;
    private boolean pendingPurchase;
    /**
     * 刚被 ESC 关掉替换面板的那一件装备。
     *
     * <p>用来实现“ESC 先取消替换、再按一次才暂停”：取消后装备仍在地上，但**不立刻重复弹面板**，
     * 否则玩家一按 ESC 就被重新追问，永远回不到暂停。走开一段距离或把它捡走即可解除。
     */
    private Pickup dismissedEquipment;

    public void reset(long dungeonSeed, int floor) {
        this.dungeonSeed = dungeonSeed;
        this.floor = Math.max(1, floor);
        selectedOffer = null;
        dismissedEquipment = null;
        clearPendingEquipment();
    }

    /** 清空满栏替换的全部中间状态。 */
    private void clearPendingEquipment() {
        pendingEquipment = null;
        pendingPurchase = false;
    }


    /**
     * 进入房间：第一次进入时按种子生成内容，之后原样保留。
     *
     * <p>换房一定会清掉“待确认商品”，所以离开商店再回来不会直接停在确认状态。
     */
    public void enterRoom(Room room, Player player) {
        selectedOffer = null;
        clearPendingEquipment();
        dismissedEquipment = null;
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
                    loot.addPickup(Pickup.shopGoods(Pickup.Type.EQUIPMENT,
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

    /**
     * 处理一次 E 交互（玩家主动按键，允许重新考虑刚被 ESC 放弃的那一件）。
     *
     * <p>每帧的自动交互走 {@link #interact(Room, Player, boolean, boolean)} 并传 {@code false}，
     * 否则刚关掉的面板会在同一帧被重新弹出来。
     */
    public Outcome interact(Room room, Player player) {
        return interact(room, player, true, false);
    }

    /**
     * 非战斗状态下的交互：{@code reconsider == false} 表示这次调用来自每帧的自动路径。
     *
     * @see #interact(Room, Player, boolean, boolean)
     */
    public Outcome interact(Room room, Player player, boolean reconsider) {
        return interact(room, player, reconsider, false);
    }

    /**
     * 处理一次 E 交互。
     *
     * <p>{@code reconsider == false} 时，刚被 ESC 放弃的那一件不再追问：ESC 之后玩家可以接着
     * 按 ESC 暂停，不会被重新弹出的面板挡住。玩家真的又按了一次 E（{@code reconsider == true}）
     * 就说明他是想重新考虑，此时解除“已放弃”并照常进入替换选择。
     *
     * @param inCombat 当前是否处于战斗中；战斗中不允许换装（见 {@link #collectEquipment}）
     */
    public Outcome interact(Room room, Player player, boolean reconsider, boolean inCombat) {
        if (pendingEquipment != null) return Outcome.EQUIPMENT_SELECTION;
        RoomLoot loot = room.loot();
        if (room.type() == RoomType.EVENT && loot.isEventPending()) return resolveEvent(room);
        if (isPortalTarget(room, player)) return Outcome.PORTAL;
        if (room.hasUnopenedChest() && nearChest(room, player)) return openChest(room);
        Pickup target = nearestPickup(room, player);
        if (target == null) return Outcome.NONE;
        if (isDismissed(target, player)) {
            if (!reconsider) return Outcome.NONE;
            dismissedEquipment = null;
        }
        if (room.type() == RoomType.SHOP && priceOf(target) >= 0) return trade(room, player, target, inCombat);
        if (target.type() == Pickup.Type.EQUIPMENT) return collectEquipment(room, player, target, inCombat);
        collect(player, target);
        loot.removePickup(target);
        return Outcome.HANDLED;
    }

    /** 当前应当显示的交互提示；没有可交互内容时返回空串。 */
    public String prompt(Room room, Player player) {
        return prompt(room, player, false);
    }

    /**
     * 当前应当显示的交互提示。
     *
     * @param inCombat 当前是否处于战斗中：战斗中装备类交互会被拦下，提示必须说明原因，
     *                 否则玩家会以为按键失灵
     */
    public String prompt(Room room, Player player, boolean inCombat) {
        if (pendingEquipment != null) {
            return inCombat
                    ? "战斗中无法换装：清空敌人后再按 1-3 / ESC"
                    : "装备栏已满：按 1-3 替换，ESC 取消";
        }
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
            if (player.getCoins() < price) {
                // 金币不够时替换面板不会弹出，提示必须说明原因，否则玩家会以为按键失灵。
                return "金币不足：需 " + price + "，当前 " + player.getCoins();
            }
            if (player.isEquipmentFull()) {
                return inCombat
                        ? "战斗中无法换装"
                        : "E  确认购买 " + price + " 金币（装备栏已满，需选择替换）";
            }
            return "E  确认购买 " + price + " 金币（走开取消）";
        }
        if (target.type() != Pickup.Type.EQUIPMENT) return "E  拾取 " + target.displayName();
        if (inCombat) return "战斗中无法换装";
        return player.isEquipmentFull()
                ? "E  换装 " + target.displayName() + "（装备栏已满，需选择替换）"
                : "E  装备 " + target.displayName();
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

    /**
     * 商品售价（金币）；不是可售装备时返回 -1。
     *
     * <p>只有货架上的商品才有价签。玩家换装或丢弃落在商店地面的装备是 {@code shopGoods == false}，
     * 按 {@code E} 直接捡起，不会被要求再付一次钱。
     */
    public static int priceOf(Pickup pickup) {
        if (pickup.type() != Pickup.Type.EQUIPMENT || !pickup.shopGoods()) return -1;
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
    private Outcome trade(Room room, Player player, Pickup offer, boolean inCombat) {
        if (selectedOffer != offer) {
            selectedOffer = offer;
            return Outcome.HANDLED;
        }
        int price = priceOf(offer);
        // 金币不足：保持选中，提示写明所缺金额。必须先于“满栏替换”判定，
        // 否则会先弹替换面板、玩家选完槽位才被告知买不起。
        if (player.getCoins() < price) return Outcome.HANDLED;
        if (player.isEquipmentFull()) {
            // 战斗中不弹换装面板：面板是模态的，在弹幕里停下来选槽位等于挨打。
            // 保留选中状态，清完怪再按一次 E 就能继续。
            if (inCombat) return Outcome.HANDLED;
            pendingEquipment = offer;
            pendingPurchase = true;
            return Outcome.EQUIPMENT_SELECTION;
        }
        if (!player.spendCoins(price)) return Outcome.HANDLED;
        player.equip(EquipmentType.values()[offer.amount()]);
        room.loot().removePickup(offer);
        selectedOffer = null;
        return Outcome.HANDLED;
    }

    /**
     * 地面装备：装有空位就直接上身，满栏则转入替换选择。
     *
     * <p>战斗中直接放弃这次交互——装备留在原地，清完怪再按 E 即可。
     */
    private Outcome collectEquipment(Room room, Player player, Pickup pickup, boolean inCombat) {
        if (inCombat) return Outcome.NONE;
        if (player.isEquipmentFull()) {
            pendingEquipment = pickup;
            pendingPurchase = false;
            return Outcome.EQUIPMENT_SELECTION;
        }
        player.equip(EquipmentType.values()[Math.floorMod(pickup.amount(), EquipmentType.values().length)]);
        room.loot().removePickup(pickup);
        return Outcome.HANDLED;
    }

    /**
     * 满栏装备选择：{@code slot < 0} 表示取消（装备留在原地），否则用新装备替换该槽位。
     *
     * <p>被替换下来的装备落在**原拾取物的位置**，所以它不会随玩家走开而消失，
     * 也不会被这次交互吃掉——想反悔就走回去再捡一次。
     *
     * @return 是否消耗了这次按键；槽位越界或钱不够时返回 {@code false}，面板保持打开
     */
    public boolean resolveEquipmentSelection(Room room, Player player, int slot) {
        if (pendingEquipment == null) return false;
        if (slot < 0) {
            dismissedEquipment = pendingEquipment;
            clearPendingEquipment();
            return true;
        }
        if (slot >= player.getEquipment().size()) return false;
        // 购买路径的二次校验：进面板之后金币可能已经花在别处。
        if (pendingPurchase && player.getCoins() < priceOf(pendingEquipment)) return false;
        if (pendingPurchase && !player.spendCoins(priceOf(pendingEquipment))) return false;
        EquipmentType incoming = EquipmentType.values()[Math.floorMod(pendingEquipment.amount(), EquipmentType.values().length)];
        EquipmentType discarded = player.replaceEquipment(slot, incoming);
        room.loot().removePickup(pendingEquipment);
        if (discarded != null) room.loot().addPickup(new Pickup(Pickup.Type.EQUIPMENT,
                pendingEquipment.x(), pendingEquipment.y(), discarded.ordinal()));
        dismissedEquipment = null;
        clearPendingEquipment();
        selectedOffer = null;
        return true;
    }

    /**
     * 丢弃指定槽位（非战斗状态的便捷入口）。
     *
     * @see #dropEquipment(Room, Player, int, boolean)
     */
    public boolean dropEquipment(Room room, Player player, int slot) {
        return dropEquipment(room, player, slot, false);
    }

    /**
     * 将指定装备放到玩家脚下；RoomLoot 挂在房间上，因此离房后回来仍可拾取。
     *
     * <p>战斗中不开放丢弃：一次误触就可能把关键装备扔在怪堆里，而且捡回来要重新穿过弹幕。
     */
    public boolean dropEquipment(Room room, Player player, int slot, boolean inCombat) {
        if (inCombat || pendingEquipment != null) return false;
        EquipmentType discarded = player.removeEquipment(slot);
        if (discarded == null) return false;
        double[] offset = dropOffset(room, player);
        room.loot().addPickup(new Pickup(Pickup.Type.EQUIPMENT,
                player.getX() + offset[0], player.getY() + offset[1], discarded.ordinal()));
        return true;
    }

    /**
     * 丢弃物的落点：玩家脚下的一个小扇形。
     *
     * <p>落点必须同时满足两件事：<b>留在原地</b>（玩家走开也不会消失，可以回来再捡）且
     * <b>不越出交互半径</b>（否则丢在脚下的东西反而按 E 够不着）。所以半径取一个略小于
     * {@link GameConfig#INTERACT_RADIUS} 的定值，并按玩家周围已有几件装备逐件旋开 45°，
     * 让连丢几件同类装备时它们不会叠成一点、分不清要捡哪一件。
     */
    private static double[] dropOffset(Room room, Player player) {
        double distance = Math.min(GameConfig.INTERACT_RADIUS * 0.75, GameConfig.PLAYER_RADIUS + 34.0);
        double angle = Math.atan2(player.getFacingY(), player.getFacingX())
                + nearbyDrops(room, player) * (Math.PI / 4.0);
        return new double[]{distance * Math.cos(angle), distance * Math.sin(angle)};
    }

    /** 玩家附近已经躺着几件装备（含刚被替换下来的那件）。 */
    private static int nearbyDrops(Room room, Player player) {
        int count = 0;
        for (Pickup pickup : room.loot().pickups()) {
            if (pickup.type() == Pickup.Type.EQUIPMENT && distance(player, pickup) <= GameConfig.INTERACT_RADIUS) {
                count++;
            }
        }
        return count;
    }

    /** 等待替换选择的那件装备；为空代表装备栏未处于选择状态。 */
    public Pickup pendingEquipment() { return pendingEquipment; }

    /** 这件装备是否刚被 ESC 放弃、且玩家还在它附近（此时既不弹面板也不显示提示）。 */
    public boolean isDismissed(Pickup pickup, Player player) {
        return pickup != null && pickup == dismissedEquipment
                && distance(player, pickup) <= GameConfig.INTERACT_RADIUS;
    }

    /** 这次替换是否来自商店购买（要扣金币）。 */
    public boolean isPendingPurchase() { return pendingPurchase; }

    /** 本次替换需要支付的金币；不是购买时为 0。 */
    public int pendingPrice() { return pendingPurchase ? priceOf(pendingEquipment) : 0; }

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
