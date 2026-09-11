package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;

import java.util.HashSet;
import java.util.Set;

/**
 * 玩家打出的一个伤害包。
 *
 * <p>从「一颗子弹固定一个伤害」升级成「一颗弹体带自己的系数、穿透次数与特殊行为」：
 * 贯日长矛要按 1.00/0.75/0.50 依次穿透三个敌人，折镜法球要在命中点重新选目标，
 * 炽核光核要慢速飞行再爆裂——这些都必须挂在弹体自己身上，靠 {@code PlayerAttackSystem}
 * 的全局状态记不住，也不该让命中结算去反查武器。
 *
 * <p>纯模型：不引用 JavaFX。
 */
public final class Projectile {

    /** 弹体行为：命中时怎么处理自己。 */
    public enum Behaviour {
        /** 命中首个目标即消失（基础光弹、三叉杖每一发）。 */
        VANILLA,
        /** 穿透：按 {@link #extraHitsRemaining} 继续飞，每穿透一个换下一个伤害系数。 */
        PIERCE,
        /** 弹射：命中后在命中点附近重新选一个未命中过的敌人继续飞。 */
        BOUNCE,
        /** 爆裂：命中或耗尽时在原地炸一次范围伤害，自身不结算接触伤害。 */
        BURST,
        /** 往返：飞到最远点后原路返回，出程与返程各结算一次。 */
        RETURN
    }

    private double x;
    private double y;
    private double velocityX;
    private double velocityY;
    private final double radius;
    private final WorldType world;
    private double remainingLifetime;
    private final AttackProfile.ProjectileShape shape;

    /** 本次命中应当结算的系数（相对该界基础伤害）。 */
    private double damageCoefficient;
    /** 逐次命中使用的系数序列：贯穿光矛是 1.00/0.75/0.50，弹射是三段递减。 */
    private double[] hitCoefficients = {1.0};
    /** 已经命中过并结算过的伤害包序号：穿透靠它换系数，弹射靠它换目标。 */
    private int hitIndex;

    private Behaviour behaviour = Behaviour.VANILLA;
    /** 还能额外命中几个敌人（不含已经命中的那个）。 */
    private int extraHitsRemaining;
    /** 已经命中过的敌人，避免穿透/弹射对同一目标重复结算。 */
    private final Set<Object> hitTargets = new HashSet<>();
    /** 弹射最多还能弹几次。 */
    private int bouncesRemaining;
    /** 弹射/爆裂的落点半径（像素）。 */
    private double effectRadius;
    /** 归影双刃：到达最远点后开始返程。 */
    private boolean returning;

    public Projectile(double x, double y, double velocityX, double velocityY,
                      double radius, WorldType world, double lifetime) {
        this(x, y, velocityX, velocityY, radius, world, lifetime,
                AttackProfile.ProjectileShape.ORB, 1.0, Behaviour.VANILLA);
    }

    public Projectile(double x, double y, double velocityX, double velocityY, double radius,
                      WorldType world, double lifetime, AttackProfile.ProjectileShape shape,
                      double damageCoefficient, Behaviour behaviour) {
        this.x = x;
        this.y = y;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.radius = radius;
        this.world = world;
        this.remainingLifetime = lifetime;
        this.shape = shape;
        this.damageCoefficient = damageCoefficient;
        this.behaviour = behaviour;
    }

    public void update(double dt) {
        x += velocityX * dt;
        y += velocityY * dt;
        remainingLifetime -= dt;
    }

    /**
     * 归影双刃的折返时刻判定。
     *
     * <p>发射时给的寿命正好是「出程 + 返程」，所以剩余寿命掉到一半以下就该掉头了。
     * 判定放在计时上而不是距离上：影刃碰到墙会提前折返，那种情况下寿命同样在走，
     * 用它当唯一依据就不会出现“折返过一次又被距离条件再折一次”。
     *
     * @param flightTime 单程时间（秒）
     * @return 本次是否发生了折返
     */
    public boolean turnAroundIfDue(double flightTime) {
        if (behaviour != Behaviour.RETURN || returning) return false;
        if (remainingLifetime > flightTime) return false;
        return reverseDirection();
    }

