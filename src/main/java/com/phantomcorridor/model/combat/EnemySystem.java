package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.Difficulty;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomFlowField;
import com.phantomcorridor.model.room.RoomNavigationSystem;
import com.phantomcorridor.model.room.Wall;
import com.phantomcorridor.util.CollisionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 共享敌人生成、AI、属性伤害以及清房门禁的纯逻辑系统。
 *
 * <p>索敌规则：敌人只要与玩家同处一房就会锁定玩家并主动接近（见 {@link #detectionRange()}），
 * 被墙挡住视线时继续绕行接近、只有确实看得见玩家时才开火；玩家换界后敌人继续索敌与施法。
 *
 * <p>首领另有召唤机制：裂隙先成型、召唤物后落地，什么时候召唤由血量阶段、打不到玩家的时长
 * 与场上剩余召唤物共同决定（见 {@link #beginSummonIfDue}）。
 *
 * <p>v2 扩展包把招式的表现拆成了三类实体，它们各自有独立的生命周期与清理规则：
 * <ul>
 *   <li>{@link EnemyAttack}：飞出去的弹体（丝矢、炮弹、慢孢子、晶羽）；</li>
 *   <li>{@link EnemyTelegraph}：先画在地上、到时再爆的预警（扇面、环带、横带、地面标记）；</li>
 *   <li>{@link RootWall}：只阻挡、不伤害的临时地形（根冠古树的根篱）。</li>
 * </ul>
 * 多段伤害靠 {@link EnemySkill.HitPolicy} + {@link HitIds} 去重：整次施法共用标识的招式最多打中一次
 * （聚棱炮的弹体与爆开、封线的三段），分段标识的招式每段各算一次（二连斩、两条根带、三点校射）。
 */
public final class EnemySystem {
    /** 一次裂隙闪现的视觉残留：起点、终点与剩余时间，供渲染层画裂隙特效。 */
    public record BlinkFlash(double fromX, double fromY, double toX, double toY, double remaining) { }

    /**
     * 贴墙绕行的候选方向偏移（角度制），第 0 组对应 +1 侧、第 1 组对应 -1 侧。
     *
     * <p>先走切线，再朝同一侧逐步加大转角，最后允许直接后退；两侧互为镜像。
     */
    private static final double[][] SLIDE_OFFSETS_DEGREES = {
            {0, 22.5, 45, 67.5, 90, 112.5, 135, 157.5, 180},
            {0, -22.5, -45, -67.5, -90, -112.5, -135, -157.5, 180}
    };

    /**
     * 战斗房的普通怪池：v1 的四种加上 v2 的三种小怪。
     *
     * <p>甲虫偏近战、孢子擅长延迟区域、翼蝠靠机动骚扰——和旧的四只混在同一张池子里，
     * 同一个种子下的房间编成仍然可复现。
     */
    private static final EnemyKind[] BATTLE_POOL = {
            EnemyKind.LANTERN, EnemyKind.WOLF, EnemyKind.GOLEM, EnemyKind.MAGE,
            EnemyKind.BEETLE, EnemyKind.SPORE, EnemyKind.RAYBAT
    };

    /** 后段战斗房用来替换普通怪的精英池。 */
    private static final EnemyKind[] ELITE_POOL = {EnemyKind.EXECUTIONER, EnemyKind.BELL};

    /** 受击飘字的停留时间（秒）。 */
    private static final double DAMAGE_FLASH_TIME = 0.85;

    /**
     * 一次受击的飘字反馈。
     *
     * @param value     飘出的数字：护盾全吸收时是被挡下的点数，否则是实际掉的血
     * @param onShield  本次是否只打掉了护盾（渲染层据此换成护盾色）
     * @param damagedHp 本次是否真的伤到了生命值（护盾只挡掉一部分时为 true）
     */
    public record DamageFlash(double x, double y, double value, DamageType type,
                              boolean onShield, boolean damagedHp, double remaining) { }

    private final List<Enemy> enemies = new ArrayList<>();
    private final List<EnemyAttack> attacks = new ArrayList<>();
    private final List<EnemyVisualEffect> visualEffects = new ArrayList<>();
    private final List<SummonRift> summonRifts = new ArrayList<>();
    private final List<DamageFlash> damageFlashes = new ArrayList<>();
    private final List<EnemyTelegraph> telegraphs = new ArrayList<>();
    private final List<RootWall> rootWalls = new ArrayList<>();
    private final List<PendingWall> pendingWalls = new ArrayList<>();
    private final Map<Enemy, ActiveCast> activeCasts = new HashMap<>();
    private final EnumMap<WorldType, Map<Integer, RoomFlowField>> flowFields = new EnumMap<>(WorldType.class);
    /** 已经结算过的命中标识：多段招式靠它区分“同一段的重复判定”和“下一段”。 */
    private final Set<Long> consumedHitIds = new HashSet<>();
    private int activeRoomId = -1;
    private int killsSinceLastRead;
    private int summonCallsSinceLastRead;
    private int floor = 1;
    private Difficulty difficulty = Difficulty.NORMAL;
    /** 当前房间的导航系统（只读引用，用于视线判定；没有房间时为 null）。 */
    private RoomNavigationSystem navigation;
    private BlinkFlash blinkFlash;

    /** 最近一次裂隙闪现的特效状态；已结束时返回 null。 */
    public BlinkFlash getBlinkFlash() { return blinkFlash; }

    public void reset() {
        enemies.clear();
        attacks.clear();
        visualEffects.clear();
        summonRifts.clear();
        damageFlashes.clear();
        telegraphs.clear();
        rootWalls.clear();
        pendingWalls.clear();
        activeCasts.clear();
        flowFields.clear();
        consumedHitIds.clear();
        activeRoomId = -1;
        killsSinceLastRead = 0;
        summonCallsSinceLastRead = 0;
        blinkFlash = null;
    }

    /** 当前层数：决定新生成敌人的生命值与防御。 */
    public void setFloor(int floor) { this.floor = Math.max(1, floor); }

    public int getFloor() { return floor; }

    /** 本局难度：与层数成长一起决定新生成敌人的基础属性。 */
    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
    }

    public Difficulty getDifficulty() { return difficulty; }

    /** 仅在未清理的战斗/Boss 房生成；Boss 房严格只生成一名首领（按层从 {@link BossRoster} 取）。 */
    public void enterRoom(Room room, long dungeonSeed, Player player, RoomNavigationSystem navigation) {
        if (activeRoomId == room.id()) return;
        enemies.clear();
        attacks.clear();
        visualEffects.clear();
        summonRifts.clear();
        telegraphs.clear();
        rootWalls.clear();
        pendingWalls.clear();
        consumedHitIds.clear();
        activeCasts.clear();
        // 房间级临时地形只在生出它的那场战斗里有效；换房时连导航层一起清掉。
        if (navigation != null) navigation.clearTemporaryWalls();
        activeRoomId = room.id();
        if (room.isCleared() || (room.type() != RoomType.BATTLE && room.type() != RoomType.BOSS)) return;

        Random random = new Random(dungeonSeed ^ ((long) room.id() * 0x9E3779B97F4A7C15L));
        if (room.type() == RoomType.BOSS) {
            // 每层的首领由本层种子决定：第 1 层守望者，第 2～5 层从五个新首领里抽四个且互不重复。
            EnemyKind boss = BossRoster.forFloor(floor, dungeonSeed);
            spawn(boss, WorldType.LIGHT, room, player, navigation, random);
            return;
        }
        int count = GameConfig.battleEnemyCount(floor,
                random.nextInt(GameConfig.BATTLE_ENEMY_MAX - GameConfig.BATTLE_ENEMY_MIN + 1));
        double eliteChance = GameConfig.battleEliteChance(floor);
        for (int i = 0; i < count; i++) {
            // 精英替换：概率随层数提高（第 1 层没精英，让玩家先认熟基础招式），总量不变。
            EnemyKind kind = i == count - 1 && random.nextDouble() < eliteChance
                    ? ELITE_POOL[random.nextInt(ELITE_POOL.length)]
                    : BATTLE_POOL[random.nextInt(BATTLE_POOL.length)];
            WorldType world = i % 2 == 0 ? WorldType.LIGHT : WorldType.SHADOW;
            spawn(kind, world, room, player, navigation, random);
        }
    }

    private void spawn(EnemyKind kind, WorldType world, Room room, Player player,
                       RoomNavigationSystem navigation, Random random) {
        Enemy enemy = new Enemy(kind, world, 0.0, 0.0, floor, difficulty);
        // 必须按物种真实身位校验：首领 46、精英 34 都比普通怪大，
        // 用统一的小半径放行会让它们出生就压在墙上，之后一步都走不动。
        double radius = enemyRadius(enemy);
        for (int attempt = 0; attempt < 128; attempt++) {
            RoomArea area = room.areas().get(random.nextInt(room.areas().size()));
            double x = area.x() + 58 + random.nextDouble() * Math.max(1, area.width() - 116);
            double y = area.y() + 58 + random.nextDouble() * Math.max(1, area.height() - 116);
            if (Math.hypot(x - player.getX(), y - player.getY()) < 170) continue;
            if (navigation.canOccupy(x, y, radius, world)) {
                enemy.setPosition(x, y);
                enemies.add(enemy);
                return;
            }
        }
        // 找不到合法点时不生成，避免敌人出生在墙/障碍物内部。
    }

    public void update(double dt, Player player, PlayerAttackSystem playerAttacks,
                       RoomNavigationSystem navigation) {
        // 玩家命中的结算要用导航系统做视线判定（弹射选目标、爆炸不隔墙），
        // 顺便也让范围伤害与召唤等路径共用同一份引用。
        this.navigation = navigation;
        updateBlinkFlash(dt);
        updateDamageFlashes(dt);
        enemies.forEach(enemy -> enemy.updateTimers(dt));
        resolvePlayerHits(player, playerAttacks);
        visualEffects.forEach(effect -> effect.update(dt));
        visualEffects.removeIf(EnemyVisualEffect::expired);

        boolean bossFell = false;
        boolean bossCrossedOver = false;
        for (var iterator = enemies.iterator(); iterator.hasNext();) {
            Enemy enemy = iterator.next();
            if (enemy.isDead()) {
                visualEffects.add(EnemyVisualEffect.body(enemy.getKind(), enemy.getWorld(), "death", enemy.getFacing(),
                        enemy.getX(), enemy.getY(), displayWidth(enemy.getKind()), .70));
                activeCasts.remove(enemy);
                killsSinceLastRead++;
                bossFell |= enemy.isBoss();
                iterator.remove();
                continue;
            }
            // 首领在半血时从光界进入暗界，保留同一实体与血量。
            if (enemy.isBoss() && enemy.getHp() * 2 <= enemy.getMaxHp() && enemy.getWorld() == WorldType.LIGHT) {
                enemy.setWorld(WorldType.SHADOW);
                attacks.removeIf(attack -> attack.getSource() == enemy.getKind());
                telegraphs.removeIf(telegraph -> telegraph.source() == enemy.getKind());
                activeCasts.remove(enemy);
                enemy.endCastGuard();
                // 换形本身不带伤害，但有一段 1.2 秒的收招：玩家要先看清新形态，而不是被瞬发贴脸。
                enemy.playAnimation("transform", GameConfig.BOSS_TRANSFORM_LOCK, false);
                enemy.setAlertRemaining(GameConfig.BOSS_TRANSFORM_LOCK);
                visualEffects.add(crossoverEffect(enemy));
                // 素材包规则 clearOnBossWorldExit：首领离开某一界，它在那一界留下的造物随之溃散。
                // 真正清理由循环外的 collapseSummons 执行——在遍历 enemies 时删除会直接抛并发修改异常。
                bossCrossedOver = true;
            }
            if (enemy.getAnimationAction().equals("transform") && !enemy.isAnimationFinished()) continue;
            if (advanceCast(enemy, player, navigation)) continue;
            if (enemy.getAnimationAction().equals("hurt") && !enemy.isAnimationFinished()) continue;
            if (enemy.getAnimationAction().equals("hurt")) enemy.playAnimation("idle", 1.0 / 6.0, true);
            if (enemy.getAnimationAction().equals("guard") && enemy.isGuarding()) continue;
            if (enemy.getAnimationAction().equals("guard")) enemy.playAnimation("idle", 1.0 / 6.0, true);
            double distance = Math.hypot(enemy.getX() - player.getX(), enemy.getY() - player.getY());
            if (!acquireTarget(enemy, distance)) continue;
            // 索敌成功后即使隔着墙/障碍也会持续接近；只有真正看得见玩家时才开火。
            // 视线用弹体半径探测：判定的其实是“这条线上弹体能不能飞过去”。
            // 若用敌人自身半径（首领 46 像素），玩家只要站在只有更小身位放得下的位置，
            // 敌人就会判定“看不见”而一路贴到脸上也不开火。
            double fireRange = attackRange(enemy.getKind());
            boolean lineOfSight = distance <= fireRange
                    && navigation.isSegmentClear(enemy.getX(), enemy.getY(),
                    player.getX(), player.getY(), GameConfig.ENEMY_PROJECTILE_RADIUS, enemy.getWorld());
            maybeEscapeWedge(enemy, player, navigation, dt, distance, lineOfSight);
            moveTowardPlayer(enemy, player, navigation, dt, lineOfSight);
            // 打不到玩家的时间只对首领有意义：它就是“该喊增援了”的计时器。
            if (enemy.isBoss()) {
                if (lineOfSight) enemy.resetSummonPressure();
                else enemy.addSummonPressure(dt);
            }
            if (beginSummonIfDue(enemy, player, distance, lineOfSight)) continue;
            if (lineOfSight && enemy.canAttack()) beginCast(enemy, player, chooseSkill(enemy, distance));
        }
        // 首领一倒、或它跨界离开，它的造物与还没成型的裂隙一起溃散：
        // 否则首领已经死了，裂隙还会吐出小怪，房间永远清不空，传送门也就不会出现。
        if (bossFell || bossCrossedOver) {
            collapseSummons();
            clearRootWalls();
            telegraphs.removeIf(telegraph -> telegraph.source().boss());
        }
        // 预警放在本体循环之后推进：释放当帧还能最后调整自己的判定范围
        // （甲虫顶撞撞墙提前停下时，警示带要跟着缩短，不能继续承诺一段走不到的直线）。
        updateTelegraphs(dt, player);
        updateRootWalls(dt, navigation);
        updateSummonRifts(dt, navigation);
        updateEnemyAttacks(dt, player, navigation);
    }

    /** 裂隙特效只是视觉残留，按剩余时间自然消退。 */
    private void updateBlinkFlash(double dt) {
        if (blinkFlash == null) return;
        double remaining = blinkFlash.remaining() - Math.max(0.0, dt);
        blinkFlash = remaining <= 0.0 ? null
                : new BlinkFlash(blinkFlash.fromX(), blinkFlash.fromY(),
                blinkFlash.toX(), blinkFlash.toY(), remaining);
    }

    /**
     * 受击飘字同样只是视觉残留。
     *
     * <p>飘字列表必须在这里推进：它同时承载“玩家挨了多少”和“敌人掉了多少”，
     * 由渲染层只读地画出来，逻辑层不依赖任何渲染帧。
     */
    private void updateDamageFlashes(double dt) {
        if (damageFlashes.isEmpty()) return;
        damageFlashes.replaceAll(flash -> new DamageFlash(flash.x(), flash.y(), flash.value(), flash.type(),
                flash.onShield(), flash.damagedHp(), flash.remaining() - Math.max(0.0, dt)));
        damageFlashes.removeIf(flash -> flash.remaining() <= 0.0);
    }

    /** 当前还在显示的受击飘字（玩家挨打 / 敌人掉血）。 */
    public List<DamageFlash> getDamageFlashes() { return Collections.unmodifiableList(damageFlashes); }

    /** 场上还没结算 / 还在表现的预警实体。 */
    public List<EnemyTelegraph> getTelegraphs() { return Collections.unmodifiableList(telegraphs); }

    /** 当前有效的临时阻挡地形（根篱），含只作装饰、不参与碰撞的那些。 */
    public List<RootWall> getRootWalls() { return Collections.unmodifiableList(rootWalls); }

    /**
     * 切界脉冲：清除玩家附近**可被脉冲清除**的敌方弹幕。
     *
     * <p>素材包给每一招都标了 {@code pulseClearable}：飞出去的孢子、丝矢、晶羽、炮弹都算，
     * 而俯冲这类「本体位移 + 地面判定」不算——它们没有可以被打散的弹体。
     * 旧包的招式没有显式标记，按历史行为一律视为可清。
     *
     * @return 实际清掉的弹体数量（调试与测试用）
     */
    public int clearPulseClearableWithin(double x, double y, double radius) {
        int before = attacks.size();
        attacks.removeIf(attack -> attack.isMoving()
                && (attack.getSkill() == null || attack.getSkill().pulseClearable())
                && Math.hypot(attack.getX() - x, attack.getY() - y) <= radius + attack.getRadius());
        // 还在预警阶段的弹体（延迟补发的第二枚）也要一起算：它们同样“已经打出来了”。
        attacks.removeIf(attack -> !attack.isActive()
                && (attack.getSkill() == null || attack.getSkill().pulseClearable())
                && Math.hypot(attack.getX() - x, attack.getY() - y) <= radius + attack.getRadius());
        return before - attacks.size();
    }

    // ==================================================================
    //  预警（EnemyTelegraph）：先画出来、到时判一次
    // ==================================================================

    /** 预警推进与判定：到点的那一刻做一次几何命中，之后只留表现。 */
    private void updateTelegraphs(double dt, Player player) {
        if (telegraphs.isEmpty()) return;
        for (EnemyTelegraph telegraph : telegraphs) {
            telegraph.update(dt);
            if (telegraph.isResolved() || !telegraph.isTriggered()) continue;
            telegraph.markResolved();
            resolveTelegraph(telegraph, player);
        }
        telegraphs.removeIf(EnemyTelegraph::isExpired);
    }

    /** 结算一次预警判定。 */
    private void resolveTelegraph(EnemyTelegraph telegraph, Player player) {
        // 爆炸是看得见的：无论打没打中，都在落点放一次命中特效。
        visualEffects.add(new EnemyVisualEffect(telegraph.source(), telegraph.world(),
                impactEffect(telegraph.source(), telegraph.world()), telegraph.x(), telegraph.y(),
                telegraph.angleRadians(), Math.max(64, telegraph.radius() * 2.6), GameConfig.TELEGRAPH_RESIDUAL_TIME));
        if (telegraph.damage() <= 0.0 || telegraph.isFake()) return;
        long hitId = telegraph.hitId();
        if (hitId != 0L && consumedHitIds.contains(hitId)) return;
        if (!telegraph.contains(player.getX(), player.getY(), GameConfig.PLAYER_RADIUS)) return;
        Player.DamageResult result = player.takeDamage(telegraph.damage(),
                DamageType.ofWorld(telegraph.world()), telegraph.x(), telegraph.y());
        if (result == null) return;
        if (hitId != 0L) consumedHitIds.add(hitId);
        recordPlayerHit(player, result);
    }

    /** 命中去重的判定：这个标识本局这次施法里是否已经结算过。 */
    private boolean alreadyConsumed(long hitId) {
        return hitId != 0L && consumedHitIds.contains(hitId);
    }

    /**
     * 起手时铺下预警实体。
     *
     * <p>素材包的接入约定是「WINDUP：播放前摇，并创建无伤害预警实体；到 windupSeconds 触发一次
     * RELEASE」。因此 v2 的几何招式都在**起手那一刻**就把判定范围画出来，玩家前摇期间读到的
     * 就是最终会爆的那块地面；多段招式（二连斩、两条根带、封线）各自铺一份独立预警。
     */
    private void createCastTelegraphs(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        if (skill.hitPolicy() == EnemySkill.HitPolicy.NONE && skill.pattern() != EnemySkill.Pattern.WALL) return;
        double angle = cast.angle;
        double ex = enemy.getX();
        double ey = enemy.getY();
        double warn = skill.windup();
        double residual = GameConfig.TELEGRAPH_RESIDUAL_TIME;
        switch (skill.pattern()) {
            case SECTOR -> {
                double[] offsets = skill.hitOffsets();
                int segments = Math.max(1, offsets.length);
                for (int i = 0; i < segments; i++) {
                    double delay = offsets.length > 0 ? offsets[i] : 0.0;
                    // 第二段镰斩改变扇面方向：反手刀落在另一个角度上，玩家不能按第一刀的方向绕圈。
                    double segmentAngle = i >= 1 ? angle + Math.toRadians(skill.secondAngleOffset()) : angle;
                    long id = skill.hitPolicy() == EnemySkill.HitPolicy.ONCE_PER_CAST
                            ? cast.hitId : HitIds.next();
                    telegraphs.add(EnemyTelegraph
                            .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                    EnemyTelegraph.Shape.SECTOR, ex, ey, segmentAngle)
                            .radius(skill.radius()).arc(skill.angleDegrees())
                            .damage(telegraphDamage(skill)).warn(warn + delay)
                            // 后一段只提前 0.55 秒重画：过早画满会让两段读成同一个大扇面。
                            .visibleLead(i == 0 ? warn + delay : 0.55)
                            .residual(residual).hitId(id).castId(cast.castId).build());
                }
            }
            case SECTOR_SPLIT -> telegraphs.add(EnemyTelegraph
                    .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                            EnemyTelegraph.Shape.SECTOR_SPLIT, ex, ey, angle)
                    .radius(skill.radius()).arc(skill.angleDegrees()).safeGapDegrees(skill.safeGapDegrees())
                    .damage(telegraphDamage(skill)).warn(warn)
                    .residual(residual).hitId(cast.hitId).castId(cast.castId).build());
            case LINE -> telegraphs.add(EnemyTelegraph
                    .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                            EnemyTelegraph.Shape.LINE, ex, ey, angle)
                    .length(skill.distance()).width(skill.width()).segments(skill.segmentCount())
                    .damage(telegraphDamage(skill)).warn(warn)
                    .residual(residual).hitId(cast.hitId).castId(cast.castId).build());
            case RING_GAP -> {
                // 缺口朝向玩家当时所在的一侧：环带留的“门”必须真的走得出去。
                double gapAngle = Math.atan2(cast.targetY - ey, cast.targetX - ex);
                telegraphs.add(EnemyTelegraph
                        .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                EnemyTelegraph.Shape.RING_GAP, ex, ey, gapAngle)
                        .ring(skill.innerRadius(), skill.outerRadius()).safeGapDegrees(skill.safeGapDegrees())
                        .damage(telegraphDamage(skill)).warn(warn)
                        .residual(residual).hitId(cast.hitId).castId(cast.castId).build());
            }
            case BAND -> {
                double[] offsets = skill.hitOffsets();
                int bands = Math.max(1, offsets.length);
                double spacing = skill.laneSpacing() > 0 ? skill.laneSpacing() : 160.0;
                for (int i = 0; i < bands; i++) {
                    double delay = offsets.length > 0 ? offsets[i] : 0.0;
                    double distance = skill.distance() + i * spacing;
                    double bx = ex + Math.cos(angle) * distance;
                    double by = ey + Math.sin(angle) * distance;
                    // 两条带的缺口要错开，否则“换位躲网”就退化成站着不动。
                    double gapSign = (i % 2 == 0) ? 1.0 : -1.0;
                    long id = skill.hitPolicy() == EnemySkill.HitPolicy.ONCE_PER_CAST
                            ? cast.hitId : HitIds.next();
                    telegraphs.add(EnemyTelegraph
                            .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                    EnemyTelegraph.Shape.BAND, bx, by, angle)
                            .band(skill.bandLength(), skill.width()).safeGap(skill.safeGap())
                            .gapOffset(gapSign * (skill.safeGap() / 2.0 + 40.0))
                            .segments(skill.segmentCount())
                            .damage(telegraphDamage(skill)).warn(warn + delay)
                            .residual(residual).hitId(id).castId(cast.castId).build());
                }
            }
            case MULTI_MARK -> createMarks(enemy, skill, cast);
            case CHARGE -> {
                // 冲撞的危险区就是那条锁定直线；真到释放时若撞墙提前停下，会在这里被改短。
                telegraphs.add(EnemyTelegraph
                        .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                EnemyTelegraph.Shape.LINE, ex, ey, angle)
                        .length(skill.distance()).width(skill.width())
                        .damage(telegraphDamage(skill)).warn(warn)
                        .residual(residual).hitId(cast.hitId).castId(cast.castId).build());
            }
            case BURROW -> {
                double[] landing = burrowLanding(enemy, skill, cast);
                telegraphs.add(EnemyTelegraph
                        .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                EnemyTelegraph.Shape.CIRCLE, landing[0], landing[1], angle)
                        .radius(skill.radius())
                        .damage(telegraphDamage(skill)).warn(warn)
                        .residual(residual).hitId(cast.hitId).castId(cast.castId).build());
            }
            case WALL -> {
                // 根篱不造成伤害，但新缺口必须提前显示（枯篱错门：先撤旧篱、再长新篱）。
                // 预览用首选距离：真正落地时若撞上玩家身位会前后挪一点，届时判定与表现仍以后者为准。
                if (navigation == null) return;
                for (Wall wall : planWalls(enemy, skill, skill.distance(), angle, navigation.getCurrentRoom())) {
                    telegraphs.add(EnemyTelegraph
                            .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                                    EnemyTelegraph.Shape.BAND,
                                    wall.x() + wall.width() / 2.0, wall.y() + wall.height() / 2.0, angle)
                            .band(Math.max(wall.width(), wall.height()), Math.min(wall.width(), wall.height()))
                            .damage(0.0).warn(warn).residual(0.2).fake().hitId(0L).castId(cast.castId).build());
                }
            }
            default -> { }
        }
    }

    /**
     * 地面标记：炮蟹的三点校射、古树的枯籽迟爆、术士的三镜校时与错秒真影共用。
     *
     * <p>标记之间拉开 {@code minimumMarkSpacing}，其中只有 {@code realMarkCount} 个是真伤害；
     * 假圈永远画成虚线空心，而且从头到尾不会“最后一刻变真”。
     */
    private void createMarks(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        int count = Math.max(1, skill.markCount());
        int real = Math.min(count, skill.realMarkCount());
        double spacing = skill.safeGap() > 0 ? skill.safeGap() : 150.0;
        double perpendicular = cast.angle + Math.PI / 2.0;
        double[] offsets = skill.hitOffsets();
        for (int i = 0; i < count; i++) {
            double lateral = (i - (count - 1) / 2.0) * spacing;
            double mx = cast.targetX + Math.cos(perpendicular) * lateral;
            double my = cast.targetY + Math.sin(perpendicular) * lateral;
            boolean isReal = isRealMark(i, count, real);
            double delay = offsets.length > i ? offsets[i] : skill.delayedBurstSeconds();
            long id = skill.hitPolicy() == EnemySkill.HitPolicy.ONCE_PER_CAST ? cast.hitId : HitIds.next();
            EnemyTelegraph.Builder builder = EnemyTelegraph
                    .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                            EnemyTelegraph.Shape.CIRCLE, mx, my, cast.angle)
                    .radius(skill.radius())
                    .damage(isReal ? telegraphDamage(skill) : 0.0)
                    .warn(skill.windup() + delay)
                    .residual(GameConfig.TELEGRAPH_RESIDUAL_TIME)
                    .hitId(isReal ? id : 0L)
                    .castId(cast.castId);
            if (!isReal) builder.fake();
            telegraphs.add(builder.build());
        }
    }

    /**
     * 第 i 个标记是真圈还是假圈。
     *
     * <p>真圈的选择是固定的、可以被学会的：三个圈里只有一个真时，真的永远在正中间（“别站在首领
     * 瞄你的那一点上”）；两个真时，真的是外侧两个（“往中间躲”）。不采用随机，
     * 否则同一招在不同局里读法不一致，玩家没法积累经验。
     */
    private static boolean isRealMark(int index, int count, int realCount) {
        if (realCount >= count) return true;
        if (realCount <= 0) return false;
        if (count == 3 && realCount == 1) return index == 1;
        if (count == 3 && realCount == 2) return index != 1;
        return index < realCount;
    }

    /** 预警实体的伤害：难度缩放后固定下来，与弹体走同一把尺子。 */
    private double telegraphDamage(EnemySkill skill) {
        return skill.damage() <= 0.0 ? 0.0 : scaledDamage(skill);
    }

    // ==================================================================
    //  临时阻挡地形（根篱）
    // ==================================================================

    /** 还要等一会儿才长出来的根篱。 */
    private record PendingWall(double x, double y, double width, double height, WorldType world,
                               double remaining, double duration, boolean blocking) { }

    /**
     * 规划根篱的两段墙体。
     *
     * <p>房间里的障碍物是轴对齐矩形，根篱也不例外：与其让一道斜墙穿过碰撞系统，不如把屏障
     * **取整到最近的轴向**——瞄准方向偏横就竖一道墙、偏纵就横一道墙。玩家读起来仍然是
     * “首领在你和它之间竖起了一道带缺口的篱笆”。墙体会被裁进房间轮廓，不会长到墙外去。
     */
    private List<Wall> planWalls(Enemy enemy, EnemySkill skill, double distance, double angle, Room room) {
        List<Wall> plan = new ArrayList<>();
        double span = GameConfig.ROOT_WALL_MAX_SPAN;
        double gap = Math.max(40.0, skill.safeGap());
        double thickness = 26.0;
        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        double cx = enemy.getX() + dx * distance;
        double cy = enemy.getY() + dy * distance;
        double half = span / 2.0;
        double gapHalf = gap / 2.0;
        if (Math.abs(dx) >= Math.abs(dy)) {
            addClipped(plan, new Wall(cx - thickness / 2.0, cy - half, thickness, half - gapHalf, enemy.getWorld()), room);
            addClipped(plan, new Wall(cx - thickness / 2.0, cy + gapHalf, thickness, half - gapHalf, enemy.getWorld()), room);
        } else {
            addClipped(plan, new Wall(cx - half, cy - thickness / 2.0, half - gapHalf, thickness, enemy.getWorld()), room);
            addClipped(plan, new Wall(cx + gapHalf, cy - thickness / 2.0, half - gapHalf, thickness, enemy.getWorld()), room);
        }
        return plan;
    }

    /** 把一段墙裁进房间轮廓；完全落在房外时丢弃。 */
    private static void addClipped(List<Wall> plan, Wall wall, Room room) {
        double left = Math.max(wall.x(), room.minX());
        double top = Math.max(wall.y(), room.minY());
        double right = Math.min(wall.x() + wall.width(), room.maxX());
        double bottom = Math.min(wall.y() + wall.height(), room.maxY());
        if (right - left < 4.0 || bottom - top < 4.0) return;
        plan.add(new Wall(left, top, right - left, bottom - top, wall.world()));
    }

    /** 这道根篱离玩家够不够远：根篱不许长在玩家脚下（素材包 {@code clearanceAroundPlayer}）。 */
    private static boolean playerClearsWalls(Player player, List<Wall> plan, double clearance) {
        double needed = Math.max(clearance, GameConfig.ROOT_WALL_PLAYER_CLEARANCE * 0.5);
        for (Wall wall : plan) {
            if (CollisionUtil.circleIntersectsRect(player.getX(), player.getY(),
                    needed, wall.x(), wall.y(), wall.width(), wall.height())) {
                return false;
            }
        }
        return true;
    }

    /** 根篱离首领的候选距离：玩家正好站在首选位置上时，往前往后挪一点再试。 */
    private static double[] wallDistanceCandidates(double preferred) {
        return new double[]{preferred, preferred + 70.0, Math.max(60.0, preferred - 70.0),
                preferred + 140.0, Math.max(60.0, preferred - 140.0)};
    }

    /** 释放根篱招式：规划 → 校验 → 落地（校验不过就退化成不挡路的装饰）。 */
    private void growWalls(Enemy enemy, EnemySkill skill, ActiveCast cast,
                           RoomNavigationSystem navigation, Player player) {
        Room room = navigation.getCurrentRoom();
        List<Wall> plan = null;
        boolean blocking = false;
        for (double distance : wallDistanceCandidates(skill.distance())) {
            List<Wall> candidate = planWalls(enemy, skill, distance, cast.angle, room);
            if (candidate.isEmpty()) continue;
            if (playerClearsWalls(player, candidate, skill.clearance())) {
                plan = candidate;
                blocking = wallsKeepRoomOpen(player, candidate, navigation);
                break;
            }
            // 所有候选都躲不开玩家时退而求其次：长出来，但不挡路。
            if (plan == null) plan = candidate;
        }
        if (plan == null) return;
        // 撤旧篱：错门需要一段“旧新墙都不阻挡”的过渡，普通生篱也直接换成新的一批。
        List<Wall> kept = new ArrayList<>();
        for (RootWall wall : rootWalls) {
            if (wall.world() != enemy.getWorld()) kept.add(wall.toWall());
        }
        clearRootWalls(enemy.getWorld());
        double delay = skill.wallTransition();
        for (Wall wall : plan) {
            if (delay > 0.0) {
                pendingWalls.add(new PendingWall(wall.x(), wall.y(), wall.width(), wall.height(), wall.world(),
                        delay, skill.wallDuration(), blocking));
            } else {
                rootWalls.add(new RootWall(wall.x(), wall.y(), wall.width(), wall.height(), wall.world(),
                        skill.wallDuration(), blocking));
            }
        }
        for (Wall wall : kept) {
            rootWalls.add(new RootWall(wall.x(), wall.y(), wall.width(), wall.height(), wall.world(),
                    skill.wallDuration(), true));
        }
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), "root_impact",
                enemy.getX(), enemy.getY(), cast.angle, 180, .45));
    }

    /**
     * 可达性校验：这道根篱会不会把房间封死。
     *
     * <p>做法是把房间铺成 {@link GameConfig#ROOT_WALL_GRID_STEP} 的粗网格，比较「加上候选墙之后
     * 玩家还能走到多少格」与「原本能走到多少格」。保留比例低于
     * {@link GameConfig#ROOT_WALL_MIN_REACHABLE_RATIO} 就判定会把玩家困住，
     * 此时按素材包规则降级为 {@code cosmetic_only_nonblocking}：根篱照常长出来，但不参与碰撞。
     */
    private boolean wallsKeepRoomOpen(Player player, List<Wall> plan, RoomNavigationSystem navigation) {
        if (navigation == null || plan.isEmpty()) return true;
        Room room = navigation.getCurrentRoom();
        double step = GameConfig.ROOT_WALL_GRID_STEP;
        int cols = (int) Math.ceil((room.maxX() - room.minX()) / step) + 1;
        int rows = (int) Math.ceil((room.maxY() - room.minY()) / step) + 1;
        if (cols <= 2 || rows <= 2 || (long) cols * rows > 40000L) return true;
        WorldType world = plan.get(0).world();
        double probe = GameConfig.PLAYER_RADIUS * 0.7;
        boolean[] open = new boolean[cols * rows];
        boolean[] blocked = new boolean[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double x = room.minX() + col * step;
                double y = room.minY() + row * step;
                if (!navigation.canOccupy(x, y, probe, world)) continue;
                open[row * cols + col] = true;
                for (Wall wall : plan) {
                    if (wall.activeIn(world) && CollisionUtil.circleIntersectsRect(
                            x, y, probe, wall.x(), wall.y(), wall.width(), wall.height())) {
                        blocked[row * cols + col] = true;
                        break;
                    }
                }
            }
        }
        int startCol = clampIndex((player.getX() - room.minX()) / step, cols);
        int startRow = clampIndex((player.getY() - room.minY()) / step, rows);
        if (!open[startRow * cols + startCol]) return true;
        int before = reachableCount(open, cols, rows, startRow * cols + startCol);
        boolean[] merged = new boolean[open.length];
        for (int i = 0; i < open.length; i++) merged[i] = open[i] && !blocked[i];
        int after = reachableCount(merged, cols, rows, startRow * cols + startCol);
        if (before <= 0) return true;
        return after >= before * GameConfig.ROOT_WALL_MIN_REACHABLE_RATIO;
    }

    private static int clampIndex(double value, int limit) {
        return (int) Math.max(0, Math.min(limit - 1, Math.round(value)));
    }

    /** 网格连通块大小（四邻 BFS）。 */
    private static int reachableCount(boolean[] open, int cols, int rows, int start) {
        if (!open[start]) return 0;
        boolean[] seen = new boolean[open.length];
        int[] queue = new int[open.length];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = true;
        int count = 0;
        while (head < tail) {
            int current = queue[head++];
            count++;
            int col = current % cols;
            int row = current / cols;
            if (col > 0) tail = push(open, seen, queue, tail, current - 1);
            if (col < cols - 1) tail = push(open, seen, queue, tail, current + 1);
            if (row > 0) tail = push(open, seen, queue, tail, current - cols);
            if (row < rows - 1) tail = push(open, seen, queue, tail, current + cols);
        }
        return count;
    }

    private static int push(boolean[] open, boolean[] seen, int[] queue, int tail, int index) {
        if (!open[index] || seen[index]) return tail;
        seen[index] = true;
        queue[tail] = index;
        return tail + 1;
    }

    /** 根篱计时：到期枯萎，同时把还在生效的部分推给导航层当临时墙。 */
    private void updateRootWalls(double dt, RoomNavigationSystem navigation) {
        if (!pendingWalls.isEmpty()) {
            for (int i = pendingWalls.size() - 1; i >= 0; i--) {
                PendingWall pending = pendingWalls.get(i);
                double remaining = pending.remaining() - Math.max(0.0, dt);
                if (remaining > 0.0) {
                    pendingWalls.set(i, new PendingWall(pending.x(), pending.y(), pending.width(), pending.height(),
                            pending.world(), remaining, pending.duration(), pending.blocking()));
                    continue;
                }
                rootWalls.add(new RootWall(pending.x(), pending.y(), pending.width(), pending.height(),
                        pending.world(), pending.duration(), pending.blocking()));
                pendingWalls.remove(i);
            }
        }
        if (!rootWalls.isEmpty()) {
            rootWalls.forEach(wall -> wall.update(dt));
            rootWalls.removeIf(RootWall::isExpired);
        }
        syncTemporaryWalls(navigation);
    }

    /** 把当前生效的根篱推给导航层：只有 blocking 的那些参与碰撞。 */
    private void syncTemporaryWalls(RoomNavigationSystem navigation) {
        if (navigation == null) return;
        List<Wall> blocking = new ArrayList<>();
        for (RootWall wall : rootWalls) {
            if (wall.blocking()) blocking.add(wall.toWall());
        }
        navigation.setTemporaryWalls(blocking);
    }

    private void clearRootWalls() {
        rootWalls.clear();
        pendingWalls.clear();
    }

    /** 只清掉某一界的根篱（首领换界时，旧界的造物随之枯萎）。 */
    private void clearRootWalls(WorldType world) {
        rootWalls.removeIf(wall -> wall.world() == world);
        pendingWalls.removeIf(pending -> pending.world() == world);
    }

    // ==================================================================
    //  换位与幻象
    // ==================================================================

    /** 镜门换位：起终点各开一道门，本体挪到 180～360 px 外的合法落点，不造成伤害。 */
    private void teleportBoss(Enemy enemy, EnemySkill skill, ActiveCast cast,
                              RoomNavigationSystem navigation, Player player) {
        double[] landing = findTeleportLanding(enemy, player, navigation, skill);
        if (landing == null) return;
        double fromX = enemy.getX();
        double fromY = enemy.getY();
        blinkFlash = new BlinkFlash(fromX, fromY, landing[0], landing[1], GameConfig.WATCHER_BLINK_FLASH_TIME);
        enemy.setPosition(landing[0], landing[1]);
        enemy.clearAvoidance();
        enemy.resetProgressTracking(Math.hypot(player.getX() - landing[0], player.getY() - landing[1]));
        // 落地后至少 1.1 秒才重新起手：镜门是位移，不是贴脸瞬狙。
        enemy.setAlertRemaining(GameConfig.TELEPORT_LANDING_RECOVERY);
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(),
                fromX, fromY, cast.angle, 150, GameConfig.TELEGRAPH_RESIDUAL_TIME));
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(),
                landing[0], landing[1], cast.angle, 170, GameConfig.TELEGRAPH_RESIDUAL_TIME));
    }

    /**
     * 换位落点：玩家周围一圈圈找合法的空地。
     *
     * <p>优先「落地就能看见玩家」的位置——换位的目的是把战斗拉回正面，而不是躲进角落。
     * 落点必须站得下自己的身位、离玩家至少 {@link GameConfig#TELEPORT_PLAYER_CLEARANCE}。
     */
    private double[] findTeleportLanding(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                         EnemySkill skill) {
        double radius = enemyRadius(enemy);
        double min = Math.max(60.0, skill.teleportMin());
        double max = Math.max(min, skill.teleportMax());
        double step = Math.max(20.0, (max - min) / GameConfig.TELEPORT_SEARCH_RINGS);
        double[] fallback = null;
        for (double ring = min; ring <= max + 1e-6; ring += step) {
            int samples = Math.max(16, (int) (Math.PI * ring / (step / 2.0)));
            for (int i = 0; i < samples; i++) {
                double angle = Math.PI * 2 * i / samples;
                double x = enemy.getX() + Math.cos(angle) * ring;
                double y = enemy.getY() + Math.sin(angle) * ring;
                if (Math.hypot(x - player.getX(), y - player.getY()) < GameConfig.TELEPORT_PLAYER_CLEARANCE) continue;
                if (!navigation.canOccupy(x, y, radius, enemy.getWorld())) continue;
                if (!skill.crossWalls()
                        && !navigation.isPathClearTo(enemy.getX(), enemy.getY(), x, y, radius, enemy.getWorld())) continue;
                if (navigation.isSegmentClear(x, y, player.getX(), player.getY(),
                        GameConfig.ENEMY_PROJECTILE_RADIUS, enemy.getWorld())) {
                    return new double[]{x, y};
                }
                if (fallback == null) fallback = new double[]{x, y};
            }
        }
        return fallback;
    }

    /**
     * 幻象：复用本体动画，但半透明 + 空心描边，且**不创建敌人实体**。
     *
     * <p>素材包对术士的要求是「假影不计清房、不产出奖励、不碰撞」，所以它们只能进视觉层；
     * 玩家要分辨的是本体脚下的实线与实心核心，而不是靠打一下试试能不能掉血。
     */
    private void spawnIllusions(Enemy enemy, EnemySkill skill, double angle) {
        int count = skill.illusions();
        if (count <= 0) return;
        for (int i = 0; i < count; i++) {
            double spread = Math.toRadians(120.0) * (i - (count - 1) / 2.0) + angle;
            double x = enemy.getX() + Math.cos(spread) * GameConfig.ILLUSION_DISTANCE;
            double y = enemy.getY() + Math.sin(spread) * GameConfig.ILLUSION_DISTANCE;
            visualEffects.add(EnemyVisualEffect.ghost(enemy.getKind(), enemy.getWorld(), "idle", enemy.getFacing(),
                    x, y, displayWidth(enemy.getKind()), GameConfig.ILLUSION_LIFETIME));
        }
    }

    // ==================================================================
    //  寻路与脱困
    // ==================================================================

    /**
     * 被墙卡住时的脱困兜底。
     *
     * <p>玩家很爱把首领顶在墙角：它半径大、又刚好站在只有自己能站、却走不出去的死角里，
     * 于是既追不上也打不到，被隔着墙磨死。这里持续跟踪“有没有更接近玩家”，
     * 连续 {@link GameConfig#ENEMY_STUCK_TIME} 秒没有进步就把敌人挪到最近的、能继续推进的合法位置。
     */
    private void maybeEscapeWedge(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                  double dt, double distance, boolean lineOfSight) {
        boolean wantsToClose = !lineOfSight || distance > standoffDistance(enemy.getKind());
        if (!wantsToClose) {
            // 站在开火站位上不靠近是正常行为，不算卡死。
            enemy.resetProgressTracking(distance);
            return;
        }
        // 首领额外看一条：玩家在它自己的寻路场里根本不可达（躲进了它挤不进去的角落）。
        // 这时它只会绕着障碍转圈，光看“没挪动”是抓不到的。
        boolean unreachable = enemy.isBoss()
                && !flowFieldFor(enemy, player, navigation, enemyRadius(enemy))
                .isReachable(enemy.getX(), enemy.getY());
        if (!enemy.trackProgress(distance, dt, unreachable)) return;
        // 首领优先用裂隙闪现：既解决“被顶在墙角”，也解决“玩家躲进它挤不进去的角落”。
        if (enemy.isBoss() && enemy.getBlinkCooldown() <= 0.0 && blinkBoss(enemy, player, navigation)) return;
        escapeWedge(enemy, player, navigation);
    }

    /**
     * 首领的短距裂隙闪现：撕开一道裂隙直接出现在玩家附近的合法位置。
     *
     * <p>只在“追不上或打不到玩家”且冷却结束时使用，落点优先能直接打到玩家，
     * 落地后还有一段起手时间，避免贴脸瞬狙。
     *
     * @return 是否成功闪现
     */
    private boolean blinkBoss(Enemy enemy, Player player, RoomNavigationSystem navigation) {
        double radius = enemyRadius(enemy);
        double[] landing = findBlinkLanding(enemy, player, navigation, radius);
        if (landing == null) return false;
        blinkFlash = new BlinkFlash(enemy.getX(), enemy.getY(), landing[0], landing[1],
                GameConfig.WATCHER_BLINK_FLASH_TIME);
        enemy.setPosition(landing[0], landing[1]);
        enemy.clearAvoidance();
        enemy.resetProgressTracking(Math.hypot(player.getX() - landing[0], player.getY() - landing[1]));
        enemy.setBlinkCooldown(GameConfig.WATCHER_BLINK_COOLDOWN);
        enemy.setAlertRemaining(GameConfig.WATCHER_BLINK_WINDUP);
        return true;
    }

    /**
     * 在玩家周围逐圈搜索闪现落点。
     *
     * <p>优先“落地就能打到玩家”的位置；一圈里没有这样的位置就继续往外找，
     * 同时记住第一个站得下的位置作为兜底（玩家躲在死角里时只能落在附近）。
     */
    private double[] findBlinkLanding(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                      double radius) {
        double[] fallback = null;
        double step = GameConfig.ENEMY_ESCAPE_SEARCH_STEP;
        for (double ring = GameConfig.WATCHER_BLINK_MIN_DISTANCE;
             ring <= GameConfig.WATCHER_BLINK_SEARCH_RADIUS; ring += step) {
            int samples = Math.max(16, (int) (Math.PI * ring / (step / 2)));
            for (int i = 0; i < samples; i++) {
                double angle = Math.PI * 2 * i / samples;
                double x = player.getX() + Math.cos(angle) * ring;
                double y = player.getY() + Math.sin(angle) * ring;
                if (!navigation.canOccupy(x, y, radius, enemy.getWorld())) continue;
                if (Math.hypot(x - enemy.getX(), y - enemy.getY()) > GameConfig.WATCHER_BLINK_RANGE) continue;
                if (navigation.isSegmentClear(x, y, player.getX(), player.getY(),
                        GameConfig.ENEMY_PROJECTILE_RADIUS, enemy.getWorld())) {
                    return new double[]{x, y};
                }
                if (fallback == null) fallback = new double[]{x, y};
            }
        }
        return fallback;
    }

    /** 找一个既放得下身位、又能继续朝玩家推进的位置把敌人挪过去。 */
    private void escapeWedge(Enemy enemy, Player player, RoomNavigationSystem navigation) {
        double radius = enemyRadius(enemy);
        double[] escape = findEscapePosition(enemy, player, navigation, radius);
        if (escape == null) return;
        enemy.setPosition(escape[0], escape[1]);
        enemy.clearAvoidance();
        enemy.resetProgressTracking(Math.hypot(player.getX() - escape[0], player.getY() - escape[1]));
    }

    /**
     * 逐圈向外搜索脱困落点。
     *
     * <p>优先级：①走得过去、且**落地之后真的迈得出下一步**的位置（真正脱困）；②走得过去的位置
     * （至少挪出死角）；③自己已经嵌进墙体里时，允许就近弹出一点点。绝不接受“隔着墙跳过去”——
     * 那样敌人会瞬移到玩家躲着的死胡同里，看起来像穿墙挂。
     *
     * <p>为什么还要验证“迈得出下一步”：距离场是 40 像素的粗网格，格心走得通不等于敌人那个
     * 具体身位走得通。只按“格心可达”挑落点时，敌人会被挪到旁边 8 像素、姿势一模一样地再次卡住，
     * 于是每 2 秒脱困一次、在原地来回抖——玩家看到的就是「这只怪癫痫了」。
     */
    private double[] findEscapePosition(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                        double radius) {
        RoomFlowField field = flowFieldFor(enemy, player, navigation, radius);
        // ① 沿距离场树直接挪到「更靠近玩家一格」的格心：格心必然与玩家连通，
        //    这是唯一能保证挪过去之后真的在推进的做法。
        for (double[] target : field.progressTargets(enemy.getX(), enemy.getY())) {
            if (!navigation.canOccupy(target[0], target[1], radius, enemy.getWorld())) continue;
            return target;
        }
        double[] reachable = null;
        double[] popOut = null;
        double popLimit = radius * 2.0;
        for (double ring = GameConfig.ENEMY_ESCAPE_SEARCH_STEP;
             ring <= GameConfig.ENEMY_ESCAPE_SEARCH_RADIUS;
             ring += GameConfig.ENEMY_ESCAPE_SEARCH_STEP) {
            int samples = Math.max(12, (int) (Math.PI * ring / (GameConfig.ENEMY_ESCAPE_SEARCH_STEP / 2)));
            for (int i = 0; i < samples; i++) {
                double angle = Math.PI * 2 * i / samples;
                double x = enemy.getX() + Math.cos(angle) * ring;
                double y = enemy.getY() + Math.sin(angle) * ring;
                if (!navigation.canOccupy(x, y, radius, enemy.getWorld())) continue;
                if (navigation.isPathClearTo(enemy.getX(), enemy.getY(), x, y, radius, enemy.getWorld())) {
                    if (escapeIsViable(enemy, player, navigation, field, x, y, radius)) return new double[]{x, y};
                    if (reachable == null) reachable = new double[]{x, y};
                } else if (popOut == null && ring <= popLimit) {
                    popOut = new double[]{x, y};
                }
            }
        }
        return reachable != null ? reachable : popOut;
    }

    /**
     * 站在这个落点上时，沿距离场连续走几步行不行、走完之后是不是真的更接近玩家。
     *
     * <p>只验证“迈得出一步”还不够：距离场是 40 像素的粗网格，某块卡住的小口袋里每一步都
     * 迈得出去、却永远不靠近玩家。要求连走 {@value #ESCAPE_PROBE_STEPS} 步并净接近玩家，
     * 才能真正筛掉这种「原地打转的窝」。
     */
    private static final int ESCAPE_PROBE_STEPS = 6;

    private boolean escapeIsViable(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                   RoomFlowField field, double startX, double startY, double radius) {
        double x = startX;
        double y = startY;
        double startDistance = Math.hypot(player.getX() - x, player.getY() - y);
        double step = Math.max(12.0, radius * 0.6);
        for (int i = 0; i < ESCAPE_PROBE_STEPS; i++) {
            double[] direction = field.directionFrom(x, y);
            if (direction == null) return false;
            double nx = x + direction[0] * step;
            double ny = y + direction[1] * step;
            if (!navigation.canOccupy(nx, ny, radius, enemy.getWorld())) return false;
            if (!navigation.isSegmentClear(x, y, nx, ny, radius, enemy.getWorld())) return false;
            x = nx;
            y = ny;
        }
        return Math.hypot(player.getX() - x, player.getY() - y) < startDistance - step;
    }

    /**
     * 索敌判定：描述敌人这一帧是否“发现”玩家。
     *
     * <p>整房索敌开启时（{@link GameConfig#ENEMY_AGGRO_WHOLE_ROOM}），敌人只要与玩家同处一房
     * 就会锁定并主动接近；锁定后不再受距离限制，会一直追到玩家换界或离开房间为止。
     */
    private static boolean acquireTarget(Enemy enemy, double distance) {
        if (enemy.isAware()) return true;
        if (distance > detectionRange()) return false;
        enemy.markAware();
        return true;
    }

    // ==================================================================
    //  玩家伤害结算
    // ==================================================================

    /**
     * 结算玩家这一帧打出的全部伤害。
     *
     * <p>与旧实现的区别：伤害不再由「玩家攻击力」一个数决定，而是每个伤害包带自己的系数
     * （见 {@link Projectile#getDamageCoefficient()}），于是三发散射、贯穿光矛的三段递减、
     * 折镜弹射的三段、爆裂的 1.10 都能各自结算——设计文档明确要求「不先把多段合并再减防御」。
     */
    private void resolvePlayerHits(Player player, PlayerAttackSystem playerAttacks) {
        // 余震指环的强化标记：每第四轮攻击为 true，作用于本轮主攻击的**第一次命中**。
        boolean empowered = isResonanceEmpowered(player, playerAttacks);

        for (Projectile projectile : playerAttacks.getProjectiles()) {
            if (projectile.isExpired()) continue;
            Enemy hit = firstEnemyHitBy(projectile);
            if (hit == null) continue;
            resolveProjectileHit(player, projectile, hit);
            if (empowered) {
                // 震波只认最先成立的一次命中；打出去之后本轮不再触发。
                empowered = false;
                spawnResonanceShockwave(player, hit.getHitboxCenterX(), hit.getHitboxCenterY(), false);
            }
        }

        // 近战：按本轮方案的扇形与距离判定，环月镰是 360°、重剑是 40° 窄劈。
        if (player.getCurrentWorld() == WorldType.SHADOW && playerAttacks.isMeleeVisible()) {
            int attackId = playerAttacks.getMeleeAttackId();
            for (Enemy enemy : enemies) {
                if (enemy.isDead()) continue;
                if (!hasLineOfSight(player.getX(), player.getY(), enemy.getHitboxCenterX(),
                        enemy.getHitboxCenterY(), WorldType.SHADOW)) continue;
                if (enemy.getLastMeleeHitId() == attackId) continue;
                if (!meleeCovers(player, playerAttacks, enemy)) continue;
                int dealt = applyDamage(player, enemy, playerAttacks.getMeleeCoefficient(),
                        player.getX(), player.getY());
                if (dealt > 0) {
                    enemy.setLastMeleeHitId(attackId);
                    if (empowered) {
                        empowered = false;
                        spawnResonanceShockwave(player, player.getX(), player.getY(), true);
                    }
                }
            }
        }
    }

    /** 弹体命中的第一个合法敌人；穿透过的目标会被弹体自己记住，不会再中第二次。 */
    private Enemy firstEnemyHitBy(Projectile projectile) {
        for (Enemy enemy : enemies) {
            if (enemy.isDead()) continue;
            if (projectile.hasHit(enemy)) continue;
            if (CollisionUtil.circleIntersectsCircle(projectile.getX(), projectile.getY(), projectile.getRadius(),
                    enemy.getHitboxCenterX(), enemy.getHitboxCenterY(), enemy.getHitboxRadius())) {
                return enemy;
            }
        }
        return null;
    }

    /**
     * 结算一次弹体命中，并按弹体的行为决定它接下来干什么。
     *
     * <p>五种行为对应设计文档里的五类武器：
     * <ul>
     *   <li>{@code VANILLA}：命中即消失（基础光弹、三叉杖每一发）；</li>
     *   <li>{@code PIERCE}：贯日长矛，按 1.00/0.75/0.50 继续飞；</li>
     *   <li>{@code BOUNCE}：折镜法球，在命中点 180 像素内挑下一个没打过的敌人；</li>
     *   <li>{@code BURST}：炽核权杖，在落点半径 80 炸一次，光核自身不结算接触伤害；</li>
     *   <li>{@code RETURN}：归影双刃，出程返程各结算一次（由 {@link Projectile} 自己管方向）。</li>
     * </ul>
     */
    private void resolveProjectileHit(Player player, Projectile projectile, Enemy hit) {
        if (projectile.getBehaviour() == Projectile.Behaviour.BURST) {
            // 光核没有独立的接触伤害：直接命中的敌人也只吃一次爆炸。
            explodeAt(player, projectile.getX(), projectile.getY(), projectile.getDamageCoefficient(),
                    GameConfig.SOLAR_BURST_RADIUS, projectile.getWorld());
            projectile.expire();
            return;
        }
        int dealt = applyDamage(player, hit, projectile.getDamageCoefficient(),
                projectile.getX(), projectile.getY());
        boolean continues = projectile.registerHit(hit);
        if (dealt <= 0) return;

        if (projectile.getBehaviour() == Projectile.Behaviour.BOUNCE && projectile.canBounce()) {
            Enemy next = nearestUnhitEnemy(projectile, hit);
            if (next == null) {
                projectile.expire();
                return;
            }
            double speed = Math.hypot(projectile.getVelocityX(), projectile.getVelocityY());
            projectile.redirect(projectile.getX(), projectile.getY(),
                    next.getHitboxCenterX() - projectile.getX(),
                    next.getHitboxCenterY() - projectile.getY(),
                    speed, GameConfig.MIRROR_ORB_BOUNCE_RADIUS / Math.max(1.0, speed));
            return;
        }
        if (!continues) projectile.expire();
    }

    /** 弹射目标：命中点附近、没被本弹体打过、且没有墙挡着的最近敌人。 */
    private Enemy nearestUnhitEnemy(Projectile projectile, Enemy justHit) {
        Enemy best = null;
        double bestDistance = GameConfig.MIRROR_ORB_BOUNCE_RADIUS;
        for (Enemy enemy : enemies) {
            if (enemy.isDead() || enemy == justHit) continue;
            if (projectile.hasHit(enemy)) continue;
            double distance = Math.hypot(enemy.getHitboxCenterX() - projectile.getX(),
                    enemy.getHitboxCenterY() - projectile.getY());
            if (distance > bestDistance) continue;
            if (!hasLineOfSight(projectile.getX(), projectile.getY(),
                    enemy.getHitboxCenterX(), enemy.getHitboxCenterY(), projectile.getWorld())) continue;
            // 距离相同时按稳定顺序（enemies 列表的生成顺序）取先遇到的那个。
            if (distance < bestDistance || best == null) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }

    /** 近战扇形判定：目标要在本轮距离内、且落在扇形角度里（360° 就是全天周）。 */
    private static boolean meleeCovers(Player player, PlayerAttackSystem attacks, Enemy enemy) {
        double dx = enemy.getHitboxCenterX() - player.getX();
        double dy = enemy.getHitboxCenterY() - player.getY();
        double distance = Math.hypot(dx, dy);
        if (distance > attacks.getMeleeRange() + enemy.getHitboxRadius()) return false;
        double arc = attacks.getMeleeArcDegrees();
        if (arc >= 360.0) return true;
        if (distance < 0.0001) return true;
        double toEnemy = Math.atan2(dy, dx);
        double half = Math.toRadians(arc / 2.0);
        double delta = Math.atan2(Math.sin(toEnemy - attacks.getMeleeAngleRadians()),
                Math.cos(toEnemy - attacks.getMeleeAngleRadians()));
        return Math.abs(delta) <= half;
    }

    /**
     * 对单个敌人结算一个伤害包。
     *
     * <p>伤害口径来自设计文档 §三：{@code 基础伤害 × 段系数 ×（1 + 同类加成之和)}，
     * 再乘难度倍率，最后走 {@link Enemy#takeHit(int)} 的「先减防御、至少 1 点」规则。
     * 基础伤害与加成都是小数，所以在进入整数结算边界之前不取整。
     *
     * <p>甲虫的前摇护甲与螳爵的架镰减伤在这里生效：它们只挡**正面**打来的伤害，
     * 所以要先知道这一击是从哪个方向来的（弹体当前位置 / 玩家位置 / 爆心）。
     */
    private int applyDamage(Player player, Enemy enemy, double coefficient, double fromX, double fromY) {
        WorldType world = player.getCurrentWorld();
        int stacks = world == WorldType.SHADOW ? enemy.consumeScorch() : 0;
        if (world == WorldType.LIGHT) {
            enemy.addScorch();
        }
        coefficient *= enemy.getKind().affinity().damageMultiplier(world, stacks > 0);
        coefficient += stacks * GameConfig.SCORCH_DAMAGE_PER_STACK;
        if (stacks > 0) player.restorePhaseEnergy(stacks * GameConfig.SCORCH_ENERGY_PER_STACK);
        double raw = player.getCurrentBaseDamage()
                * coefficient * player.damageMultiplier(player.getCurrentWorld())
                * hunterBonus(player, enemy);
        raw *= enemy.incomingDamageMultiplier(fromX, fromY);
        int dealt = enemy.takeHit(scaledPlayerDamage(raw));
        if (dealt > 0) recordEnemyHit(enemy, dealt);
        return dealt;
    }

    /**
     * 猎影牙饰：目标生命比例**高于** 70% 时，影界伤害 +25%。
     *
     * <p>每个伤害包在结算前各自检查目标当前生命比例，所以第一段把敌人打到阈值以下之后，
     * 第二段就不再享受加成（设计文档 §五-12）。
     */
    private static double hunterBonus(Player player, Enemy enemy) {
        if (player.getCurrentWorld() != WorldType.SHADOW) return 1.0;
        if (player.equipmentCount(com.phantomcorridor.model.EquipmentType.HUNTERS_FANG) == 0) return 1.0;
        if (enemy.getMaxHp() <= 0) return 1.0;
        double ratio = enemy.getHp() / (double) enemy.getMaxHp();
        if (ratio <= GameConfig.HUNTERS_FANG_HP_THRESHOLD) return 1.0;
        return 1.0 + GameConfig.HUNTERS_FANG_BONUS
                * player.equipmentCount(com.phantomcorridor.model.EquipmentType.HUNTERS_FANG);
    }

    /** 本轮攻击是否吃余震指环的强化标记（每第 4 轮）。 */
    private static boolean isResonanceEmpowered(Player player, PlayerAttackSystem attacks) {
        if (player.equipmentCount(com.phantomcorridor.model.EquipmentType.RESONANCE_RING) == 0) return false;
        return attacks.getWorldAttackCount() % GameConfig.RESONANCE_RING_INTERVAL == 0;
    }

    /**
     * 余震指环的震波：光界在命中点炸半径 60 的金色震波，影界在出手位置炸 0.75 倍影斩距离的紫波。
     *
     * <p>震波不计新的攻击轮次、也不会再触发震波，所以这里直接结算范围伤害。
     */
    private void spawnResonanceShockwave(Player player, double x, double y, boolean shadow) {
        double coefficient = shadow ? GameConfig.RESONANCE_RING_SHADOW_COEFFICIENT
                : GameConfig.RESONANCE_RING_LIGHT_COEFFICIENT;
        double radius = shadow
                ? GameConfig.SHADOW_MELEE_RANGE * GameConfig.RESONANCE_RING_SHADOW_RANGE_SCALE
                : GameConfig.RESONANCE_RING_LIGHT_RADIUS;
        WorldType world = player.getCurrentWorld();
        explodeAt(player, x, y, coefficient, radius, world);
    }

    /**
     * 范围伤害：对半径内且从爆心看得见的敌人各结算一次。
     *
     * <p>视线检查是设计文档的硬要求——爆炸不能隔着墙输出（「以爆心做视线判定」）。
     */
    private void explodeAt(Player player, double x, double y, double coefficient,
                           double radius, WorldType world) {
        for (Enemy enemy : enemies) {
            if (enemy.isDead()) continue;
            double distance = Math.hypot(enemy.getHitboxCenterX() - x, enemy.getHitboxCenterY() - y);
            if (distance > radius + enemy.getHitboxRadius()) continue;
            if (!hasLineOfSight(x, y, enemy.getHitboxCenterX(), enemy.getHitboxCenterY(), world)) continue;
            applyDamage(player, enemy, coefficient, x, y);
        }
    }

    /** 两点之间在该世界里是否没有墙挡着（范围伤害与弹射都用它）。 */
    private boolean hasLineOfSight(double fromX, double fromY, double toX, double toY, WorldType world) {
        return navigation == null || navigation.isSegmentClear(fromX, fromY, toX, toY, 1.0, world);
    }

    /**
     * 玩家一击打出的伤害（含难度倍率）。
     *
     * <p>参数是 {@code double}：武器系数（0.45、0.55、1.60……）本身就是小数，
     * 在这里先取整会把三叉杖每发的 0.45 抹成 0，再被「至少 1 点」兜底成 1——
     * 等于每发都按满伤害结算。所以只在真正落到敌人身上那一刻才整数化。
     *
     * <p>低难度下敌人生命只有一半，玩家伤害同步减半，双方比值不变；
     * 「简单」依然更简单，靠的是敌人总量与玩家容错，而不是把玩家也一起削。
     */
    private int scaledPlayerDamage(double baseDamage) {
        return Math.max(1, (int) Math.round(baseDamage * difficulty.playerDamageMultiplier()));
    }

    /** 取当前房间的导航系统（范围伤害与弹射的视线判定要用它）。 */
    public void setNavigation(RoomNavigationSystem navigation) {
        this.navigation = navigation;
    }

    /**
     * 记下玩家打出的这一击，供渲染层在敌人头顶飘出伤害数字。
     *
     * <p>画在敌人**头顶**而不是命中点：弹体命中后会立刻失效，
     * 挂在弹体位置上的数字会跟着一起消失。
     */
    private void recordEnemyHit(Enemy enemy, int dealt) {
        if (dealt <= 0) return;
        double height = enemy.getKind().overheadHeight();
        damageFlashes.add(new DamageFlash(enemy.getX(), enemy.getY() - height - 16.0, dealt,
                DamageType.PHYSICAL, false, true, DAMAGE_FLASH_TIME));
    }

    private void moveTowardPlayer(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                  double dt, boolean lineOfSight) {
        double dx = player.getX() - enemy.getX();
        double dy = player.getY() - enemy.getY();
        double distance = Math.hypot(dx, dy);
        // 看得见玩家时按物种保持开火站位；视线被遮挡时不再保持距离，一路贴近到重新获得视线。
        double desiredDistance = lineOfSight ? standoffDistance(enemy.getKind()) : 0.0;
        if (distance <= desiredDistance || distance < 0.001) {
            enemy.playAnimation("idle", 1.0 / 6.0, true);
            return;
        }
        double step = GameConfig.PLAYER_BASE_SPEED * enemy.getKind().speedMultiplier() * dt;
        double radius = enemyRadius(enemy);
        // 只有整段直线都走得通才走直线；被墙挡住时一律改走房间距离场，
        // 否则“这一帧直线能挪一点、下一帧被挡回原位”会精确抵消，敌人贴着墙永远走不出去。
        if (navigation.isPathClearTo(enemy.getX(), enemy.getY(), player.getX(), player.getY(),
                radius, enemy.getWorld())) {
            double nx = enemy.getX() + dx / distance * step;
            double ny = enemy.getY() + dy / distance * step;
            if (navigation.canOccupy(nx, ny, radius, enemy.getWorld())) {
                enemy.setPosition(nx, ny);
                enemy.setFacingFromVector(dx, dy);
                enemy.playAnimation("move", 0.42, true);
                enemy.setAvoidanceHeading(Math.atan2(dy, dx));
                enemy.clearAvoidance();
                return;
            }
        }
        if (stepAlongFlowField(enemy, player, navigation, step, radius)) return;
        slideAlongObstacle(enemy, navigation, dt, dx, dy, distance);
    }

    /**
     * 取该敌人对应的房间距离场，必要时重建。
     *
     * <p>按世界 + 身位半径缓存：首领（半径 46）过得去的缝和普通怪（27）不一样，
     * 用同一个半径建场会把大体型敌人引到它挤不过去的窄缝里，然后卡死在缝口。
     * 距离场只在玩家换格、换房间或换界时重建，每个身位档最多一张。
     */
    private RoomFlowField flowFieldFor(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                       double radius) {
        Room room = navigation.getCurrentRoom();
        int radiusKey = (int) Math.round(radius);
        Map<Integer, RoomFlowField> byRadius =
                flowFields.computeIfAbsent(enemy.getWorld(), world -> new HashMap<>());
        RoomFlowField field = byRadius.get(radiusKey);
        if (field == null || field.roomId() != room.id()) {
            field = new RoomFlowField(room);
            byRadius.put(radiusKey, field);
        }
        if (!field.isTargeting(player.getX(), player.getY())) {
            field.rebuild(navigation, player.getX(), player.getY(), enemy.getWorld(), radius);
        }
        return field;
    }

    /** 沿房间距离场朝玩家推进一格方向；距离场给不出方向（或该方向仍被挡）时返回 false。 */
    private boolean stepAlongFlowField(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                       double step, double radius) {
        RoomFlowField field = flowFieldFor(enemy, player, navigation, radius);
        // 先按前瞻方向走；被挡时退回只看相邻格的方向，再不行才交给贴墙绕行。
        if (stepAlong(enemy, navigation, field.directionFrom(enemy.getX(), enemy.getY()), step, radius)) return true;
        return stepAlong(enemy, navigation, field.neighbourDirectionFrom(enemy.getX(), enemy.getY()), step, radius);
    }

    private boolean stepAlong(Enemy enemy, RoomNavigationSystem navigation, double[] direction,
                              double step, double radius) {
        if (direction == null) return false;
        double nx = enemy.getX() + direction[0] * step;
        double ny = enemy.getY() + direction[1] * step;
        if (!navigation.canOccupy(nx, ny, radius, enemy.getWorld())) return false;
        if (!navigation.isSegmentClear(enemy.getX(), enemy.getY(), nx, ny, radius, enemy.getWorld())) return false;
        enemy.setPosition(nx, ny);
        enemy.setFacingFromVector(direction[0], direction[1]);
        enemy.playAnimation("move", 0.42, true);
        enemy.setAvoidanceHeading(Math.atan2(direction[1], direction[0]));
        enemy.clearAvoidance();
        return true;
    }

    /**
     * 贴住障碍时的局部绕行。
     *
     * <p>绕行只沿选定的那一侧进行：先试与追击方向垂直的切线，再朝同一侧逐步加大旋转角度。
     * 这里有两件事必须记住，否则敌人会永久卡死：
     * <ul>
     *   <li>绕行方向不能每帧重挑，否则会在两个相邻位置之间来回横跳；</li>
     *   <li>不允许掉头，否则「直线朝玩家走一步 + 绕行退回原处」会精确抵消，位置纹丝不动。</li>
     * </ul>
     * 选定的一侧彻底走不动超过 {@link GameConfig#ENEMY_AVOIDANCE_FLIP_TIME} 秒时才改走另一侧。
     */
    private boolean slideAlongObstacle(Enemy enemy, RoomNavigationSystem navigation, double dt,
                                       double dx, double dy, double distance) {
        double radius = enemyRadius(enemy);
        double step = GameConfig.PLAYER_BASE_SPEED * enemy.getKind().speedMultiplier() * dt;
        double tangentAngle = Math.atan2(dx / distance, -dy / distance);
        int side = enemy.getAvoidanceSide();
        if (side == 0) {
            side = chooseAvoidanceSide(enemy, navigation, radius, tangentAngle);
            enemy.setAvoidanceSide(side);
        }
        double heading = enemy.getAvoidanceHeading();
        // 彻底走不动超过一秒时解除“不许掉头”的限制：那只在正常绕行时才有必要
        // （防止「朝玩家一步 + 绕行退回来」互相抵消），而一个人都挪不动的敌人只有退出去才有活路。
        boolean fullyBlocked = enemy.getAvoidanceStuckTime() >= GameConfig.ENEMY_AVOIDANCE_FLIP_TIME;
        for (double offsetDegrees : SLIDE_OFFSETS_DEGREES[side > 0 ? 0 : 1]) {
            double angle = tangentAngle + Math.toRadians(offsetDegrees);
            if (!fullyBlocked && !Double.isNaN(heading)
                    && Math.cos(angle - heading) < GameConfig.ENEMY_AVOIDANCE_MIN_TURN_COSINE) continue;
            double sx = enemy.getX() + Math.cos(angle) * step;
            double sy = enemy.getY() + Math.sin(angle) * step;
            if (!navigation.canOccupy(sx, sy, radius, enemy.getWorld())) continue;
            if (!navigation.isSegmentClear(enemy.getX(), enemy.getY(), sx, sy, radius, enemy.getWorld())) continue;
            enemy.setPosition(sx, sy);
            enemy.setFacingFromVector(Math.cos(angle), Math.sin(angle));
            enemy.playAnimation("move", 0.42, true);
            enemy.setAvoidanceHeading(angle);
            enemy.resetAvoidanceStuckTime();
            return true;
        }
        enemy.addAvoidanceStuckTime(dt);
        if (enemy.getAvoidanceStuckTime() >= GameConfig.ENEMY_AVOIDANCE_FLIP_TIME) {
            enemy.setAvoidanceSide(-side);
        }
        return false;
    }

    /**
     * 选择绕行方向：比较两侧「绕过障碍」方向上前方能走多远，取更开阔的一侧；一样开阔时固定取 +1。
     *
     * <p>只看切线本身分不出两侧——两侧的切线是同一条，区别在于接下来往哪边转。
     */
    private static int chooseAvoidanceSide(Enemy enemy, RoomNavigationSystem navigation,
                                           double radius, double tangentAngle) {
        int bestSide = 1;
        double bestClear = -1.0;
        for (int side : new int[]{1, -1}) {
            double angle = tangentAngle + side * Math.PI / 2.0;
            double clear = 0.0;
            for (double travelled = GameConfig.ENEMY_AVOIDANCE_PROBE_STEP;
                 travelled <= GameConfig.ENEMY_AVOIDANCE_PROBE_DISTANCE;
                 travelled += GameConfig.ENEMY_AVOIDANCE_PROBE_STEP) {
                double px = enemy.getX() + Math.cos(angle) * travelled;
                double py = enemy.getY() + Math.sin(angle) * travelled;
                if (!navigation.canOccupy(px, py, radius, enemy.getWorld())) break;
                clear = travelled;
            }
            if (clear > bestClear) {
                bestClear = clear;
                bestSide = side;
            }
        }
        return bestSide;
    }

    // ==================================================================
    //  出招状态机
    // ==================================================================

    /**
     * 从随机出招表里挑一个当前距离可用、且不在冷却思路之外的技能。
     *
     * <p>召唤不算在这个表里：它由 {@link #beginSummonIfDue} 按“该不该喊人”单独判断，
     * 混进随机轮换会让首领在玩家满血、场面干净的时候白白浪费一次召唤。
     */
    private EnemySkill chooseSkill(Enemy enemy, double distance) {
        List<EnemySkill> choices = EnemySkill.forEnemy(enemy.getKind(), enemy.getWorld()).stream()
                .filter(skill -> skill.pattern() != EnemySkill.Pattern.SUMMON)
                .filter(skill -> distance <= skill.range()).toList();
        if (choices.isEmpty()) return null;
        return choices.get(Math.floorMod(enemy.nextSkillIndex(), choices.size()));
    }

    private void beginCast(Enemy enemy, Player player, EnemySkill skill) {
        if (skill == null) return;
        startCast(enemy, player, skill);
    }

    /** 攻击严格按“前摇 → 出招（只触发一次释放）→ 收招”播放，逻辑事件不依赖渲染帧。 */
    private void startCast(Enemy enemy, Player player, EnemySkill skill) {
        double dx = player.getX() - enemy.getX(), dy = player.getY() - enemy.getY();
        if (enemy.isBoss()) {
            if (skill.pattern() == EnemySkill.Pattern.SUMMON) enemy.resetNormalCastsSinceSummon();
            else enemy.recordNormalCast();
        }
        enemy.setFacingFromVector(dx, dy);
        enemy.playAnimation(skill.actionBase() + "_windup", skill.windup(), false);
        ActiveCast cast = new ActiveCast(skill, Math.atan2(dy, dx), player.getX(), player.getY());
        activeCasts.put(enemy, cast);
        // 正面减伤（甲虫抬角、螳爵架镰）只在前摇期间有效：收招时它自己会取消。
        if (skill.guardsDuringWindup()) {
            enemy.beginCastGuard(skill.guardReduction(), skill.guardAngleDegrees(), cast.angle);
        }
        // 前摇特效：v1 物种各有专门的蓄力动作，v2 物种用技能自己的独立特效顶替。
        String charge = switch (enemy.getKind()) {
            case LANTERN, MAGE, BELL, EXECUTIONER -> "charge";
            case WATCHER -> "cast_charge";
            default -> "";
        };
        if (!charge.isEmpty()) {
            visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), charge,
                    enemy.getX(), enemy.getY() - 24, 0.0, enemy.isBoss() ? 176 : 100, skill.windup()));
        } else if (enemy.getKind().expansion()) {
            visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(),
                    enemy.getX(), enemy.getY(), cast.angle, Math.max(72, skill.radius() * 1.6), skill.windup()));
        }
        // 幻象与本体一起抬手：假影不计清房、不产出奖励、不参与碰撞。
        spawnIllusions(enemy, skill, cast.angle);
        createCastTelegraphs(enemy, skill, cast);
    }

    /**
     * 首领的召唤判定：三种“适时”条件满足任意一条就起手召唤。
     *
     * <ol>
     *   <li><b>血量阶段</b>：血量第一次跌破召唤档案里的比例，立刻召唤一次，且无视冷却；</li>
     *   <li><b>逼出掩体</b>：连续 {@link SummonProfile#pressureTime()} 秒打不到玩家，
     *       就把小怪放出去——它们身位小、跑得快，能钻首领自己进不去的缝；</li>
     *   <li><b>补位</b>：场上一个召唤物都不剩时再来一波，冷却决定节奏。</li>
     * </ol>
     *
     * <p>后两条受冷却与 {@link SummonProfile#maxAlive()} 限制；阶段召唤只受存活上限限制，
     * 且被上限挡住时不推进阶段，等位置空出来照样补上。
     *
     * @return 本帧是否已经起手召唤（起手后不再走普通攻击）
     */
    private boolean beginSummonIfDue(Enemy boss, Player player, double distance, boolean lineOfSight) {
        if (!boss.isBoss() || activeCasts.containsKey(boss)) return false;
        SummonProfile profile = SummonProfile.of(boss.getKind());
        if (profile == null) return false;
        int load = summonLoad();
        if (load >= profile.aliveCap(floor)) return false;
        boolean stageDue = profile.hasStage(boss.getSummonStage())
                && boss.getHp() <= boss.getMaxHp() * profile.healthTrigger(boss.getSummonStage());
        boolean pressureDue = boss.getSummonPressure() >= profile.pressureTime();
        // 160px 已进入首领模型边缘与玩家近战的交错距离；在这个距离内强制本体攻击。
        // 仍略小于守望者 190px 的远程站位，保证它在中距离能正常使用新的召唤机制。
        boolean playerIsClose = lineOfSight && distance <= profile.deferDistance();
        // 玩家已经贴近首领时，补位增援不应抢走本体攻击；血量阶段/地形困住仍可触发一次，
        // 这样新机制存在感足够，又不会造成“Boss 只会叫小怪”的体验。
        if (playerIsClose && !stageDue && !pressureDue) return false;
        // 补位不会立刻抢走原本的普攻轮换。首领在每波增援前至少会完整施放两次本体技能，
        // 所以旧有弹幕/审判/长矛（影界的连斩/冲刺）始终可见；血量阶段与被地形困住时
        // 仍可无视该限制召唤，保留新机制的压迫感。
        boolean reinforcementDue = !playerIsClose && load == 0 && boss.getNormalCastsSinceSummon() >= 2;
        if (!stageDue && !pressureDue && !reinforcementDue) return false;
        if (!stageDue && boss.getSummonCooldown() > 0.0) return false;
        EnemySkill summon = EnemySkill.forEnemy(boss.getKind(), boss.getWorld()).stream()
                .filter(skill -> skill.pattern() == EnemySkill.Pattern.SUMMON).findFirst().orElse(null);
        if (summon == null) return false;
        if (stageDue) boss.advanceSummonStage();
        boss.setSummonCooldown(profile.cooldown());
        boss.resetSummonPressure();
        startCast(boss, player, summon);
        summonCallsSinceLastRead++;
        return true;
    }

    /** 场上已经占掉的召唤名额：活着的召唤物 + 还没成型的裂隙。 */
    private int summonLoad() {
        return summonRifts.size() + (int) enemies.stream().filter(Enemy::isSummoned).count();
    }

    /**
     * 沿首领周围几圈找裂隙落点。
     *
     * <p>优先开在“首领与玩家之间”，像在包抄：这条规律玩家看得懂、也能提前让开，
     * 比随机撒点更像“这招有章法”。落点必须放得下召唤物身位、离玩家有安全距离，
     * 两个裂隙之间也要留出间距，免得两只怪叠在同一个点上。
     */
    private List<double[]> findSummonSpots(Enemy boss, Player player, RoomNavigationSystem navigation,
                                           SummonProfile profile, int count) {
        List<double[]> spots = new ArrayList<>();
        double toPlayer = Math.atan2(player.getY() - boss.getY(), player.getX() - boss.getX());
        double minRing = profile.riftMinDistance();
        double maxRing = Math.max(minRing, profile.riftMaxDistance());
        double ringStep = Math.max(24.0, (maxRing - minRing) / 3.0);
        for (double ring = minRing; ring <= maxRing + 1e-6 && spots.size() < count; ring += ringStep) {
            List<Double> angles = new ArrayList<>();
            int samples = 24;
            for (int i = 0; i < samples; i++) angles.add(toPlayer + Math.PI * 2 * i / samples);
            angles.sort(Comparator.comparingDouble(angle -> angleDifference(angle, toPlayer)));
            for (double angle : angles) {
                if (spots.size() >= count) break;
                double x = boss.getX() + Math.cos(angle) * ring;
                double y = boss.getY() + Math.sin(angle) * ring;
                if (Math.hypot(x - player.getX(), y - player.getY()) < profile.playerClearance()) continue;
                double radius = profile.minion(boss.getWorld(), spots.size(), floor).footRadius();
                if (!navigation.canOccupy(x, y, radius, boss.getWorld())) continue;
                if (spots.stream().anyMatch(spot -> Math.hypot(spot[0] - x, spot[1] - y) < profile.riftSpacing())) {
                    continue;
                }
                spots.add(new double[]{x, y});
            }
        }
        return spots;
    }

    /** 两个角度之间的最小夹角（弧度，恒为非负）。 */
    private static double angleDifference(double a, double b) {
        return EnemyTelegraph.angleDelta(a, b);
    }

    /**
     * 裂隙成型：在裂隙位置放出召唤物，并留下一圈迸发特效。
     *
     * <p>召唤物的世界、层数与难度都继承首领，只有生命值按召唤档案打折；
     * 成型后还有一段起手时间，刚钻出来不会立刻贴脸开火。
     */
    private void updateSummonRifts(double dt, RoomNavigationSystem navigation) {
        if (summonRifts.isEmpty()) return;
        for (var iterator = summonRifts.iterator(); iterator.hasNext();) {
            SummonRift rift = iterator.next();
            rift.update(dt);
            if (!rift.isReady()) continue;
            iterator.remove();
            Enemy minion = Enemy.summoned(rift.kind(), rift.world(), rift.x(), rift.y(), floor, difficulty,
                    rift.hitPointScale());
            double radius = radiusOf(minion.getKind());
            double[] landing = navigation.findNearestSafePosition(rift.x(), rift.y(), rift.world(), radius);
            if (landing == null) continue;   // 周围实在站不下就不放，绝不把召唤物塞进墙里
            minion.setPosition(landing[0], landing[1]);
            minion.setAlertRemaining(rift.alertSeconds());
            enemies.add(minion);
            visualEffects.add(new EnemyVisualEffect(rift.summoner(), rift.world(), "summon_portal",
                    landing[0], landing[1], 0.0, 190, .40));
        }
    }

    /**
     * 首领的造物一同溃散：首领倒下、或它离开某一界时都会调用。
     *
     * <p>未成型的裂隙必须一起清掉——否则首领已经死了，裂隙还会吐出小怪，房间永远清不空。
     * 溃散不计入击杀，玩家不会因为首领倒下白拿金币与相位能量。
     */
    private void collapseSummons() {
        for (var iterator = enemies.iterator(); iterator.hasNext();) {
            Enemy enemy = iterator.next();
            if (!enemy.isSummoned()) continue;
            // 溃散用首领自己的召唤门特效收尾：素材包里只有召唤型首领带 summon_portal，
            // 而小怪没有——所以这里必须借用召唤者的素材，而不是被召唤者自己的。
            visualEffects.add(new EnemyVisualEffect(summonerKindOf(enemy), enemy.getWorld(), "summon_portal",
                    enemy.getX(), enemy.getY(), 0.0, 150, .40));
            activeCasts.remove(enemy);
            iterator.remove();
        }
        for (SummonRift rift : summonRifts) {
            visualEffects.add(new EnemyVisualEffect(rift.summoner(), rift.world(), "summon_portal",
                    rift.x(), rift.y(), 0.0, 150, .40));
        }
        summonRifts.clear();
    }

    /** 召唤物的主人：首领房里唯一的那只首领。 */
    private EnemyKind summonerKindOf(Enemy minion) {
        for (Enemy enemy : enemies) {
            if (enemy.isBoss()) return enemy.getKind();
        }
        return EnemyKind.WATCHER;
    }

    /**
     * 首领半血换形的视觉。
     *
     * <p>旧包的守望者专门画了 {@code phase_transition} 帧；v2 的五个首领没有这一组素材，
     * 用它们自己的 {@code transform} 本体动作演一次——素材包里每个首领都画了这个动作，
     * 效果同样是「相位收拢、换一身形态」。
     */
    private static EnemyVisualEffect crossoverEffect(Enemy enemy) {
        if (enemy.getKind() == EnemyKind.WATCHER) {
            return new EnemyVisualEffect(enemy.getKind(), WorldType.LIGHT, "phase_transition",
                    enemy.getX(), enemy.getY(), 0.0, 210, .45);
        }
        return EnemyVisualEffect.body(enemy.getKind(), WorldType.LIGHT, "transform", enemy.getFacing(),
                enemy.getX(), enemy.getY(), displayWidth(enemy.getKind()), GameConfig.BOSS_TRANSFORM_LOCK);
    }

    private boolean advanceCast(Enemy enemy, Player player, RoomNavigationSystem navigation) {
        ActiveCast cast = activeCasts.get(enemy);
        if (cast == null) return false;
        if (cast.stage == 0 && enemy.isAnimationFinished()) {
            cast.stage = 1;
            enemy.playAnimation(cast.skill.actionBase() + "_release", cast.skill.active(), false);
            releaseSkill(enemy, cast, player, navigation);
            return true;
        }
        if (cast.stage == 1 && enemy.isAnimationFinished()) {
            cast.stage = 2;
            enemy.endCastGuard();
            enemy.playAnimation(cast.skill.actionBase() + "_recovery", cast.skill.recovery(), false);
            return true;
        }
        if (cast.stage == 2 && enemy.isAnimationFinished()) {
            enemy.setAttackCooldown(cast.skill.cooldown());
            // 精英在完整攻击后短暂进入 guard，既能读到防御动作，也不会在寻路阶段长期堵死自己。
            if (!enemy.isBoss() && enemy.getKind().elite()) enemy.beginGuard(.32);
            else enemy.playAnimation("idle", 1.0 / 6.0, true);
            activeCasts.remove(enemy);
            return enemy.isGuarding();
        }
        return true;
    }

    private void releaseSkill(Enemy enemy, ActiveCast cast, Player player, RoomNavigationSystem navigation) {
        EnemySkill skill = cast.skill;
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(), enemy.getX(), enemy.getY(),
                cast.angle, Math.max(72, skill.radius() * 1.65), .42));
        switch (skill.pattern()) {
            case PROJECTILE -> volley(enemy, skill, cast);
            case SPREAD -> spread(enemy, skill, cast.angle, 3, 15);
            case FAN -> spread(enemy, skill, cast.angle, 5, 17.5);
            case RING -> radial(enemy, skill, 9, cast.angle + Math.PI / 4.0);
            case DOUBLE_RING -> radial(enemy, skill, 16, cast.angle);
            case ARC -> area(enemy, skill, enemy.getX() + Math.cos(cast.angle) * skill.radius() * .48,
                    enemy.getY() + Math.sin(cast.angle) * skill.radius() * .48, cast.angle, .12, 0.0);
            case DOUBLE_ARC -> {
                area(enemy, skill, enemy.getX() + Math.cos(cast.angle) * skill.radius() * .48,
                        enemy.getY() + Math.sin(cast.angle) * skill.radius() * .48, cast.angle, .12, 0.0);
                area(enemy, skill, enemy.getX() + Math.cos(cast.angle) * skill.radius() * .55,
                        enemy.getY() + Math.sin(cast.angle) * skill.radius() * .55, cast.angle, .12, .45);
            }
            case CRACK -> {
                for (int i = 1; i <= 3; i++) area(enemy, skill,
                        enemy.getX() + Math.cos(cast.angle) * 65 * i,
                        enemy.getY() + Math.sin(cast.angle) * 65 * i, cast.angle, .15, .18 * (i - 1));
            }
            case MARK -> area(enemy, skill, cast.targetX, cast.targetY, cast.angle, .16, .36);
            case TRIPLE_MARK -> {
                for (int i = -1; i <= 1; i++) area(enemy, skill,
                        cast.targetX + Math.cos(cast.angle + Math.PI / 2.0) * i * 74,
                        cast.targetY + Math.sin(cast.angle + Math.PI / 2.0) * i * 74,
                        cast.angle, .16, .30 + .22 * (i + 1));
            }
            case RING_AREA -> radial(enemy, skill, 9, cast.angle);
            case DASH, DASH_NO_DAMAGE -> dash(enemy, skill, cast.angle, navigation);
            case SUMMON -> summonMinions(enemy, player, navigation);
            case BARRAGE -> barrage(enemy, skill, cast);
            // 下面五种在起手时就已经把判定铺在地上（见 createCastTelegraphs），释放阶段只补位移。
            case CHARGE -> chargeForward(enemy, skill, cast, navigation);
            case BURROW -> burrowStrike(enemy, skill, cast, navigation);
            case TELEPORT -> teleportBoss(enemy, skill, cast, navigation, player);
            case WALL -> growWalls(enemy, skill, cast, navigation, player);
            case SECTOR, SECTOR_SPLIT, LINE, RING_GAP, BAND, MULTI_MARK -> { }
        }
    }

    /**
     * 多轮弹幕：一次施法按时间连发 {@code volleys} 轮，每轮整体转过 {@code angleStep}。
     *
     * <p>这是「全图弹幕」的核心读法：单轮 {@code count} 发按 {@code angleDegrees} 铺开
     * （360° 就是一圈），第二轮起把整圈转一个固定角度，于是第一轮留下的缝隙会在第二轮被填上，
     * 玩家必须边走边找新缝，而不是记住一个安全点站桩。
     */
    private void barrage(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        int volleys = Math.max(1, skill.volleys());
        int perVolley = Math.max(1, skill.count());
        double interval = Math.max(0.12, skill.volleyInterval());
        double rotation = Math.toRadians(skill.angleStep());
        long id = pelletHitId(skill, cast);
        for (int volley = 0; volley < volleys; volley++) {
            for (int i = 0; i < perVolley; i++) {
                fireOne(enemy, skill, cast.angle + rotation * volley + fanOffset(skill, i, perVolley),
                        volley * interval, id, skill.range());
            }
        }
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(),
                enemy.getX(), enemy.getY(), cast.angle, 220, Math.max(.4, interval * volleys)));
    }

    /**
     * 一枚弹体相对锁定方向的偏角。
     *
     * <p>环形弹幕（{@link EnemySkill#isRing()}）把 {@code count} 发均匀铺满整圈，
     * 第 0 发正对锁定方向；普通扇面则按「相邻夹角」向两侧展开。
     * 这两种读法必须分开算：把环形当成扇面会让相邻两发隔了整整一圈，
     * 结果整轮弹幕全部叠在同一个方向（而且是背着玩家的那一边）飞出去。
     */
    private static double fanOffset(EnemySkill skill, int index, int count) {
        if (count <= 1) return 0.0;
        if (skill.isRing()) return Math.PI * 2 * index / count;
        return Math.toRadians(skill.angleDegrees()) * (index - (count - 1) / 2.0);
    }

    /**
     * 弹体齐射：单发、扇形齐射、保留缺口的环射、以及延迟补发的第二发都走这里。
     *
     * <p>{@code hitOffsets} 不为空代表「同一次施法里按时间补发」（逆砂回响的第二枚）；
     * {@code projectileCount > 1} 代表同时射出多发（折潮三炮、叉影丝梭、冠落晶籽）。
     */
    private void volley(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        double[] offsets = skill.hitOffsets();
        if (offsets.length > 1) {
            for (double offset : offsets) {
                long id = skill.hitPolicy() == EnemySkill.HitPolicy.ONCE_PER_CAST ? cast.hitId : HitIds.next();
                fireOne(enemy, skill, cast.angle, offset, id, lobTravel(enemy, skill, cast));
            }
            return;
        }
        if (skill.count() > 1) {
            if (skill.safeGapDegrees() > 0.0) {
                // 环射保留缺口：让玩家始终有一条能站住的生路，而不是被整圈封死。
                int count = skill.count();
                double gap = Math.toRadians(Math.min(300.0, skill.safeGapDegrees()));
                double start = cast.angle + gap / 2.0;
                double step = (Math.PI * 2 - gap) / count;
                for (int i = 0; i < count; i++) {
                    fireOne(enemy, skill, start + step * i, 0.0, pelletHitId(skill, cast), skill.range());
                }
                return;
            }
            int count = skill.count();
            for (int i = 0; i < count; i++) {
                double angle = cast.angle + fanOffset(skill, i, count);
                fireOne(enemy, skill, angle, 0.0, pelletHitId(skill, cast), skill.range());
            }
            return;
        }
        fireOne(enemy, skill, cast.angle, 0.0, pelletHitId(skill, cast), lobTravel(enemy, skill, cast));
    }

    /**
     * 抛掷型弹体（孢子）的落点距离。
     *
     * <p>孢子不是「打出去的子弹」而是「扔出去的东西」：它必须在**瞄准的那个点**落地，
     * 否则从 200 像素外吐一口孢子会一路飞过玩家头顶、在射程尽头开花，预警圈画在谁也够不到的地方。
     * 因此落点取「锁定目标距离」与「最大射程」中的较小者；玩家挪开就能躲，这正是它的玩法。
     */
    private double lobTravel(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        if (skill.delayedBurstSeconds() <= 0.0 && skill.pulses().length == 0) return skill.range();
        double aimDistance = Math.hypot(cast.targetX - enemy.getX(), cast.targetY - enemy.getY());
        return Math.max(40.0, Math.min(skill.range(), aimDistance));
    }

    /** 同一轮里的每一发弹体共用什么命中标识：整次施法一发，还是各发各的。 */
    private long pelletHitId(EnemySkill skill, ActiveCast cast) {
        return skill.hitPolicy() == EnemySkill.HitPolicy.ONCE_PER_CAST ? cast.hitId : HitIds.next();
    }

    /**
     * 发射一枚弹体。寿命由射程反推，保证最远处开火也能飞到玩家身上；
     * 抛掷型弹体则正好在 {@code travel} 处落地。
     *
     * <p>炮弹与孢子这类「炸开型」弹体，技能上的 {@code radius} 指的是**爆炸范围**，
     * 不是飞行阶段的身位：直接拿 65 当碰撞半径的话，炮弹离墙 65 像素就会自爆，
     * 在带障碍物的房间里几乎打不出射程。所以飞行阶段用收窄后的半径，
     * 落地 / 撞墙时再按技能半径炸开。
     */
    private void fireOne(Enemy enemy, EnemySkill skill, double angle, double delay, long hitId, double travel) {
        double speed = Math.max(1.0, skill.speed());
        boolean lobbed = skill.delayedBurstSeconds() > 0.0 || skill.pulses().length > 0;
        double lifetime = lobbed ? travel / speed + 0.05 : Math.max(0.6, travel / speed * 1.15);
        // 反弹弹体要活得更久：每多一次反弹，就多给一段飞行时间。
        if (skill.bouncing()) {
            lifetime += skill.bounces() * GameConfig.ENEMY_BOUNCE_LIFETIME_BONUS;
        }
        double damage = skill.contactDamage() ? scaledDamage(skill) : 0.0;
        double radius = (lobbed || skill.explodeOnImpact()) ? Math.min(skill.radius(), 26.0) : skill.radius();
        attacks.add(new EnemyAttack(enemy.getX(), enemy.getY(),
                Math.cos(angle) * speed, Math.sin(angle) * speed, radius,
                enemy.getWorld(), enemy.getKind(), lifetime, skill.effect(), angle, delay, damage,
                skill, hitId, skill.bounces()));
    }

    /** 旧包扇面 / 环形齐射：每一发各自独立结算（沿用旧行为，不受新的多段标识约束）。 */
    private void spread(Enemy enemy, EnemySkill skill, double angle, int count, double spacingDegrees) {
        for (int i = 0; i < count; i++) {
            fireOne(enemy, skill, angle + Math.toRadians((i - (count - 1) / 2.0) * spacingDegrees),
                    0.0, 0L, skill.range());
        }
    }

    private void radial(Enemy enemy, EnemySkill skill, int count, double offset) {
        for (int i = 0; i < count; i++) {
            fireOne(enemy, skill, offset + Math.PI * 2 * i / count, 0.0, 0L, skill.range());
        }
    }

    /** 静态范围攻击也要保存瞄准角度，独立攻击特效才能与实际出招方向一致。 */
    private void area(Enemy enemy, EnemySkill skill, double x, double y, double angle,
                      double duration, double delay) {
        attacks.add(new EnemyAttack(x, y, 0, 0, skill.radius(), enemy.getWorld(), enemy.getKind(), duration,
                skill.effect(), angle, delay, scaledDamage(skill)));
    }

    private void dash(Enemy enemy, EnemySkill skill, double angle, RoomNavigationSystem navigation) {
        double distance = skill.kind() == EnemyKind.WATCHER ? 260 : 170;
        double nx = enemy.getX() + Math.cos(angle) * distance, ny = enemy.getY() + Math.sin(angle) * distance;
        if (navigation.isSegmentClear(enemy.getX(), enemy.getY(), nx, ny, enemyRadius(enemy), enemy.getWorld())
                && navigation.canOccupy(nx, ny, enemyRadius(enemy), enemy.getWorld())) enemy.setPosition(nx, ny);
        // 冲刺抵达后保留一个短暂、可见的落点判定帧；既能给命中效果留出播放时间，
        // 也不会因“生成即撞到玩家、同一逻辑帧删除”而让攻击在渲染层完全看不见。
        if (skill.pattern() == EnemySkill.Pattern.DASH) {
            area(enemy, skill, enemy.getX(), enemy.getY(), angle, .15, .05);
        }
    }

    /**
     * 沿锁定直线冲撞（甲虫顶撞、螳爵突刺、翼蝠俯冲）。
     *
     * <p>撞墙立即停下（{@code stopOnWall}），此时把起手时铺下的警示带**改短到实际行程**——
     * 预警承诺的范围必须等于真正会挨打的范围，否则玩家会“站在明知安全的空地上被打中”。
     */
    private void chargeForward(Enemy enemy, EnemySkill skill, ActiveCast cast, RoomNavigationSystem navigation) {
        double angle = cast.angle;
        double radius = enemyRadius(enemy);
        if (skill.sideStep() > 0.0) {
            // 折步掠杀：先侧步再突刺；侧步本身没有伤害，只用来错开玩家的预判线。
            double perpendicular = angle + Math.PI / 2.0;
            double sx = enemy.getX() + Math.cos(perpendicular) * skill.sideStep();
            double sy = enemy.getY() + Math.sin(perpendicular) * skill.sideStep();
            double[] safe = navigation.findNearestSafePosition(sx, sy, enemy.getWorld(), radius);
            if (safe != null) {
                visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), "dash_trail",
                        enemy.getX(), enemy.getY(), perpendicular, 120, .28));
                enemy.setPosition(safe[0], safe[1]);
            }
        }
        double startX = enemy.getX();
        double startY = enemy.getY();
        double step = 14.0;
        double travelled = 0.0;
        while (travelled < skill.distance()) {
            // 最后一步只走剩下的距离：多迈的那几像素会让判定带比预警承诺的范围更长。
            double advance = Math.min(step, skill.distance() - travelled);
            double nx = enemy.getX() + Math.cos(angle) * advance;
            double ny = enemy.getY() + Math.sin(angle) * advance;
            if (!navigation.canOccupy(nx, ny, radius, enemy.getWorld())) break;
            if (!navigation.isSegmentClear(enemy.getX(), enemy.getY(), nx, ny, radius, enemy.getWorld())) break;
            enemy.setPosition(nx, ny);
            travelled += advance;
        }
        truncateCastTelegraphs(cast.castId);
        if (skill.damage() > 0.0) {
            telegraphs.add(EnemyTelegraph
                    .at(enemy.getKind(), enemy.getWorld(), skill.effect(),
                            EnemyTelegraph.Shape.LINE, startX, startY, angle)
                    .length(Math.max(24.0, travelled)).width(skill.width())
                    .damage(telegraphDamage(skill)).warn(0.0)
                    .residual(GameConfig.TELEGRAPH_RESIDUAL_TIME)
                    .hitId(cast.hitId).castId(cast.castId).build());
        }
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), skill.effect(),
                enemy.getX(), enemy.getY(), angle, 150, .34));
        enemy.setFacingFromVector(Math.cos(angle), Math.sin(angle));
    }

    /**
     * 掘进破土：沿地面裂痕钻到已锁定的合法落点，再从地里刺出来。
     *
     * <p>「不是无提示追踪传送」——落点在前摇开始时就已经定下并画成圆圈，最后 0.4 秒不再追人。
     */
    private void burrowStrike(Enemy enemy, EnemySkill skill, ActiveCast cast, RoomNavigationSystem navigation) {
        double[] landing = burrowLanding(enemy, skill, cast);
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), "dig_dust",
                enemy.getX(), enemy.getY(), cast.angle, 130, .40));
        enemy.setPosition(landing[0], landing[1]);
        enemy.setFacingFromVector(Math.cos(cast.angle), Math.sin(cast.angle));
        visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), "dig_dust",
                landing[0], landing[1], cast.angle + Math.PI, 150, .40));
    }

    /** 掘进的落点：锁定方向上的合法点，站不下就退到最近能站的位置。 */
    private double[] burrowLanding(Enemy enemy, EnemySkill skill, ActiveCast cast) {
        double radius = enemyRadius(enemy);
        double targetX = enemy.getX() + Math.cos(cast.angle) * skill.distance();
        double targetY = enemy.getY() + Math.sin(cast.angle) * skill.distance();
        if (navigation == null) return new double[]{targetX, targetY};
        double[] safe = navigation.findNearestSafePosition(targetX, targetY, enemy.getWorld(), radius);
        return safe == null ? new double[]{enemy.getX(), enemy.getY()} : safe;
    }

    /** 丢掉某次施法还没结算的预警（冲撞改短范围时用）。 */
    private void truncateCastTelegraphs(long castId) {
        telegraphs.removeIf(telegraph -> telegraph.castId() == castId && !telegraph.isResolved());
    }

    /**
     * 招式伤害随本局难度缩放。
     *
     * <p>与敌人的生命、防御用同一个倍率：难度改的是双方数值的整体刻度，
     * 而不是单方面把敌人堆厚（否则高难度只会变成“同样的招式挨更多下才死”）。
     */
    private double scaledDamage(EnemySkill skill) {
        return skill.damage() * difficulty.playerDamageMultiplier();
    }

    /**
     * 打开召唤裂隙：不直接生成召唤物，只在预定落点留下几道正在成型的裂隙。
     *
     * <p>数量受召唤档案的存活上限限制——场上（连同未成型的裂隙）已经站满时这一波就地取消，
     * 召唤不是刷怪机器。
     */
    private void summonMinions(Enemy boss, Player player, RoomNavigationSystem navigation) {
        SummonProfile profile = SummonProfile.of(boss.getKind());
        if (profile == null) return;
        int slots = profile.aliveCap(floor) - summonLoad();
        // 「血量越低越疯狂」：跌破最后一个血量阶段之后，每一波再多叫一只。
        boolean desperate = boss.getHp() <= boss.getMaxHp() * profile.lastHealthTrigger();
        int count = Math.min(profile.waveSize(floor, desperate), Math.max(0, slots));
        if (count <= 0) return;
        List<double[]> spots = findSummonSpots(boss, player, navigation, profile, count);
        for (int i = 0; i < spots.size(); i++) {
            double[] spot = spots.get(i);
            summonRifts.add(new SummonRift(profile.minion(boss.getWorld(), i, floor), boss.getKind(), boss.getWorld(),
                    spot[0], spot[1], profile.riftTime(), profile.hitPointScale(), profile.alert()));
        }
    }

    private static final class ActiveCast {
        private final EnemySkill skill;
        private final double angle, targetX, targetY;
        /** 本次施法的共享命中标识：整次只算一次的招式（或多段里的第一段）用它。 */
        private final long castId;
        private final long hitId;
        private int stage;
        private ActiveCast(EnemySkill skill, double angle, double targetX, double targetY) {
            this.skill = skill; this.angle = angle; this.targetX = targetX; this.targetY = targetY;
            this.castId = HitIds.next();
            this.hitId = this.castId;
        }
    }

    private void updateEnemyAttacks(double dt, Player player, RoomNavigationSystem navigation) {
        for (EnemyAttack attack : attacks) {
            double oldX = attack.getX();
            double oldY = attack.getY();
            attack.update(dt);
            if (!attack.isActive()) continue;
            // 飞行弹体才受墙阻挡；地裂、斩击、钟波等短暂地面判定不能因为效果范围比敌人碰撞半径大
            // 就在生成当帧被导航系统提前清除。会反弹的弹体撞墙时按法线折返，而不是消失。
            if (attack.isMoving() && !advanceOrBounce(attack, oldX, oldY, dt, navigation)) attack.expire();
            if (!attack.isExpired() && attack.getDamage() > 0.0
                    && CollisionUtil.circleIntersectsCircle(attack.getX(), attack.getY(), attack.getRadius(),
                    player.getX(), player.getY(), GameConfig.PLAYER_RADIUS)) {
                // 伤害跟着这一招自己的数值走：傀儡的践踏与灯魇的小弹不再打掉同样多的血。
                resolvePlayerHit(player, attack);
                attack.expire();
            }
            if (attack.isExpired() && !attack.isResolved()) {
                attack.markResolved();
                // 弹体结束时的收尾：孢子落地延迟绽放、雾区脉冲、炮弹撞墙爆开。
                resolveAttackEnding(attack);
                if (attack.consumeImpact()) {
                    visualEffects.add(new EnemyVisualEffect(attack.getSource(), attack.getWorld(),
                            impactEffect(attack.getSource(), attack.getWorld()), attack.getX(), attack.getY(),
                            attack.getAngleRadians(), Math.max(64, attack.getRadius() * 3.8), .34));
                }
            }
        }
        attacks.removeIf(EnemyAttack::isExpired);
    }

    /**
     * 弹体结束时的收尾结算。
     *
     * <ul>
     *   <li>{@code pulses} 不为空：落地形成持续区域，按 0 / 0.8 / 1.6 秒各脉冲一次（暮孢滞留）；</li>
     *   <li>{@code explodeOnImpact}：撞墙即爆，与弹体共享命中标识（聚棱炮「弹体与爆炸共享一次命中」）；</li>
     *   <li>{@code delayedBurstSeconds}：落地后延迟一段时间绽开一次（迟绽晨孢）。</li>
     * </ul>
     */
    private void resolveAttackEnding(EnemyAttack attack) {
        EnemySkill skill = attack.getSkill();
        if (skill == null || !attack.isMoving()) return;
        double[] pulses = skill.pulses();
        if (pulses.length > 0) {
            for (double pulse : pulses) {
                telegraphs.add(EnemyTelegraph
                        .at(attack.getSource(), attack.getWorld(), "spore_cloud",
                                EnemyTelegraph.Shape.CIRCLE, attack.getX(), attack.getY(), attack.getAngleRadians())
                        .radius(skill.radius())
                        .damage(telegraphDamage(skill)).warn(pulse)
                        .visibleLead(Math.max(pulse, 0.35))
                        .residual(GameConfig.TELEGRAPH_RESIDUAL_TIME)
                        .hitId(HitIds.next()).build());
            }
            visualEffects.add(new EnemyVisualEffect(attack.getSource(), attack.getWorld(), "spore_cloud",
                    attack.getX(), attack.getY(), 0.0, skill.radius() * 2.6, 2.1));
            return;
        }
        if (skill.explodeOnImpact()) {
            telegraphs.add(EnemyTelegraph
                    .at(attack.getSource(), attack.getWorld(), impactEffect(attack.getSource(), attack.getWorld()),
                            EnemyTelegraph.Shape.CIRCLE, attack.getX(), attack.getY(), attack.getAngleRadians())
                    .radius(skill.radius())
                    .damage(telegraphDamage(skill)).warn(0.0)
                    .residual(GameConfig.TELEGRAPH_RESIDUAL_TIME)
                    // 共享标识：弹体已经打中玩家时，这次爆开不再重复结算。
                    .hitId(attack.getHitId()).build());
            return;
        }
        if (skill.delayedBurstSeconds() > 0.0) {
            telegraphs.add(EnemyTelegraph
                    .at(attack.getSource(), attack.getWorld(), impactEffect(attack.getSource(), attack.getWorld()),
                            EnemyTelegraph.Shape.CIRCLE, attack.getX(), attack.getY(), attack.getAngleRadians())
                    .radius(skill.radius())
                    .damage(telegraphDamage(skill)).warn(skill.delayedBurstSeconds())
                    .residual(GameConfig.TELEGRAPH_RESIDUAL_TIME)
                    .hitId(HitIds.next()).build());
            visualEffects.add(new EnemyVisualEffect(attack.getSource(), attack.getWorld(), "spore_cloud",
                    attack.getX(), attack.getY(), 0.0, skill.radius() * 2.2, skill.delayedBurstSeconds()));
        }
    }

    /**
     * 把一次命中的伤害落到玩家身上：先由护盾吸收，剩下打进生命值。
     *
     * <p>飘字画在玩家头顶而不是弹体身上——弹体命中后立刻消失，飘在弹体位置会跟着一起没。
     */
    private void resolvePlayerHit(Player player, EnemyAttack attack) {
        if (alreadyConsumed(attack.getHitId())) return;
        Player.DamageResult result = player.takeDamage(
                attack.getDamage(), attack.getDamageType(), attack.getX(), attack.getY());
        if (result == null) return;   // 无敌帧内或零伤害：不重复扣血，也不飘字
        if (attack.getHitId() != 0L) consumedHitIds.add(attack.getHitId());
        recordPlayerHit(player, result);
    }

    /** 玩家的伤害包命中后统一走这里记飘字：护盾全吃掉时写「盾 N」，否则写实际掉的血。 */
    private void recordPlayerHit(Player player, Player.DamageResult result) {
        boolean onShield = result.healthLost() <= 0 && result.absorbedByShield() > 0.0;
        damageFlashes.add(new DamageFlash(player.getX(), player.getY() - 62.0,
                onShield ? result.absorbedByShield() : result.healthLost(),
                result.type(), onShield, !onShield && result.absorbedByShield() > 0.0,
                DAMAGE_FLASH_TIME));
    }

    /**
     * 推进一枚弹体；撞墙时按法线折返，返回它是否还活着。
     *
     * <p>房间是轴对齐矩形的集合，所以「反射」只需要在候选速度里挑一个走得通的方向：
     * 先试反转 X（撞竖墙），再试反转 Y（撞横墙），最后试两者都反转（撞墙角）。
     * 三个方向都被挡住、或者已经没有反弹次数了，弹体才真的消失——
     * 这样反弹弹不会在窄缝里无限弹射，也不会穿墙。
     */
    private boolean advanceOrBounce(EnemyAttack attack, double oldX, double oldY, double dt,
                                    RoomNavigationSystem navigation) {
        if (navigation.canProjectileOccupy(attack.getX(), attack.getY(), attack.getRadius(), attack.getWorld())
                && navigation.isSegmentClear(oldX, oldY, attack.getX(), attack.getY(),
                attack.getRadius(), attack.getWorld())) {
            return true;
        }
        if (!attack.isBouncing()) return false;
        double velocityX = attack.getVelocityX();
        double velocityY = attack.getVelocityY();
        double[][] candidates = {{-velocityX, velocityY}, {velocityX, -velocityY}, {-velocityX, -velocityY}};
        for (double[] candidate : candidates) {
            double nx = oldX + candidate[0] * dt;
            double ny = oldY + candidate[1] * dt;
            if (Math.hypot(nx - oldX, ny - oldY) < GameConfig.ENEMY_BOUNCE_MIN_STEP) continue;
            if (!navigation.canProjectileOccupy(nx, ny, attack.getRadius(), attack.getWorld())) continue;
            if (!navigation.isSegmentClear(oldX, oldY, nx, ny, attack.getRadius(), attack.getWorld())) continue;
            attack.setPosition(nx, ny);
            attack.reflect(candidate[0], candidate[1]);
            return true;
        }
        return false;
    }

    /** 每个物种的命中特效：测试会逐个核对这些特效目录是否真的存在于资源里。 */
    static String impactEffect(EnemyKind kind, WorldType world) {
        boolean light = world == WorldType.LIGHT;
        return switch (kind) {
            case LANTERN -> light ? "orb_impact" : "needle_impact";
            case WOLF -> "bite_impact";
            case GOLEM -> light ? "stone_burst" : "dark_debris";
            case MAGE -> light ? "pellet_impact" : "mirror_impact";
            case EXECUTIONER -> light ? "spear_impact" : "slash_impact";
            case BELL -> "impact";
            case WATCHER -> light ? "spear_impact" : "slash_impact";
            // v2 扩展包：每个物种都有自己的命中特效。
            case BEETLE -> "impact";
            case SPORE -> "spore_impact";
            case RAYBAT -> "impact";
            case PRISM_CRAB -> "shell_impact";
            case MANTIS -> "hit_sparks";
            case WEAVER -> "silk_impact";
            case ROOTKING -> "root_impact";
            case HOURGLASS -> "shard_impact";
        };
    }

    /** 切界保留共享战场中的索敌、施法和攻击。 */
    public void onWorldChanged(WorldType currentWorld) {
        // 敌人的索敌、施法、弹幕与命中去重均连续保留，切界不能免费清场。
    }

    public void spawnEventEnemies(Room room, long seed, Player player, RoomNavigationSystem navigation) {
        Random random = new Random(seed ^ room.id() * 0x51ED270BL);
        enemies.clear(); attacks.clear(); visualEffects.clear(); summonRifts.clear();
        telegraphs.clear(); rootWalls.clear(); pendingWalls.clear(); consumedHitIds.clear();
        activeCasts.clear(); activeRoomId = room.id();
        int count = 2 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            EnemyKind kind = i == 0 ? EnemyKind.WOLF : EnemyKind.LANTERN;
            spawn(kind, i % 2 == 0 ? WorldType.LIGHT : WorldType.SHADOW, room, player, navigation, random);
        }
    }

    public boolean isRoomCleared() { return enemies.isEmpty(); }
    public int getCount(WorldType world) { return (int) enemies.stream().filter(enemy -> enemy.getWorld() == world).count(); }
    public int consumeKills() { int result = killsSinceLastRead; killsSinceLastRead = 0; return result; }

    /**
     * 只投放一只指定物种的敌人，位置交给调用方设置。
     *
     * <p>给测试与后续调试场景使用：正常流程一律走 {@link #enterRoom} 的按房生成，
     * 那条路径的物种与站位都由种子决定，没法用来单独核对某个物种的伤害数值。
     *
     * @return 新生成的敌人
     */
    public Enemy spawnForTest(EnemyKind kind, WorldType world, int floor, Difficulty difficulty) {
        Enemy enemy = new Enemy(kind, world, 0.0, 0.0, Math.max(1, floor),
                difficulty == null ? Difficulty.NORMAL : difficulty);
        enemies.add(enemy);
        return enemy;
    }

    /** 自上帧以来首领起手召唤的次数：会话层据此提醒玩家“增援来了”。 */
    public int consumeSummonCalls() { int result = summonCallsSinceLastRead; summonCallsSinceLastRead = 0; return result; }

    /**
     * 调试与测试用：让指定敌人立刻按给定招式起手，跳过选技与距离过滤。
     *
     * <p>正常流程一律走 {@link #chooseSkill} 的轮换，招式由距离与冷却共同决定；
     * 想单独核对「这一招的判定到底是什么形状」，只能把某一招直接按下去。
     */
    public void castForTest(Enemy enemy, Player player, EnemySkill skill) {
        if (enemy == null || skill == null) return;
        startCast(enemy, player, skill);
    }

    /** 场上还活着的召唤物数量（不含未成型的裂隙）。 */
    public int getSummonedCount() { return (int) enemies.stream().filter(Enemy::isSummoned).count(); }

    /** 正在成型、尚未放出召唤物的裂隙。 */
    public List<SummonRift> getSummonRifts() { return Collections.unmodifiableList(summonRifts); }

    public List<Enemy> getEnemies() { return Collections.unmodifiableList(enemies); }
    public List<EnemyAttack> getAttacks() { return Collections.unmodifiableList(attacks); }
    public List<EnemyVisualEffect> getVisualEffects() { return Collections.unmodifiableList(visualEffects); }

    /**
     * 当前生效的索敌半径（像素）。
     *
     * <p>整房索敌开启时取「房间外接矩形对角线」，因此敌人只要与玩家同处一房就一定会参战，
     * 不再出现站得远就完全不动的情况；关闭时使用保守的固定半径。
     */
    public static double detectionRange() {
        return GameConfig.ENEMY_AGGRO_WHOLE_ROOM
                ? GameConfig.ENEMY_ROOM_AGGRO_RADIUS
                : GameConfig.ENEMY_DETECTION_RANGE;
    }

    private static double attackRange(EnemyKind kind) {
        // 以该物种所有可用技能的最远施法距离做“能否看见并考虑起手”的上限；
        // 真正选技时仍按每个技能自己的 range 过滤，近战招式不会隔半个房间释放。
        return java.util.Arrays.stream(WorldType.values())
                .flatMap(world -> EnemySkill.forEnemy(kind, world).stream())
                .mapToDouble(EnemySkill::range).max()
                .orElse(kind.ranged() ? GameConfig.ENEMY_RANGED_ATTACK_RANGE : GameConfig.ENEMY_MELEE_ATTACK_RANGE);
    }

    /**
     * 各物种保持的站位距离（像素）。
     *
     * <p>站位决定“它在你多远的地方停下来开始出招”，必须和它的招式射程配套：
     * 炮蟹要拉开距离才用得出聚棱炮，甲虫则必须贴到 70 才顶得到人。
     *
     * <p>上限刻意压在 {@link GameConfig#ENEMY_RANGED_STANDOFF_DISTANCE}（190）以内：
     * 站位一旦超过它，敌人在 190～站位之间的距离上就会既不靠近也不后退，
     * 看上去像是「站在远处发呆」——而整房索敌的前提是每个敌人都主动接近。
     */
    private static double standoffDistance(EnemyKind kind) {
        return switch (kind) {
            case LANTERN -> 180.0;
            case MAGE -> 185.0;
            case BELL -> 190.0;
            // 首领远程技射程很远，但不能把安全站位拉得过大；否则在中距离会显得原地发呆。
            // 守望者停在普通远程站位；玩家主动贴脸时由上面的近身优先规则强制它直接出招。
            case WATCHER -> 190.0;
            case WOLF -> 78.0;
            case GOLEM -> 96.0;
            case EXECUTIONER -> 120.0;
            // v2 小怪：甲虫贴脸顶撞、孢子远远地吐、翼蝠保持中距骚扰。
            case BEETLE -> 70.0;
            case SPORE -> 185.0;
            case RAYBAT -> 150.0;
            // v2 首领：炮蟹拉开炮击、螳爵贴身、蛛后与古树中距、术士留在标准远程站位。
            case PRISM_CRAB -> 190.0;
            case MANTIS -> 95.0;
            case WEAVER -> 188.0;
            case ROOTKING -> 190.0;
            case HOURGLASS -> 180.0;
        };
    }

    private static double enemyRadius(Enemy enemy) { return radiusOf(enemy.getKind()); }

    /** 物种真实身位半径：首领 46、精英 34、普通怪 27。 */
    private static double radiusOf(EnemyKind kind) { return kind.footRadius(); }

    /** 物种本体的渲染宽度（像素）：画布边长 × 统一缩放刻度。 */
    private static double displayWidth(EnemyKind kind) {
        return kind.canvasSize() * GameConfig.MONSTER_RENDER_SCALE;
    }
}
