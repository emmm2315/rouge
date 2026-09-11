package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

/** 敌人释放后的独立攻击对象；本体动作与飞行/命中特效不绑定。 */
public final class EnemyAttack {
    private double x;
    private double y;
    /** 速度不是 final：碰墙反弹时要把分量取反（见 {@link #reflect}）。 */
    private double velocityX;
    private double velocityY;
    private final double radius;
    private final WorldType world;
    private final EnemyKind source;
    private final String effectId;
    private final double angleRadians;
    /** 命中玩家一次造成的伤害：由出招的招式决定，和血量一起随难度缩放后固定下来。 */
    private final double damage;
    private final DamageType damageType;
    /** 生成这枚攻击的招式；旧包的基础构造器为 null。释放后的延迟行为（孢子落地绽放）靠它读取参数。 */
    private final EnemySkill skill;
    /** 命中去重标识：同一次施法的多段攻击共用或分段，见 {@link EnemySkill.HitPolicy}。0 表示不去重。 */
    private final long hitId;
    /** 还能反弹几次；0 表示撞墙即结束。 */
    private int bouncesRemaining;
    private double startupDelay;
    private double remainingLifetime;
    private boolean impactPlayed;
    /** 结束结算（延迟绽放、雾区脉冲、炮弹爆开）是否已经跑过：一枚弹体只允许收尾一次。 */
    private boolean resolved;

    public EnemyAttack(double x, double y, double velocityX, double velocityY, double radius,
                       WorldType world, EnemyKind source, double lifetime) {
        this(x, y, velocityX, velocityY, radius, world, source, lifetime, "", 0.0, 0.0,
                source == null ? 1.0 : source.attackDamage(), null, 0L);
    }

    public EnemyAttack(double x, double y, double velocityX, double velocityY, double radius,
                       WorldType world, EnemyKind source, double lifetime, String effectId,
                       double angleRadians, double startupDelay) {
        this(x, y, velocityX, velocityY, radius, world, source, lifetime, effectId, angleRadians, startupDelay,
                source == null ? 1.0 : source.attackDamage(), null, 0L);
    }

    public EnemyAttack(double x, double y, double velocityX, double velocityY, double radius,
                       WorldType world, EnemyKind source, double lifetime, String effectId,
                       double angleRadians, double startupDelay, double damage) {
        this(x, y, velocityX, velocityY, radius, world, source, lifetime, effectId, angleRadians, startupDelay,
                damage, null, 0L);
    }

    public EnemyAttack(double x, double y, double velocityX, double velocityY, double radius,
                       WorldType world, EnemyKind source, double lifetime, String effectId,
                       double angleRadians, double startupDelay, double damage,
                       EnemySkill skill, long hitId) {
        this(x, y, velocityX, velocityY, radius, world, source, lifetime, effectId, angleRadians, startupDelay,
                damage, skill, hitId, 0);
    }

    public EnemyAttack(double x, double y, double velocityX, double velocityY, double radius,
                       WorldType world, EnemyKind source, double lifetime, String effectId,
                       double angleRadians, double startupDelay, double damage,
                       EnemySkill skill, long hitId, int bounces) {
        this.x = x;
        this.y = y;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.radius = radius;
        this.world = world;
        this.source = source;
        this.remainingLifetime = lifetime;
        this.effectId = effectId;
        this.angleRadians = angleRadians;
        this.startupDelay = startupDelay;
        this.damage = Math.max(0.0, damage);
        this.damageType = DamageType.ofWorld(world);
        this.skill = skill;
        this.hitId = hitId;
        this.bouncesRemaining = Math.max(0, bounces);
    }

    public void update(double dt) {
        if (startupDelay > 0.0) { startupDelay -= dt; return; }
        x += velocityX * dt; y += velocityY * dt; remainingLifetime -= dt;
    }
    public void expire() { remainingLifetime = 0.0; }
    public boolean isExpired() { return remainingLifetime <= 0.0; }
    public double getX() { return x; }
    public double getY() { return y; }
    /** 弹体位置由反弹逻辑回退 / 改写（撞墙那一帧要把位置挪回墙上）。 */
    public void setPosition(double x, double y) { this.x = x; this.y = y; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public double getRadius() { return radius; }
    public WorldType getWorld() { return world; }
    public EnemyKind getSource() { return source; }
    public String getEffectId() { return effectId; }
    public double getAngleRadians() { return angleRadians; }
    public boolean isActive() { return startupDelay <= 0.0; }
    public boolean isMoving() { return Math.abs(velocityX) > 0.001 || Math.abs(velocityY) > 0.001; }

    /** 命中玩家一次造成的伤害（已含难度缩放）。 */
    public double getDamage() { return damage; }

    /** 该攻击的伤害类型，用于受击反馈与后续抗性判定。 */
    public DamageType getDamageType() { return damageType; }

    /** 生成这枚攻击的招式；由旧构造器创建时为 {@code null}。 */
    public EnemySkill getSkill() { return skill; }

    /** 命中去重标识；0 表示按“每枚弹体各算一次”处理。 */
    public long getHitId() { return hitId; }

    /** 还能反弹几次。 */
    public int getBouncesRemaining() { return bouncesRemaining; }

    /** 是否是会碰墙反弹的弹体。 */
    public boolean isBouncing() { return bouncesRemaining > 0; }

    /**
     * 碰墙反弹：把速度改成新的分量并消耗一次反弹机会。
     *
     * <p>反射方向由 {@link EnemySystem} 按“哪一侧还走得通”试出来（房间是轴对齐矩形，
     * 所以只需要在“反转 X”和“反转 Y”之间二选一），这里只负责写入结果。
     */
    public void reflect(double newVelocityX, double newVelocityY) {
        this.velocityX = newVelocityX;
        this.velocityY = newVelocityY;
        this.bouncesRemaining--;
    }

    /** 命中、撞墙或寿命结束时只允许生成一次独立消散/命中特效。 */
    public boolean consumeImpact() {
        if (impactPlayed) return false;
        impactPlayed = true;
        return true;
    }

    /** 收尾结算（延迟绽放 / 雾区脉冲 / 爆开）是否已经执行过。 */
    public boolean isResolved() { return resolved; }

    public void markResolved() { resolved = true; }
}
