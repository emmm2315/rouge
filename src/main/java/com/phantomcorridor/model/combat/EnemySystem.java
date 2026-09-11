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
import com.phantomcorridor.util.CollisionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 敌人生成、同界 AI、伤害以及清房门禁的纯逻辑系统。
 *
 * <p>索敌规则：同界敌人只要与玩家同处一房就会锁定玩家并主动接近（见 {@link #detectionRange()}），
 * 被墙挡住视线时继续绕行接近、只有确实看得见玩家时才开火；玩家换界后敌人丢失目标，重新索敌。
 *
 * <p>首领另有召唤机制：裂隙先成型、召唤物后落地，什么时候召唤由血量阶段、打不到玩家的时长
 * 与场上剩余召唤物共同决定（见 {@link #beginSummonIfDue}）。
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

    /** 守望者的血量召唤阶段：血量第一次跌破这些比例时立刻召唤一次（无视冷却）。 */
    private static final double[] SUMMON_STAGE_HP = {0.70, 0.35};

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
    private final Map<Enemy, ActiveCast> activeCasts = new HashMap<>();
    private final EnumMap<WorldType, Map<Integer, RoomFlowField>> flowFields = new EnumMap<>(WorldType.class);
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
        activeCasts.clear();
        flowFields.clear();
        activeRoomId = -1;
        killsSinceLastRead = 0;
        summonCallsSinceLastRead = 0;
    }

    /** 当前层数：决定新生成敌人的生命值与防御。 */
    public void setFloor(int floor) { this.floor = Math.max(1, floor); }

    public int getFloor() { return floor; }

    /** 本局难度：与层数成长一起决定新生成敌人的基础属性。 */
    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
    }

    public Difficulty getDifficulty() { return difficulty; }

    /** 仅在未清理的战斗/Boss 房生成；Boss 房严格只生成一名首领。 */
    public void enterRoom(Room room, long dungeonSeed, Player player, RoomNavigationSystem navigation) {
        if (activeRoomId == room.id()) return;
        enemies.clear();
        attacks.clear();
        visualEffects.clear();
        summonRifts.clear();
        activeCasts.clear();
        activeRoomId = room.id();
        if (room.isCleared() || (room.type() != RoomType.BATTLE && room.type() != RoomType.BOSS)) return;

        Random random = new Random(dungeonSeed ^ ((long) room.id() * 0x9E3779B97F4A7C15L));
        if (room.type() == RoomType.BOSS) {
            spawn(EnemyKind.WATCHER, WorldType.LIGHT, room, player, navigation, random);
            return;
        }
        int count = GameConfig.BATTLE_ENEMY_MIN
                + random.nextInt(GameConfig.BATTLE_ENEMY_MAX - GameConfig.BATTLE_ENEMY_MIN + 1);
        EnemyKind[] normals = {EnemyKind.LANTERN, EnemyKind.WOLF, EnemyKind.GOLEM, EnemyKind.MAGE};
        for (int i = 0; i < count; i++) {
            // 后段战斗房偶尔用一只精英替换普通怪，不改变 5~7 的总量。
            EnemyKind kind = i == count - 1 && room.id() >= 5 && random.nextDouble() < 0.35
                    ? (random.nextBoolean() ? EnemyKind.EXECUTIONER : EnemyKind.BELL)
                    : normals[random.nextInt(normals.length)];
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
        resolvePlayerHits(player, playerAttacks);
        visualEffects.forEach(effect -> effect.update(dt));
        visualEffects.removeIf(EnemyVisualEffect::expired);

        boolean bossFell = false;
        boolean bossCrossedOver = false;
        for (var iterator = enemies.iterator(); iterator.hasNext();) {
            Enemy enemy = iterator.next();
            enemy.updateTimers(dt);
            if (enemy.isDead()) {
                visualEffects.add(EnemyVisualEffect.body(enemy.getKind(), enemy.getWorld(), "death", enemy.getFacing(),
                        enemy.getX(), enemy.getY(), enemy.isBoss() ? 323 : enemy.getKind().elite() ? 230 : 179, .70));
                activeCasts.remove(enemy);
                killsSinceLastRead++;
                bossFell |= enemy.isBoss();
                iterator.remove();
                continue;
            }
            // 首领在半血时从光界进入暗界，保留同一实体与血量。
            if (enemy.isBoss() && enemy.getHp() * 2 <= enemy.getMaxHp() && enemy.getWorld() == WorldType.LIGHT) {
                enemy.setWorld(WorldType.SHADOW);
                attacks.removeIf(attack -> attack.getSource() == EnemyKind.WATCHER);
                visualEffects.add(new EnemyVisualEffect(enemy.getKind(), WorldType.LIGHT, "phase_transition",
                        enemy.getX(), enemy.getY(), 0.0, 210, .45));
                // 素材包规则 clearOnBossWorldExit：首领离开某一界，它在那一界留下的造物随之溃散。
                // 真正清理由循环外的 collapseSummons 执行——在遍历 enemies 时删除会直接抛并发修改异常。
                bossCrossedOver = true;
            }
            if (enemy.getWorld() != player.getCurrentWorld()) continue;
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
        if (bossFell || bossCrossedOver) collapseSummons();
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
        // 守望者优先用裂隙闪现：既解决“被顶在墙角”，也解决“玩家躲进它挤不进去的角落”。
        if (enemy.isBoss() && enemy.getBlinkCooldown() <= 0.0 && blinkWatcher(enemy, player, navigation)) return;
        escapeWedge(enemy, player, navigation);
    }

    /**
     * 守望者的短距裂隙闪现：撕开一道裂隙直接出现在玩家附近的合法位置。
     *
     * <p>只在“追不上或打不到玩家”且冷却结束时使用，落点优先能直接打到玩家，
     * 落地后还有一段起手时间，避免贴脸瞬狙。
     *
     * @return 是否成功闪现
     */
    private boolean blinkWatcher(Enemy enemy, Player player, RoomNavigationSystem navigation) {
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
     * <p>优先级：①走得过去、且距离场给得出方向的位置（真正脱困）；②走得过去的位置（至少挪出死角）；
     * ③自己已经嵌进墙体里时，允许就近弹出一点点。绝不接受“隔着墙跳过去”——那样敌人会瞬移到
     * 玩家躲着的死胡同里，看起来像穿墙挂。
     */
    private double[] findEscapePosition(Enemy enemy, Player player, RoomNavigationSystem navigation,
                                        double radius) {
        RoomFlowField field = flowFieldFor(enemy, player, navigation, radius);
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
                    if (field.directionFrom(x, y) != null) return new double[]{x, y};
                    if (reachable == null) reachable = new double[]{x, y};
                } else if (popOut == null && ring <= popLimit) {
                    popOut = new double[]{x, y};
                }
            }
        }
        return reachable != null ? reachable : popOut;
    }

    /**
     * 索敌判定：描述敌人这一帧是否“发现”玩家。
     *
     * <p>整房索敌开启时（{@link GameConfig#ENEMY_AGGRO_WHOLE_ROOM}），同界敌人只要与玩家同处一房
     * 就会锁定并主动接近；锁定后不再受距离限制，会一直追到玩家换界或离开房间为止。
     */
    private static boolean acquireTarget(Enemy enemy, double distance) {
        if (enemy.isAware()) return true;
        if (distance > detectionRange()) return false;
        enemy.markAware();
        return true;
    }

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
                if (enemy.getWorld() != WorldType.SHADOW || enemy.isDead()) continue;
                if (enemy.getLastMeleeHitId() == attackId) continue;
                if (!meleeCovers(player, playerAttacks, enemy)) continue;
                int dealt = applyDamage(player, enemy, playerAttacks.getMeleeCoefficient());
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
            if (enemy.isDead() || projectile.getWorld() != enemy.getWorld()) continue;
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
        int dealt = applyDamage(player, hit, projectile.getDamageCoefficient());
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

    /** 弹射目标：命中点附近、同界、没被本弹体打过、且没有墙挡着的最近敌人。 */
    private Enemy nearestUnhitEnemy(Projectile projectile, Enemy justHit) {
        Enemy best = null;
        double bestDistance = GameConfig.MIRROR_ORB_BOUNCE_RADIUS;
        for (Enemy enemy : enemies) {
            if (enemy.isDead() || enemy == justHit) continue;
            if (enemy.getWorld() != projectile.getWorld() || projectile.hasHit(enemy)) continue;
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
     */
    private int applyDamage(Player player, Enemy enemy, double coefficient) {
        double raw = player.getCurrentBaseDamage()
                * coefficient * player.damageMultiplier(enemy.getWorld())
                * hunterBonus(player, enemy);
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
        if (enemy.getWorld() != WorldType.SHADOW) return 1.0;
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
     * 范围伤害：对半径内、同界、且从爆心看得见的敌人各结算一次。
     *
     * <p>视线检查是设计文档的硬要求——爆炸不能隔着墙输出（「以爆心做视线判定」）。
     */
    private void explodeAt(Player player, double x, double y, double coefficient,
                           double radius, WorldType world) {
        for (Enemy enemy : enemies) {
            if (enemy.isDead() || enemy.getWorld() != world) continue;
            double distance = Math.hypot(enemy.getHitboxCenterX() - x, enemy.getHitboxCenterY() - y);
            if (distance > radius + enemy.getHitboxRadius()) continue;
            if (!hasLineOfSight(x, y, enemy.getHitboxCenterX(), enemy.getHitboxCenterY(), world)) continue;
            applyDamage(player, enemy, coefficient);
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
        double height = enemy.isBoss() ? 168.0 : enemy.getKind().elite() ? 124.0 : 96.0;
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
        for (double offsetDegrees : SLIDE_OFFSETS_DEGREES[side > 0 ? 0 : 1]) {
            double angle = tangentAngle + Math.toRadians(offsetDegrees);
            if (!Double.isNaN(heading)
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
        activeCasts.put(enemy, new ActiveCast(skill, Math.atan2(dy, dx), player.getX(), player.getY()));
        String charge = switch (enemy.getKind()) {
            case LANTERN, MAGE, BELL -> "charge";
            case EXECUTIONER -> enemy.getWorld() == WorldType.LIGHT ? "charge" : "charge";
            case WATCHER -> "cast_charge";
            default -> "";
        };
        if (!charge.isEmpty()) visualEffects.add(new EnemyVisualEffect(enemy.getKind(), enemy.getWorld(), charge,
                enemy.getX(), enemy.getY() - 24, 0.0, enemy.isBoss() ? 176 : 100, skill.windup()));
    }

    /**
     * 首领的召唤判定：三种“适时”条件满足任意一条就起手召唤。
     *
     * <ol>
     *   <li><b>血量阶段</b>：血量第一次跌破 {@link #SUMMON_STAGE_HP} 里的比例，立刻召唤一次，且无视冷却；</li>
     *   <li><b>逼出掩体</b>：连续 {@link GameConfig#WATCHER_SUMMON_PRESSURE_TIME} 秒打不到玩家，
     *       就把小怪放出去——它们身位小、跑得快，能钻首领自己进不去的缝；</li>
     *   <li><b>补位</b>：场上一个召唤物都不剩时再来一波，冷却决定节奏。</li>
     * </ol>
     *
     * <p>后两条受冷却与 {@link GameConfig#WATCHER_SUMMON_MAX_ALIVE} 限制；阶段召唤只受存活上限限制，
     * 且被上限挡住时不推进阶段，等位置空出来照样补上。
     *
     * @return 本帧是否已经起手召唤（起手后不再走普通攻击）
     */
    private boolean beginSummonIfDue(Enemy boss, Player player, double distance, boolean lineOfSight) {
        if (!boss.isBoss() || activeCasts.containsKey(boss)) return false;
        int load = summonLoad();
        if (load >= GameConfig.WATCHER_SUMMON_MAX_ALIVE) return false;
        boolean stageDue = boss.getSummonStage() < SUMMON_STAGE_HP.length
                && boss.getHp() <= boss.getMaxHp() * SUMMON_STAGE_HP[boss.getSummonStage()];
        boolean pressureDue = boss.getSummonPressure() >= GameConfig.WATCHER_SUMMON_PRESSURE_TIME;
        // 160px 已进入首领模型边缘与玩家近战的交错距离；在这个距离内强制本体攻击。
        // 仍略小于守望者 190px 的远程站位，保证它在中距离能正常使用新的召唤机制。
        boolean playerIsClose = lineOfSight && distance <= 160.0;
        // 玩家已经贴近首领时，补位增援不应抢走本体攻击；血量阶段/地形困住仍可触发一次，
        // 这样新机制存在感足够，又不会造成“Boss 只会叫小怪”的体验。
        if (playerIsClose && !stageDue && !pressureDue) return false;
        // 补位不会立刻抢走原本的普攻轮换。首领在每波增援前至少会完整施放两次本体技能，
        // 所以旧有弹幕/审判/长矛（影界的连斩/冲刺）始终可见；血量阶段与被地形困住时
        // 仍可无视该限制召唤，保留新机制的压迫感。
        boolean reinforcementDue = !playerIsClose && load == 0 && boss.getNormalCastsSinceSummon() >= 2;
        if (!stageDue && !pressureDue && !reinforcementDue) return false;
        if (!stageDue && boss.getSummonCooldown() > 0.0) return false;
        EnemySkill summon = EnemySkill.forEnemy(EnemyKind.WATCHER, boss.getWorld()).stream()
                .filter(skill -> skill.pattern() == EnemySkill.Pattern.SUMMON).findFirst().orElse(null);
        if (summon == null) return false;
        if (stageDue) boss.advanceSummonStage();
        boss.setSummonCooldown(GameConfig.WATCHER_SUMMON_COOLDOWN);
        boss.resetSummonPressure();
        startCast(boss, player, summon);
        summonCallsSinceLastRead++;
        return true;
    }

    /** 场上已经占掉的召唤名额：活着的召唤物 + 还没成型的裂隙。 */
    private int summonLoad() {
        return summonRifts.size() + (int) enemies.stream().filter(Enemy::isSummoned).count();
    }

    /** 该世界的召唤位序：第一只是本界的猎手，第二只是法师——行为差别明显，玩家一眼分得清。 */
    private static EnemyKind summonKind(WorldType world, int index) {
        if (index % 2 == 1) return EnemyKind.MAGE;
        return world == WorldType.LIGHT ? EnemyKind.LANTERN : EnemyKind.WOLF;
    }

    /**
     * 沿首领周围几圈找裂隙落点。
     *
     * <p>优先开在“首领与玩家之间”，像在包抄：这条规律玩家看得懂、也能提前让开，
     * 比随机撒点更像“这招有章法”。落点必须放得下召唤物身位、离玩家有安全距离，
     * 两个裂隙之间也要留出间距，免得两只怪叠在同一个点上。
     */
    private List<double[]> findSummonSpots(Enemy boss, Player player, RoomNavigationSystem navigation, int count) {
        List<double[]> spots = new ArrayList<>();
        double toPlayer = Math.atan2(player.getY() - boss.getY(), player.getX() - boss.getX());
        double minRing = GameConfig.WATCHER_SUMMON_RIFT_MIN_DISTANCE;
        double maxRing = Math.max(minRing, GameConfig.WATCHER_SUMMON_RIFT_MAX_DISTANCE);
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
                if (Math.hypot(x - player.getX(), y - player.getY()) < GameConfig.WATCHER_SUMMON_PLAYER_CLEARANCE) continue;
                if (!navigation.canOccupy(x, y, radiusOf(summonKind(boss.getWorld(), spots.size())), boss.getWorld())) continue;
                if (spots.stream().anyMatch(spot -> Math.hypot(spot[0] - x, spot[1] - y)
                        < GameConfig.WATCHER_SUMMON_RIFT_SPACING)) continue;
                spots.add(new double[]{x, y});
            }
        }
        return spots;
    }

    /** 两个角度之间的最小夹角（弧度，恒为非负）。 */
    private static double angleDifference(double a, double b) {
        double difference = Math.abs(a - b) % (Math.PI * 2);
        return Math.min(difference, Math.PI * 2 - difference);
    }

    /**
     * 裂隙成型：在裂隙位置放出召唤物，并留下一圈迸发特效。
     *
     * <p>召唤物的世界、层数与难度都继承首领，只有生命值按 {@link GameConfig#WATCHER_SUMMON_HP_SCALE} 打折；
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
                    GameConfig.WATCHER_SUMMON_HP_SCALE);
            double radius = radiusOf(minion.getKind());
            double[] landing = navigation.findNearestSafePosition(rift.x(), rift.y(), rift.world(), radius);
            if (landing == null) continue;   // 周围实在站不下就不放，绝不把召唤物塞进墙里
            minion.setPosition(landing[0], landing[1]);
            minion.setAlertRemaining(GameConfig.WATCHER_SUMMON_ALERT);
            enemies.add(minion);
            visualEffects.add(new EnemyVisualEffect(EnemyKind.WATCHER, rift.world(), "summon_portal",
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
            visualEffects.add(new EnemyVisualEffect(EnemyKind.WATCHER, enemy.getWorld(), "phase_transition",
                    enemy.getX(), enemy.getY(), 0.0, 150, .40));
            activeCasts.remove(enemy);
            iterator.remove();
        }
        for (SummonRift rift : summonRifts) {
            visualEffects.add(new EnemyVisualEffect(EnemyKind.WATCHER, rift.world(), "phase_transition",
                    rift.x(), rift.y(), 0.0, 150, .40));
        }
        summonRifts.clear();
    }

    private boolean advanceCast(Enemy enemy, Player player, RoomNavigationSystem navigation) {
        ActiveCast cast = activeCasts.get(enemy);
        if (cast == null) return false;
        if (cast.stage == 0 && enemy.isAnimationFinished()) {
            cast.stage = 1;
            enemy.playAnimation(cast.skill.actionBase() + "_release", .16, false);
            releaseSkill(enemy, cast, player, navigation);
            return true;
        }
        if (cast.stage == 1 && enemy.isAnimationFinished()) {
            cast.stage = 2;
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
            case PROJECTILE -> projectile(enemy, skill, cast.angle);
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
        }
    }

    private void projectile(Enemy enemy, EnemySkill skill, double angle) {
        attacks.add(new EnemyAttack(enemy.getX(), enemy.getY(), Math.cos(angle) * skill.speed(), Math.sin(angle) * skill.speed(),
                skill.radius(), enemy.getWorld(), enemy.getKind(), 3.2, skill.effect(), angle, 0.0,
                scaledDamage(skill)));
    }
    private void spread(Enemy enemy, EnemySkill skill, double angle, int count, double spacingDegrees) {
        for (int i = 0; i < count; i++) projectile(enemy, skill, angle + Math.toRadians((i - (count - 1) / 2.0) * spacingDegrees));
    }
    private void radial(Enemy enemy, EnemySkill skill, int count, double offset) {
        for (int i = 0; i < count; i++) projectile(enemy, skill, offset + Math.PI * 2 * i / count);
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
     * <p>数量受 {@link GameConfig#WATCHER_SUMMON_MAX_ALIVE} 限制——场上（连同未成型的裂隙）
     * 已经站满时这一波就地取消，召唤不是刷怪机器。
     */
    private void summonMinions(Enemy boss, Player player, RoomNavigationSystem navigation) {
        int slots = GameConfig.WATCHER_SUMMON_MAX_ALIVE - summonLoad();
        int count = Math.min(GameConfig.WATCHER_SUMMON_COUNT, Math.max(0, slots));
        if (count <= 0) return;
        List<double[]> spots = findSummonSpots(boss, player, navigation, count);
        for (int i = 0; i < spots.size(); i++) {
            double[] spot = spots.get(i);
            summonRifts.add(new SummonRift(summonKind(boss.getWorld(), i), boss.getWorld(), spot[0], spot[1],
                    GameConfig.WATCHER_SUMMON_RIFT_TIME));
        }
    }

    private static final class ActiveCast {
        private final EnemySkill skill; private final double angle, targetX, targetY; private int stage;
        private ActiveCast(EnemySkill skill, double angle, double targetX, double targetY) {
            this.skill = skill; this.angle = angle; this.targetX = targetX; this.targetY = targetY;
        }
    }

    private void updateEnemyAttacks(double dt, Player player, RoomNavigationSystem navigation) {
        for (EnemyAttack attack : attacks) {
            double oldX = attack.getX();
            double oldY = attack.getY();
            attack.update(dt);
            if (!attack.isActive()) continue;
            // 飞行弹体才受墙阻挡；地裂、斩击、钟波等短暂地面判定不能因为效果范围比敌人碰撞半径大
            // 就在生成当帧被导航系统提前清除。
            if (attack.isMoving() && (!navigation.canProjectileOccupy(attack.getX(), attack.getY(), attack.getRadius(), attack.getWorld())
                    || !navigation.isSegmentClear(oldX, oldY, attack.getX(), attack.getY(), attack.getRadius(), attack.getWorld()))) attack.expire();
            if (!attack.isExpired() && attack.getWorld() == player.getCurrentWorld()
                    && CollisionUtil.circleIntersectsCircle(attack.getX(), attack.getY(), attack.getRadius(),
                    player.getX(), player.getY(), GameConfig.PLAYER_RADIUS)) {
                // 伤害跟着这一招自己的数值走：傀儡的践踏与灯魇的小弹不再打掉同样多的血。
                resolvePlayerHit(player, attack);
                attack.expire();
            }
            if (attack.isExpired() && attack.consumeImpact()) {
                visualEffects.add(new EnemyVisualEffect(attack.getSource(), attack.getWorld(),
                        impactEffect(attack.getSource(), attack.getWorld()), attack.getX(), attack.getY(),
                        attack.getAngleRadians(), Math.max(64, attack.getRadius() * 3.8), .34));
            }
        }
        attacks.removeIf(EnemyAttack::isExpired);
    }

    /**
     * 把一次命中的伤害落到玩家身上：先由护盾吸收，剩下打进生命值。
     *
     * <p>飘字画在玩家头顶而不是弹体身上——弹体命中后立刻消失，飘在弹体位置会跟着一起没。
     */
    private void resolvePlayerHit(Player player, EnemyAttack attack) {
        Player.DamageResult result = player.takeDamage(
                attack.getDamage(), attack.getDamageType(), attack.getX(), attack.getY());
        if (result == null) return;   // 无敌帧内或零伤害：不重复扣血，也不飘字
        boolean onShield = result.healthLost() <= 0 && result.absorbedByShield() > 0.0;
        damageFlashes.add(new DamageFlash(player.getX(), player.getY() - 62.0,
                onShield ? result.absorbedByShield() : result.healthLost(),
                result.type(), onShield, !onShield && result.absorbedByShield() > 0.0,
                DAMAGE_FLASH_TIME));
    }


    private static String impactEffect(EnemyKind kind, WorldType world) {
        boolean light = world == WorldType.LIGHT;
        return switch (kind) {
            case LANTERN -> light ? "orb_impact" : "needle_impact";
            case WOLF -> "bite_impact";
            case GOLEM -> light ? "stone_burst" : "dark_debris";
            case MAGE -> light ? "pellet_impact" : "mirror_impact";
            case EXECUTIONER -> light ? "spear_impact" : "slash_impact";
            case BELL -> "impact";
            case WATCHER -> light ? "spear_impact" : "slash_impact";
        };
    }

    /** 切界时不能留下看不见的旧世界伤害；离开当前世界的敌人清空索敌状态，回到该世界时重新索敌。 */
    public void onWorldChanged(WorldType currentWorld) {
        attacks.removeIf(attack -> attack.getWorld() != currentWorld);
        visualEffects.removeIf(effect -> effect.world() != currentWorld);
        activeCasts.entrySet().removeIf(entry -> entry.getKey().getWorld() != currentWorld);
        for (Enemy enemy : enemies) if (enemy.getWorld() != currentWorld) enemy.loseAwareness();
    }
    public void spawnEventEnemies(Room room, long seed, Player player, RoomNavigationSystem navigation) {
        Random random = new Random(seed ^ room.id() * 0x51ED270BL);
        enemies.clear(); attacks.clear(); visualEffects.clear(); summonRifts.clear();
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
     * <p>整房索敌开启时取「房间外接矩形对角线」，因此同界敌人只要与玩家同处一房就一定会参战，
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
        };
    }

    private static double enemyRadius(Enemy enemy) { return radiusOf(enemy.getKind()); }

    /** 物种真实身位半径：首领 46、精英 34、普通怪 27。 */
    private static double radiusOf(EnemyKind kind) {
        return kind == EnemyKind.WATCHER ? 46.0 : kind.elite() ? 34.0 : 27.0;
    }
}
