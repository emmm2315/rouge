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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗数值重构与「更凶的敌人」验收。
 *
 * <p>四条被重构的规则：
 * <ol>
 *   <li><b>刻度</b>：玩家基础攻击 10，敌人生命小怪几十 / 精英上百 / 首领数百，
 *       防御回到「一次基础攻击的 10%～30%」，不再一出手就被防御吃光只剩 1 点；</li>
 *   <li><b>房间规模</b>：每往下一层多刷一只怪，精英从第 2 层开始按层数提高概率；</li>
 *   <li><b>首领增援</b>：第 3 层起召唤物里混精英，波次与存活上限随层数增长，
 *       首领跌破最后一个血量阶段后再追加一只；</li>
 *   <li><b>更难躲的弹幕</b>：首领前摇整体压短，并新增「多轮环形弹幕」与「碰墙反弹」两类招式，
 *       部分小怪也学会了同样的打法。</li>
 * </ol>
 */
class CombatRebalanceTest {

    private static final double DT = AppConfig.FIXED_DT;

    /** 首领前摇的上限：超过这个数，玩家站在原地就能等到招式落空。 */
    private static final double MAX_BOSS_WINDUP = 1.2;

    // ================= 刻度 =================

    @Test
    void playerDamageAndEnemyHealthShareOneReadableScale() {
        assertEquals(10, Player.BASE_ATTACK_DAMAGE, "玩家一次基础攻击是 10 点");
        // 防御最多只啃掉一小部分：否则「至少 1 点」的兜底会把整条伤害公式压平。
        assertTrue(GameConfig.ENEMY_DEFENSE_MAX <= Player.BASE_ATTACK_DAMAGE * 0.35,
                "满层防御不能超过一次基础攻击的 35%，实际上限 " + GameConfig.ENEMY_DEFENSE_MAX);
    }

    @Test
    void weakMobsSurviveMoreThanACoupleOfBasicShots() {
        Enemy lantern = new Enemy(EnemyKind.LANTERN, WorldType.LIGHT, 0, 0, 1);
        int hits = hitsToKill(lantern, Player.BASE_ATTACK_DAMAGE);
        assertTrue(hits >= 3, "第 1 层最瘦的小怪也该挨 3 下以上，实际 " + hits + " 下");

        Enemy golem = new Enemy(EnemyKind.GOLEM, WorldType.LIGHT, 0, 0, 1);
        assertTrue(hitsToKill(golem, Player.BASE_ATTACK_DAMAGE) > hits,
                "傀儡要比灯魇更耐打");
    }

    @Test
    void deepFloorMobsNeedAWholeMagazine() {
        Enemy deep = new Enemy(EnemyKind.GOLEM, WorldType.LIGHT, 0, 0, GameConfig.TOTAL_FLOORS);
        int hits = hitsToKill(deep, Player.BASE_ATTACK_DAMAGE);
        assertTrue(hits >= 10, "第 5 层的傀儡应当需要一管以上的充能，实际 " + hits + " 下");
        assertTrue(hits <= GameConfig.ATTACK_CHARGE_MAX * 3,
                "再厚也不能厚到打不动，实际 " + hits + " 下");
    }

    @Test
    void defenseIsAReadableFractionInsteadOfTheWholeHit() {
        Enemy armoured = new Enemy(EnemyKind.GOLEM, WorldType.LIGHT, 0, 0, GameConfig.TOTAL_FLOORS);
        assertTrue(armoured.getDefense() > 0);
        int dealt = armoured.takeHit(Player.BASE_ATTACK_DAMAGE);
        assertEquals(Player.BASE_ATTACK_DAMAGE - armoured.getDefense(), dealt);
        assertTrue(dealt >= Player.BASE_ATTACK_DAMAGE * 0.5,
                "防御不该把一次基础攻击削掉一半以上，实际剩 " + dealt);

        // 「至少 1 点」仍然保留，但它只是兜底，不再是常态。
        Enemy cheap = new Enemy(EnemyKind.LANTERN, WorldType.LIGHT, 0, 0, GameConfig.TOTAL_FLOORS);
        assertEquals(1, cheap.takeHit(1), "攻击力低于防御时仍然至少掉 1 点血");
    }

