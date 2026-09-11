package com.phantomcorridor.model.entity;

/**
 * 护盾：玩家的**临时生命值**。
 *
 * <p>它只做一件事——在生命值之前挨打。命中结算的顺序永远是
 * <b>先扣护盾、扣完才轮到生命</b>（见 {@link #absorb(double)}），
 * 所以护盾本质上是“一次性的额外血条”，而不是减伤或无敌：
 * <ul>
 *   <li><b>不吃治疗</b>：生命恢复药剂只回生命，不会补护盾；</li>
 *   <li><b>不自然回满</b>：进下一层时重置为一整条（见
 *       {@code GameSession.startFloor}），一局之内打完就没了；</li>
 *   <li><b>不保留溢出</b>：单次伤害打穿护盾后，多出来的部分照常打进生命值，
 *       不会因为“刚好有盾”就白吃一次重击。</li>
 * </ul>
 *
 * <p>数值上用 {@code double} 累加，最终结算到生命值时才向上取整，
 * 这样 4.5 点这类半整数伤害在护盾上不会被反复取整放大或抹平。
 *
 * <p>纯模型类：不依赖 JavaFX。
 */
public final class Shield {

    /** 护盾当前剩余点数。 */
    private double current;

    /** 护盾容量（满值）。 */
    private double capacity;

    /**
     * @param capacity 护盾容量（满值）；小于 0 时按 0 处理
     */
    public Shield(double capacity) {
        this.capacity = Math.max(0.0, capacity);
        this.current = this.capacity;
    }

    /** 护盾容量（满值时等于它）。 */
    public double getCapacity() { return capacity; }

    /** 当前剩余护盾点数。 */
    public double getCurrent() { return current; }

    /** 是否还剩护盾可挡。 */
    public boolean isActive() { return current > 0.0; }

    /** 剩余比例（0～1）；容量为 0 时恒为 0。 */
    public double getRatio() {
        return capacity <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, current / capacity));
    }

    /** 重置容量与剩余量（换层时调用）。 */
    public void reset(double capacity) {
        this.capacity = Math.max(0.0, capacity);
        this.current = this.capacity;
    }

    /** 回满到当前容量。 */
    public void refill() { current = capacity; }

    /**
     * 只改容量、保留剩余量（当前值超出新容量时截断）。
     *
     * <p>换装备导致容量变化时走这里，而不是 {@link #reset(double)}：
     * 否则“进商店换一件饰品”就会白送一整条护盾。
     */
    public void setCapacity(double capacity) {
        this.capacity = Math.max(0.0, capacity);
        this.current = Math.min(this.current, this.capacity);
    }

    /** 直接补充护盾（上限为容量）。 */
    public void restore(double amount) {
        current = Math.max(0.0, Math.min(capacity, current + Math.max(0.0, amount)));
    }

    /** 清空护盾（不改变容量）。 */
    public void clear() { current = 0.0; }

    /**
     * 用护盾吸收一次伤害。
     *
     * @param damage 本次伤害（小于 0 按 0 处理）
     * @return 护盾吸收之后**还没被吸收、需要打进生命值**的溢出伤害
     */
    public double absorb(double damage) {
        double incoming = Math.max(0.0, damage);
        if (incoming <= 0.0 || current <= 0.0) return incoming;
        double absorbed = Math.min(current, incoming);
        current -= absorbed;
        return incoming - absorbed;
    }
}
