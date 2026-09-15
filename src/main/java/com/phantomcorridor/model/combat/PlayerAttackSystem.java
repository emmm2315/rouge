package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.RoomNavigationSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家的攻击方案执行器：按当前世界与武器决定「这一轮打什么」，并管理飞行中的弹体。
 *
 * <p>对应《新增 15 件装备与攻击特效设计》§四：8 件武器不再是“攻击力 +1”，
 * 而是**替换整套攻击方案**（弹体数量、每发系数、穿透/弹射/爆裂、前后摇与间隔）。
 * 方案本身是不可变的 {@link AttackProfile}，这里只负责按方案出手、计时、以及维护
 * {@link PendingAttack} 这类“还没发生的一击”。
 *
 * <p>纯模型层：不引用 JavaFX，也不使用现实时间定时器——所有计时都由 {@code dt} 推进，
 * 所以暂停时自然冻结（见需求文档「暂停不推进特效或冷却」）。
 */
public final class PlayerAttackSystem {
    /** 同一轮攻击中每一件影界武器对应的一次近战窗口。 */
    public record MeleeStrike(double x, double y, double angleRadians, double arcDegrees, double range,
                              double coefficient, int attackId, double visualTime) { }

    private final List<Projectile> projectiles = new ArrayList<>();
    private double cooldownRemaining;
    private double meleeVisibleRemaining;
    private double meleeAngleRadians;
    private int meleeAttackId;
    /** 本轮影斩的扇形与距离（来自当时的攻击方案，供命中与渲染读取）。 */
    private double meleeArcDegrees = GameConfig.SHADOW_MELEE_ARC_DEGREES;
    private double meleeRange = GameConfig.SHADOW_MELEE_RANGE;
    /** 还没结算的延迟打击（重剑前摇、蚀界仪复斩、二连发的第二颗）。 */
    private final List<PendingAttack> pending = new ArrayList<>();
    /** 余震指环：当前世界里已经成功启动的攻击轮数（切界清零）。 */
    private int worldAttackCount;
    /** 影斩实际释放的次数（不含前摇中还没落下的那一刀）。 */
    private int meleeReleaseCount;
    /** 当前这一刀（或复斩）的伤害系数。 */
    private double meleeCoefficient = 1.0;
    private final List<MeleeWindow> meleeWindows = new ArrayList<>();

    private static final class MeleeWindow {
        final double x, y, angleRadians, arcDegrees, range, coefficient;
        final java.util.Set<Object> hitTargets = new java.util.HashSet<>();
        final int attackId;
        double remaining;
        MeleeWindow(double x, double y, double angleRadians, double arcDegrees, double range,
                    double coefficient, int attackId) {
            this.x = x;
            this.y = y;
            this.angleRadians = angleRadians;
            this.arcDegrees = arcDegrees;
            this.range = range;
            this.coefficient = coefficient;
            this.attackId = attackId;
            this.remaining = GameConfig.SHADOW_MELEE_VISIBLE_TIME;
        }
    }

    /** 当前生效的攻击方案；没有武器改变攻击方式时为基础方案。 */
    private AttackProfile currentProfile = AttackProfile.baseLight();
    private List<AttackProfile> currentProfiles = List.of(currentProfile);

    /**
     * 一件「稍后才会发生」的攻击：重剑的前摇、蚀界仪的复斩与第二颗光弹都用它。
     *
     * <p>设计文档要求这些延迟攻击**不跟随玩家**：释放位置与方向在登记时就锁死，
     * 所以这里存的是坐标与角度，而不是对玩家的引用。
     */
    private static final class PendingAttack {
        final double delay;
        final WorldType world;
        final AttackProfile profile;
        final double originX;
        final double originY;
        final double directionX;
        final double directionY;
        /** 还剩多久触发。 */
        double remaining;
        /** 这一批里第几个伤害包（用于取系数）。 */
        final int pelletIndex;
        final PendingKind kind;

