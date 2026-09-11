package com.phantomcorridor.model;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.combat.EnemyProjectile;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Direction;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionTest {

    @Test
    void worldShiftConsumesPulseAndClearsNearbyEnemyProjectiles() {
        GameSession session = new GameSession();
        session.newRun();
        double x = session.getPlayer().getX();
        double y = session.getPlayer().getY();
        session.getEnemyProjectiles().add(new EnemyProjectile(x + 20.0, y, WorldType.LIGHT));
        session.getEnemyProjectiles().add(new EnemyProjectile(
                x + GameConfig.PHASE_PULSE_RADIUS + 20.0, y, WorldType.LIGHT));

        assertTrue(session.tryShiftWorld());
        assertTrue(session.isPhasePulseVisible());
        assertEquals(1, session.getEnemyProjectiles().getProjectiles().size());
        assertFalse(session.getWorldShift().consumePulse(), "脉冲事件必须由会话层即时消费");
    }

    @Test
    void shopKeepsItsStockWhenThePlayerLeavesAndComesBack() {
        GameSession session = new GameSession();
        session.newRun("1");
        Room entrance = openRoom(0, RoomType.ENTRANCE, 0, 0);
        Room shop = openRoom(1, RoomType.SHOP, 0, -1);
        enterMap(session, entrance, shop);

        walkThroughDoor(session, Direction.NORTH);
        assertEquals(RoomType.SHOP, session.getNavigation().getCurrentRoom().type());
        int stock = session.getPickups().size();
        assertEquals(GameConfig.SHOP_OFFER_COUNT, stock, "商店第一次进入应当上架商品");
        assertTrue(session.getShopPrice(session.getPickups().getFirst()) > 0, "商品必须标价");

        // 出门再回来：货架既不能空掉，也不能重刷。
        walkThroughDoor(session, Direction.SOUTH);
        assertEquals(RoomType.ENTRANCE, session.getNavigation().getCurrentRoom().type());
        walkThroughDoor(session, Direction.NORTH);

        assertEquals(RoomType.SHOP, session.getNavigation().getCurrentRoom().type());
        assertEquals(stock, session.getPickups().size(), "离开商店再回来不能把货架清空");
    }

    @Test
    void defeatedBattleRoomNeverSpawnsEnemiesAgain() {
        GameSession session = new GameSession();
        session.newRun("1");
        Room entrance = openRoom(0, RoomType.ENTRANCE, 0, 0);
        Room battle = openRoom(1, RoomType.BATTLE, 0, -1);
        enterMap(session, entrance, battle);

        walkThroughDoor(session, Direction.NORTH);
        assertFalse(session.getEnemies().getEnemies().isEmpty(), "第一次进入战斗房应当刷怪");

        // 清空房间：门解锁、宝箱出现、房间被标记为已清空。
        for (Enemy enemy : new ArrayList<>(session.getEnemies().getEnemies())) enemy.damage(999);
        for (int frame = 0; frame < 5; frame++) update(session);
        assertTrue(session.getEnemies().getEnemies().isEmpty());
        assertTrue(session.getNavigation().getCurrentRoom().isCleared(), "清空后房间应当标记为已清空");
        assertTrue(session.isChestVisible(), "清空战斗房后应当出现宝箱");
        assertTrue(session.getNavigation().getCurrentRoom().hasRemainingLoot());

        walkThroughDoor(session, Direction.SOUTH);
        walkThroughDoor(session, Direction.NORTH);

        assertEquals(RoomType.BATTLE, session.getNavigation().getCurrentRoom().type());
        assertTrue(session.getEnemies().getEnemies().isEmpty(), "已击败的怪物房再次进入不能重新刷怪");
        assertTrue(session.isChestVisible(), "没开的宝箱不会因为进出房间消失");
        assertTrue(session.getNavigation().getCurrentRoom().isCleared());
    }

    @Test
    void difficultyIsChosenAtRunStartAndDrivesEnemyStats() {
        GameSession normal = new GameSession();
        normal.newRun("2024", Difficulty.NORMAL);
        GameSession insane = new GameSession();
        insane.newRun("2024", Difficulty.INSANE);

        assertEquals(Difficulty.NORMAL, normal.getDifficulty());
        assertEquals(Difficulty.INSANE, insane.getDifficulty());

        // 同一个种子、同一批敌人：屌炸天那局每个敌人的生命值都必须更高。
        var normalEnemies = normal.getEnemies().getEnemies();
        var insaneEnemies = insane.getEnemies().getEnemies();
        assertEquals(normalEnemies.size(), insaneEnemies.size());
        for (int i = 0; i < normalEnemies.size(); i++) {
            assertEquals(normalEnemies.get(i).getKind(), insaneEnemies.get(i).getKind());
            assertTrue(insaneEnemies.get(i).getMaxHp() > normalEnemies.get(i).getMaxHp(),
                    insaneEnemies.get(i).getKind() + " 在屌炸天下应当更肉");
        }
    }

    @Test
    void difficultySurvivesFloorChanges() {
        GameSession session = new GameSession();
        session.newRun("2024", Difficulty.HARD);

        enterFloorBossRoom(session);
        defeatTheBoss(session);
        useThePortal(session);

        assertEquals(2, session.getFloor());
        assertEquals(Difficulty.HARD, session.getDifficulty(), "层数推进不应改变难度");
        assertTrue(session.getEnemies().getFloor() == 2);
        assertTrue(session.getEnemies().getEnemies().stream().allMatch(enemy -> enemy.getDifficulty() == Difficulty.HARD),
                "新一层的敌人要按同一难度生成");
    }

    @Test
    void bossSummonsReinforcementsAndTheyVanishWhenItFalls() {
        GameSession session = new GameSession();
        session.newRun("2024");
        enterFloorBossRoom(session);
        Enemy boss = session.getEnemies().getEnemies().getFirst();
        assertTrue(boss.isBoss());
        // 打到第一阶段以下：召唤不再等开场缓冲。
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        boolean riftsSeen = false;
        for (int frame = 0; frame < 8 * 60 && !riftsSeen; frame++) {
            update(session);
            riftsSeen = !session.getSummonRifts().isEmpty();
        }
        assertTrue(riftsSeen, "首领跌破血量阶段时应当撕开召唤裂隙");
        assertTrue(session.getRoomAnnouncement().contains("召唤"),
                "召唤要给玩家一条即时提示，实际：" + session.getRoomAnnouncement());

        for (int frame = 0; frame < 4 * 60 && session.getSummonedCount() == 0; frame++) update(session);
        assertEquals(GameConfig.WATCHER_SUMMON_COUNT, session.getSummonedCount(), "裂隙成型后放出召唤物");
        assertEquals(3, session.getLightEnemyCount(), "HUD 的残敌数必须把召唤物算进去：首领 + 两只增援");

        // 只打死首领：它的造物要跟着溃散，房间照样立刻清空、传送门照常出现。
        for (Enemy enemy : new ArrayList<>(session.getEnemies().getEnemies())) {
            if (enemy.isBoss()) enemy.damage(999);
        }
        for (int frame = 0; frame < 5; frame++) update(session);

        assertEquals(0, session.getSummonedCount(), "首领倒下时召唤物应当一并溃散");
        assertTrue(session.getNavigation().getCurrentRoom().isCleared(), "首领清空后房间应当标记为已清空");
    }

    @Test
    void startPositionOnTheFirstFloorIsAnnounced() {
        GameSession session = new GameSession();
        session.newRun("2024");

        assertEquals(1, session.getFloor());
        assertEquals(GameConfig.TOTAL_FLOORS, session.getTotalFloors());
        assertFalse(session.isRunCleared());
        assertTrue(session.getRoomAnnouncement().contains("第 1 层"),
                "进入初始房间要提示当前层数，实际：" + session.getRoomAnnouncement());
    }

    @Test
    void defeatingTheBossOpensAPortalThatAdvancesToTheNextFloor() {
        GameSession session = new GameSession();
        session.newRun("2024");
        session.getPlayer().addCoins(7);
        session.getPlayer().equip(com.phantomcorridor.model.EquipmentType.DAWN_WAND);

        enterFloorBossRoom(session);
        defeatTheBoss(session);
        assertTrue(session.getNavigation().getCurrentRoom().hasPortal(), "击败首领后应当刷出传送门");

        useThePortal(session);

        assertEquals(2, session.getFloor(), "走进传送门应当进入下一层");
        assertTrue(session.getRoomAnnouncement().contains("第 2 层"),
                "进入新一层的初始房间要提示层数，实际：" + session.getRoomAnnouncement());
        assertEquals(1, session.getPlayer().getEquipment().size(), "装备跨层保留");
        assertTrue(session.getCoins() >= 7, "金币跨层保留");
        assertTrue(session.getNavigation().getCurrentRoom().type() == RoomType.ENTRANCE,
                "新一层从入口房开始");
    }

    @Test
    void newRunClearsEquipmentAndDoesNotInheritItsMaxHpOrShield() {
        GameSession session = new GameSession();
        session.newRun("2024");
        Player player = session.getPlayer();
        player.equip(com.phantomcorridor.model.EquipmentType.WAYFARER_HEART);
        player.equip(com.phantomcorridor.model.EquipmentType.PHASE_VESSEL);
        player.equip(com.phantomcorridor.model.EquipmentType.WAYFARER_HEART);
        assertTrue(player.maxHp() > GameConfig.PLAYER_MAX_HP, "两件心核应当把生命上限抬上去");
        assertTrue(player.getMaxShield() > GameConfig.PLAYER_SHIELD_CAPACITY, "相位容器应当撑大护盾容量");

        session.newRun("2024");

        assertTrue(session.getPlayer().getEquipment().isEmpty(), "新一局必须清空上一局的装备");
        assertEquals(GameConfig.PLAYER_MAX_HP, session.getPlayer().maxHp(), "新一局不继承心核的生命上限");
        assertEquals(GameConfig.PLAYER_SHIELD_CAPACITY, session.getPlayer().getMaxShield(), 1e-9,
                "新一局不继承相位容器的护盾容量");
    }

    @Test
    void clearingTheFifthFloorWinsTheRun() {
        GameSession session = new GameSession();
        session.newRun("2024");

        for (int floor = 1; floor <= GameConfig.TOTAL_FLOORS; floor++) {
            assertEquals(floor, session.getFloor());
            enterFloorBossRoom(session);
            defeatTheBoss(session);
            useThePortal(session);
        }

        assertTrue(session.isRunCleared(), "穿过第五层的传送门应当通关");
        assertFalse(session.getEnemies().getEnemies().stream().anyMatch(enemy -> enemy.isBoss() && !enemy.isDead()));
    }

    @Test
    void enemiesGetTougherOnDeeperFloors() {
        GameSession session = new GameSession();
        session.newRun("2024");
        int firstFloorHp = session.getEnemies().getFloor();
        assertEquals(1, firstFloorHp);

        enterFloorBossRoom(session);
        defeatTheBoss(session);
        useThePortal(session);
        enterFloorBossRoom(session);

        var boss = session.getEnemies().getEnemies().getFirst();
        assertTrue(boss.isBoss());
        assertEquals(2, session.getFloor());
        assertTrue(boss.getMaxHp() > boss.getKind().hitPoints(),
                "第二层的首领生命值应当高于它的基础值，实际 " + boss.getMaxHp());
        assertTrue(boss.getDefense() > 0, "第二层的敌人应当有防御");
    }

    /** 把玩家直接放进当前层的 Boss 房（用自定义小地图替代生成的地图）。 */
    private static void enterFloorBossRoom(GameSession session) {
        Room entrance = openRoom(0, RoomType.ENTRANCE, 0, 0);
        Room boss = openRoom(1, RoomType.BOSS, 0, -1);
        enterMap(session, entrance, boss);
        walkThroughDoor(session, Direction.NORTH);
        assertEquals(RoomType.BOSS, session.getNavigation().getCurrentRoom().type());
    }

    private static void defeatTheBoss(GameSession session) {
        assertFalse(session.getEnemies().getEnemies().isEmpty(), "首领房应当有首领");
        // 用「当前生命上限」而不是写死的数字：首领生命随层数与难度增长，写死会在高层打不死。
        for (Enemy enemy : new ArrayList<>(session.getEnemies().getEnemies())) enemy.damage(enemy.getMaxHp());
        for (int frame = 0; frame < 5; frame++) update(session);
        assertTrue(session.getNavigation().getCurrentRoom().isCleared(), "首领清空后房间应当标记为已清空");
    }

    /** 走到房间中央（传送门位置）并按 E。 */
    private static void useThePortal(GameSession session) {
        Room room = session.getNavigation().getCurrentRoom();
        double centerX = (room.minX() + room.maxX()) / 2.0;
        double centerY = (room.minY() + room.maxY()) / 2.0;
        for (int frame = 0; frame < 300; frame++) {
            double dx = centerX - session.getPlayer().getX();
            double dy = centerY - session.getPlayer().getY();
            if (Math.hypot(dx, dy) < 8) break;
            session.update(AppConfig.FIXED_DT, dx, dy, dx, dy, false);
        }
        session.requestInteract();
        update(session);
    }

    private static void enterMap(GameSession session, Room entrance, Room second) {
        entrance.connect(Direction.NORTH, second.id());
        second.connect(Direction.SOUTH, entrance.id());
        session.getNavigation().reset(new DungeonMap(List.of(entrance, second)));
        session.getNavigation().placeAtEntrance(session.getPlayer());
        update(session);
    }

    /** 朝门口一直走，直到真的换了房间。 */
    private static void walkThroughDoor(GameSession session, Direction direction) {
        Room room = session.getNavigation().getCurrentRoom();
        double[] door = doorTarget(room, direction);
        for (int frame = 0; frame < 900; frame++) {
            if (session.getNavigation().getCurrentRoom() != room) return;
            double dx = door[0] - session.getPlayer().getX();
            double dy = door[1] - session.getPlayer().getY();
            session.update(AppConfig.FIXED_DT, dx, dy, dx, dy, false);
        }
        fail("一直朝 " + direction + " 走却没有换到下一间房");
    }

    private static double[] doorTarget(Room room, Direction direction) {
        return switch (direction) {
            case NORTH -> new double[]{room.doorCenter(Direction.NORTH), room.minY()};
            case SOUTH -> new double[]{room.doorCenter(Direction.SOUTH), room.maxY()};
            case WEST -> new double[]{room.minX(), room.doorCenter(Direction.WEST)};
            case EAST -> new double[]{room.maxX(), room.doorCenter(Direction.EAST)};
        };
    }

    private static void update(GameSession session) {
        session.update(AppConfig.FIXED_DT, 0, 0, session.getPlayer().getX(), session.getPlayer().getY(), false);
    }

    private static Room openRoom(int id, RoomType type, int mapX, int mapY) {
        return new Room(id, type, mapX, mapY, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)), List.of());
    }
}
