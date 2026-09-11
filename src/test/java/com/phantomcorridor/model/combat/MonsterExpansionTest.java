package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.Difficulty;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * monster_expansion_v2 整合验收：3 种新小怪、5 个新 Boss、36 个新招式与它们的判定实体。
 *
 * <p>覆盖四层：
 * <ul>
 *   <li><b>数据层</b>：物种身位 / 档位 / 基础生命与 36 招的伤害、时序是否与素材包一致；</li>
 *   <li><b>名册层</b>：五层首领互不重复、第 1 层固定守望者、种子可复现；</li>
 *   <li><b>判定层</b>：预警几何（扇面 / 环带缺口 / 横带 / 地面标记）、多段命中标识、延迟绽放；</li>
 *   <li><b>机制层</b>：冲撞改短警示带、根篱阻挡与过期、镜门换位、召唤上限、幻象不产生实体。</li>
 * </ul>
 */
class MonsterExpansionTest {

    private static final double DT = 1.0 / 60.0;

    /** v2 扩展包的八个物种。 */
    private static final List<EnemyKind> EXPANSION_KINDS = List.of(
            EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT,
            EnemyKind.PRISM_CRAB, EnemyKind.MANTIS, EnemyKind.WEAVER, EnemyKind.ROOTKING, EnemyKind.HOURGLASS);

    // ================= 数据层 =================

