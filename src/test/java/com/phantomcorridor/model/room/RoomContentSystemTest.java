package com.phantomcorridor.model.room;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.Pickup;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 房间内容的生成与交互规则：只生成一次、离开不丢、商店二次确认与价格。
 */
class RoomContentSystemTest {

    @Test
    void shopStockIsGeneratedOncePerRoomAndSurvivesLeaving() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);

        content.enterRoom(shop, player);
        assertEquals(GameConfig.SHOP_OFFER_COUNT, shop.loot().pickups().size(), "商店应当上架固定件数的商品");
        assertTrue(shop.loot().isRolled());
        List<Pickup> firstStock = List.copyOf(shop.loot().pickups());

        // 离开（换房）后再次进入：货架必须原样还在，而且不会重刷成别的东西。
        content.enterRoom(shop, player);
        content.enterRoom(shop, player);
        assertEquals(firstStock, shop.loot().pickups(), "反复进出既不能清空货架，也不能重刷内容");
    }

    @Test
    void shopOffersArePricedAndPlacedApartEnoughToPickOne() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);

        List<Pickup> offers = shop.loot().pickups();
        assertEquals(GameConfig.SHOP_OFFER_COUNT, offers.size());
        for (Pickup offer : offers) {
            int price = RoomContentSystem.priceOf(offer);
            assertTrue(price > 3, "商店价格应当明显高于旧版固定 3 金币，实际 " + price);
            assertEquals(EquipmentType.values()[offer.amount()].price(), price, "售价来自装备自身定价");
            assertTrue(Math.hypot(offer.x() - player.getX(), offer.y() - player.getY()) > GameConfig.INTERACT_RADIUS,
                    "站在房间正中时不该同时够得着商品，否则没法挑");
        }
        assertTrue(Math.hypot(offers.get(0).x() - offers.get(1).x(), offers.get(0).y() - offers.get(1).y())
                > 2 * GameConfig.INTERACT_RADIUS, "商品间距必须大于两倍交互半径");
    }

    @Test
    void shopPurchaseNeedsASecondPressAndResetsWhenWalkingOutOfRange() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);
        player.addCoins(price);

        // 第一次按 E 只是选中：不扣钱、不装备、提示变成确认。
        assertEquals(RoomContentSystem.Outcome.HANDLED, content.interact(shop, player));
        assertTrue(content.isOfferSelected(offer));
        assertEquals(price, player.getCoins());
        assertTrue(player.getEquipment().isEmpty());
        assertTrue(content.prompt(shop, player).startsWith("E  确认购买"), content.prompt(shop, player));

        // 走出范围：选择自动取消。
        player.setPosition(offer.x() + GameConfig.SHOP_CONFIRM_RESET_RADIUS + 30, offer.y());
        content.update(shop, player);
        assertFalse(content.isOfferSelected(offer), "离开商品范围后必须重置选择");

        // 再走回来是重新“购买”，而不是直接停在确认。
        player.setPosition(offer.x(), offer.y());
        assertEquals("E  购买 " + offer.displayName() + " " + price + " 金币", content.prompt(shop, player));
        content.interact(shop, player);
        assertTrue(content.isOfferSelected(offer));
        assertEquals(price, player.getCoins(), "选中不算购买");

        // 第二次按 E 才真正成交。
        content.interact(shop, player);
        assertEquals(0, player.getCoins());
        assertFalse(player.getEquipment().isEmpty(), "购买后应当装备上");
        assertFalse(shop.loot().pickups().contains(offer));
        assertFalse(content.isOfferSelected(offer));
    }

    @Test
    void shopRejectsPurchaseWhenCoinsAreNotEnough() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);
        player.addCoins(price - 1);

        content.interact(shop, player);
        content.interact(shop, player);

        assertEquals(price - 1, player.getCoins(), "金币不足时不能扣款");
        assertTrue(player.getEquipment().isEmpty());
        assertTrue(shop.loot().pickups().contains(offer), "买不起的商品应当留在货架上");
        assertTrue(content.prompt(shop, player).startsWith("金币不足"), content.prompt(shop, player));
    }

    @Test
    void purchasedItemStaysGoneAfterLeavingAndReturning() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        player.addCoins(200);
        content.interact(shop, player);
        content.interact(shop, player);
        int remaining = shop.loot().pickups().size();

        content.enterRoom(shop, player);

        assertEquals(remaining, shop.loot().pickups().size(), "买走的商品不能靠进出房间刷回来");
        assertFalse(shop.loot().pickups().contains(offer));
    }

    @Test
    void clearedBattleRoomKeepsItsChestUntilItIsOpened() {
        Room battle = openRoom(4, RoomType.BATTLE);
        battle.setCleared(true);
        Player player = new Player(battle.doorCenter(Direction.NORTH),
                battle.minY() + RoomConfig.CHEST_OFFSET_Y);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(11L, 1);

        assertTrue(battle.hasUnopenedChest());
        assertTrue(battle.hasRemainingLoot(), "没开的宝箱算作房间还有东西可拿");
        assertEquals("E  打开宝箱", content.prompt(battle, player));

        content.interact(battle, player);

        assertFalse(battle.hasUnopenedChest());
        assertFalse(battle.loot().pickups().isEmpty(), "开箱奖励应当留在房间里等玩家拾取");
        assertTrue(battle.hasRemainingLoot());
    }

    @Test
    void defeatedBattleRoomIsMarkedAsClearedForTheMiniMap() {
        Room battle = openRoom(4, RoomType.BATTLE);
        assertFalse(battle.isDefeatedBattleRoom(), "没打过之前不算已清空");
        battle.setCleared(true);
        assertTrue(battle.isDefeatedBattleRoom());
        assertFalse(openRoom(5, RoomType.REWARD).isDefeatedBattleRoom(), "奖励房不进“已清空”提示");
    }

    @Test
    void pickupsAreNamedAndHealthPacksReadAsPotions() {
        assertEquals("生命恢复药剂", new Pickup(Pickup.Type.HEALTH, 0, 0, 2).displayName());
        assertEquals("金币", new Pickup(Pickup.Type.COIN, 0, 0, 5).displayName());
        assertEquals("相位碎片", new Pickup(Pickup.Type.PHASE_FRAGMENT, 0, 0, 2).displayName());
        Pickup weapon = new Pickup(Pickup.Type.EQUIPMENT, 0, 0, 0);
        assertEquals(EquipmentType.values()[0].displayName(), weapon.displayName());
    }

    @Test
    void interactionPromptAndTargetNameThePickup() {
        Room room = openRoom(2, RoomType.REWARD);
        Player player = new Player(600, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(5L, 1);
        room.loot().addPickup(new Pickup(Pickup.Type.HEALTH, 610, 480, 2));

        assertEquals("E  拾取 生命恢复药剂", content.prompt(room, player));
        assertEquals(new Pickup(Pickup.Type.HEALTH, 610, 480, 2), content.currentTarget(room, player));

        // 走出交互半径：提示与名称标签目标一起消失。
        player.setPosition(900, 480);
        assertEquals("", content.prompt(room, player));
        assertNull(content.currentTarget(room, player));
    }

    @Test
    void healthPickupRestoresTenPointsPerPotionOnTheHundredScale() {
        // 生命刻度改成 100 点之后，拾取物的 amount 是“几瓶药剂”而不是“几点血”：
        // 两瓶 = 20 点。旧实现直接把 amount 当点数用，在 100 点血条上等于没回血。
        Room room = openRoom(2, RoomType.REWARD);
        Player player = new Player(600, 480);
        player.clearShield();
        player.takeDamage(50.0, com.phantomcorridor.model.combat.DamageType.SHADOW);
        assertEquals(50, player.getHp());
        RoomContentSystem content = new RoomContentSystem();
        content.reset(5L, 1);
        room.loot().addPickup(new Pickup(Pickup.Type.HEALTH, 610, 480, 2));

        content.interact(room, player);

        assertEquals(70, player.getHp(), "两瓶生命恢复药剂应当回 20 点生命");
        assertTrue(room.loot().pickups().isEmpty(), "拾取之后药剂应当从地面消失");
    }

    @Test
    void equipmentPromptShowsTheItemName() {
        Room room = openRoom(2, RoomType.REWARD);
        Player player = new Player(600, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(5L, 1);
        Pickup equipment = new Pickup(Pickup.Type.EQUIPMENT, 610, 480, 1);
        room.loot().addPickup(equipment);

        assertEquals("E  装备 " + equipment.displayName(), content.prompt(room, player));
    }

    @Test
    void rewardRoomContentsStayCenteredInsteadOfFollowingTheEntryPosition() {
        Room room = openRoom(12, RoomType.REWARD);
        Player player = new Player(120, 180);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);

        content.enterRoom(room, player);

        assertEquals(2, room.loot().pickups().size());
        double averageX = room.loot().pickups().stream().mapToDouble(Pickup::x).average().orElseThrow();
        double averageY = room.loot().pickups().stream().mapToDouble(Pickup::y).average().orElseThrow();
        assertEquals((room.minX() + room.maxX()) / 2.0, averageX);
        assertEquals((room.minY() + room.maxY()) / 2.0, averageY);
    }

    @Test
    void shopPromptShowsTheItemNameAndPrice() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);

        assertEquals("E  购买 " + offer.displayName() + " " + price + " 金币", content.prompt(shop, player));
    }

    @Test
    void fullEquipmentBarWaitsForAChosenReplacementAndDropsTheOldItem() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        player.equip(EquipmentType.DAWN_WAND);
        player.equip(EquipmentType.SHADOW_FANG);
        player.equip(EquipmentType.DAWN_SEAL);
        Pickup incoming = new Pickup(Pickup.Type.EQUIPMENT, 640, 480, EquipmentType.CRESCENT_REAPER.ordinal());
        room.loot().addPickup(incoming);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player));
        assertSame(incoming, content.pendingEquipment());
        assertTrue(room.loot().pickups().contains(incoming), "未确认替换前，地面装备不能消失");

        assertTrue(content.resolveEquipmentSelection(room, player, 1));
        assertEquals(EquipmentType.CRESCENT_REAPER, player.getEquipment().get(1));
        assertTrue(room.loot().pickups().stream().anyMatch(pickup ->
                pickup.type() == Pickup.Type.EQUIPMENT && pickup.amount() == EquipmentType.SHADOW_FANG.ordinal()));
    }

    @Test
    void cancellingAReplacementKeepsTheGroundEquipmentAndStopsRePrompting() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        Pickup incoming = new Pickup(Pickup.Type.EQUIPMENT, 640, 480, EquipmentType.CRESCENT_REAPER.ordinal());
        room.loot().addPickup(incoming);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player));
        assertTrue(content.resolveEquipmentSelection(room, player, -1), "ESC 必须能取消");
        assertNull(content.pendingEquipment());
        assertEquals(3, player.getEquipment().size());
        assertTrue(room.loot().pickups().contains(incoming), "取消拾取不能把地面装备吃掉");

        // 关掉面板之后不再重复追问：否则玩家一按 ESC 就被重新弹面板，永远回不到暂停。
        assertTrue(content.isDismissed(incoming, player));
        assertEquals(RoomContentSystem.Outcome.NONE, content.interact(room, player, false),
                "非主动交互（每帧自动路径）不能把刚关掉的面板重新弹出来");
        assertNull(content.pendingEquipment());
        // 但提示仍然要写明“再按 E 就能重新考虑”，否则玩家会以为这件装备再也拿不了。
        assertTrue(content.prompt(room, player).startsWith("E  换装"), content.prompt(room, player));

        // 走开一段时间再回来：玩家不按键就不会自动弹面板。
        player.setPosition(incoming.x() + GameConfig.INTERACT_RADIUS + 60, incoming.y());
        assertEquals("", content.prompt(room, player), "走远之后也够不着，没有提示");
        player.setPosition(incoming.x(), incoming.y());
        assertEquals(RoomContentSystem.Outcome.NONE, content.interact(room, player, false));
        assertTrue(content.prompt(room, player).startsWith("E  换装"),
                "回到装备旁边只是重新显示提示，不自动弹面板：" + content.prompt(room, player));
    }

    @Test
    void dismissedEquipmentIsPickedUpAgainWithOneMorePress() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        Pickup incoming = new Pickup(Pickup.Type.EQUIPMENT, 640, 480, EquipmentType.CRESCENT_REAPER.ordinal());
        room.loot().addPickup(incoming);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player));
        assertTrue(content.resolveEquipmentSelection(room, player, -1));
        assertTrue(content.isDismissed(incoming, player));

        // 玩家主动再按一次 E：解除“已放弃”，重新进入替换选择。
        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player),
                "再按一次 E 应当允许重新考虑");
        assertFalse(content.isDismissed(incoming, player));
        assertSame(incoming, content.pendingEquipment());
        assertEquals(3, player.getEquipment().size(), "重新进入选择时装备栏也不该变化");

        // 这一次真的换掉：换下来的那一件落在原位置，新装备进槽位。
        assertTrue(content.resolveEquipmentSelection(room, player, 1));
        assertEquals(EquipmentType.CRESCENT_REAPER, player.getEquipment().get(1));
        assertTrue(room.loot().pickups().stream().anyMatch(pickup ->
                        pickup.type() == Pickup.Type.EQUIPMENT && pickup.amount() == EquipmentType.SHADOW_FANG.ordinal()),
                "换下来的影牙短刃留在地上");
    }

    @Test
    void shopCannotOpenTheReplacementPanelWhenCoinsAreNotEnough() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);
        player.addCoins(price - 1);

        content.interact(shop, player);   // 选中
        RoomContentSystem.Outcome outcome = content.interact(shop, player);   // 确认购买

        assertEquals(RoomContentSystem.Outcome.HANDLED, outcome,
                "买不起时不能弹替换面板，否则玩家选完槽位才被告知没钱");
        assertNull(content.pendingEquipment());
        assertTrue(content.prompt(shop, player).startsWith("金币不足"), content.prompt(shop, player));
        assertTrue(shop.loot().pickups().contains(offer), "买不起的商品留在货架上");
        assertEquals(price - 1, player.getCoins());
    }

    @Test
    void buyingIntoAFullBarChargesExactlyOnceAndDropsTheReplacedItemInTheShop() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);
        player.addCoins(price + 5);

        content.interact(shop, player);   // 选中
        assertEquals("E  确认购买 " + price + " 金币（装备栏已满，需选择替换）", content.prompt(shop, player),
                "选中阶段就要说明满栏，玩家才知道按下去会弹替换面板");

        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(shop, player));
        assertTrue(content.isPendingPurchase());
        assertEquals(price, content.pendingPrice());
        assertEquals("装备栏已满：按 1-3 替换，ESC 取消", content.prompt(shop, player));
        assertEquals(price + 5, player.getCoins(), "弹面板时还没成交，不能先扣钱");

        EquipmentType replaced = player.getEquipment().get(0);
        assertTrue(content.resolveEquipmentSelection(shop, player, 0));

        assertEquals(5, player.getCoins(), "只扣一次款");
        assertEquals(EquipmentType.values()[offer.amount()], player.getEquipment().get(0), "买到的新装备进槽位");
        assertFalse(shop.loot().pickups().contains(offer), "已购买的商品下架");
        Pickup dropped = shop.loot().pickups().stream()
                .filter(pickup -> pickup.type() == Pickup.Type.EQUIPMENT
                        && pickup.amount() == replaced.ordinal())
                .findFirst()
                .orElseThrow(() -> new AssertionError("被替换下来的装备应当落在商店地面上"));

        // 掉在商店里的装备不是商品：没有价签，捡回来也不该再花钱。
        assertEquals(-1, RoomContentSystem.priceOf(dropped), "玩家丢下的装备不能变成商品");
        assertFalse(dropped.shopGoods());
        assertEquals("E  换装 " + replaced.displayName() + "（装备栏已满，需选择替换）",
                content.prompt(shop, player),
                "被替换下来的装备离玩家最近，提示应当是“换装”而不是带价签的购买");

        // 腾出一格再走回去捡：按 E 直接上身，金币一分不动。
        player.removeEquipment(1);
        player.setPosition(dropped.x(), dropped.y());
        assertEquals(RoomContentSystem.Outcome.HANDLED, content.interact(shop, player));
        assertEquals(5, player.getCoins(), "捡回自己换下的装备不能扣钱");
        assertEquals(1, player.equipmentCount(replaced), "捡回来的还是原来那一件");
        assertFalse(shop.loot().pickups().contains(dropped));
    }

    @Test
    void droppingEquipmentLandsWithinReachSoItCanBePickedBackUp() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        player.equip(EquipmentType.WAYFARER_HEART);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);
        int maxHpWithHeart = player.maxHp();

        assertTrue(content.dropEquipment(room, player, 0));
        assertTrue(player.getEquipment().isEmpty(), "丢弃后装备栏必须空出来");
        assertTrue(player.maxHp() < maxHpWithHeart, "摘掉心核后生命上限要跟着降下来");

        Pickup ground = room.loot().pickups().getFirst();
        double distance = Math.hypot(ground.x() - player.getX(), ground.y() - player.getY());
        assertTrue(distance <= GameConfig.INTERACT_RADIUS,
                "丢在脚下的装备必须仍在交互半径内（实际 " + distance + "）");
        assertEquals(EquipmentType.WAYFARER_HEART.ordinal(), ground.amount(), "落地的必须是刚丢掉的那一件");

        // 走开再回来照样能捡：掉落物挂在房间上，不随玩家离开而消失。
        player.setPosition(player.getX() + 300, player.getY());
        content.update(room, player);
        player.setPosition(ground.x(), ground.y());
        assertEquals(RoomContentSystem.Outcome.HANDLED, content.interact(room, player));
        assertEquals(1, player.equipmentCount(EquipmentType.WAYFARER_HEART), "捡回来的还是同一件装备");
        assertEquals(maxHpWithHeart, player.maxHp());
        assertTrue(room.loot().pickups().isEmpty());
    }

    @Test
    void combatBlocksEquipmentPickupDropAndReplacement() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        Pickup incoming = new Pickup(Pickup.Type.EQUIPMENT, 640, 480, EquipmentType.CRESCENT_REAPER.ordinal());
        room.loot().addPickup(incoming);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        // 战斗中按 E：装备留在原地，不弹换装面板。
        assertEquals(RoomContentSystem.Outcome.NONE, content.interact(room, player, true, true));
        assertNull(content.pendingEquipment());
        assertEquals(3, player.getEquipment().size());
        assertTrue(room.loot().pickups().contains(incoming), "战斗中拾取失败不能把地面装备吃掉");
        assertEquals("战斗中无法换装", content.prompt(room, player, true));

        // 战斗中数字键：不丢东西。
        assertFalse(content.dropEquipment(room, player, 0, true));
        assertEquals(3, player.getEquipment().size(), "战斗中丢弃必须被拒绝");
        assertTrue(room.loot().pickups().size() == 1, "地面上不该多出掉落物");

        // 脱战之后一切照旧：先拾取（进入替换选择），再真正替换。
        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player, true, false));
        assertTrue(content.resolveEquipmentSelection(room, player, 0));
        assertEquals(EquipmentType.CRESCENT_REAPER, player.getEquipment().get(0));
        assertTrue(content.dropEquipment(room, player, 0, false), "脱战之后可以正常丢弃");
    }

    @Test
    void combatDoesNotBlockPickingUpANonEquipmentReward() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        Pickup coins = new Pickup(Pickup.Type.COIN, 640, 480, 7);
        room.loot().addPickup(coins);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        // 金币、药剂这些不涉及装备栏的东西不受战斗限制，否则清怪路上捡不了钱。
        assertEquals("E  拾取 金币", content.prompt(room, player, true), "非装备拾取的提示不受战斗影响");
        assertEquals(RoomContentSystem.Outcome.HANDLED, content.interact(room, player, true, true));
        assertEquals(7, player.getCoins());
        assertFalse(room.loot().pickups().contains(coins));
    }

    @Test
    void combatKeepsTheShopSelectionInsteadOfOpeningTheReplacementPanel() {
        Room shop = openRoom(3, RoomType.SHOP);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(42L, 1);
        content.enterRoom(shop, player);
        Pickup offer = nearestOffer(shop, player);
        int price = RoomContentSystem.priceOf(offer);
        player.addCoins(price + 5);

        content.interact(shop, player, true, true);    // 选中（金币够，选中不受战斗限制）
        assertEquals(RoomContentSystem.Outcome.HANDLED, content.interact(shop, player, true, true));
        assertNull(content.pendingEquipment(), "战斗中不弹换装面板");
        assertEquals(price + 5, player.getCoins(), "没成交就不能扣钱");
        assertTrue(shop.loot().pickups().contains(offer), "商品还在货架上");
        assertEquals("战斗中无法换装", content.prompt(shop, player, true));

        // 清完怪再按一次 E：照常进入替换选择并成交。
        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(shop, player, true, false));
        assertTrue(content.resolveEquipmentSelection(shop, player, 0));
        assertEquals(5, player.getCoins());
    }

    @Test
    void twoDropsFromTheSameSpotDoNotStackIntoOneUnreachablePile() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        assertTrue(content.dropEquipment(room, player, 0));
        assertTrue(content.dropEquipment(room, player, 0));
        assertEquals(2, room.loot().pickups().size());
        Pickup first = room.loot().pickups().get(0);
        Pickup second = room.loot().pickups().get(1);
        assertTrue(Math.hypot(first.x() - second.x(), first.y() - second.y()) > 1.0,
                "两件掉落物不能完全重叠，否则没法只捡回其中一件");
        for (Pickup pickup : room.loot().pickups()) {
            double distance = Math.hypot(pickup.x() - player.getX(), pickup.y() - player.getY());
            assertTrue(distance <= GameConfig.INTERACT_RADIUS, "第二件也必须够得着（实际 " + distance + "）");
        }
    }

    @Test
    void replacementSelectionGuardsAgainstBadInputAndReentry() {
        Room room = openRoom(9, RoomType.REWARD);
        Player player = new Player(640, 480);
        fillEquipmentBar(player);
        Pickup incoming = new Pickup(Pickup.Type.EQUIPMENT, 640, 480, EquipmentType.CRESCENT_REAPER.ordinal());
        room.loot().addPickup(incoming);
        RoomContentSystem content = new RoomContentSystem();
        content.reset(1L, 1);

        assertFalse(content.resolveEquipmentSelection(room, player, 0), "没有待处理的选择时不该改任何状态");
        assertFalse(content.dropEquipment(room, player, 7), "越界槽位丢弃应当失败");
        assertEquals(3, player.getEquipment().size());

        content.interact(room, player);
        assertEquals(RoomContentSystem.Outcome.EQUIPMENT_SELECTION, content.interact(room, player),
                "面板打开时再按 E 不应当重复入队");
        assertFalse(content.resolveEquipmentSelection(room, player, 3), "越界槽位不能替换");
        assertSame(incoming, content.pendingEquipment(), "非法输入后面板保持打开");

        assertTrue(content.resolveEquipmentSelection(room, player, 2));
        assertNull(content.pendingEquipment());
        assertFalse(content.resolveEquipmentSelection(room, player, 0), "结算一次后选择状态必须清干净");
    }

    /** 装满三格：所有“满栏”路径的前置条件。 */
    private static void fillEquipmentBar(Player player) {
        player.equip(EquipmentType.DAWN_WAND);
        player.equip(EquipmentType.SHADOW_FANG);
        player.equip(EquipmentType.DAWN_SEAL);
    }

    /** 把玩家放到离某件商品最近的位置，避免测试依赖商店的摆放顺序。 */
    private static Pickup nearestOffer(Room shop, Player player) {
        Pickup nearest = shop.loot().pickups().getFirst();
        double best = Double.MAX_VALUE;
        for (Pickup offer : shop.loot().pickups()) {
            double distance = Math.hypot(offer.x() - player.getX(), offer.y() - player.getY());
            if (distance < best) {
                best = distance;
                nearest = offer;
            }
        }
        player.setPosition(nearest.x(), nearest.y());
        return nearest;
    }

    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, 1280, 960)), List.of());
    }
}
