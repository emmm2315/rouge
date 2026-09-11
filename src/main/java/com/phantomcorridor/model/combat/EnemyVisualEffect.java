package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

/** 独立于本体姿态的攻击、蓄力、命中和消散视觉层。 */
public final class EnemyVisualEffect {
    private final EnemyKind source;
    private final WorldType world;
    private final String effectId;
    private final boolean bodyAnimation;
    private final String facing;
    /** 幻象：只画半透明的本体帧与空心描边，不创建敌人实体、不参与任何判定。 */
    private final boolean ghost;
    private final double x, y, angleRadians, size;
    private final double duration;
    private double age;

    public EnemyVisualEffect(EnemyKind source, WorldType world, String effectId,
                             double x, double y, double angleRadians, double size, double duration) {
        this(source, world, effectId, x, y, angleRadians, size, duration, false, "right", false);
    }
    private EnemyVisualEffect(EnemyKind source, WorldType world, String effectId,
                              double x, double y, double angleRadians, double size, double duration,
                              boolean bodyAnimation, String facing, boolean ghost) {
        this.source = source; this.world = world; this.effectId = effectId;
        this.x = x; this.y = y; this.angleRadians = angleRadians; this.size = size;
        this.duration = duration; this.bodyAnimation = bodyAnimation; this.facing = facing;
        this.ghost = ghost; }
    public static EnemyVisualEffect body(EnemyKind source, WorldType world, String action, String facing,
                                         double x, double y, double size, double duration) {
        return new EnemyVisualEffect(source, world, action, x, y, 0.0, size, duration, true, facing, false);
    }

    /**
     * 幻象本体：术士的假影复用本体动画，但半透明并用空心描边区分。
     *
     * <p>素材包明确要求幻象「不创建 Enemy 实体」——它不计清房、不产出奖励、不参与碰撞，
     * 只是逼玩家在真假之间辨认，所以它只能是一条视觉记录。
     */
    public static EnemyVisualEffect ghost(EnemyKind source, WorldType world, String action, String facing,
                                          double x, double y, double size, double duration) {
        return new EnemyVisualEffect(source, world, action, x, y, 0.0, size, duration, true, facing, true);
    }
    public void update(double dt) { age += Math.max(0, dt); }
    public boolean expired() { return age >= duration; }
    public EnemyKind source() { return source; }
    public WorldType world() { return world; }
    public String effectId() { return effectId; }
    public double x() { return x; }
    public double y() { return y; }
    public double angleRadians() { return angleRadians; }
    public double size() { return size; }
    public double age() { return age; }
    public double duration() { return duration; }
    public boolean isBodyAnimation() { return bodyAnimation; }
    public String facing() { return facing; }
    /** 是否为假影：渲染层用低透明度 + 空心描边绘制。 */
    public boolean isGhost() { return ghost; }
}