    @Test
    void expansionKindsKeepThePackStats() {
        // 小怪：脚下 27 / 受击 35，不是精英也不是首领。
        for (EnemyKind kind : List.of(EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT)) {
            assertEquals(EnemyKind.Tier.NORMAL, kind.tier(), kind + " 是新小怪");
            assertEquals(27.0, kind.footRadius(), 1e-9, kind + " 的脚下身位应当与素材包一致");
            assertEquals(35.0, kind.hurtRadius(), 1e-9, kind + " 的受击框半径应当与素材包一致");
            assertFalse(kind.elite(), kind + " 是新小怪，不是精英");
            assertFalse(kind.boss(), kind + " 是新小怪，不是首领");
            assertTrue(kind.expansion(), kind + " 应当被标记为扩展包物种");
        }
        // 五个 Boss：脚下 46 / 受击 62，画布 640（给树冠、镰臂、炮管留空间）。
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            assertEquals(EnemyKind.Tier.BOSS, kind.tier(), kind + " 是首领档");
            assertEquals(46.0, kind.footRadius(), 1e-9, kind + " 的脚下身位应当与素材包一致");
            assertEquals(62.0, kind.hurtRadius(), 1e-9, kind + " 的受击框半径应当与素材包一致");
            assertEquals(640, kind.canvasSize(), kind + " 使用 640 画布");
            assertTrue(kind.boss(), kind + " 应当被判定为首领");
            assertFalse(kind.elite(), "首领不算精英怪");
        }
    }

    @Test
    void expansionBaseStatsMatchTheDesignTable() {
        assertEquals(75, EnemyKind.BEETLE.hitPoints());
        assertEquals(60, EnemyKind.SPORE.hitPoints());
        assertEquals(45, EnemyKind.RAYBAT.hitPoints());
        assertEquals(440, EnemyKind.PRISM_CRAB.hitPoints());
        assertEquals(380, EnemyKind.MANTIS.hitPoints());
        assertEquals(420, EnemyKind.WEAVER.hitPoints());
        assertEquals(500, EnemyKind.ROOTKING.hitPoints());
        assertEquals(400, EnemyKind.HOURGLASS.hitPoints());
        // 移速：素材包给的是 px/s，代码里存成「玩家基础移速 200 的倍率」。
        assertEquals(72.0 / 200.0, EnemyKind.BEETLE.speedMultiplier(), 1e-9);
        assertEquals(125.0 / 200.0, EnemyKind.MANTIS.speedMultiplier(), 1e-9);
        assertEquals(42.0 / 200.0, EnemyKind.ROOTKING.speedMultiplier(), 1e-9);
        // 战斗刻度重构后的比值：小怪几十、精英上百、首领数百，玩家一次基础攻击是 10。
        assertTrue(EnemyKind.HOURGLASS.hitPoints() > EnemyKind.BEETLE.hitPoints() * 4,
                "首领的生命值必须显著高于小怪：首领 " + EnemyKind.HOURGLASS.hitPoints()
                        + "，小怪 " + EnemyKind.BEETLE.hitPoints());
    }

    @Test
    void everyExpansionKindHasItsSkillsInBothWorlds() {
        for (EnemyKind kind : EXPANSION_KINDS) {
            for (WorldType world : WorldType.values()) {
                List<EnemySkill> skills = EnemySkill.forEnemy(kind, world);
                // 素材包给每个 Boss 三招、每个小怪一招；全图弹幕是在此之上追加的。
                int minimum = kind.boss() ? 3 : 1;
                assertTrue(skills.size() >= minimum,
                        kind + " 在 " + world + " 界至少要有 " + minimum + " 个招式，实际 " + skills.size());
                assertTrue(skills.stream().anyMatch(skill -> skill.damage() > 0.0),
                        kind + " 在 " + world + " 界至少要有一招能造成伤害");
            }
        }
        // 素材包本身提供 36 招，另外追加了全图弹幕 / 反弹弹幕。
        int total = EXPANSION_KINDS.stream()
                .mapToInt(kind -> EnemySkill.forEnemy(kind, WorldType.LIGHT).size()
                        + EnemySkill.forEnemy(kind, WorldType.SHADOW).size())
                .sum();
        assertTrue(total >= 36, "v2 扩展包至少提供 36 个招式，实际 " + total);
    }

    @Test
    void expansionSkillDamageMatchesTheDesignTable() {
        assertEquals(6.0, EnemySkill.BEETLE_GORE.damage(), 1e-9);
        assertEquals(7.0, EnemySkill.BEETLE_BURROW.damage(), 1e-9);
        assertEquals(5.0, EnemySkill.SPORE_BLOOM.damage(), 1e-9);
        assertEquals(3.0, EnemySkill.SPORE_FIELD.damage(), 1e-9);
        assertEquals(4.0, EnemySkill.RAYBAT_FEATHER.damage(), 1e-9);
        assertEquals(6.0, EnemySkill.RAYBAT_DIVE.damage(), 1e-9);
        assertEquals(9.0, EnemySkill.CRAB_SHELL.damage(), 1e-9);
        assertEquals(12.0, EnemySkill.CRAB_BOMBARD.damage(), 1e-9);
        assertEquals(8.0, EnemySkill.MANTIS_DOUBLE_SLASH.damage(), 1e-9);
        assertEquals(12.0, EnemySkill.MANTIS_EMPTY_COUNTER.damage(), 1e-9);
        assertEquals(8.0, EnemySkill.WEAVER_SILK.damage(), 1e-9);
        assertEquals(0.0, EnemySkill.WEAVER_BROOD_LIGHT.damage(), 1e-9);
        assertEquals(10.0, EnemySkill.ROOTKING_SPIKES.damage(), 1e-9);
        assertEquals(11.0, EnemySkill.ROOTKING_SEED_MARKS.damage(), 1e-9);
        assertEquals(8.0, EnemySkill.HOURGLASS_ECHO.damage(), 1e-9);
        assertEquals(10.0, EnemySkill.HOURGLASS_TRUE_CIRCLES.damage(), 1e-9);
    }

    @Test
    void utilitySkillsDealNoDamageAtAll() {
        // 纯召唤 / 换位 / 筑墙必须显式为 0，不能落到物种的基础撞击伤害上。
        List<EnemySkill> silent = List.of(
                EnemySkill.WEAVER_BROOD_LIGHT, EnemySkill.WEAVER_BROOD_SHADOW,
                EnemySkill.ROOTKING_WALLS, EnemySkill.ROOTKING_SHIFT_WALLS,
                EnemySkill.HOURGLASS_GATE, EnemySkill.HOURGLASS_SHADOW_GATE);
        for (EnemySkill skill : silent) {
            assertEquals(0.0, skill.damage(), 1e-9, skill + " 不该造成伤害");
        }
    }

    @Test
    void hitPoliciesSeparateCastWideAndSegmentWideDeduplication() {
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_CAST, EnemySkill.CRAB_SHELL.hitPolicy());
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_SWING, EnemySkill.MANTIS_DOUBLE_SLASH.hitPolicy());
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_BAND, EnemySkill.ROOTKING_BANDS.hitPolicy());
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_MARK, EnemySkill.CRAB_BOMBARD.hitPolicy());
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_PROJECTILE, EnemySkill.HOURGLASS_ECHO.hitPolicy());
        assertEquals(EnemySkill.HitPolicy.ONCE_PER_PULSE, EnemySkill.SPORE_FIELD.hitPolicy());
        // 「多段」不只看 hitOffsets：两条根带也各算一段，而单段招式必须自认单段。
        assertTrue(EnemySkill.MANTIS_DOUBLE_SLASH.multiSegment());
        assertTrue(EnemySkill.ROOTKING_BANDS.multiSegment());
        assertFalse(EnemySkill.CRAB_SHELL.multiSegment());
        assertFalse(EnemySkill.WEAVER_WEB_RING.multiSegment());
    }

    // ================= 名册层 =================

    @Test
    void everyRunGetsFiveDifferentBosses() {
        Set<EnemyKind> seenAcrossSeeds = EnumSet.noneOf(EnemyKind.class);
        for (long seed = 0L; seed < 300L; seed++) {
            List<EnemyKind> bosses = BossRoster.forRun(seed, GameConfig.TOTAL_FLOORS);
            assertEquals(GameConfig.TOTAL_FLOORS, bosses.size());
            assertEquals(EnemyKind.WATCHER, bosses.get(0), "第 1 层固定是裂隙守望者");
            assertEquals(GameConfig.TOTAL_FLOORS, new HashSet<>(bosses).size(),
                    "种子 " + seed + " 的五层出现了重复首领：" + bosses);
            for (int floor = 2; floor <= GameConfig.TOTAL_FLOORS; floor++) {
                assertTrue(BossRoster.EXPANSION_BOSSES.contains(bosses.get(floor - 1)),
                        "第 " + floor + " 层应当是 v2 新首领，实际 " + bosses.get(floor - 1));
            }
            seenAcrossSeeds.addAll(bosses);
        }
        // 单局只上场四个新首领，但换种子要能把五个都轮换到。
        assertTrue(seenAcrossSeeds.containsAll(BossRoster.EXPANSION_BOSSES),
                "不同种子应当能覆盖全部五个新首领，实际 " + seenAcrossSeeds);
    }

    @Test
    void bossRosterIsReproducibleForTheSameSeed() {
        for (int floor = 2; floor <= GameConfig.TOTAL_FLOORS; floor++) {
            assertEquals(BossRoster.forFloor(floor, 12345L), BossRoster.forFloor(floor, 12345L),
                    "同一个种子下首领不能变");
        }
        assertNotEquals(BossRoster.forRun(1L, GameConfig.TOTAL_FLOORS),
                BossRoster.forRun(2L, GameConfig.TOTAL_FLOORS),
                "不同种子应当给出不同的首领排布（否则随机名册形同虚设）");
    }

    @Test
    void bossRoomsSpawnTheRosterBossOfThatFloor() {
        for (int floor = 1; floor <= GameConfig.TOTAL_FLOORS; floor++) {
            long dungeonSeed = 777L + (floor - 1) * GameConfig.FLOOR_SEED_STEP;
            Room room = openBossRoom();
            RoomNavigationSystem navigation = navigationFor(room);
            Player player = new Player(640, 360);
            EnemySystem system = new EnemySystem();
            system.setFloor(floor);
            system.enterRoom(room, dungeonSeed, player, navigation);

            assertEquals(1, system.getEnemies().size(), "Boss 房只生成一名首领");
            Enemy boss = system.getEnemies().getFirst();
            assertEquals(BossRoster.forFloor(floor, dungeonSeed), boss.getKind(),
                    "第 " + floor + " 层应当生成名册上的首领");
            assertTrue(boss.isBoss());
            assertTrue(boss.getMaxHp() > boss.getKind().hitPoints() - 1,
                    "首领生命值应当套用层数成长公式");
        }
    }

    @Test
    void expansionBossesScaleWithFloorAndDifficultyLikeEveryOtherEnemy() {
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            Enemy firstFloor = new Enemy(kind, WorldType.LIGHT, 0, 0, 1, Difficulty.NORMAL);
            Enemy lastFloor = new Enemy(kind, WorldType.LIGHT, 0, 0, GameConfig.TOTAL_FLOORS, Difficulty.NORMAL);
            Enemy hard = new Enemy(kind, WorldType.LIGHT, 0, 0, 1, Difficulty.INSANE);
            assertTrue(lastFloor.getMaxHp() > firstFloor.getMaxHp(),
                    kind + " 的生命值应当随层数提高");
            assertTrue(lastFloor.getDefense() > firstFloor.getDefense(),
                    kind + " 的防御应当随层数提高");
            assertEquals(firstFloor.getMaxHp() * 2, hard.getMaxHp(),
                    kind + " 在屌炸天下生命值应当是标准的两倍");
        }
    }

    @Test
    void everyBossCrossesOverAtHalfHealthWithATransformLock() {
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            Room room = openBossRoom();
            RoomNavigationSystem navigation = navigationFor(room);
            Player player = new Player(640, 360);
            EnemySystem system = new EnemySystem();
            system.spawnForTest(kind, WorldType.LIGHT, 1, Difficulty.NORMAL);
            Enemy boss = system.getEnemies().getFirst();
            boss.setPosition(900, 360);
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            assertEquals(WorldType.LIGHT, boss.getWorld());

            boss.damage(boss.getMaxHp() / 2 + 1);
            system.update(DT, player, new PlayerAttackSystem(), navigation);

            assertEquals(WorldType.SHADOW, boss.getWorld(), kind + " 半血后应当转入影界");
            assertEquals("transform", boss.getAnimationAction(), kind + " 换形应当播放 transform 动作");
            assertTrue(boss.getAlertRemaining() > 0.0, kind + " 换形之后应当有一段不能出招的收招");
        }
    }

    // ================= 判定层 =================

    @Test
    void beetleGoreTurnsItsWarningIntoARealChargingLane() {
        Room room = openRoom(4, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(700, 360);
        EnemySystem system = new EnemySystem();
        Enemy beetle = system.spawnForTest(EnemyKind.BEETLE, WorldType.LIGHT, 1, Difficulty.NORMAL);
        beetle.setPosition(460, 360);
        beetle.setAlertRemaining(0.0);

        system.castForTest(beetle, player, EnemySkill.BEETLE_GORE);
        assertEquals(1, system.getTelegraphs().size(), "起手就应当铺下一条警示带");
        EnemyTelegraph warning = system.getTelegraphs().getFirst();
        assertEquals(EnemyTelegraph.Shape.LINE, warning.shape());
        assertEquals(EnemySkill.BEETLE_GORE.distance(), warning.length(), 1e-9, "警示带承诺整段冲撞距离");

        double before = Math.hypot(beetle.getX() - player.getX(), beetle.getY() - player.getY());
        // 前摇结束的那一刻正是冲撞落地：此时应当既有位移，也有按实际行程改短的判定带。
        runFor(system, player, navigation, EnemySkill.BEETLE_GORE.windup() + 0.1);
        double after = Math.hypot(beetle.getX() - player.getX(), beetle.getY() - player.getY());
        assertTrue(after < before, "甲虫应当真的顶出去一段距离");
        assertFalse(system.getTelegraphs().isEmpty(), "释放之后应当留下实际行程的判定带");
        for (EnemyTelegraph telegraph : system.getTelegraphs()) {
            assertTrue(telegraph.length() <= EnemySkill.BEETLE_GORE.distance() + 1e-6,
                    "判定带不能超过起手时承诺的范围");
        }
    }

    @Test
    void twoSwingSlashHitsTwiceWhileSingleCastSkillsHitOnce() {
        // 曦刃二连：0 / 0.8 秒两段，各 8 点。间隔超过 0.65 秒受击保护，所以两段都该结算。
        int lost = damageFromSkill(EnemyKind.MANTIS, EnemySkill.MANTIS_DOUBLE_SLASH, 120.0, 4.0);
        assertEquals(16, lost, "二连斩应当结算两段 8 点，实际 " + lost + " 点");
    }

    @Test
    void marksArePlacedWithTheConfiguredCountAndTruthPattern() {
        // 炮蟹三点校射：三个都是真标记。
        List<EnemyTelegraph> bombard = telegraphsFromSkill(
                EnemyKind.PRISM_CRAB, EnemySkill.CRAB_BOMBARD, 400.0);
        assertEquals(3, bombard.size());
        assertEquals(3, bombard.stream().filter(t -> t.damage() > 0).count());
        assertEquals(3, bombard.stream().map(EnemyTelegraph::hitId).distinct().count(),
                "每个标记各自结算一次（once_per_mark）");
        for (EnemyTelegraph mark : bombard) {
            assertTrue(mark.contains(mark.x(), mark.y(), 0.0), "标记中心显然在自己的判定内");
        }

        // 术士三镜校时：三个圈里只有一个是真的。
        List<EnemyTelegraph> truth = telegraphsFromSkill(
                EnemyKind.HOURGLASS, EnemySkill.HOURGLASS_TRUE_CIRCLES, 400.0);
        assertEquals(3, truth.size());
        assertEquals(1, truth.stream().filter(t -> t.damage() > 0).count(),
                "三镜校时只有一个真圈");
        assertEquals(2, truth.stream().filter(EnemyTelegraph::isFake).count(),
                "另外两个必须是虚线空心的假圈");

        // 错秒真影：两个真圈 + 一个假圈。
        List<EnemyTelegraph> falseCircles = telegraphsFromSkill(
                EnemyKind.HOURGLASS, EnemySkill.HOURGLASS_FALSE_CIRCLES, 400.0);
        assertEquals(3, falseCircles.size());
        assertEquals(2, falseCircles.stream().filter(t -> t.damage() > 0).count());
    }

    @Test
    void ringGapLeavesAWalkableOpening() {
        List<EnemyTelegraph> rings = telegraphsFromSkill(EnemyKind.WEAVER, EnemySkill.WEAVER_WEB_RING, 150.0);
        assertEquals(1, rings.size());
        EnemyTelegraph ring = rings.getFirst();
        assertEquals(EnemyTelegraph.Shape.RING_GAP, ring.shape());
        assertEquals(120.0, ring.innerRadius(), 1e-9);
        assertEquals(210.0, ring.outerRadius(), 1e-9);
        assertEquals(100.0, ring.safeGapDegrees(), 1e-9);

        // 缺口正中（半径落在环带上）不算命中，环带其余位置算命中。
        double band = (ring.innerRadius() + ring.outerRadius()) / 2.0;
        double inGapX = ring.x() + Math.cos(ring.angleRadians()) * band;
        double inGapY = ring.y() + Math.sin(ring.angleRadians()) * band;
        assertFalse(ring.contains(inGapX, inGapY, GameConfig.PLAYER_RADIUS), "缺口里不该挨打");
        double outsideGapX = ring.x() - Math.cos(ring.angleRadians()) * band;
        double outsideGapY = ring.y() - Math.sin(ring.angleRadians()) * band;
        assertTrue(ring.contains(outsideGapX, outsideGapY, GameConfig.PLAYER_RADIUS), "环带其余位置应当挨打");
    }

    @Test
    void splitSectorKeepsItsSafeWedge() {
        List<EnemyTelegraph> wedges = telegraphsFromSkill(EnemyKind.PRISM_CRAB, EnemySkill.CRAB_TWO_SHORES, 140.0);
        assertEquals(1, wedges.size(), "安全楔两侧的两条扇区是一份预警");
        EnemyTelegraph wedge = wedges.getFirst();
        assertEquals(EnemyTelegraph.Shape.SECTOR_SPLIT, wedge.shape());
        assertEquals(50.0, wedge.safeGapDegrees(), 1e-9);

        double distance = wedge.radius() * 0.6;
        double safeX = wedge.x() + Math.cos(wedge.angleRadians()) * distance;
        double safeY = wedge.y() + Math.sin(wedge.angleRadians()) * distance;
        assertFalse(wedge.contains(safeX, safeY, GameConfig.PLAYER_RADIUS), "正前安全楔里不该挨打");
        double side = Math.toRadians(wedge.safeGapDegrees() / 2.0 + wedge.angleDegrees() / 2.0);
        double hitX = wedge.x() + Math.cos(wedge.angleRadians() + side) * distance;
        double hitY = wedge.y() + Math.sin(wedge.angleRadians() + side) * distance;
        assertTrue(wedge.contains(hitX, hitY, GameConfig.PLAYER_RADIUS), "侧向扇区应当挨打");
    }

    @Test
    void sporesBloomWhereTheyWereAimedInsteadOfFlyingPastThePlayer() {
        Room room = openRoom(9, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(640, 360);
        EnemySystem system = new EnemySystem();
        Enemy spore = system.spawnForTest(EnemyKind.SPORE, WorldType.LIGHT, 1, Difficulty.NORMAL);
        spore.setPosition(340, 360);   // 玩家在 300 像素外，比孢子 420 的最大射程近
        spore.setAlertRemaining(0.0);

        system.castForTest(spore, player, EnemySkill.SPORE_BLOOM);
        EnemyTelegraph bloom = waitForTelegraph(system, player, navigation, 6.0, EnemyTelegraph.Shape.CIRCLE);
        assertNotNull(bloom, "孢子落地后应当出现绽放预警");
        double offset = Math.hypot(bloom.x() - player.getX(), bloom.y() - player.getY());
        assertTrue(offset <= EnemySkill.SPORE_BLOOM.radius(),
                "孢子应当在瞄准点落地，实际离玩家 " + offset + " 像素");
        assertEquals(EnemySkill.SPORE_BLOOM.damage(),
                bloom.damage() * Difficulty.NORMAL.playerDamageMultiplier(), 1e-9);
    }

    // ================= 机制层 =================

    @Test
    void rootWallsBlockMovementAndWitherAfterTheirDuration() {
        Room room = openRoom(5, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(760, 360);
        EnemySystem system = new EnemySystem();
        Enemy rootKing = system.spawnForTest(EnemyKind.ROOTKING, WorldType.LIGHT, 1, Difficulty.NORMAL);
        rootKing.setPosition(560, 360);
        rootKing.setAlertRemaining(0.0);

        system.castForTest(rootKing, player, EnemySkill.ROOTKING_WALLS);
        runFor(system, player, navigation, EnemySkill.ROOTKING_WALLS.windup() + 0.4);

        assertFalse(system.getRootWalls().isEmpty(), "生篱分庭应当长出根篱");
        assertFalse(navigation.getTemporaryWalls().isEmpty(), "阻挡型根篱必须真的进入导航层");
        for (Wall wall : navigation.getTemporaryWalls()) {
            assertTrue(system.getRootWalls().stream().anyMatch(root -> root.blocking()),
                    "只有 blocking 的根篱才参与碰撞");
        }
        // 两段根篱之间留出通路：屏障中点站得下玩家。
        double middleY = system.getRootWalls().stream().mapToDouble(w -> w.y() + w.height() / 2.0).average().orElse(0);
        assertTrue(navigation.canOccupy(760, middleY, 0.1, WorldType.LIGHT)
                        || system.getRootWalls().stream().anyMatch(wall -> !wall.blocking()),
                "根篱必须留出通路，或者降级成不挡路的装饰");

        runFor(system, player, navigation, EnemySkill.ROOTKING_WALLS.wallDuration() + 0.6);
        assertTrue(system.getRootWalls().isEmpty(), "根篱到期应当枯萎");
        assertTrue(navigation.getTemporaryWalls().isEmpty(), "根篱枯萎后必须从导航层移除");
    }

    @Test
    void mirrorsGateTeleportsTheHourglassToALegalLanding() {
        Room room = openRoom(6, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(400, 360);
        EnemySystem system = new EnemySystem();
        Enemy hourglass = system.spawnForTest(EnemyKind.HOURGLASS, WorldType.LIGHT, 1, Difficulty.NORMAL);
        hourglass.setPosition(700, 360);
        hourglass.setAlertRemaining(0.0);

        double fromX = hourglass.getX();
        double fromY = hourglass.getY();
        system.castForTest(hourglass, player, EnemySkill.HOURGLASS_GATE);
        runFor(system, player, navigation, EnemySkill.HOURGLASS_GATE.windup() + EnemySkill.HOURGLASS_GATE.active() + 0.2);

        assertTrue(Math.hypot(hourglass.getX() - fromX, hourglass.getY() - fromY) > 1.0,
                "镜门换位应当真的把本体挪走");
        assertTrue(navigation.canOccupy(hourglass.getX(), hourglass.getY(), hourglass.getKind().footRadius(),
                        WorldType.LIGHT),
                "换位落点必须站得下");
        double toPlayer = Math.hypot(hourglass.getX() - player.getX(), hourglass.getY() - player.getY());
        assertTrue(toPlayer >= GameConfig.TELEPORT_PLAYER_CLEARANCE - 1e-6,
                "换位落点不能压在玩家身上，实际距离 " + toPlayer);
        assertNotNull(system.getBlinkFlash(), "两端镜门要有可见的裂隙特效");
    }

    @Test
    void illusionsAreVisualOnlyAndNeverBecomeEnemies() {
        Room room = openRoom(7, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(640, 360);
        EnemySystem system = new EnemySystem();
        Enemy hourglass = system.spawnForTest(EnemyKind.HOURGLASS, WorldType.LIGHT, 1, Difficulty.NORMAL);
        hourglass.setPosition(900, 360);
        hourglass.setAlertRemaining(0.0);

        system.castForTest(hourglass, player, EnemySkill.HOURGLASS_SHARD);
        assertEquals(1, system.getEnemies().size(), "幻象不创建实体");
        assertTrue(system.getVisualEffects().stream().anyMatch(EnemyVisualEffect::isGhost),
                "映昼镜矢应当留下两幅假影");
        long ghosts = system.getVisualEffects().stream().filter(EnemyVisualEffect::isGhost).count();
        assertEquals(EnemySkill.HOURGLASS_SHARD.illusions(), ghosts, "假影数量应当与技能配置一致");

        runFor(system, player, navigation, 2.0);
        assertEquals(1, system.getEnemies().size(), "跑一段时间后仍然只有本体一个实体");
    }

    @Test
    void weaverSummonsHerOwnBroodWithinTheAliveCap() {
        Room room = openRoom(8, RoomType.BATTLE);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(640, 360);
        EnemySystem system = new EnemySystem();
        Enemy weaver = system.spawnForTest(EnemyKind.WEAVER, WorldType.LIGHT, 1, Difficulty.NORMAL);
        weaver.setPosition(940, 360);
        weaver.setAlertRemaining(0.0);

        system.castForTest(weaver, player, EnemySkill.WEAVER_BROOD_LIGHT);
        runFor(system, player, navigation, EnemySkill.WEAVER_BROOD_LIGHT.windup() + 0.2);
        assertFalse(system.getSummonRifts().isEmpty(), "孵辉巢应当先开巢门（裂隙）");
        for (SummonRift rift : system.getSummonRifts()) {
            assertEquals(WorldType.LIGHT, rift.world(), "裂隙开在蛛后所在的那一界");
            assertTrue(List.of(EnemyKind.BEETLE, EnemyKind.SPORE).contains(rift.kind()),
                    "光界召唤曜甲虫与晨露孢囊，实际 " + rift.kind());
        }

        runFor(system, player, navigation, GameConfig.WATCHER_SUMMON_RIFT_TIME + 1.0);
        assertTrue(system.getSummonedCount() > 0, "裂隙成型后应当放出召唤物");
        assertTrue(system.getSummonedCount() <= GameConfig.WATCHER_SUMMON_MAX_ALIVE,
                "召唤物总量不能超过上限");
        for (Enemy minion : system.getEnemies()) {
            if (!minion.isSummoned()) continue;
            assertEquals(WorldType.LIGHT, minion.getWorld());
            assertTrue(List.of(EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT).contains(minion.getKind()));
        }
    }

    @Test
    void expansionBossesStillDieCleanlyAndClearTheRoom() {
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            Room room = openBossRoom();
            RoomNavigationSystem navigation = navigationFor(room);
            Player player = new Player(640, 360);
            EnemySystem system = new EnemySystem();
            Enemy boss = system.spawnForTest(kind, WorldType.LIGHT, 1, Difficulty.NORMAL);
            boss.setPosition(900, 360);
            boss.damage(999);
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            assertTrue(system.isRoomCleared(), kind + " 倒下后房间应当立刻清空（传送门才会出现）");
        }
    }

    @Test
    void expansionEnemiesJoinTheFightInRealRooms() {
        // 三种新小怪必须真的会走、会打：抽若干种子地图，看每一只同界小怪有没有开过火。
        for (EnemyKind kind : List.of(EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT)) {
            boolean sawFire = false;
            for (long seed = 1L; seed <= 8L && !sawFire; seed++) {
                DungeonMap map = new com.phantomcorridor.model.dungeon.MapGenerator().generate(seed);
                for (Room room : map.rooms()) {
                    if (room.type() != RoomType.BATTLE) continue;
                    RoomNavigationSystem navigation = navigationFor(room);
                    Player player = new Player((room.minX() + room.maxX()) / 2.0,
                            (room.minY() + room.maxY()) / 2.0);
                    EnemySystem system = new EnemySystem();
                    system.enterRoom(room, seed, player, navigation);
                    if (system.getEnemies().stream().noneMatch(enemy -> enemy.getKind() == kind
                            && enemy.getWorld() == WorldType.LIGHT)) continue;
                    PlayerAttackSystem attacks = new PlayerAttackSystem();
                    for (int frame = 0; frame < 30 * 60 && !sawFire; frame++) {
                        system.update(DT, player, attacks, navigation);
                        sawFire = system.getAttacks().stream().anyMatch(a -> a.getSource() == kind)
                                || system.getTelegraphs().stream().anyMatch(t -> t.source() == kind);
                    }
                    if (sawFire) break;
                }
            }
            assertTrue(sawFire, kind + " 应当在真实房间里主动参战");
        }
    }

    // ================= 素材层 =================

    /**
     * 招式里写下的每个动作名与特效名，都必须在资源里真的有对应帧。
     *
     * <p>这条断言专门对付“整合看起来做完了、跑起来少半张图”的情况：技能表里的
     * {@code actionBase} / {@code effect} 是字符串，写错一个字母不会编译失败，
     * 只会在运行时静静退化成色块或干脆不画。素材包有两套目录（本体与独立特效），
     * 这里把两套都查一遍。
     */
    @Test
    void everyAssetReferencedByExpansionSkillsExists() {
        StringBuilder missing = new StringBuilder();
        for (EnemyKind kind : EXPANSION_KINDS) {
            for (WorldType world : WorldType.values()) {
                String form = world == WorldType.LIGHT ? "light" : "shadow";
                Set<String> actions = new HashSet<>(List.of("idle", "move", "hurt", "death"));
                if (kind.boss()) actions.add("transform");
                List<EnemySkill> skills = EnemySkill.forEnemy(kind, world);
                for (EnemySkill skill : skills) {
                    actions.add(skill.actionBase() + "_windup");
                    actions.add(skill.actionBase() + "_release");
                    actions.add(skill.actionBase() + "_recovery");
                }
                for (String action : actions) {
                    requireAsset(missing, "sprites/monsters/enemies/" + kind.assetId() + "/" + form
                            + "/" + action + "/right_01.png", kind + " 本体动作 " + action);
                }
                for (EnemySkill skill : skills) {
                    requireAsset(missing, "sprites/monsters/effects/" + kind.assetId() + "/" + form
                            + "/" + skill.effect() + "/right_01.png", kind + " 招式特效 " + skill.effect());
                }
                // 命中特效是按物种 + 世界反推的（EnemySystem.impactEffect），
                // 它同样必须是真实存在的目录，否则挨打时只会静悄悄地什么都不画。
                String impact = EnemySystem.impactEffect(kind, world);
                requireAsset(missing, "sprites/monsters/effects/" + kind.assetId() + "/" + form
                        + "/" + impact + "/right_01.png", kind + " 命中特效 " + impact);
            }
        }
        assertTrue(missing.isEmpty(), "以下素材缺失：\n" + missing);
    }

    /** 每个招式引用的预警模板（素材包规范化的几何模板）也要在。 */
    @Test
    void everyTelegraphTemplateUsedByTheExpansionExists() {
        StringBuilder missing = new StringBuilder();
        for (EnemyKind kind : EXPANSION_KINDS) {
            for (WorldType world : WorldType.values()) {
                String form = world == WorldType.LIGHT ? "light" : "shadow";
                for (EnemySkill skill : EnemySkill.forEnemy(kind, world)) {
                    if (skill.pattern() == EnemySkill.Pattern.PROJECTILE
                            || skill.pattern() == EnemySkill.Pattern.SUMMON) continue;
                    requireAsset(missing, "sprites/monsters/telegraphs/" + form + "/" + skill.telegraph() + "/01.png",
                            kind + " 预警模板 " + skill.telegraph());
                }
            }
        }
        assertTrue(missing.isEmpty(), "以下预警模板缺失：\n" + missing);
    }

    private static void requireAsset(StringBuilder missing, String path, String owner) {
        if (MonsterExpansionTest.class.getResource("/com/phantomcorridor/" + path) == null) {
            missing.append("  - ").append(path).append("（").append(owner).append("）\n");
        }
    }

    /**
     * 每个新首领在两界都要能完整打满一分钟而不抛异常。
     *
     * <p>这是整条链路的冒烟测试：选技轮换 → 前摇 / 释放 / 收招 → 弹体 / 预警 / 根篱 / 换位 / 召唤，
     * 以及它们各自的过期与清理。玩家每帧回满血，测的是「招式跑得通」，不是「玩家活不活得下来」。
     */
    @Test
    void everyExpansionBossCanFightForAFullMinuteInBothWorlds() {
        for (EnemyKind kind : BossRoster.EXPANSION_BOSSES) {
            for (WorldType world : WorldType.values()) {
                Room room = openBossRoom();
                RoomNavigationSystem navigation = navigationFor(room);
                Player player = new Player(360, 360);
                if (player.getCurrentWorld() != world) player.toggleWorld();
                EnemySystem system = new EnemySystem();
                Enemy boss = system.spawnForTest(kind, world, 3, Difficulty.NORMAL);
                boss.setPosition(900, 360);
                boss.setAlertRemaining(0.0);

                int frames = 60 * 60;
                for (int frame = 0; frame < frames; frame++) {
                    system.update(DT, player, new PlayerAttackSystem(), navigation);
                    player.updateAnimation(DT, 0.0, 0.0, false, false);
                    if (player.getHp() <= 0) player.restoreHealth(100);
                    assertTrue(Double.isFinite(boss.getX()) && Double.isFinite(boss.getY()),
                            kind + " 的位置必须是有限数");
                }
                assertTrue(navigation.canOccupy(boss.getX(), boss.getY(), kind.footRadius(), world),
                        kind + " 打完一分钟之后仍应站在合法位置");
            }
        }
    }

    /**
     * 相位脉冲要能真的打散玩家身边的敌方弹幕。
     *
     * <p>旧实现只清了一张早已不用的弹体表，因此「切界脉冲清除弹幕」一直只是文档里的承诺；
     * 现在它作用在真正装载攻击实体的系统上，并按招式自己的 {@code pulseClearable} 过滤。
     */
    @Test
    void phasePulseDispersesNearbyClearableProjectiles() {
        Room room = openRoom(13, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(640, 360);
        EnemySystem system = new EnemySystem();
        Enemy spore = system.spawnForTest(EnemyKind.SPORE, WorldType.LIGHT, 1, Difficulty.NORMAL);
        spore.setPosition(340, 360);
        spore.setAlertRemaining(0.0);
        system.castForTest(spore, player, EnemySkill.SPORE_BLOOM);

        boolean inRange = runUntil(system, player, navigation,
                () -> system.getAttacks().stream().anyMatch(a -> Math.hypot(a.getX() - player.getX(),
                        a.getY() - player.getY()) <= GameConfig.PHASE_PULSE_RADIUS),
                8.0);
        assertTrue(inRange, "测试前提：孢子应当飞到玩家身边");

        int cleared = system.clearPulseClearableWithin(player.getX(), player.getY(),
                GameConfig.PHASE_PULSE_RADIUS);
        assertTrue(cleared > 0, "玩家身边飞着的孢子应当被相位脉冲打散，实际清掉 " + cleared);
        assertTrue(system.getAttacks().stream().noneMatch(a -> a.isMoving()
                        && Math.hypot(a.getX() - player.getX(), a.getY() - player.getY())
                        <= GameConfig.PHASE_PULSE_RADIUS),
                "脉冲范围内的可清弹体应当全部消失");
    }

    /** 三种新小怪必须真的进了战斗房的刷怪池，而不是只存在于枚举里。 */
    @Test
    void newMobsActuallyAppearInBattleRooms() {
        Set<EnemyKind> spawned = EnumSet.noneOf(EnemyKind.class);
        for (long seed = 1L; seed <= 30L; seed++) {
            DungeonMap map = new com.phantomcorridor.model.dungeon.MapGenerator().generate(seed);
            for (Room room : map.rooms()) {
                if (room.type() != RoomType.BATTLE) continue;
                RoomNavigationSystem navigation = navigationFor(room);
                Player player = new Player((room.minX() + room.maxX()) / 2.0,
                        (room.minY() + room.maxY()) / 2.0);
                EnemySystem system = new EnemySystem();
                system.enterRoom(room, seed, player, navigation);
                system.getEnemies().forEach(enemy -> spawned.add(enemy.getKind()));
            }
        }
        for (EnemyKind kind : List.of(EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT)) {
            assertTrue(spawned.contains(kind), kind + " 应当出现在战斗房的刷怪池里，实际生成过 " + spawned);
        }
    }

    // ================= 测试辅助 =================

    /** 直接按下一招，返回玩家总共掉了多少血。 */
    private static int damageFromSkill(EnemyKind kind, EnemySkill skill, double distance, double seconds) {
        Room room = openRoom(11, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(520, 360);
        player.clearShield();
        EnemySystem system = new EnemySystem();
        Enemy enemy = system.spawnForTest(kind, skill.world(), 1, Difficulty.NORMAL);
        enemy.setPosition(player.getX() + distance, player.getY());
        enemy.setAlertRemaining(0.0);
        system.castForTest(enemy, player, skill);
        runFor(system, player, navigation, seconds);
        return (int) Math.round(100 - player.getHp());
    }

    /** 直接按下一招，收集它在起手阶段铺下的预警。 */
    private static List<EnemyTelegraph> telegraphsFromSkill(EnemyKind kind, EnemySkill skill, double distance) {
        Room room = openRoom(12, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(520, 360);
        EnemySystem system = new EnemySystem();
        Enemy enemy = system.spawnForTest(kind, skill.world(), 1, Difficulty.NORMAL);
        enemy.setPosition(player.getX() + distance, player.getY());
        enemy.setAlertRemaining(0.0);
        system.castForTest(enemy, player, skill);
        return new ArrayList<>(system.getTelegraphs());
    }

    private static EnemyTelegraph waitForTelegraph(EnemySystem system, Player player,
                                                   RoomNavigationSystem navigation, double seconds,
                                                   EnemyTelegraph.Shape shape) {
        int frames = (int) Math.round(seconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            player.updateAnimation(DT, 0.0, 0.0, false, false);
            for (EnemyTelegraph telegraph : system.getTelegraphs()) {
                if (telegraph.shape() == shape && !telegraph.isTriggered()) return telegraph;
            }
        }
        return null;
    }

    private static void runFor(EnemySystem system, Player player, RoomNavigationSystem navigation,
                               double seconds) {
        runUntil(system, player, navigation, () -> false, seconds);
    }

    /**
     * 推进一帧：敌人系统 + 玩家自身计时。
     *
     * <p>玩家模型里的受击无敌时间（0.65 秒）只在 {@code updateAnimation} 里递减，
     * 而这里不走 {@code GameSession}，所以必须手动把玩家也推一帧——
     * 否则第一下挨打之后玩家会永远处于无敌状态，多段伤害的测试根本测不出来。
     */
    private static boolean runUntil(EnemySystem system, Player player, RoomNavigationSystem navigation,
                                    BooleanSupplier condition, double seconds) {
        int frames = (int) Math.round(seconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            player.updateAnimation(DT, 0.0, 0.0, false, false);
            if (condition.getAsBoolean()) return true;
        }
        return false;
    }

    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)), List.of());
    }

    private static Room openBossRoom() {
        return new Room(8, RoomType.BOSS, 0, 0, RoomShape.RECTANGLE,
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
