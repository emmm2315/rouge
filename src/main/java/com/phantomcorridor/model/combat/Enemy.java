package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.RoomNavigationSystem;

/**
 * 第 6 天的初版敌人模型，已被 {@link com.phantomcorridor.model.entity.Enemy} 取代。
 *
 * <p><b>当前没有任何生产代码引用本类</b>：敌人现在由 {@code EnemySystem} +
 * {@code model.entity.Enemy} 驱动，只有测试在用它自己的 {@link EnemyProjectileSystem} 回路。
 *
 * <p>它 {@code release()} 里写死的 {@code 3.0} 伤害属于旧刻度，**不要**照着它改数值——
 * 现行伤害一律配置在 {@link EnemySkill#damage()} 上，命中结算见
 * {@code EnemySystem.resolvePlayerHit}。保留本文件只是为了对照旧实现。
 */
public final class Enemy {
    public enum State { IDLE, CHASE, WINDUP, RECOVERY, HURT, DEAD }
    private final EnemyType type; private final WorldType world; private double x, y; private int hp;
    private State state = State.IDLE; private double stateTime; private double attackCooldown; private double facingX = 1, facingY;
    private double attackEffectRemaining;
    public Enemy(EnemyType type, WorldType world, double x, double y) { this.type=type; this.world=world; this.x=x; this.y=y; this.hp=type.maxHp(); }
    public void update(double dt, Player player, RoomNavigationSystem navigation, EnemyProjectileSystem projectiles) {
        attackEffectRemaining = Math.max(0, attackEffectRemaining - dt);
        if (state == State.DEAD || player.getCurrentWorld() != world) return;
        stateTime += dt; attackCooldown = Math.max(0, attackCooldown - dt);
        double dx=player.getX()-x, dy=player.getY()-y, distance=Math.hypot(dx,dy);
        if (distance > 0.01) { facingX=dx/distance; facingY=dy/distance; }
        if (state == State.WINDUP) { if (stateTime >= 0.65) { release(projectiles); state=State.RECOVERY; stateTime=0; } return; }
        if (state == State.RECOVERY) { if (stateTime >= 0.8) { state=State.CHASE; stateTime=0; } return; }
        if (distance > 110 && type.speedFactor() > 0) {
            double step = GameConfig.PLAYER_BASE_SPEED * type.speedFactor() * dt;
            double nx=x+facingX*step, ny=y+facingY*step;
            if (navigation.canOccupy(nx, ny, 18, world)) { x=nx; y=ny; }
            state=State.CHASE;
        }
        if (attackCooldown <= 0 && distance < 520) { state=State.WINDUP; stateTime=0; attackCooldown=2.0; }
    }
    // 参数 player 未被使用（IDE 的 Unused parameter 检查会报）：弹体朝自身朝向飞，不需要玩家位置。
    // 按注释保留旧签名备查。
    private void release(/* Player player, */ EnemyProjectileSystem projectiles) {
        double speed = GameConfig.LIGHT_PROJECTILE_SPEED * 0.65;
        double radius = type == EnemyType.WATCHER ? 12 : type == EnemyType.GOLEM ? 10 : 7;
        projectiles.add(new EnemyProjectile(x, y, facingX*speed, facingY*speed, radius, world, 3.0, true));
        attackEffectRemaining = 0.34;
    }
    public void damage(int amount) { if (state == State.DEAD) return; hp=Math.max(0,hp-Math.max(0,amount)); state=hp==0?State.DEAD:State.HURT; stateTime=0; }
    public EnemyType type(){return type;} public WorldType world(){return world;} public double getX(){return x;} public double getY(){return y;}
    public int hp(){return hp;} public State state(){return state;} public double stateTime(){return stateTime;} public double facingX(){return facingX;} public double facingY(){return facingY;}
    public boolean isAttackEffectVisible(){return attackEffectRemaining > 0.0;}
    public double attackEffectRemaining(){return attackEffectRemaining;}
}
