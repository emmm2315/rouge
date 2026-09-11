package com.phantomcorridor.model.entity;

/**
 * 冲刺拖尾中的一段残影：记录残影生成时的角色位置与剩余寿命。
 *
 * <p>渲染层只读取本记录，不修改游戏状态；老化（{@link #aged(double)}）由
 * {@link Player#updateDash(double)} 在逻辑帧里统一推进。
 *
 * @param x         残影生成时的角色横坐标（像素）
 * @param y         残影生成时的角色纵坐标（像素）
 * @param remaining 剩余寿命（秒），降到 0 即消散
 * @param total     生成时的总寿命（秒），用于换算淡出进度
 */
public record DashTrailPoint(double x, double y, double remaining, double total) {

    public DashTrailPoint {
        if (total <= 0.0) throw new IllegalArgumentException("残影总寿命必须为正数");
    }

    /** 剩余寿命比例：1 表示刚生成，0 表示已经消散。 */
    public double life() {
        return Math.max(0.0, remaining) / total;
    }

    /** 已经淡完、可以从拖尾里移除。 */
    public boolean expired() {
        return remaining <= 0.0;
    }

    /** 推进一帧的寿命；返回值是新记录，调用方负责替换旧记录。 */
    public DashTrailPoint aged(double dt) {
        return new DashTrailPoint(x, y, Math.max(0.0, remaining - Math.max(0.0, dt)), total);
    }
}
