package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

/**
 * 敌方预警与延迟判定区域。
 *
 * <p>v2 素材包的三分之一招式不是「飞出去的弹体」，而是「先画在地上的图形、到时再爆」：
 * 扇面、环带、横带、地面标记、延迟绽放的孢子、三点校射。它们必须满足同一条读法——
 * <b>预警先完整出现，判定只结算一次</b>——所以统一由一个实体承载：
 *
 * <ol>
 *   <li>{@link #warnSeconds} 期间只画预警（渲染层按 {@link #progress()} 推进填充与倒计时环）；</li>
 *   <li>到点后由 {@link EnemySystem} 做一次几何命中判定；</li>
 *   <li>再残留 {@link #residualSeconds} 表现爆开的效果，然后销毁。</li>
 * </ol>
 *
 * <p>几何形状与技能参数一一对应（角度、缺口、半径、条带长度都写在技能上），
 * 因此不需要把通用预警模板当成精确判定范围——素材包文档也明确要求按参数绘制实际缺口与标记。
 */
public final class EnemyTelegraph {

    /** 判定几何。 */
    public enum Shape {
        /** 圆形范围（孢子绽放、雾区脉冲、地面标记）。 */
        CIRCLE,
        /** 以本体为顶点、朝锁定方向的扇面。 */
        SECTOR,
        /** 正前方留安全楔的两条扇面。 */
        SECTOR_SPLIT,
        /** 从本体向锁定方向伸出的长条（根刺、突刺轨迹）。 */
        LINE,
        /** 内 / 外半径之间的环带，留固定角度缺口。 */
        RING_GAP,
        /** 垂直于锁定方向的横带，可留一段缺口通路。 */
        BAND
    }

    private final EnemyKind source;
    private final WorldType world;
    private final String effectId;
    private final Shape shape;
    private final double x;
    private final double y;
    private final double angleRadians;
    private final double radius;
    private final double innerRadius;
    private final double outerRadius;
    private final double angleDegrees;
    private final double safeGapDegrees;
    private final double length;
    private final double width;
    private final double bandLength;
    private final double safeGap;
    private final double gapOffset;
    /** 条带 / 长条被拆成几段（根刺三根、封线三段）：渲染层据此画分段标记。 */
    private final int segments;
    private final double damage;
    private final long hitId;
    /** 生成它的那次施法：释放阶段可以按它回收 / 改短自己铺下的预警。 */
    private final long castId;
    private final boolean fake;
    private final double warnSeconds;
    private final double residualSeconds;
    /** 提前多久开始显示：第二段镰斩等「后画的预警」不该和第一段同时出现。 */
    private final double visibleLeadSeconds;
    private double age;
    private boolean resolved;

    private EnemyTelegraph(Builder builder) {
        this.source = builder.source;
        this.world = builder.world;
        this.effectId = builder.effectId;
        this.shape = builder.shape;
        this.x = builder.x;
        this.y = builder.y;
        this.angleRadians = builder.angleRadians;
        this.radius = builder.radius;
        this.innerRadius = builder.innerRadius;
        this.outerRadius = builder.outerRadius;
        this.angleDegrees = builder.angleDegrees;
        this.safeGapDegrees = builder.safeGapDegrees;
        this.length = builder.length;
        this.width = builder.width;
        this.bandLength = builder.bandLength;
        this.safeGap = builder.safeGap;
        this.gapOffset = builder.gapOffset;
        this.segments = Math.max(1, builder.segments);
        this.damage = Math.max(0.0, builder.damage);
        this.hitId = builder.hitId;
        this.castId = builder.castId;
        this.fake = builder.fake;
        this.warnSeconds = Math.max(0.0, builder.warnSeconds);
        this.residualSeconds = Math.max(0.05, builder.residualSeconds);
        this.visibleLeadSeconds = builder.visibleLeadSeconds <= 0.0
                ? builder.warnSeconds : builder.visibleLeadSeconds;
    }

    public void update(double dt) { age += Math.max(0.0, dt); }

    /** 判定是否已经结算过：一次预警只允许结算一次伤害。 */
    public boolean isResolved() { return resolved; }

    public void markResolved() { resolved = true; }

    /** 渲染层此刻是否该画出这道预警。 */
    public boolean isVisible() { return warnSeconds - age <= visibleLeadSeconds + 1e-9; }