    @Test
    void bossesAreAnOrderOfMagnitudeTougherThanMobs() {
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            assertTrue(kind.hitPoints() >= EnemyKind.GOLEM.hitPoints() * 4,
                    kind + " 的生命值应当显著高于普通怪");
        }
        assertTrue(EnemyKind.WATCHER.hitPoints() >= 400);
    }

    // ================= 单波数量保持小规模 =================

    @Test
    void battleWavesKeepSmallPopulationOnDeeperFloors() {
        assertEquals(GameConfig.BATTLE_ENEMY_MIN, GameConfig.battleEnemyCount(1, 0));
        for (int floor = 2; floor <= GameConfig.TOTAL_FLOORS; floor++) {
            assertTrue(GameConfig.battleEnemyCount(floor, 0) == GameConfig.battleEnemyCount(floor - 1, 0),
                    "第 " + floor + " 层不应增加同屏怪物数量");
        }
        assertTrue(GameConfig.battleEnemyCount(GameConfig.TOTAL_FLOORS, 2)
                <= GameConfig.BATTLE_ENEMY_MAX_CAP, "数量要封顶，不能把房间挤爆");

        // 真进房间数一遍：同一种子、同一张图，第 5 层的同屏数量也不能增加。
        Room room = openRoom(4, RoomType.BATTLE, 1280, 960);
        int shallow = enemiesOnFloor(room, 1);
        int deep = enemiesOnFloor(room, GameConfig.TOTAL_FLOORS);
        assertEquals(shallow, deep, "深层仍保持每波少量敌人");
    }

    @Test
    void elitesOnlyAppearFromTheSecondFloorAndGetMoreCommon() {
        assertEquals(0.0, GameConfig.battleEliteChance(1), "第 1 层不出精英");
        assertTrue(GameConfig.battleEliteChance(2) > 0.0);
        assertTrue(GameConfig.battleEliteChance(5) > GameConfig.battleEliteChance(2));
        assertTrue(GameConfig.battleEliteChance(99) <= GameConfig.BATTLE_ELITE_CHANCE_CAP);
    }

    @Test
    void newMobsStillFillTheBiggerRooms() {
        // 刷怪池被楼层规模放大之后，v2 的三只小怪仍然照常出场。
        Set<EnemyKind> spawned = EnumSet.noneOf(EnemyKind.class);
        for (long seed = 1L; seed <= 12L; seed++) {
            DungeonMap map = new MapGenerator().generate(seed);
            for (Room room : map.rooms()) {
                if (room.type() != RoomType.BATTLE) continue;
                RoomNavigationSystem navigation = navigationFor(room);
                Player player = new Player((room.minX() + room.maxX()) / 2.0,
                        (room.minY() + room.maxY()) / 2.0);
                EnemySystem system = new EnemySystem();
                system.setFloor(4);
                system.enterRoom(room, seed, player, navigation);
                system.getEnemies().forEach(enemy -> spawned.add(enemy.getKind()));
            }
        }
        for (EnemyKind kind : List.of(EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT)) {
            assertTrue(spawned.contains(kind), kind + " 应当在放大后的刷怪池里照常出现，实际 " + spawned);
        }
    }

    // ================= 首领增援：精英与波次 =================

    @Test
    void bossReinforcementWavesGrowWithTheFloorAndLatePhases() {
        SummonProfile profile = SummonProfile.of(EnemyKind.WATCHER);
        assertNotNull(profile);
        assertEquals(GameConfig.WATCHER_SUMMON_COUNT, profile.waveSize(1, false), "第 1 层仍是两只，保持旧手感");
        assertTrue(profile.waveSize(GameConfig.TOTAL_FLOORS, false) > profile.waveSize(1, false),
                "越深的首领一波叫得越多");
        assertEquals(profile.waveSize(3, false) + GameConfig.SUMMON_WAVE_LOW_HEALTH_BONUS,
                profile.waveSize(3, true), "跌破最后一个血量阶段后，每一波再追加一只");
        assertTrue(profile.aliveCap(GameConfig.TOTAL_FLOORS) > profile.aliveCap(1),
                "场上的召唤物上限也要跟着层数涨");
        assertEquals(GameConfig.WATCHER_SUMMON_MAX_ALIVE, profile.aliveCap(1), "第 1 层沿用旧上限");

        assertFalse(GameConfig.summonIncludesElites(1));
        assertTrue(GameConfig.summonIncludesElites(GameConfig.SUMMON_ELITE_FROM_FLOOR));
        assertEquals(EnemyKind.LANTERN, profile.minion(WorldType.LIGHT, 0, 1), "第 1 层还是普通小怪");
        assertEquals(EnemyKind.EXECUTIONER, profile.minion(WorldType.LIGHT, 0, 3), "第 3 层起第一只是精英");
        assertEquals(EnemyKind.BELL, profile.minion(WorldType.SHADOW, 0, 3));
        assertEquals(EnemyKind.LANTERN, profile.minion(WorldType.LIGHT, 1, 3),
                "精英之后的几只仍然是本来的小怪名单");
    }

    @Test
    void deepFloorSummonsActuallyBringAnElite() {
        Room room = openRoom(8, RoomType.BATTLE, 1280, 960);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(640, 480);
        EnemySystem system = new EnemySystem();
        system.setFloor(GameConfig.SUMMON_ELITE_FROM_FLOOR);
        Enemy weaver = system.spawnForTest(EnemyKind.WEAVER, WorldType.LIGHT,
                GameConfig.SUMMON_ELITE_FROM_FLOOR, Difficulty.NORMAL);
        weaver.setPosition(940, 480);
        weaver.setAlertRemaining(0.0);

        system.castForTest(weaver, player, EnemySkill.WEAVER_BROOD_LIGHT);
        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_RIFT_TIME + 1.5);

        assertTrue(system.getSummonedCount() > 0, "巢门成型后应当放出召唤物");
        assertTrue(system.getEnemies().stream()
                        .anyMatch(enemy -> enemy.isSummoned() && enemy.getKind() == EnemyKind.EXECUTIONER),
                "第 3 层的召唤物里应当混进一只精英");
        assertTrue(system.getSummonedCount() <= GameConfig.summonAliveCap(GameConfig.SUMMON_ELITE_FROM_FLOOR),
                "再多也不能超过该层的存活上限");
    }

    // ================= 前摇与弹幕 =================

    @Test
    void bossWindupsAreShortEnoughToPunishStandingStill() {
        for (EnemyKind kind : allBossKinds()) {
            for (WorldType world : WorldType.values()) {
                for (EnemySkill skill : EnemySkill.forEnemy(kind, world)) {
                    assertTrue(skill.windup() <= MAX_BOSS_WINDUP,
                            kind + " 的 " + skill + " 前摇 " + skill.windup() + " 秒太长，玩家站着不动都能躲开");
                    assertTrue(skill.windup() >= 0.5,
                            kind + " 的 " + skill + " 前摇太短，玩家没有反应时间");
                }
            }
        }
    }

    @Test
    void everyBossHasAFullArenaBarrageInBothWorlds() {
        for (EnemyKind kind : allBossKinds()) {
            for (WorldType world : WorldType.values()) {
                List<EnemySkill> skills = EnemySkill.forEnemy(kind, world);
                // 「全图弹幕」有三种做法，任取其一即可：环形铺满、碰墙反弹、或者连发多轮。
                boolean areaCovering = skills.stream().anyMatch(skill ->
                        (skill.isRing() && skill.count() >= 8)
                                || skill.bouncing()
                                || (skill.pattern() == EnemySkill.Pattern.BARRAGE && skill.volleys() >= 2));
                assertTrue(areaCovering, kind + " 在 " + world + " 界应当有一招覆盖整间房的弹幕");
            }
        }
    }

    @Test
    void aRingBarrageCoversEveryDirection() {
        Room room = openRoom(9, RoomType.BATTLE, 1280, 960);
        RoomNavigationSystem navigation = navigationFor(room);
        // 玩家站远一点：贴着首领站会被第一发立刻打中，弹幕还没铺开就没了。
        Player player = new Player(1120, 480);
        EnemySystem system = new EnemySystem();
        Enemy watcher = system.spawnForTest(EnemyKind.WATCHER, WorldType.LIGHT, 1, Difficulty.NORMAL);
        watcher.setPosition(240, 480);
        watcher.setAlertRemaining(0.0);

        EnemySkill skill = EnemySkill.WATCHER_SUN_CASCADE;
        system.castForTest(watcher, player, skill);
        // 只数**已经出手**的那一轮：后续轮次已经排进列表里，但还在倒计时等待发射。
        boolean firstVolleySeen = runUntil(system, player, navigation,
                () -> activeAttacks(system).size() >= skill.count(), 6.0);
        assertTrue(firstVolleySeen, "日轮连射应当先铺满一整圈");

        List<Double> angles = new ArrayList<>();
        for (EnemyAttack attack : activeAttacks(system)) {
            angles.add(Math.atan2(attack.getVelocityY(), attack.getVelocityX()));
        }
        angles.sort(Double::compareTo);
        assertEquals(skill.count(), new HashSet<>(angles).size(),
                "环形弹幕的每一发都应当朝向各自的方向，而不是叠在一起");
        double step = Math.PI * 2 / skill.count();
        double maxGap = 0.0;
        for (int i = 0; i < angles.size(); i++) {
            double next = i + 1 < angles.size() ? angles.get(i + 1) : angles.get(0) + Math.PI * 2;
            maxGap = Math.max(maxGap, next - angles.get(i));
        }
        assertTrue(maxGap <= step * 1.3, "环形弹幕不该留下比一个步长还大的空档，实际 " + Math.toDegrees(maxGap) + "°");
    }

    @Test
    void multiVolleyBarragesArriveInWaves() {
        Room room = openRoom(10, RoomType.BATTLE, 1280, 960);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(1120, 480);
        // 影界的招式只在「玩家也在影界」时才会推进：同界是硬规则，测试也必须踩在同界上。
        player.toggleWorld();
        EnemySystem system = new EnemySystem();
        Enemy mantis = system.spawnForTest(EnemyKind.MANTIS, WorldType.SHADOW, 1, Difficulty.NORMAL);
        mantis.setPosition(240, 480);
        mantis.setAlertRemaining(0.0);

        EnemySkill skill = EnemySkill.MANTIS_NIGHT_WAVES;
        assertTrue(skill.volleys() >= 3, "夜刃连斩波应当是连续多轮");
        system.castForTest(mantis, player, skill);

        // 只数「已经出手」的弹体：一波一波涨上去才算多轮弹幕。
        int waves = 0;
        int previous = 0;
        int frames = (int) Math.round((skill.windup() + skill.active() + 1.2) / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            int alive = activeAttacks(system).size();
            if (alive > previous) waves++;
            previous = alive;
        }
        assertTrue(waves >= skill.volleys(),
                "多轮弹幕应当分 " + skill.volleys() + " 波出现，实际只数到 " + waves + " 波");
    }

    @Test
    void bouncingProjectilesSurviveWallHits() {
        // 小房间：不反弹的弹体在 2 秒内必然全部撞在墙上消失。
        Room room = openRoom(11, RoomType.BATTLE, 600, 400);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(540, 200);
        player.toggleWorld();
        EnemySystem system = new EnemySystem();
        Enemy watcher = system.spawnForTest(EnemyKind.WATCHER, WorldType.SHADOW, 1, Difficulty.NORMAL);
        watcher.setPosition(60, 200);
        watcher.setAlertRemaining(0.0);

        EnemySkill skill = EnemySkill.WATCHER_RIFT_RICOCHET;
        assertTrue(skill.bouncing(), "裂影回弹必须是会反弹的弹体");
        system.castForTest(watcher, player, skill);

        boolean sawReverse = false;
        int peak = 0;
        int frames = (int) Math.round(3.0 / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            List<EnemyAttack> alive = activeAttacks(system);
            peak = Math.max(peak, alive.size());
            for (EnemyAttack attack : alive) {
                if (attack.getBouncesRemaining() < skill.bounces()) sawReverse = true;
            }
        }
        assertTrue(peak > 0, "裂影回弹应当真的射出弹体");
        assertTrue(sawReverse, "裂影回弹应当真的用掉过反弹次数");
        assertFalse(activeAttacks(system).isEmpty(),
                "3 秒之后反弹弹体应当还在场（不反弹的同速弹体早已撞墙消失）");
    }

    @Test
    void someMobsLearnedTheNewTricksToo() {
        assertTrue(EnemySkill.forEnemy(EnemyKind.MAGE, WorldType.LIGHT).stream()
                        .anyMatch(skill -> skill.pattern() == EnemySkill.Pattern.BARRAGE),
                "法师光界应当会多轮散辉连射");
        assertTrue(EnemySkill.forEnemy(EnemyKind.MAGE, WorldType.SHADOW).stream()
                        .anyMatch(EnemySkill::bouncing), "法师影界应当会碰墙反弹的镜片");
        assertTrue(EnemySkill.forEnemy(EnemyKind.BELL, WorldType.LIGHT).stream()
                        .anyMatch(skill -> skill.isRing() && skill.volleys() >= 3),
                "鸣钟者光界应当会多轮钟波");
    }

    // ================= 测试辅助 =================

    /** 用一次不减免的基础攻击打死它需要几发。 */
    private static int hitsToKill(Enemy enemy, int attackDamage) {
        int maxHp = enemy.getMaxHp();
        int dealt = enemy.takeHit(attackDamage);
        return (int) Math.ceil(maxHp / (double) Math.max(1, dealt));
    }

    /**
     * 已经出手、正在飞的攻击。
     *
     * <p>多轮弹幕会把后面几轮也先排进攻击列表（用倒计时等待发射），
     * 所以「列表长度」不等于「场上弹幕数量」，数弹幕必须过滤掉还没出手的那些。
     */
    private static List<EnemyAttack> activeAttacks(EnemySystem system) {
        return system.getAttacks().stream().filter(EnemyAttack::isActive).toList();
    }

    private static int enemiesOnFloor(Room room, int floor) {
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player((room.minX() + room.maxX()) / 2.0, (room.minY() + room.maxY()) / 2.0);
        EnemySystem system = new EnemySystem();
        system.setFloor(floor);
        system.setDifficulty(Difficulty.NORMAL);
        system.enterRoom(room, 4242L, player, navigation);
        return system.getEnemies().size();
    }

    private static Set<EnemyKind> allBossKinds() {
        Set<EnemyKind> kinds = EnumSet.noneOf(EnemyKind.class);
        kinds.add(EnemyKind.WATCHER);
        kinds.addAll(BossRoster.EXPANSION_BOSSES);
        return kinds;
    }

    private static void runFor(EnemySystem system, Player player, RoomNavigationSystem navigation,
                               double seconds) {
        runUntil(system, player, navigation, () -> false, seconds);
    }

    private static boolean runUntil(EnemySystem system, Player player, RoomNavigationSystem navigation,
                                    java.util.function.BooleanSupplier condition, double seconds) {
        int frames = (int) Math.round(seconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            player.updateAnimation(DT, 0.0, 0.0, false, false);
            if (condition.getAsBoolean()) return true;
        }
        return false;
    }

    private static Room openRoom(int id, RoomType type, double width, double height) {
        List<Wall> walls = new ArrayList<>();
        return new Room(id, type, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, width, height)), walls);
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
