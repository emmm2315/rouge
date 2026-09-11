package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.room.Wall;

/**
 * 根冠古树长出的临时根篱。
 *
 * <p>根篱是「地形招」而不是伤害招：它只挡路，碰到不掉血（设计文档 {@code wallDamage: 0}）。
 * 因此它必须满足两条硬约束，否则会变成纯粹的恶意：
 * <ol>
 *   <li>中间永远留出通路（{@code safeGap}），玩家总能穿过去；</li>
 *   <li>落点要通过可达性校验，一旦会把房间封死就降级成{@code 无碰撞装饰}
 *       （{@code failedPlacementPolicy: cosmetic_only_nonblocking}）——看得见，但不挡路。</li>
 * </ol>
 */
public final class RootWall {
    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final WorldType world;
    private final double duration;
    /** 是否真的参与碰撞：可达性校验失败时为 false，只作为视觉装饰存在。 */
    private final boolean blocking;
    private double age;

    public RootWall(double x, double y, double width, double height, WorldType world,
                    double duration, boolean blocking) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.world = world;
        this.duration = Math.max(0.1, duration);
        this.blocking = blocking;
    }

    public void update(double dt) { age += Math.max(0.0, dt); }

    public boolean isExpired() { return age >= duration; }

    /** 生长 / 枯萎进度 0～1：渲染层据此让根篱从地里长出来再塌回去。 */
    public double progress() { return Math.min(1.0, age / duration); }

    public double x() { return x; }
    public double y() { return y; }
    public double width() { return width; }
    public double height() { return height; }
    public WorldType world() { return world; }
    public double duration() { return duration; }
    public boolean blocking() { return blocking; }

    /** 转成导航层认识的墙体；装饰性根篱不参与碰撞，由调用方过滤。 */
    public Wall toWall() { return new Wall(x, y, width, height, world); }
}