    /** 预警是否已经走完：走完的那一刻做一次判定。 */
    public boolean isTriggered() { return age >= warnSeconds; }

    /** 判定之后是否已经连残留表现都放完。 */
    public boolean isExpired() { return age >= warnSeconds + residualSeconds; }

    /** 预警进度 0～1。 */
    public double progress() { return warnSeconds <= 0.0 ? 1.0 : Math.min(1.0, age / warnSeconds); }

    /** 判定之后的残留进度 0～1（用于爆开特效的淡出）。 */
    public double residualProgress() {
        if (!isTriggered()) return 0.0;
        double after = age - warnSeconds;
        return Math.min(1.0, after / residualSeconds);
    }

    public EnemyKind source() { return source; }
    public WorldType world() { return world; }
    public String effectId() { return effectId; }
    public Shape shape() { return shape; }
    public double x() { return x; }
    public double y() { return y; }
    public double angleRadians() { return angleRadians; }
    public double radius() { return radius; }
    public double innerRadius() { return innerRadius; }
    public double outerRadius() { return outerRadius; }
    public double angleDegrees() { return angleDegrees; }
    public double safeGapDegrees() { return safeGapDegrees; }
    public double length() { return length; }
    public double width() { return width; }
    public double bandLength() { return bandLength; }
    public double safeGap() { return safeGap; }
    public double gapOffset() { return gapOffset; }
    /** 条带被拆成几段；1 表示整条连成一体。 */
    public int segments() { return segments; }
    public double damage() { return damage; }
    public long hitId() { return hitId; }
    /** 生成它的那次施法标识：同一次施法的预警可以整体回收或改短。 */
    public long castId() { return castId; }
    /** 假圈：只画虚线空心的预警，永远不造成伤害（术士的两幅假影与假圈）。 */
    public boolean isFake() { return fake; }
    public double warnSeconds() { return warnSeconds; }
    public double residualSeconds() { return residualSeconds; }
    public double age() { return age; }

    /**
     * 玩家（按自身半径）是否落在这次判定的范围内。
     *
     * @param playerRadius 玩家的碰撞半径，让判定与「贴边蹭到」的直觉一致
     */
    public boolean contains(double px, double py, double playerRadius) {
        if (fake || damage <= 0.0) return false;
        double dx = px - x;
        double dy = py - y;
        double distance = Math.hypot(dx, dy);
        return switch (shape) {
            case CIRCLE -> distance <= radius + playerRadius;
            case SECTOR -> distance <= radius + playerRadius
                    && withinArc(dx, dy, 0.0, angleDegrees);
            case SECTOR_SPLIT -> distance <= radius + playerRadius
                    && splitSectorHit(dx, dy);
            case LINE -> lineHit(dx, dy, playerRadius);
            case RING_GAP -> distance >= Math.max(0.0, innerRadius - playerRadius)
                    && distance <= outerRadius + playerRadius
                    && !withinArc(dx, dy, 0.0, safeGapDegrees);
            case BAND -> bandHit(dx, dy, playerRadius);
        };
    }

    /**
     * 安全楔两侧的两条扇面。
     *
     * <p>正前方留下 {@code safeGapDegrees} 的楔形，两侧各扫 {@code angleDegrees}
     * （炮蟹「开钳双岸」的 50° 安全楔 + 左右各 65°）。
     */
    private boolean splitSectorHit(double dx, double dy) {
        double gapHalf = Math.toRadians(safeGapDegrees / 2.0);
        double sideHalf = Math.toRadians(angleDegrees / 2.0);
        double delta = Math.abs(angleDelta(Math.atan2(dy, dx), angleRadians));
        double centre = gapHalf + sideHalf;
        return delta >= centre - sideHalf && delta <= centre + sideHalf;
    }

    /** 长条：沿锁定方向伸出 length，宽度 width（判定时各向外放宽玩家半径）。 */
    private boolean lineHit(double dx, double dy, double playerRadius) {
        double cos = Math.cos(angleRadians);
        double sin = Math.sin(angleRadians);
        double along = dx * cos + dy * sin;
        double lateral = -dx * sin + dy * cos;
        return along >= -playerRadius && along <= length + playerRadius
                && Math.abs(lateral) <= width / 2.0 + playerRadius;
    }