        PendingAttack(PendingKind kind, double delay, WorldType world, AttackProfile profile,
                      double originX, double originY, double directionX, double directionY, int pelletIndex) {
            this.kind = kind;
            this.delay = delay;
            this.remaining = delay;
            this.world = world;
            this.profile = profile;
            this.originX = originX;
            this.originY = originY;
            this.directionX = directionX;
            this.directionY = directionY;
            this.pelletIndex = pelletIndex;
        }
    }

    /** 延迟攻击的种类：决定到点时是补一颗弹体还是补一次近战结算。 */
    private enum PendingKind { PROJECTILE, MELEE }

    public void reset() {
        projectiles.clear();
        pending.clear();
        cooldownRemaining = 0.0;
        meleeVisibleRemaining = 0.0;
        meleeAngleRadians = 0.0;
        meleeAttackId = 0;
        worldAttackCount = 0;
        meleeReleaseCount = 0;
        meleeArcDegrees = GameConfig.SHADOW_MELEE_ARC_DEGREES;
        meleeRange = GameConfig.SHADOW_MELEE_RANGE;
        meleeCoefficient = 1.0;
        meleeWindows.clear();
        currentProfile = AttackProfile.baseLight();
        currentProfiles = List.of(currentProfile);
    }

    public void update(double dt) {
        update(dt, null);
    }

    public void update(double dt, RoomNavigationSystem navigation) {
        cooldownRemaining = Math.max(0.0, cooldownRemaining - dt);
        meleeVisibleRemaining = Math.max(0.0, meleeVisibleRemaining - dt);
        for (MeleeWindow window : meleeWindows) window.remaining = Math.max(0.0, window.remaining - Math.max(0.0, dt));
        meleeWindows.removeIf(window -> window.remaining <= 0.0);
        projectiles.forEach(projectile -> {
            double oldX = projectile.getX();
            double oldY = projectile.getY();
            projectile.update(dt);
            projectile.turnAroundIfDue(GameConfig.RETURNING_FANG_FLIGHT_TIME);
            if (navigation != null && (!navigation.canProjectileOccupy(
                    projectile.getX(), projectile.getY(), projectile.getRadius(), projectile.getWorld())
                    || !navigation.isSegmentClear(oldX, oldY, projectile.getX(), projectile.getY(),
                    projectile.getRadius(), projectile.getWorld()))) {
                // 归影双刃的特殊规则：出程遇墙从墙前折返，返程遇墙才消失。
                if (projectile.getBehaviour() == Projectile.Behaviour.RETURN && !projectile.isReturning()) {
                    projectile.reverseDirection();
                } else {
                    projectile.expire();
                }
            }
        });
        projectiles.removeIf(Projectile::isExpired);
        advancePending(dt);
    }

    /**
     * 推进延迟攻击。
     *
     * <p>到点时才真正生成弹体或开启一次近战窗口——「不要用动画播完决定扣血」，
     * 也不让延迟攻击在中途跟随玩家：位置在 {@link #schedule} 时就锁定了。
     */
    private void advancePending(double dt) {
        if (pending.isEmpty()) return;
        List<PendingAttack> due = new ArrayList<>();
        for (PendingAttack attack : pending) {
            attack.remaining -= dt;
            if (attack.remaining <= 0.0) due.add(attack);
        }
        if (due.isEmpty()) return;
        pending.removeAll(due);
        for (PendingAttack attack : due) {
            if (attack.kind == PendingKind.MELEE) {
                openMelee(attack.profile, attack.originX, attack.originY, attack.directionX, attack.directionY);
            } else {
                spawnPellets(attack.profile, attack.originX, attack.originY,
                        attack.directionX, attack.directionY, attack.pelletIndex, 1);
            }
        }
    }

    /** 清空全部临时攻击：切界、离房、死亡、换武器时调用。 */
    public void cancelForWorldShift() {
        clearTransientAttacks();
        cooldownRemaining = 0.0;
    }

