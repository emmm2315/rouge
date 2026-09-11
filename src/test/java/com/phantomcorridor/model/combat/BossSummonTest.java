package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.Difficulty;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.dungeon.MapGenerator;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomNavigationSystem;
import com.phantomcorridor.model.room.RoomShape;
import com.phantomcorridor.model.room.Wall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 首领召唤机制。
 *
 * <p>校验四件事：什么时候召唤（开场缓冲、血量阶段、打不到玩家、场上没人）、
 * 召唤怎么出场（先裂隙后落地、只在自己这一界、离玩家有安全距离）、
 * 召唤有多少（存活上限与裂隙间距）、以及召唤物什么时候消失（首领倒下或跨界）。
 */
class BossSummonTest {
    private static final double DT = AppConfig.FIXED_DT;

    @Test
    void bossFightsAloneForTheOpeningSecondsBeforeCallingReinforcements() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);

        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_OPENING_DELAY - 1.0);
        assertEquals(0, system.getSummonedCount(), "开场缓冲期内不该已经有召唤物");
        assertTrue(system.getSummonRifts().isEmpty(), "开场缓冲期内不该已经开裂隙");

        double summoned = runUntil(system, player, navigation,
                () -> system.getSummonedCount() > 0, GameConfig.WATCHER_SUMMON_COOLDOWN);
        assertTrue(summoned > 0, "开场缓冲结束后，守望者应当召唤增援");
        assertEquals(2, system.getSummonedCount(),
                "一次召唤放两只：本界的猎手 + 法师");
    }

    @Test
    void summonRiftWarnsBeforeTheMinionMaterializes() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);

        // 血量阶段召唤无视开场冷却：首领一掉血就会开裂隙，测试不必干等 6 秒。
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);
        double opened = runUntil(system, player, navigation, () -> !system.getSummonRifts().isEmpty(), 6.0);
        assertTrue(opened > 0, "首领跌破血量阶段时应当立刻开裂隙");

        List<SummonRift> rifts = List.copyOf(system.getSummonRifts());
        assertEquals(GameConfig.WATCHER_SUMMON_COUNT, rifts.size(), "一次召唤应当开两道裂隙");
        assertEquals(0, system.getSummonedCount(), "裂隙成型之前不能已经有召唤物——召唤必须看得见预警");
        for (SummonRift rift : rifts) {
            assertEquals(boss.getWorld(), rift.world(), "裂隙开在首领所在的那一界");
            assertTrue(navigation.canOccupy(rift.x(), rift.y(), 27.0, rift.world()),
                    "裂隙落点必须放得下召唤物身位，实际 (" + rift.x() + "," + rift.y() + ")");
            assertTrue(Math.hypot(rift.x() - player.getX(), rift.y() - player.getY())
                            >= GameConfig.WATCHER_SUMMON_PLAYER_CLEARANCE,
                    "裂隙不能开在玩家脸上，实际距离 "
                            + Math.hypot(rift.x() - player.getX(), rift.y() - player.getY()));
        }
        assertTrue(Math.hypot(rifts.get(0).x() - rifts.get(1).x(), rifts.get(0).y() - rifts.get(1).y())
                        >= GameConfig.WATCHER_SUMMON_RIFT_SPACING,
                "两道裂隙之间要留出间距，否则两只召唤物会叠在一起");

        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_RIFT_TIME + 2 * DT);
        assertTrue(system.getSummonRifts().isEmpty(), "裂隙到时间就该成型");
        assertEquals(GameConfig.WATCHER_SUMMON_COUNT, system.getSummonedCount(), "裂隙成型后放出召唤物");
        for (Enemy minion : system.getEnemies()) {
            if (!minion.isSummoned()) continue;
            assertEquals(boss.getWorld(), minion.getWorld());
            assertTrue(navigation.canOccupy(minion.getX(), minion.getY(), 27.0, minion.getWorld()),
                    "召唤物不能出生在墙里，实际 (" + minion.getX() + "," + minion.getY() + ")");
            assertTrue(minion.getAlertRemaining() > 0.0,
                    "刚钻出裂隙的召唤物应当还在起手时间里，实际 " + minion.getAlertRemaining());
            Enemy reference = new Enemy(minion.getKind(), minion.getWorld(), 0, 0, system.getFloor());
            assertTrue(minion.getMaxHp() < reference.getMaxHp(),
                    "召唤物应当比同层同种的房间怪更脆：" + minion.getKind());
        }
    }

    @Test
    void lightPhaseSummonUsesTheTransformAction() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        boolean sawTransformWindup = false;
        for (int frame = 0; frame < 6 * 60 && !sawTransformWindup; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            sawTransformWindup = boss.getAnimationAction().equals("transform_windup");
        }

        // 素材包里守望者的光形态没有 summon_* 本体动作：光界召唤必须走 transform，
        // 否则渲染层找不到动作帧，首领会在召唤时呆站着。
        assertTrue(sawTransformWindup, "光界召唤应当播放 transform_windup，实际 " + boss.getAnimationAction());
        assertEquals(WorldType.LIGHT, boss.getWorld());
    }

    @Test
    void shadowPhaseSummonUsesTheSummonAction() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        player.toggleWorld();
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setWorld(WorldType.SHADOW);
        boss.setPosition(760, 240);
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        boolean sawSummonWindup = false;
        for (int frame = 0; frame < 6 * 60 && !sawSummonWindup; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            sawSummonWindup = boss.getAnimationAction().equals("summon_windup");
        }

        assertTrue(sawSummonWindup, "影界召唤应当播放 summon_windup，实际 " + boss.getAnimationAction());
    }

    @Test
    void bossThatCannotReachThePlayerCallsForHelp() {
        // 玩家躲在小屋里：首领（直径 92）挤不进去，也不可能隔着墙开火——这正是召唤的用武之地。
        Room room = sealedBoxRoom();
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(400, 480);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 3L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(980, 820);
        boss.setBlinkCooldown(999.0);   // 关掉裂隙闪现：这里只测召唤

        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_PRESSURE_TIME);
        assertEquals(0, system.getSummonedCount(), "还没到冷却就不该召唤");
        assertTrue(boss.getSummonPressure() >= GameConfig.WATCHER_SUMMON_PRESSURE_TIME - DT,
                "打不到玩家的时间应当被记下来，实际 " + boss.getSummonPressure());

        double summoned = runUntil(system, player, navigation,
                () -> system.getSummonedCount() > 0, GameConfig.WATCHER_SUMMON_COOLDOWN);
        assertTrue(summoned > 0, "连续打不到玩家时，守望者应当召唤增援把玩家逼出来");
        assertTrue(system.getEnemies().stream().filter(Enemy::isSummoned)
                        .allMatch(minion -> minion.getWorld() == boss.getWorld()),
                "增援必须和首领同界，否则玩家在另一界根本看不见它们");
    }

    @Test
    void summonWavesNeverExceedTheAliveCap() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);

        int waves = 0;
        int peakLoad = 0;
        for (int frame = 0; frame < 90 * 60; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            waves += system.consumeSummonCalls();
            peakLoad = Math.max(peakLoad, system.getSummonedCount() + system.getSummonRifts().size());
            assertTrue(system.getSummonedCount() + system.getSummonRifts().size()
                            <= GameConfig.WATCHER_SUMMON_MAX_ALIVE,
                    "场上召唤物加裂隙不能超过上限，实际 " + (system.getSummonedCount() + system.getSummonRifts().size()));
            // 玩家把增援清掉：首领应当隔一段时间再补一波，而不是一次堆满。
            for (Enemy enemy : new ArrayList<>(system.getEnemies())) {
                if (enemy.isSummoned()) enemy.damage(enemy.getMaxHp());
            }
        }

        assertTrue(waves >= 3, "清掉增援后应当反复补充，实际只召唤了 " + waves + " 次");
        assertTrue(peakLoad >= 2, "首领至少要真的放出过召唤物");
    }

    @Test
    void summonsAlwaysAppearInTheBossWorldAndOffWalls() {
        for (long seed = 1L; seed <= 8L; seed++) {
            DungeonMap map = new MapGenerator().generate(seed);
            Room room = map.rooms().stream().filter(candidate -> candidate.type() == RoomType.BOSS)
                    .findFirst().orElseThrow();
            RoomNavigationSystem navigation = navigationFor(room);
            double centerX = (room.minX() + room.maxX()) / 2.0;
            double centerY = (room.minY() + room.maxY()) / 2.0;
            double[] safe = navigation.canOccupy(centerX, centerY, GameConfig.PLAYER_RADIUS, WorldType.LIGHT)
                    ? new double[]{centerX, centerY}
                    : navigation.findNearestSafePosition(centerX, centerY, WorldType.LIGHT);
            assertNotNull(safe, "首领房必须能给玩家找到安全落点");
            Player player = new Player(safe[0], safe[1]);
            EnemySystem system = new EnemySystem();
            system.enterRoom(room, seed, player, navigation);
            Enemy boss = system.getEnemies().getFirst();
            // 直接把首领打到第一阶段：不用等开场缓冲，测的是落点规则而不是节奏。
            boss.damage(boss.getMaxHp() * 30 / 100 + 1);

            String context = "种子 " + seed;
            for (int frame = 0; frame < 8 * 60; frame++) {
                system.update(DT, player, new PlayerAttackSystem(), navigation);
                for (SummonRift rift : system.getSummonRifts()) {
                    assertEquals(boss.getWorld(), rift.world(), context + "：裂隙必须在首领这一界");
                    assertTrue(navigation.canOccupy(rift.x(), rift.y(), 27.0, rift.world()),
                            context + "：裂隙落点站不下召唤物 (" + rift.x() + "," + rift.y() + ")");
                }
                for (Enemy enemy : system.getEnemies()) {
                    if (!enemy.isSummoned()) continue;
                    assertEquals(boss.getWorld(), enemy.getWorld(), context + "：召唤物必须在首领这一界");
                    assertTrue(navigation.canOccupy(enemy.getX(), enemy.getY(), 27.0, enemy.getWorld()),
                            context + "：召唤物不能站在墙里 (" + enemy.getX() + "," + enemy.getY() + ")");
                }
            }
            assertTrue(system.getSummonedCount() > 0, context + "：首领应当召唤出增援");
        }
    }

    @Test
    void defeatingTheBossDispelsItsReinforcementsAndPendingRifts() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        assertTrue(runUntil(system, player, navigation, () -> system.getSummonedCount() > 0, 8.0) > 0);
        system.consumeKills();
        boss.damage(boss.getMaxHp());
        runFor(system, player, navigation, 2 * DT);

        assertTrue(system.isRoomCleared(), "首领倒下后房间必须清空，否则传送门不会出现");
        assertEquals(0, system.getSummonedCount(), "首领倒下时它的造物应当一并溃散");
        assertTrue(system.getSummonRifts().isEmpty(), "还没成型的裂隙也必须一起清掉");
        assertEquals(1, system.consumeKills(), "溃散不算击杀：只应计入首领本人");
    }

    @Test
    void defeatingTheBossBeforeTheRiftOpensLeavesNothingBehind() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        assertTrue(runUntil(system, player, navigation, () -> !system.getSummonRifts().isEmpty(), 8.0) > 0,
                "测试前提：裂隙已经开出来");
        assertEquals(0, system.getSummonedCount());

        boss.damage(boss.getMaxHp());
        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_RIFT_TIME + 0.5);

        assertTrue(system.getSummonRifts().isEmpty(), "首领已死，裂隙不能再吐出小怪");
        assertEquals(0, system.getSummonedCount());
        assertTrue(system.isRoomCleared(), "首领死后的房间应当一直是清空状态");
    }

    @Test
    void crossingOverCollapsesTheSummonsOfTheLeftWorld() {
        Room room = openRoom(8, RoomType.BOSS);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(240, 240);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy boss = system.getEnemies().getFirst();
        boss.setPosition(760, 240);
        boss.damage(boss.getMaxHp() * 30 / 100 + 1);

        assertTrue(runUntil(system, player, navigation, () -> system.getSummonedCount() > 0, 8.0) > 0,
                "测试前提：光界已经放出召唤物");
        assertEquals(WorldType.LIGHT, boss.getWorld());

        // 打到半血以下：首领转入影界，光界留下的造物随之溃散（素材包 clearOnBossWorldExit）。
        boss.damage(boss.getMaxHp() / 2);
        runFor(system, player, navigation, DT);

        assertEquals(WorldType.SHADOW, boss.getWorld(), "半血后守望者应当转入影界");
        assertEquals(0, system.getSummonedCount(), "首领离开光界时，光界的召唤物应当溃散");
        assertTrue(system.getSummonRifts().isEmpty(), "未成型的裂隙也一并关闭");
        assertFalse(system.isRoomCleared(), "首领还活着，房间不算清空");
    }

    @Test
    void summonStatsFollowFloorAndDifficulty() {
        // 召唤物同样吃层数成长与难度倍率，只是生命按召唤倍率打折。
        Enemy weak = Enemy.summoned(EnemyKind.WOLF, WorldType.SHADOW, 0, 0, 1, Difficulty.NORMAL,
                GameConfig.WATCHER_SUMMON_HP_SCALE);
        Enemy roomWolf = new Enemy(EnemyKind.WOLF, WorldType.SHADOW, 0, 0, 1);
        assertTrue(weak.isSummoned());
        assertFalse(roomWolf.isSummoned());
        assertTrue(weak.getMaxHp() < roomWolf.getMaxHp());

        Enemy deepFloor = Enemy.summoned(EnemyKind.WOLF, WorldType.SHADOW, 0, 0, GameConfig.TOTAL_FLOORS,
                Difficulty.NORMAL, GameConfig.WATCHER_SUMMON_HP_SCALE);
        assertTrue(deepFloor.getMaxHp() > weak.getMaxHp(), "召唤物也要随层数变强");
        assertTrue(deepFloor.getDefense() > weak.getDefense(), "召唤物也要随层数获得防御");
    }

    // ---- 测试脚手架 ----

    /** 一直跑到条件成立（或超时），返回成立所用秒数；超时返回 -1。 */
    private static double runUntil(EnemySystem system, Player player, RoomNavigationSystem navigation,
                                   BooleanSupplier condition, double limitSeconds) {
        int frames = (int) Math.round(limitSeconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            if (condition.getAsBoolean()) return (frame + 1) * DT;
        }
        return -1.0;
    }

    private static void runFor(EnemySystem system, Player player, RoomNavigationSystem navigation, double seconds) {
        int frames = (int) Math.round(seconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
        }
    }

    /** 把玩家围在中间的小屋：内部放得下玩家，但首领从外面挤不进来。 */
    private static Room sealedBoxRoom() {
        return new Room(8, RoomType.BOSS, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)),
                List.of(new Wall(300, 380, 200, 30, null), new Wall(300, 550, 200, 30, null),
                        new Wall(270, 380, 30, 200, null), new Wall(500, 380, 30, 200, null)));
    }

    /** 无障碍的整幅房间，便于按指定坐标摆放玩家与首领。 */
    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)), List.of());
    }

    private static RoomNavigationSystem navigationFor(Room room) {
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
