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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 敌人伤害的数值与归属。
 *
 * <p>旧实现里所有命中都在结算处写死 {@code player.takeDamage(1)}：
 * 不管是灯魇的一发小弹、傀儡的践踏还是守望者的裂隙长矛，玩家掉的血一模一样，
 * 不同敌人之间没有任何“这一下很疼 / 那一下可以硬吃”的区别。
 * 这组用例把「伤害跟着招式走」钉在三个层面上：
 * <ul>
 *   <li><b>数值层</b>：每一招都有自己的伤害，同一只怪的小招与重招不同；</li>
 *   <li><b>行为层</b>：真的让某只怪打中玩家，核对掉的血等于它这一招的伤害；</li>
 *   <li><b>护盾层</b>：护盾先吃伤害，打穿了剩下的才轮到生命值。</li>
 * </ul>
 *
 * <p>行为用例只靠「距离」来收敛招式：同一界里每个招式的施法距离不同，
 * 把敌人摆在某个距离上，它这一界可能够得着的招式就只剩固定几种，
 * 因此断言写成「本次伤害必须落在该物种该世界的招式数值集合里」，
 * 既能挡住「所有攻击都是 1 点」的旧行为，也不会因为 AI 随机挑招而闪烁。
 */
class EnemyDamageTest {
    private static final double DT = AppConfig.FIXED_DT;

    /** 行为用例的统一摆放距离：多数怪在这个距离上只有一两种招式够得着。 */
    private static final double ENGAGEMENT_DISTANCE = 300.0;

    // ---- 数值层 ----

    @Test
    void everySkillCarriesTheDamageItDeals() {
        // 同一只怪的小招与重招必须不同，否则“读得出轻重”无从谈起。
        assertEquals(6.0, EnemySkill.GOLEM_CRACK.damage(), 1e-9);
        assertEquals(7.5, EnemySkill.GOLEM_SLAM.damage(), 1e-9);
        assertEquals(5.0, EnemySkill.LANTERN_SEEKER.damage(), 1e-9);
        assertEquals(4.0, EnemySkill.LANTERN_NEEDLES.damage(), 1e-9);
        // 精英与首领的正经招式明显更疼。
        assertEquals(9.0, EnemySkill.EXECUTIONER_SPEAR.damage(), 1e-9);
        assertEquals(12.0, EnemySkill.WATCHER_JUDGMENT.damage(), 1e-9);
        // 例外：守望者的 16 发环形弹幕单发必须便宜，否则擦到就融血。
        assertEquals(3.0, EnemySkill.WATCHER_BARRAGE.damage(), 1e-9);
        // 召唤与纯位移招不该造成正经伤害。
        assertEquals(0.0, EnemySkill.WATCHER_CALL.damage(), 1e-9);
        assertEquals(0.0, EnemySkill.WATCHER_SUMMON.damage(), 1e-9);
    }

    @Test
    void damageValuesCoverAWideSpread() {
        Set<Double> damages = new HashSet<>();
        for (EnemySkill skill : EnemySkill.values()) {
            if (skill.damage() > 0.0) damages.add(skill.damage());
        }
        assertTrue(damages.size() >= 8,
                "招式伤害应当拉开档次，实际只有 " + damages.size() + " 种：" + damages);
        double lightest = damages.stream().min(Double::compare).orElseThrow();
        double heaviest = damages.stream().max(Double::compare).orElseThrow();
        assertTrue(heaviest >= 3.0 * lightest,
                "最重的招式至少要打到最轻招式的 3 倍，轻重才读得出来：" + damages);
    }

    @Test
    void normalEnemiesElitesAndTheBossDealDifferentContactDamage() {
        assertTrue(EnemyKind.LANTERN.attackDamage() < EnemyKind.EXECUTIONER.attackDamage(),
                "精英的撞身伤害应当高于普通小怪");
        assertTrue(EnemyKind.EXECUTIONER.attackDamage() < EnemyKind.WATCHER.attackDamage(),
                "首领的撞身伤害应当高于精英");
    }

    @Test
    void aSkillInheritsTheDamageTypeOfItsWorld() {
        assertEquals(DamageType.LIGHT, EnemySkill.LANTERN_SEEKER.damageType());
        assertEquals(DamageType.SHADOW, EnemySkill.LANTERN_NEEDLES.damageType());
        assertEquals(DamageType.LIGHT, EnemySkill.WATCHER_JUDGMENT.damageType());
        assertEquals(DamageType.SHADOW, EnemySkill.WATCHER_DOUBLE_SLASH.damageType());
    }

    @Test
    void everySpawnableEnemyHasAtLeastOneDamagingSkillInBothWorlds() {
        for (EnemyKind kind : EnemyKind.values()) {
            for (WorldType world : WorldType.values()) {
                List<EnemySkill> skills = EnemySkill.forEnemy(kind, world);
                assertFalse(skills.isEmpty(), kind + " 在 " + world + " 界没有可用招式");
                for (EnemySkill skill : skills) {
                    assertTrue(skill.damage() >= 0.0, kind + " 的 " + skill + " 伤害不能为负");
                }
                assertTrue(skills.stream().anyMatch(skill -> skill.damage() > 0.0),
                        kind + " 在 " + world + " 界至少要有一招能造成伤害，否则它打不死玩家");
            }
        }
    }