    public void clearTransientAttacks() {
        projectiles.clear();
        pending.clear();
        meleeWindows.clear();
        meleeVisibleRemaining = 0.0;
        // 余震指环的计数在切界/离房时清零：设计文档明确「不能反复穿戴预存强化」。
        worldAttackCount = 0;
    }

    /**
     * 攻击方式的稳定顺序。它不再用于“只选一件”，而是用于确定多件装备同时出手时的
     * 视觉/命中顺序；越靠前的方案越先生成。
     */
    private static final List<EquipmentType> WEAPON_PRIORITY = List.of(
            EquipmentType.SUNLANCE,
            EquipmentType.SOLAR_BURST_STAFF,
            EquipmentType.MIRROR_ORB,
            EquipmentType.PRISM_FAN_WAND,
            EquipmentType.NIGHTFALL_GREATSWORD,
            EquipmentType.RETURNING_FANG,
            EquipmentType.CRESCENT_REAPER,
            EquipmentType.ECLIPSE_RELAY);

    /** 当前世界下真正生效的第一件武器；没有则返回 {@code null}（兼容旧调用方）。 */
    public static EquipmentType activeWeapon(Player player) {
        return activeWeapons(player).stream().findFirst().orElse(null);
    }

    /**
     * 当前世界下所有改变攻击方式的装备，按稳定优先级返回。
     * 同名装备也会保留多次，意味着玩家可以叠加两套相同攻击方式。
     */
    public static List<EquipmentType> activeWeapons(Player player) {
        WorldType world = player.getCurrentWorld();
        List<EquipmentType> result = new ArrayList<>();
        for (EquipmentType weapon : WEAPON_PRIORITY) {
            for (EquipmentType equipped : player.getEquipment()) {
                if (equipped == weapon && AttackProfile.changesAttack(weapon, world)) result.add(weapon);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 一次成功启动的攻击所采用的方案（供测试与渲染查询）。 */
    public AttackProfile getCurrentProfile() { return currentProfile; }
    /** 本轮同时启用的全部攻击方案（第一项与旧 getCurrentProfile 保持一致）。 */
    public List<AttackProfile> getCurrentProfiles() { return currentProfiles; }

    /** 归影双刃的攻击方案：往返影刃，间隔 max(1.25, 0.50 秒)。 */
    private static AttackProfile returningFang() {
        return new AttackProfile(WorldType.SHADOW, AttackProfile.ProjectileShape.FANG,
                1, 0.0, new double[]{GameConfig.RETURNING_FANG_COEFFICIENT}, 0,
                1.0, 1.0, 1.0, 1.25, 0.50,
                0.0, 1.0, 0.0, 0.0);
    }

    /** 一次攻击解析出来的可执行方案：当前世界生效的全部攻击方式，以及它们各自对应的武器。 */
    private record ResolvedAttack(List<AttackProfile> profiles, List<EquipmentType> weapons) { }

    /**
     * 把「装备栏 + 当前世界」翻译成一组可执行的攻击方案。
     *
     * <p>普攻与蓄力发射共用这一段：两者只有「伤害倍率、弹体尺度、是否击落敌弹」不同，
     * 攻击方式本身必须完全一致，否则蓄力就会绕开玩家辛苦攒的武器形态。
     */
    private ResolvedAttack resolveAttack(Player player) {
        WorldType world = player.getCurrentWorld();
        List<EquipmentType> weapons = activeWeapons(player);
        if (weapons.isEmpty()) weapons = Collections.singletonList(null);
        List<AttackProfile> profiles = new ArrayList<>();
        List<EquipmentType> resolvedWeapons = new ArrayList<>();
        for (EquipmentType weapon : weapons) {
            AttackProfile profile = weapon == EquipmentType.RETURNING_FANG && world == WorldType.SHADOW
                    ? returningFang() : weapon == null
                    ? (world == WorldType.LIGHT ? AttackProfile.baseLight() : AttackProfile.baseShadow())
                    : AttackProfile.forWeapon(weapon, world);
            if (profile != null) {
                // 晨曦法杖不是“改变攻击方式”的武器：没有其它形态武器时，
                // 它只给基础光弹增加速度。同名装备按件数叠加；专属武器的速度
                // 仍以各自方案为准，避免把法杖加成错误地套到长杖/光核上。
                if (weapon == null && world == WorldType.LIGHT) {
                    profile = profile.withSpeedScale(player.lightProjectileSpeedMultiplier());
                }
                profiles.add(profile);
                resolvedWeapons.add(weapon);
            }
        }
        // weapons 里允许出现 null（表示"这一界没有改变攻击方式的武器，用基础方案"），
        // 所以这里不能用 List.copyOf——它会拒绝 null 元素。
        return new ResolvedAttack(List.copyOf(profiles), Collections.unmodifiableList(resolvedWeapons));
    }

    /**
     * 光形态蓄力发射。
     *
     * <p>沿用当前光界生效的整套攻击方案（三叉杖的三向、贯日长杖的穿透、炽核权杖的爆裂……），
     * 只把每个伤害包的系数整体乘上蓄力倍率（1 → 5）。弹体同时被放大并带上
     * 「飞行中击落敌方弹幕」的能力——这就是"蓄满可以抵消敌人弹幕"。
     *
     * <p>这正是「装备适配」的落点：蓄力不是另一套写死的攻击，而是**当前这一套装备的强化版**。
     *
     * <p>蓄满（{@code fullCharge}）时再叠两条招牌效果：**无限穿透**与**命中叠满灼痕**——
     * 于是"光界蓄满 → 切影界引爆"成为一条成立的连招。
     *
     * @param multiplier 蓄力倍率，由 {@link Player#consumeCharge()} 结算后给出
     * @param fullCharge 是否蓄满
     * @return 是否真的打出了这一发
     */
    public boolean fireChargedShot(Player player, double targetX, double targetY,
                                   double multiplier, boolean fullCharge) {
        if (player.getHp() <= 0 || player.isCastingAbility() || player.isDashing()) return false;
        if (player.getCurrentWorld() != WorldType.LIGHT) return false;
        ResolvedAttack resolved = resolveAttack(player);
        if (resolved.profiles().isEmpty()) return false;
        double scale = Math.max(1.0, multiplier);
        List<AttackProfile> profiles = resolved.profiles().stream()
                .map(profile -> profile.withCoefficientScale(scale)).toList();
        double[] aim = aimDirection(player, targetX, targetY);
        currentProfile = profiles.getFirst();
        currentProfiles = List.copyOf(profiles);
        player.startAttackAnimation(aim[0], aim[1], 0.0);
        worldAttackCount++;
        meleeAngleRadians = Math.atan2(aim[1], aim[0]);
        for (AttackProfile profile : profiles) {
            spawnPellets(profile, player.getX(), player.getY(), aim[0], aim[1], 0, profile.pellets(),
                    true, fullCharge);
        }
        // 打完这一发依然要等这轮武器自己的攻击间隔：蓄力不是"额外白送一次普攻"。
        cooldownRemaining = profiles.stream().mapToDouble(p -> cooldownFor(player, p)).max().orElse(0.0);
        return true;
    }

    public boolean tryAttack(Player player, double targetX, double targetY) {
        if (player.getHp() <= 0 || player.isCastingAbility() || player.isDashing() || cooldownRemaining > 0.0) {
            return false;
        }
        double[] aim = aimDirection(player, targetX, targetY);
        double unitX = aim[0];
        double unitY = aim[1];
        WorldType world = player.getCurrentWorld();
        ResolvedAttack resolved = resolveAttack(player);
        if (resolved.profiles().isEmpty()) return false;
        List<EquipmentType> resolvedWeapons = resolved.weapons();
        List<AttackProfile> profiles = new ArrayList<>(resolved.profiles());
        // 格挡成功攒下的那一次强化：整套暗影方案整体 ×10，取用一次就消失。
        // 放在这里而不是渲染层，是因为"下一次暗影普攻"必须和真正打出去的那一击绑定——
        // 空按（冷却中、施法中）不该白白吃掉这次强化。
        if (world == WorldType.SHADOW && player.hasBlockEmpower()) {
            profiles.replaceAll(profile -> profile.withCoefficientScale(
                    GameConfig.SHADOW_BLOCK_EMPOWER_MULTIPLIER));
            player.consumeBlockEmpower();
        }
        AttackProfile profile = profiles.getFirst();
        currentProfile = profile;
        currentProfiles = List.copyOf(profiles);

        // Basic attacks are the sustainable fallback; the blue resource is reserved for skills.
        double maxWindup = profiles.stream().mapToDouble(AttackProfile::windup).max().orElse(0.0);
        player.startAttackAnimation(unitX, unitY, maxWindup);
        // 计数只在**真的启动了一轮攻击**之后才推进：蓝条不够、没有可用方案时不算一轮，
        // 否则余震指环会被空按刷出来。
        worldAttackCount++;
        meleeAngleRadians = Math.atan2(unitY, unitX);
        if (profiles.stream().anyMatch(p -> p.world() == WorldType.SHADOW && !p.hasWindup())) {
            // 夜行披风：影界攻击「实际释放」时给短时加速。重剑有前摇，所以延迟到
            // openMelee 那一刻才触发（见 advancePending），前摇本身不给加速。
            player.onShadowAttackReleased();
        }

        for (int i = 0; i < profiles.size(); i++) {
            AttackProfile selected = profiles.get(i);
            EquipmentType weapon = resolvedWeapons.get(i);
            if (weapon == EquipmentType.RETURNING_FANG && world == WorldType.SHADOW) {
                spawnReturningFang(player, unitX, unitY);
            } else if (selected.world() == WorldType.LIGHT) {
                spawnPellets(selected, player.getX(), player.getY(), unitX, unitY, 0, selected.pellets());
            } else {
                if (selected.hasWindup()) {
                    schedule(PendingKind.MELEE, selected.windup(), selected,
                            player.getX(), player.getY(), unitX, unitY, 0);
                } else {
                    openMelee(selected, player.getX(), player.getY(), unitX, unitY);
                }
                // 蚀界仪在影界：先斩 0.75，再在 0.22 秒后原方向复斩 0.45。
                if (selected.pelletInterval() > 0.0) {
                    schedule(PendingKind.MELEE, selected.pelletInterval(), AttackProfile.eclipseRelayReslash(),
                            player.getX(), player.getY(), unitX, unitY, 1);
                }
            }
        }
        cooldownRemaining = profiles.stream().mapToDouble(p -> cooldownFor(player, p)).max().orElse(0.0);
        return true;
    }

    /**
     * 归影双刃：沿瞄准方向飞出的回旋影刃，最远 1.60 倍影斩距离，出程约 0.22 秒。
     *
     * <p>到达最远点后沿原路返回到出手位置（由 {@link EnemySystem} 在出程结束时调
     * {@link Projectile#reverseDirection()}），出程与返程各结算一次 0.55 倍影伤。
     * 影刃不会被敌人挡住，只有墙能让它折返或消失。
     */
    private void spawnReturningFang(Player player, double unitX, double unitY) {
        double travel = GameConfig.SHADOW_MELEE_RANGE * 1.60;
        double speed = travel / GameConfig.RETURNING_FANG_FLIGHT_TIME;
        double offset = GameConfig.PLAYER_RADIUS + 6.0;
        Projectile fang = new Projectile(
                player.getX() + unitX * offset, player.getY() + unitY * offset,
                unitX * speed, unitY * speed,
                9.0, WorldType.SHADOW, GameConfig.RETURNING_FANG_FLIGHT_TIME * 2.0,
                AttackProfile.ProjectileShape.FANG, GameConfig.RETURNING_FANG_COEFFICIENT,
                Projectile.Behaviour.RETURN);
        fang.setHitCoefficients(new double[]{
                GameConfig.RETURNING_FANG_COEFFICIENT, GameConfig.RETURNING_FANG_COEFFICIENT});
        projectiles.add(fang);
    }

    /**
     * 余震指环用的计数：**当前世界**里成功启动的攻击轮数。
     *
     * <p>设计文档要求「光界、影界分别计数；连续处于同一世界时，每第四轮成功启动的攻击标记为
     * 强化攻击」。切界会清零（见 {@link #clearTransientAttacks()}），所以这里只需要一个
     * 「本轮是第几次」的序号，强化判定由命中结算按 {@code count % 4 == 0} 读。
     */
    public int getWorldAttackCount() { return worldAttackCount; }

    /** 这一轮攻击是否是余震指环的强化轮（第 4、8、12……轮）。 */
    public boolean isResonanceRound() {
        return worldAttackCount > 0 && worldAttackCount % GameConfig.RESONANCE_RING_INTERVAL == 0;
    }

    /** 最终攻击间隔 = 基础间隔 × 武器倍率 × 饰品倍率 × 临时效果倍率，并不低于方案的绝对下限。 */
    private double cooldownFor(Player player, AttackProfile profile) {
        double base = profile.world() == WorldType.LIGHT
                ? GameConfig.LIGHT_ATTACK_COOLDOWN : GameConfig.SHADOW_ATTACK_COOLDOWN;
        double cooldown = base * profile.cooldownScale();
        // 饰品（凝光透镜 / 相位陀螺）的间隔倍率在这里统一相乘。
        cooldown *= player.getAttackCooldownMultiplier(profile.world());
        return Math.max(cooldown, profile.minCooldown());
    }

    private static double[] aimDirection(Player player, double targetX, double targetY) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getY();
        double length = Math.hypot(dx, dy);
        if (length < 0.0001) return new double[]{1.0, 0.0};
        return new double[]{dx / length, dy / length};
    }

    /**
     * 按方案生成伤害包。
     *
     * <p>散射按 {@code spreadDegrees} 均匀展开（三发是 -14°/0°/+14°）；
     * 多包且有间隔的（蚀界仪二连发）只立即发出第一颗，其余登记成延迟攻击。
     */
    private void spawnPellets(AttackProfile profile, double originX, double originY,
                              double unitX, double unitY, int startIndex, int count) {
        spawnPellets(profile, originX, originY, unitX, unitY, startIndex, count, false, false);
    }

    /**
     * 按方案生成伤害包。
     *
     * @param charged    是否是蓄力光弹：弹体更大、且会在飞行中击落敌方弹幕
     * @param fullCharge 是否蓄满：无限穿透 + 命中给敌人叠满灼痕
     */
    private void spawnPellets(AttackProfile profile, double originX, double originY,
                              double unitX, double unitY, int startIndex, int count,
                              boolean charged, boolean fullCharge) {
        if (profile.world() != WorldType.LIGHT) return;
        double offset = GameConfig.PLAYER_RADIUS + GameConfig.LIGHT_PROJECTILE_RADIUS + 3.0;
        double speed = GameConfig.LIGHT_PROJECTILE_SPEED * profile.speedScale();
        double lifetime = GameConfig.LIGHT_PROJECTILE_LIFETIME * profile.lifetimeScale();
        double radiusScale = charged ? GameConfig.LIGHT_CHARGE_RADIUS_SCALE : 1.0;
        double radius = GameConfig.LIGHT_PROJECTILE_RADIUS * profile.radiusScale() * radiusScale;
        // 蓄满的招牌是"无限穿透"：所有按方案发出去的光弹都吃这一条。
        // 唯一例外是炽核权杖——它的身份是爆裂（光核本身没有接触伤害），
        // 强行改成穿透会让它退化成一颗普通子弹，所以保留它的爆裂行为。
        boolean infinitePierce = charged && fullCharge
                && profile.shape() != AttackProfile.ProjectileShape.CORE;

        for (int i = 0; i < count; i++) {
            int index = startIndex + i;
            // 多包且有间隔的方案（蚀界仪二连发）：第一颗立即出手，后面的交给延迟通道
            // ——「间隔 0.10 秒」是设计文档的硬要求，不能一次全喷出去。
            // 蓄力弹例外：它是一发整体打出去的"重击"，拆分反而读不出蓄力的分量，所以一次全放。
            if (index > startIndex && profile.pelletInterval() > 0.0 && !charged) {
                schedule(PendingKind.PROJECTILE, profile.pelletInterval() * (index - startIndex), profile,
                        originX, originY, unitX, unitY, index);
                continue;
            }
            double angle = spreadAngle(profile, index);
            double rotatedX = unitX * Math.cos(angle) - unitY * Math.sin(angle);
            double rotatedY = unitX * Math.sin(angle) + unitY * Math.cos(angle);
            Projectile projectile = new Projectile(
                    originX + rotatedX * offset, originY + rotatedY * offset,
                    rotatedX * speed, rotatedY * speed,
                    radius, WorldType.LIGHT, lifetime,
                    profile.shape(), profile.coefficient(index),
                    infinitePierce ? Projectile.Behaviour.PIERCE : behaviourFor(profile));
            configure(projectile, profile);
            // 穿透次数要写在 configure 之后：configure 会按方案里的 extraHits 覆盖它。
            if (infinitePierce) {
                projectile.setExtraHits(GameConfig.LIGHT_CHARGE_PIERCE_MAX);
                projectile.setScorchStacks(GameConfig.LIGHT_CHARGE_SCORCH_STACKS);
            }
            projectile.setClearsEnemyBullets(charged);
            projectiles.add(projectile);
        }
    }

    /** 第 {@code index} 发相对瞄准方向的夹角：偶数发以中心对称展开。 */
    private static double spreadAngle(AttackProfile profile, int index) {
        if (profile.pellets() <= 1 || profile.spreadDegrees() <= 0.0) return 0.0;
        double center = (profile.pellets() - 1) / 2.0;
        return Math.toRadians((index - center) * profile.spreadDegrees());
    }

    private static Projectile.Behaviour behaviourFor(AttackProfile profile) {
        return switch (profile.shape()) {
            case LANCE -> profile.extraHits() > 0 ? Projectile.Behaviour.PIERCE : Projectile.Behaviour.VANILLA;
            case CORE -> Projectile.Behaviour.BURST;
            case ORB -> profile.damageCoefficients().length > 1 && profile.pellets() == 1
                    ? Projectile.Behaviour.BOUNCE : Projectile.Behaviour.VANILLA;
            case FANG -> Projectile.Behaviour.VANILLA;
        };
    }

    private static void configure(Projectile projectile, AttackProfile profile) {
        projectile.setExtraHits(profile.extraHits());
        if (projectile.getBehaviour() == Projectile.Behaviour.BOUNCE
                || projectile.getBehaviour() == Projectile.Behaviour.PIERCE) {
            // 贯穿与弹射都是“同一个弹体按顺序换系数”，所以系数序列交给弹体自己维护。
            projectile.setHitCoefficients(profile.damageCoefficients());
        }
        if (projectile.getBehaviour() == Projectile.Behaviour.BOUNCE) {
            projectile.setBounces(profile.damageCoefficients().length - 1);
        }
        if (projectile.getBehaviour() == Projectile.Behaviour.BURST) {
            projectile.setEffectRadius(GameConfig.SOLAR_BURST_RADIUS);
            // 光核的接触伤害是系数 0（damageCoefficients[0]），真正的伤害在爆炸那一份
            // （damageCoefficients[1]）。爆炸结算读的是这个字段，而不是接触伤害系数。
            projectile.setBurstCoefficient(profile.coefficient(1));
        }
    }

    private void schedule(PendingKind kind, double delay, AttackProfile profile,
                          double originX, double originY, double unitX, double unitY, int pelletIndex) {
        pending.add(new PendingAttack(kind, delay, profile.world(), profile,
                originX, originY, unitX, unitY, pelletIndex));
    }

    /**
     * 开启一次近战窗口：记录当轮扇形与距离，命中结算按它判定。
     *
     * <p>这也是「影界攻击实际释放」的时刻——夜行披风的加速在这里触发，
     * 所以重剑的 0.18 秒前摇期间不会提前给到加速（设计文档 §五-10）。
     */
    private void openMelee(AttackProfile profile, double x, double y, double unitX, double unitY) {
        meleeVisibleRemaining = GameConfig.SHADOW_MELEE_VISIBLE_TIME;
        meleeAngleRadians = Math.atan2(unitY, unitX);
        meleeArcDegrees = profile.meleeArcDegrees();
        meleeRange = GameConfig.SHADOW_MELEE_RANGE * profile.meleeRangeScale();
        // 系数必须跟着这一刀走，不能读“最后一次出手的方案”：蚀界仪的复斩是另一份方案，
        // 读 currentProfile 会把复斩的 0.45 算成主斩的 0.75。
        meleeCoefficient = profile.coefficient(0);
        meleeAttackId++;
        meleeReleaseCount++;
        meleeWindows.add(new MeleeWindow(x, y, meleeAngleRadians, meleeArcDegrees, meleeRange,
                meleeCoefficient, meleeAttackId));
    }

    /** 当前影斩的扇形角度（度）。 */
    public double getMeleeArcDegrees() { return meleeArcDegrees; }

    /**
     * 影斩实际释放的次数。
     *
     * <p>「释放」= 前摇走完、这一刀真的落下。{@code GameSession} 每帧与上一帧比较这个数，
     * 变大了就说明刚挥出一刀，于是触发夜行披风的加速——重剑前摇期间不会提前给。
     */
    public int getMeleeReleaseCount() { return meleeReleaseCount; }

    /** 当前影斩的距离（像素）。 */
    public double getMeleeRange() { return meleeRange; }

    /** 当前影斩的伤害系数（跟着挥出的那一刀走，复斩不会读成主斩的系数）。 */
    public double getMeleeCoefficient() { return meleeCoefficient; }

    public List<Projectile> getProjectiles() {
        return Collections.unmodifiableList(projectiles);
    }

    /** 还没结算的延迟攻击数量（测试用来确认切界/离房真的清干净了）。 */
    public int getPendingCount() { return pending.size(); }

    public boolean isMeleeVisible() { return !meleeWindows.isEmpty() || meleeVisibleRemaining > 0.0; }
    public double getMeleeVisualTime() {
        if (!meleeWindows.isEmpty()) {
            return GameConfig.SHADOW_MELEE_VISIBLE_TIME - meleeWindows.get(meleeWindows.size() - 1).remaining;
        }
        return GameConfig.SHADOW_MELEE_VISIBLE_TIME - meleeVisibleRemaining;
    }
    public double getMeleeAngleRadians() { return meleeAngleRadians; }
    public int getMeleeAttackId() { return meleeAttackId; }
    /** 返回当前所有仍在显示/结算的影界近战窗口，供命中与渲染同时支持多种武器。 */
    public List<MeleeStrike> getMeleeStrikes() {
        List<MeleeStrike> result = new ArrayList<>(meleeWindows.size());
        for (MeleeWindow window : meleeWindows) {
            result.add(new MeleeStrike(window.x, window.y, window.angleRadians, window.arcDegrees, window.range,
                    window.coefficient, window.attackId,
                    GameConfig.SHADOW_MELEE_VISIBLE_TIME - window.remaining));
        }
        return Collections.unmodifiableList(result);
    }
    public double getCooldownRemaining() { return cooldownRemaining; }

    /** 命中记录随窗口释放；多个同时存在的窗口不能覆盖彼此的记录。 */
    public boolean registerMeleeHit(int attackId, Object target) {
        for (MeleeWindow window : meleeWindows) {
            if (window.attackId == attackId) return window.hitTargets.add(target);
        }
        return false;
    }
}
