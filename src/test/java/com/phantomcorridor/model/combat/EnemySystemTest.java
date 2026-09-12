package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.dungeon.MapGenerator;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomFlowField;
import com.phantomcorridor.model.room.RoomNavigationSystem;
import com.phantomcorridor.model.room.RoomShape;
import com.phantomcorridor.model.room.Wall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EnemySystemTest {
    private static final double DT = AppConfig.FIXED_DT;

    @Test
    void battleRoomSpawnsFiveToSevenEnemiesAcrossBothWorlds() {
        Room room = new Room(3, RoomType.BATTLE, 0, 0);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0);
        EnemySystem system = new EnemySystem();

        system.enterRoom(room, 42L, player, navigation);

        int count = system.getEnemies().size();
        assertTrue(count >= GameConfig.BATTLE_ENEMY_MIN && count <= GameConfig.BATTLE_ENEMY_MAX);
        assertTrue(system.getCount(WorldType.LIGHT) > 0);
        assertTrue(system.getCount(WorldType.SHADOW) > 0);
        assertFalse(room.isCleared());
    }

    @Test
    void bossRoomSpawnsOnlyTheWatcher() {
        Room room = new Room(8, RoomType.BOSS, 0, 0);
        RoomNavigationSystem navigation = navigationFor(room);
        EnemySystem system = new EnemySystem();

        system.enterRoom(room, 7L, new Player(AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0), navigation);

        assertEquals(1, system.getEnemies().size());
        assertEquals(EnemyKind.WATCHER, system.getEnemies().getFirst().getKind());
        assertEquals(WorldType.LIGHT, system.getEnemies().getFirst().getWorld());
    }

    @Test
    void detectionRangeCoversTheWholeRoom() {
        double roomDiagonal = Math.hypot(AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);

        assertTrue(EnemySystem.detectionRange() >= roomDiagonal,
                "索敌范围必须覆盖房间对角线，否则对角位置的敌人永远不会参战");
    }

    @Test
    void enemyAcrossTheRoomLocksOnAndClosesIn() {
        Room room = openRoom(3, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(180, 800);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 42L, player, navigation);
        Enemy enemy = soleEnemy(system, WorldType.LIGHT, player, navigation);
        enemy.setPosition(1120, 120);

        double before = Math.hypot(enemy.getX() - player.getX(), enemy.getY() - player.getY());
        assertTrue(before > 900, "测试前提：敌人开局远在房间另一头");

        runFor(system, player, navigation, 1.0);

        double after = Math.hypot(enemy.getX() - player.getX(), enemy.getY() - player.getY());
        assertTrue(enemy.isAware(), "远处的同界敌人应当锁定玩家");
        assertTrue(after < before - 20, "锁定后应当主动接近，而不是原地不动");
    }

    @Test
    void distantRangedEnemyEventuallyAttacks() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 840);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy watcher = system.getEnemies().getFirst();
        watcher.setPosition(1160, 200);

        double startDistance = Math.hypot(watcher.getX() - player.getX(), watcher.getY() - player.getY());
        assertTrue(startDistance > GameConfig.ENEMY_RANGED_ATTACK_RANGE,
                "测试前提：开局距离超过开火距离");

        boolean fired = false;
        for (int frame = 0; frame < 25 * 60 && !fired; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            fired = !system.getAttacks().isEmpty();
        }

        assertTrue(fired, "远处敌人走近后应当主动开火，而不是一直不攻击玩家");
    }

    @Test
    void projectileLifetimeReachesTheMaximumFiringRange() {
        assertTrue(GameConfig.ENEMY_PROJECTILE_LIFETIME * GameConfig.ENEMY_PROJECTILE_SPEED
                        >= GameConfig.ENEMY_RANGED_ATTACK_RANGE,
                "弹体寿命必须够飞到最大开火距离，否则远距离射击会在半路自行消失");
    }

    @Test
    void enemyBehindWallClosesInWithoutShootingThroughIt() {
        Room room = new Room(3, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)),
                List.of(new Wall(600, 0, 60, 500, null)));
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(200, 250);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 11L, player, navigation);
        Enemy enemy = soleEnemy(system, WorldType.LIGHT, player, navigation);
        enemy.setPosition(1150, 250);

        double beforeX = enemy.getX();
        runFor(system, player, navigation, 1.0);

        assertTrue(enemy.getX() < beforeX - 20, "视线被墙挡住时也要继续接近，而不是僵在原地");
        assertTrue(system.getAttacks().isEmpty(), "没有视线时不能隔墙开火");
    }

    @Test
    void enemyPursuesRegardlessOfPlayerForm() {
        Room room = openRoom(3, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 42L, player, navigation);
        Enemy shadowEnemy = soleEnemy(system, WorldType.SHADOW, player, navigation);
        shadowEnemy.setPosition(player.getX() + 300, player.getY());
        double beforeX = shadowEnemy.getX();
        double beforeY = shadowEnemy.getY();

        runFor(system, player, navigation, 1.0);

        assertEquals(WorldType.LIGHT, player.getCurrentWorld());
        assertTrue(Math.hypot(shadowEnemy.getX() - player.getX(), shadowEnemy.getY() - player.getY())
                < Math.hypot(beforeX - player.getX(), beforeY - player.getY()));
        assertTrue(shadowEnemy.isAware());
    }

    @Test
    void shiftingWorldPreservesEnemyAwareness() {
        Room room = openRoom(3, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 42L, player, navigation);
        List<Enemy> lightEnemies = system.getEnemies().stream()
                .filter(enemy -> enemy.getWorld() == WorldType.LIGHT).toList();
        assertFalse(lightEnemies.isEmpty());

        runFor(system, player, navigation, 0.2);
        assertTrue(lightEnemies.stream().allMatch(Enemy::isAware));

        player.toggleWorld();
        system.onWorldChanged(player.getCurrentWorld());

        assertTrue(lightEnemies.stream().allMatch(Enemy::isAware),
                "共享敌人在切界后继续索敌");
    }

    @Test
    void everySameWorldEnemyInRealRoomsJoinsTheFight() {
        int centralRooms = 0;
        for (long seed = 1L; seed <= 40L; seed++) {
            DungeonMap map = new MapGenerator().generate(seed);
            for (Room room : map.rooms()) {
                if (room.type() != RoomType.BATTLE && room.type() != RoomType.BOSS) continue;
                RoomNavigationSystem navigation = navigationFor(room);
                double centerX = (room.minX() + room.maxX()) / 2.0;
                double centerY = (room.minY() + room.maxY()) / 2.0;
                // 只把玩家放在房间正中的开阔位置；若中心点本身站不下玩家，
                // 退而求其次的落点可能是只有玩家身位挤得进的窄缝，敌人根本到不了，不适合做严格断言。
                boolean centralSpot = navigation.canOccupy(centerX, centerY, GameConfig.PLAYER_RADIUS, WorldType.LIGHT);
                double[] safe = centralSpot ? new double[]{centerX, centerY}
                        : navigation.findNearestSafePosition(centerX, centerY, WorldType.LIGHT);
                assertNotNull(safe, "战斗房必须能给玩家找到安全落点");
                Player player = new Player(safe[0], safe[1]);
                EnemySystem system = new EnemySystem();
                system.enterRoom(room, seed, player, navigation);

                List<Enemy> sameWorldEnemies = system.getEnemies().stream()
                        .filter(enemy -> enemy.getWorld() == player.getCurrentWorld()).toList();
                assertFalse(sameWorldEnemies.isEmpty(), "房间 " + room.id() + " 应当有玩家当前世界的敌人");
                Set<EnemyKind> kindsInPlayerWorld = sameWorldEnemies.stream()
                        .map(Enemy::getKind).collect(Collectors.toSet());
                Map<Enemy, Double> startDistances = sameWorldEnemies.stream()
                        .collect(Collectors.toMap(enemy -> enemy, enemy -> distance(player, enemy)));
                Map<Enemy, Double> closestDistances = new HashMap<>(startDistances);
                Set<EnemyKind> kindsThatFired = EnumSet.noneOf(EnemyKind.class);
                PlayerAttackSystem playerAttacks = new PlayerAttackSystem();
                for (int frame = 0; frame < 30 * 60; frame++) {
                    system.update(DT, player, playerAttacks, navigation);
                    // 只统计开局就在场的这几种敌人：首领中途会召唤增援，召唤物的攻击不该被算成
                    // “开局的敌人参战了”。战斗房不会召唤，首领房开局的种类是 WATCHER，
                    // 而召唤出来的只会是灯灵/影狼/法师，不会和开局的种类撞车。
                    // v2 扩展包里有相当一部分招式不是弹体而是「先画在地上的预警」
                    // （甲虫顶撞、扇面、环带、地面标记），所以预警实体同样算“开火了”。
                    system.getAttacks().stream()
                            .filter(attack -> kindsInPlayerWorld.contains(attack.getSource()))
                            .forEach(attack -> kindsThatFired.add(attack.getSource()));
                    system.getTelegraphs().stream()
                            .filter(telegraph -> kindsInPlayerWorld.contains(telegraph.source()))
                            .forEach(telegraph -> kindsThatFired.add(telegraph.source()));
                    for (Enemy enemy : sameWorldEnemies) {
                        closestDistances.merge(enemy, distance(player, enemy), Math::min);
                    }
                }

                String context = "种子 " + seed + " 的房间 " + room.id() + "（" + room.shape() + "）";
                for (Enemy enemy : sameWorldEnemies) {
                    assertTrue(enemy.isAware(), context + "：" + enemy.getKind() + " 应当锁定玩家");
                    assertTrue(closestDistances.get(enemy) < startDistances.get(enemy)
                                    || startDistances.get(enemy) <= GameConfig.ENEMY_RANGED_STANDOFF_DISTANCE,
                            context + "：" + enemy.getKind() + " 不能对远处的玩家无动于衷");
                }
                if (centralSpot) {
                    assertEquals(kindsInPlayerWorld, kindsThatFired, context
                            + "：玩家站在房间中央时，每种同界敌人都应当走到能开火的位置并攻击玩家");
                    centralRooms++;
                }
            }
        }
        assertTrue(centralRooms >= 20, "至少应当严格校验若干个中央开阔房间，实际 " + centralRooms);
    }

    private static double distance(Player player, Enemy enemy) {
        return Math.hypot(enemy.getX() - player.getX(), enemy.getY() - player.getY());
    }

    @Test
    void enemyWedgedInsideAWallIsFreedAndResumesTheChase() {
        // 模拟“被顶进墙里”：身位和墙重叠，站不下也走不动，只有脱困兜底能救它。
        Room room = new Room(3, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)),
                List.of(new Wall(600, 400, 200, 200, null)));
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(150, 150);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 42L, player, navigation);
        Enemy enemy = soleEnemy(system, WorldType.LIGHT, player, navigation);
        enemy.setPosition(610, 500);
        assertFalse(navigation.canOccupy(610, 500, 27, WorldType.LIGHT), "测试前提：敌人和墙重叠");

        runFor(system, player, navigation, GameConfig.ENEMY_STUCK_TIME + 2.0);

        assertTrue(navigation.canOccupy(enemy.getX(), enemy.getY(), 27, WorldType.LIGHT),
                "卡住的敌人必须被挪到站得下的位置，而不是一直贴在墙上");
        double afterEscape = distance(player, enemy);
        runFor(system, player, navigation, 2.0);
        assertTrue(distance(player, enemy) < afterEscape, "脱困之后应当继续朝玩家推进");
    }

    @Test
    void escapingEnemiesNeverTeleportThroughWalls() {
        // 玩家被围在小屋里、首领在外面转圈：脱困兜底可以把它挪开，但不能隔墙跳进小屋。
        Room room = sealedBoxRoom();
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(400, 480);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 3L, player, navigation);
        Enemy watcher = system.getEnemies().getFirst();
        watcher.setPosition(576, 300);
        watcher.setBlinkCooldown(999.0);   // 关掉裂隙闪现，只看普通脱困

        for (int frame = 0; frame < 12 * 60; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            assertFalse(insideSealedBox(watcher.getX(), watcher.getY()),
                    "脱困不能把敌人隔墙搬进小屋，实际位置 (" + watcher.getX() + "," + watcher.getY() + ")");
        }
    }

    /** 小屋墙体围出来的内部区域（首领站得下但进不来）。 */
    private static boolean insideSealedBox(double x, double y) {
        return x > 346 && x < 454 && y > 456 && y < 504;
    }

    @Test
    void watcherBlinkClosesAGapItCannotWalkThrough() {
        // 玩家被围在一间只有玩家身位挤得进的小屋里：首领（直径 92）正常寻路永远到不了。
        Room room = sealedBoxRoom();
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(400, 480);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 3L, player, navigation);
        Enemy watcher = system.getEnemies().getFirst();
        assertTrue(watcher.isBoss());
        watcher.setPosition(980, 820);
        assertTrue(navigation.canOccupy(980, 820, 46, WorldType.LIGHT), "测试前提：首领站在合法位置");
        RoomFlowField bossField = new RoomFlowField(room);
        bossField.rebuild(navigation, player.getX(), player.getY(), WorldType.LIGHT, 46);
        assertFalse(bossField.isReachable(980, 820), "测试前提：首领与玩家之间没有可走的路");

        boolean sawFlash = false;
        PlayerAttackSystem playerAttacks = new PlayerAttackSystem();
        for (int frame = 0; frame < 20 * 60; frame++) {
            system.update(DT, player, playerAttacks, navigation);
            sawFlash |= system.getBlinkFlash() != null;
        }

        assertTrue(sawFlash, "追不上时守望者应当撕开裂隙闪现");
        assertTrue(navigation.canOccupy(watcher.getX(), watcher.getY(), 46, WorldType.LIGHT),
                "闪现落点必须站得下");
        assertTrue(distance(player, watcher) <= GameConfig.WATCHER_BLINK_SEARCH_RADIUS,
                "闪现后应当出现在玩家附近，实际距离 " + distance(player, watcher));
        assertTrue(distance(player, watcher) > 46 + GameConfig.PLAYER_RADIUS,
                "落点不能直接压在玩家身上，实际距离 " + distance(player, watcher)
                        + "，首领位于 (" + watcher.getX() + "," + watcher.getY() + ")");
    }

    @Test
    void blinkCooldownBlocksASecondRiftJump() {
        Room room = sealedBoxRoom();
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(400, 480);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 3L, player, navigation);
        Enemy watcher = system.getEnemies().getFirst();
        watcher.setPosition(980, 820);
        watcher.setBlinkCooldown(999.0);

        boolean sawFlash = false;
        PlayerAttackSystem playerAttacks = new PlayerAttackSystem();
        for (int frame = 0; frame < 6 * 60; frame++) {
            system.update(DT, player, playerAttacks, navigation);
            sawFlash |= system.getBlinkFlash() != null;
        }

        assertFalse(sawFlash, "冷却期间不能闪现");
    }

    /** 把玩家围在中间的小屋：内部放得下玩家，但首领从外面挤不进来。 */
    private static Room sealedBoxRoom() {
        return new Room(8, RoomType.BOSS, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)),
                List.of(new Wall(300, 380, 200, 30, null), new Wall(300, 550, 200, 30, null),
                        new Wall(270, 380, 30, 200, null), new Wall(500, 380, 30, 200, null)));
    }

    /** 保留房内唯一的指定世界敌人，其余全部清掉，让断言不受其他随机敌人干扰。 */
    private static Enemy soleEnemy(EnemySystem system, WorldType world, Player player, RoomNavigationSystem navigation) {
        Enemy keep = system.getEnemies().stream()
                .filter(enemy -> enemy.getWorld() == world).findFirst().orElseThrow();
        for (Enemy enemy : system.getEnemies()) {
            if (enemy != keep) enemy.damage(enemy.getMaxHp());
        }
        system.update(DT, player, new PlayerAttackSystem(), navigation);
        assertEquals(1, system.getEnemies().size());
        return keep;
    }

    private static void runFor(EnemySystem system, Player player, RoomNavigationSystem navigation, double seconds) {
        int frames = (int) Math.round(seconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
        }
    }

    /** 无障碍的整幅房间，便于按指定坐标摆放玩家与敌人。 */
    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)), List.of());
    }

    private static RoomNavigationSystem navigationFor(Room room) {
        // RoomNavigationSystem 进入房间时会查找相邻节点，这里为邻居补上占位房间，保证单房也能导航。
        List<Room> rooms = new ArrayList<>();
        rooms.add(room);
        for (int neighborId : room.neighbors().values()) {
            rooms.add(new Room(neighborId, RoomType.BATTLE, 0, 0));
        }
        RoomNavigationSystem navigation = new RoomNavigationSystem();
        navigation.reset(new DungeonMap(rooms));
        return navigation;
    }
}