    @Test
    void difficultyScalesDamageTogetherWithEnemyStats() {
        for (Difficulty difficulty : Difficulty.values()) {
            assertEquals(difficulty.enemyStatMultiplier(), difficulty.playerDamageMultiplier(), 1e-9,
                    "难度倍率必须同时作用于敌人属性与玩家伤害，否则高难度只会变成磨血");
        }
    }

    // ---- 行为层：真的挨打，看掉多少血 ----

    @Test
    void everyEnemyDealsOneOfItsOwnConfiguredDamagesAndNeverAFixedOne() {
        Set<Integer> observed = new HashSet<>();
        for (EnemyKind kind : EnemyKind.values()) {
            for (WorldType world : WorldType.values()) {
                int lost = healthLostToFirstHit(kind, world, ENGAGEMENT_DISTANCE);
                Set<Integer> allowed = allowedDamageFor(kind, world);
                assertTrue(allowed.contains(lost),
                        kind + "（" + world + "）打掉了 " + lost + " 点，但它没有任何一招是这个数值：" + allowed);
                observed.add(lost);
            }
        }
        assertTrue(observed.size() >= 4,
                "不同敌人的第一下伤害应当有明显差异，实际只观察到 " + observed);
    }

    @Test
    void aDistantGolemCrackCostsSixPointsNotOne() {
        // 傀儡光界站在 250 像素外：地裂（射程 195）够得着、践踏（射程 180）够不着，
        // 而影界的技能在光界根本不会释放，所以这一下必然是地裂 = 6 点。
        assertEquals(6, healthLostToFirstHit(EnemyKind.GOLEM, WorldType.LIGHT, 250.0),
                "傀儡地裂应当打掉 6 点，而不是所有攻击统一的 1 点");
    }

    @Test
    void aLanternOrbAndANeedleSpreadCostDifferentAmounts() {
        // 灯魇两界的招式都是弹体，伤害却不同（光界 5 / 影界 4），
        // 而且它每一界都只有一招：这是最干净的一组对照。
        assertEquals(5, healthLostToFirstHit(EnemyKind.LANTERN, WorldType.LIGHT, ENGAGEMENT_DISTANCE),
                "灯魇光界的追踪光球应当打掉 5 点");
        assertEquals(4, healthLostToFirstHit(EnemyKind.LANTERN, WorldType.SHADOW, ENGAGEMENT_DISTANCE),
                "灯魇影界的暗针散射单发应当只打 4 点");
    }

    @Test
    void aWolfPounceLandsItsSixPointsOnContact() {
        // 影狼影界的扑咬是一次冲刺 + 落点咬击：冲刺固定飞 170 像素，
        // 所以只有把玩家放在“冲刺落点正好咬得到”的距离上（约 231 像素）才咬得中，
        // 贴脸站反而会被它冲过头——这是招式本身的几何，不是测试的偶然。
        assertEquals(6, healthLostToFirstHit(EnemyKind.WOLF, WorldType.SHADOW, 231.0),
                "棱晶狼影界的扑咬应当打掉 6 点");
    }

    @Test
    void theBossHitsConsiderablyHarderThanItsBarragePellets() {
        int bossHit = healthLostToFirstHit(EnemyKind.WATCHER, WorldType.LIGHT, ENGAGEMENT_DISTANCE);
        assertTrue(allowedDamageFor(EnemyKind.WATCHER, WorldType.LIGHT).contains(bossHit),
                "守望者的伤害必须来自它自己的招式表：" + bossHit);
        assertEquals(12, EnemySkill.WATCHER_JUDGMENT.damage(), 1e-9, "日审判是全游戏最重的单体一招");
        assertTrue(EnemySkill.WATCHER_JUDGMENT.damage() >= 4.0 * EnemySkill.WATCHER_BARRAGE.damage(),
                "压轴重招与弹幕单发必须拉开数量级差距，否则弹幕会变成纯粹的血量税");
    }