    public void expire() { remainingLifetime = 0.0; }

    /** 把弹体放到指定位置并改向（弹射用）。 */
    public void redirect(double newX, double newY, double directionX, double directionY,
                         double speed, double newLifetime) {
        this.x = newX;
        this.y = newY;
        double length = Math.hypot(directionX, directionY);
        if (length < 1e-9) { expire(); return; }
        this.velocityX = directionX / length * speed;
        this.velocityY = directionY / length * speed;
        this.remainingLifetime = Math.max(this.remainingLifetime, newLifetime);
    }

    /** 归影双刃返程：把速度反向。返回 false 表示没有可返程的速度。 */
    public boolean reverseDirection() {
        double speed = Math.hypot(velocityX, velocityY);
        if (speed < 1e-9) return false;
        velocityX = -velocityX;
        velocityY = -velocityY;
        returning = true;
        return true;
    }

    /**
     * 记一次命中并推进状态。
     *
     * @param target 被命中的敌人（用于穿透/弹射去重）
     * @return {@code true} 表示弹体应当继续存在（穿透或弹射），{@code false} 表示该消失了
     */
    public boolean registerHit(Object target) {
        hitTargets.add(target);
        hitIndex++;
        if (behaviour == Behaviour.PIERCE && extraHitsRemaining > 0) {
            extraHitsRemaining--;
            damageCoefficient = hitCoefficients[Math.min(hitIndex, hitCoefficients.length - 1)];
            return true;
        }
        if (behaviour == Behaviour.BOUNCE && bouncesRemaining > 0) {
            // 弹射次数在这里就扣掉：调用方拿到 true 之后一定会去接下一个目标，
            // 如果把扣减留给调用方，一旦那个目标不存在就会漏扣，链条能无限弹下去。
            bouncesRemaining--;
            damageCoefficient = hitCoefficients[Math.min(hitIndex, hitCoefficients.length - 1)];
            return true;
        }
        if (behaviour == Behaviour.RETURN) return true;
        return false;
    }

    /** 逐次命中使用的系数序列（贯穿、弹射用）。 */
    public void setHitCoefficients(double[] coefficients) {
        this.hitCoefficients = coefficients.clone();
        this.damageCoefficient = hitCoefficients[0];
    }

    /** 这个目标是否已经吃过本弹体的一次结算。 */
    public boolean hasHit(Object target) { return hitTargets.contains(target); }

    /** 弹射是否还有次数（只用于判断，扣减在 {@link #registerHit} 里完成）。 */
    public boolean canBounce() { return behaviour == Behaviour.BOUNCE && bouncesRemaining > 0; }

    public void setExtraHits(int extraHits) { this.extraHitsRemaining = Math.max(0, extraHits); }
    public void setBounces(int bounces) { this.bouncesRemaining = Math.max(0, bounces); }
    public void setEffectRadius(double effectRadius) { this.effectRadius = effectRadius; }
    public double getEffectRadius() { return effectRadius; }
    public void setDamageCoefficient(double coefficient) { this.damageCoefficient = coefficient; }
    public double getDamageCoefficient() { return damageCoefficient; }
    public int getHitIndex() { return hitIndex; }
    public Behaviour getBehaviour() { return behaviour; }
    public AttackProfile.ProjectileShape getShape() { return shape; }
    public boolean isReturning() { return returning; }
    public void setReturning(boolean returning) { this.returning = returning; }

    public boolean isExpired() { return remainingLifetime <= 0.0; }
    public double getRemainingLifetime() { return remainingLifetime; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getRadius() { return radius; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public WorldType getWorld() { return world; }
}
