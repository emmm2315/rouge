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