    @Test
    void enemyHitLeavesAShieldFlashOnTheHudChannel() {
        Room room = openRoom(8, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(560, 470);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        system.spawnForTest(EnemyKind.WATCHER, WorldType.LIGHT, 1, null)
                .setPosition(player.getX() + ENGAGEMENT_DISTANCE, player.getY());

        assertTrue(runUntilFirstHit(system, player, navigation, 12.0), "守望者应当打到玩家");

        assertEquals(1, system.getDamageFlashes().size(), "一次命中应当只留一条飘字");
        EnemySystem.DamageFlash flash = system.getDamageFlashes().getFirst();
        assertTrue(flash.y() < player.getY(), "飘字要画在玩家头顶，不能盖在角色身上");
        assertTrue(flash.remaining() <= 1.0, "飘字应当是短命的视觉残留");
    }

    // ---- 护盾层 ----

    @Test
    void shieldSwallowsTheFirstHitAndHealthOnlyPaysAfterItBreaks() {
        Room room = openRoom(8, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(560, 470);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        system.spawnForTest(EnemyKind.WATCHER, WorldType.LIGHT, 1, null)
                .setPosition(player.getX() + ENGAGEMENT_DISTANCE, player.getY());

        double shieldBefore = player.getShield();
        assertTrue(shieldBefore > 0.0, "测试前提：开局带一整条护盾");
        boolean shielded = runUntil(system, player, navigation,
                () -> system.getDamageFlashes().stream().anyMatch(EnemySystem.DamageFlash::onShield), 12.0);

        assertTrue(shielded, "第一下应当被护盾吃掉，并留下“盾”的飘字");
        assertEquals(100, player.getHp(), "护盾还挡得住的时候，生命值不该掉");
        assertTrue(player.getShield() < shieldBefore, "护盾应当真的被扣掉一截");
    }

    @Test
    void damageThatBlowsThroughTheShieldStillCostsHealth() {
        Player player = new Player(0, 0);
        player.clearShield();
        player.restoreShield(5.0);   // 只剩 5 点盾

        // 12 点重击打穿只剩 5 点的护盾：剩下 7 点必须落到生命值上。
        Player.DamageResult result = player.takeDamage(EnemySkill.WATCHER_JUDGMENT.damage(),
                EnemySkill.WATCHER_JUDGMENT.damageType());

        assertNotNull(result);
        assertEquals(5.0, result.absorbedByShield(), 1e-9);
        assertEquals(7, result.healthLost());
        assertEquals(93, player.getHp());
        assertFalse(player.hasShield(), "护盾应当被打碎");
        assertEquals(DamageType.LIGHT, result.type(), "伤害类型要一路带到结算结果上");
    }

    @Test
    void clearedRoomsCleanUpTheirDamageFlashes() {
        Room room = openRoom(9, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(560, 470);
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        system.spawnForTest(EnemyKind.WATCHER, WorldType.LIGHT, 1, null)
                .setPosition(player.getX() + ENGAGEMENT_DISTANCE, player.getY());
        assertTrue(runUntilFirstHit(system, player, navigation, 12.0));

        system.reset();

        assertTrue(system.getDamageFlashes().isEmpty(), "换层重置后不该残留上一层的飘字");
    }

    // ---- 工具 ----

    /** 该物种在该世界下所有可能打出的伤害（向上取整成生命值的扣减额）。 */
    private static Set<Integer> allowedDamageFor(EnemyKind kind, WorldType world) {
        Set<Integer> allowed = new HashSet<>();
        for (EnemySkill skill : EnemySkill.forEnemy(kind, world)) {
            if (skill.damage() > 0.0) allowed.add((int) Math.ceil(skill.damage()));
        }
        return allowed;
    }

    /**
     * 让某只怪单独对上玩家，返回它第一下打掉玩家多少血。
     *
     * <p>先把护盾清空，测出来的就是纯粹的“这一招多少伤害”。
     * 房间用不会自动刷怪的奖励房，只手动投放目标物种——否则按房生成会把
     * 一屋子随机怪都放进来，根本分不清是谁打的。
     */
    private static int healthLostToFirstHit(EnemyKind kind, WorldType world, double distance) {
        Room room = openRoom(8, RoomType.REWARD);
        RoomNavigationSystem navigation = navigationFor(room);
        Player player = new Player(560, 470);
        player.clearShield();
        // 敌人只在“与玩家同界”时才行动，测影界招式必须把玩家也切到影界。
        if (world == WorldType.SHADOW) player.toggleWorld();
        EnemySystem system = new EnemySystem();
        system.enterRoom(room, 7L, player, navigation);
        Enemy enemy = system.spawnForTest(kind, world, 1, null);
        enemy.setPosition(player.getX() + distance, player.getY());

        assertTrue(runUntilFirstHit(system, player, navigation, 12.0),
                kind + "（" + world + "，距离 " + distance + "）应当在 12 秒内打到玩家");
        return 100 - player.getHp();
    }

    private static boolean runUntilFirstHit(EnemySystem system, Player player,
                                            RoomNavigationSystem navigation, double limitSeconds) {
        // 必须记录“开跑那一刻的值”再比较：护盾被测试主动清空后本来就小于上限，
        // 直接拿 getMaxShield() 当基线会让条件在开头就恒真。
        double healthBefore = player.getHp();
        double shieldBefore = player.getShield();
        return runUntil(system, player, navigation,
                () -> player.getHp() < healthBefore || player.getShield() < shieldBefore, limitSeconds);
    }

    private static boolean runUntil(EnemySystem system, Player player, RoomNavigationSystem navigation,
                                    BooleanSupplier condition, double limitSeconds) {
        int frames = (int) Math.round(limitSeconds / DT);
        for (int frame = 0; frame < frames; frame++) {
            system.update(DT, player, new PlayerAttackSystem(), navigation);
            if (condition.getAsBoolean()) return true;
        }
        return false;
    }

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