    /**
     * 横带：垂直于锁定方向的一段带子，中间留 {@code safeGap} 宽的通路。
     *
     * <p>缺口位置 {@link #gapOffset()} 是沿带子方向的偏移，让两条带子的缺口错开，
     * 玩家必须真的横向换位而不是原地不动。
     */
    private boolean bandHit(double dx, double dy, double playerRadius) {
        double cos = Math.cos(angleRadians);
        double sin = Math.sin(angleRadians);
        double along = dx * cos + dy * sin;
        double lateral = -dx * sin + dy * cos;
        if (Math.abs(along) > width / 2.0 + playerRadius) return false;
        if (Math.abs(lateral) > bandLength / 2.0 + playerRadius) return false;
        return Math.abs(lateral - gapOffset) >= safeGap / 2.0 - playerRadius;
    }

    /** 目标方向是否落在以锁定方向为中轴、总宽度 degrees 的扇形里。 */
    private boolean withinArc(double dx, double dy, double halfOffsetDegrees, double degrees) {
        if (degrees >= 360.0) return true;
        if (Math.hypot(dx, dy) < 0.0001) return true;
        double delta = Math.abs(angleDelta(Math.atan2(dy, dx), angleRadians));
        double half = Math.toRadians(degrees / 2.0);
        double offset = Math.toRadians(halfOffsetDegrees);
        return delta >= offset - half && delta <= offset + half;
    }

    /** 两个角度之间的最小夹角（弧度，恒为非负）。 */
    public static double angleDelta(double a, double b) {
        double difference = Math.abs(a - b) % (Math.PI * 2);
        return Math.min(difference, Math.PI * 2 - difference);
    }

    /** 起始构建器。 */
    public static Builder at(EnemyKind source, WorldType world, String effectId, Shape shape,
                             double x, double y, double angleRadians) {
        return new Builder(source, world, effectId, shape, x, y, angleRadians);
    }

    /** 预警实体参数表。 */
    public static final class Builder {
        private final EnemyKind source;
        private final WorldType world;
        private final String effectId;
        private final Shape shape;
        private final double x, y, angleRadians;
        private double radius = 60, innerRadius, outerRadius, angleDegrees = 360, safeGapDegrees;
        private double length, width = 60, bandLength, safeGap, gapOffset;
        private int segments = 1;
        private double damage, warnSeconds = .5, residualSeconds = .34;
        private double visibleLeadSeconds;
        private long hitId;
        private long castId;
        private boolean fake;

        private Builder(EnemyKind source, WorldType world, String effectId, Shape shape,
                        double x, double y, double angleRadians) {
            this.source = source; this.world = world; this.effectId = effectId; this.shape = shape;
            this.x = x; this.y = y; this.angleRadians = angleRadians;
        }

        public Builder radius(double value) { radius = value; return this; }
        public Builder ring(double inner, double outer) { innerRadius = inner; outerRadius = outer; return this; }
        public Builder arc(double degrees) { angleDegrees = degrees; return this; }
        public Builder safeGapDegrees(double value) { safeGapDegrees = value; return this; }
        public Builder length(double value) { length = value; return this; }
        public Builder width(double value) { width = value; return this; }
        public Builder band(double lengthValue, double widthValue) {
            bandLength = lengthValue; width = widthValue; return this;
        }
        public Builder safeGap(double value) { safeGap = value; return this; }
        public Builder gapOffset(double value) { gapOffset = value; return this; }
        /** 条带拆成几段（根刺三根、封线三段）。 */
        public Builder segments(int value) { segments = value; return this; }
        public Builder damage(double value) { damage = value; return this; }
        public Builder warn(double seconds) { warnSeconds = seconds; return this; }
        /** 只在这道预警判定前的最后 N 秒开始显示（多段招式的第二段用）。 */
        public Builder visibleLead(double seconds) { visibleLeadSeconds = seconds; return this; }
        public Builder residual(double seconds) { residualSeconds = seconds; return this; }
        public Builder hitId(long value) { hitId = value; return this; }
        /** 归属的施法标识：释放阶段按它回收自己铺下的预警。 */
        public Builder castId(long value) { castId = value; return this; }
        public Builder fake() { fake = true; return this; }
        public EnemyTelegraph build() { return new EnemyTelegraph(this); }
    }
}
