package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.ItemType;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.combat.DamageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 玩家运行时模型，不依赖任何 JavaFX 控件。 */
public final class Player {

    /** 受击结算结果：护盾吸收了多少、生命实际掉了多少、伤害类型是什么。 */
    public record DamageResult(double absorbedByShield, int healthLost, double remainingShield,
                               DamageType type) {
        /** 是否发生了有效受击（护盾或生命至少有一项被扣）。 */
        public boolean landed() { return absorbedByShield > 0.0 || healthLost > 0; }
    }

    private int hp;
    private double x;
    private double y;
    private int coins;
    private double phaseEnergy;
    private int attackCharges;
    private int maxAttackCharges;
    private double attackChargeRecoveryTimer;
    /** 切界后攻击充能恢复加速的剩余时间（秒），期间蓝条回复速度翻倍。 */
    private double attackChargeBoostRemaining;
    private WorldType currentWorld;
    private PlayerAnimationState animationState;
    private double animationTime;
    private double facingX;
    private double facingY;
    private double hitInvulnerability;
    private double dashTimeRemaining;
    private double dashCooldownRemaining;
    private double dashDirectionX;
    private double dashDirectionY;
    private final List<DashTrailPoint> dashTrail = new ArrayList<>();
    private double hitFlashRemaining;
    private boolean hitKnockbackPending;
    private double hitKnockbackX;
    private double hitKnockbackY;
    /** 护盾：在生命值之前挨打的临时生命值。 */
    private Shield shield;
    private final List<ItemType> items = new ArrayList<>();
    private final List<EquipmentType> equipment = new ArrayList<>();

    public Player(double x, double y) {
        reset(x, y);
    }

    public void reset(double x, double y) {
        // 先清理上一局的装备，再计算初始生命上限，避免行者心核把新局初始血量错误地保留为 120。
        this.items.clear();
        this.equipment.clear();
        this.hp = maxHp();
        this.x = x;
        this.y = y;
        this.coins = 0;
        this.phaseEnergy = GameConfig.PHASE_ENERGY_INITIAL;
        this.maxAttackCharges = GameConfig.ATTACK_CHARGE_MAX;
        this.attackCharges = maxAttackCharges;
        this.attackChargeRecoveryTimer = 0.0;
        this.attackChargeBoostRemaining = 0.0;
        this.currentWorld = WorldType.LIGHT;
        this.animationState = PlayerAnimationState.IDLE;
        this.animationTime = 0.0;
        this.facingX = 1.0;
        this.facingY = 0.0;
        this.hitInvulnerability = 0.0;
        this.dashTimeRemaining = 0.0;
        this.dashCooldownRemaining = 0.0;
        this.dashDirectionX = 1.0;
        this.dashDirectionY = 0.0;
        this.dashTrail.clear();
        this.hitFlashRemaining = 0.0;
        this.hitKnockbackPending = false;
        this.hitKnockbackX = 0.0;
        this.hitKnockbackY = 0.0;
        this.shield = new Shield(shieldCapacity());
    }

    public void move(double directionX, double directionY, double dt,
                     double minX, double minY, double maxX, double maxY) {
        double length = Math.hypot(directionX, directionY);
        if (length == 0.0) {
            return;
        }
        double speed = GameConfig.PLAYER_BASE_SPEED
                * (currentWorld == WorldType.SHADOW ? GameConfig.SHADOW_SPEED_MULTIPLIER : 1.0);
        x = clamp(x + directionX / length * speed * dt, minX, maxX);
        y = clamp(y + directionY / length * speed * dt, minY, maxY);
    }

    /**
     * 触发一次闪避冲刺（空格）。
     *
     * <p>冲刺期间位移完全由冲刺方向决定，玩家输入的转向被忽略——这是"闪避"而不是"加速跑"。
     * 方向为空向量时沿当前朝向冲，保证站着不动按空格也能朝看得见的方向翻出去。
     *
     * @param directionX 期望的冲刺方向（通常是当前的移动输入；未归一化）
     * @param directionY 期望的冲刺方向
     * @return 是否真的开始冲刺：冷却未好、已在冲刺中或已阵亡时返回 {@code false}
     */
    public boolean tryStartDash(double directionX, double directionY) {
        if (hp <= 0 || isDashing() || dashCooldownRemaining > 0.0) return false;
        double length = Math.hypot(directionX, directionY);
        if (length > 0.0) {
            dashDirectionX = directionX / length;
            dashDirectionY = directionY / length;
        } else {
            dashDirectionX = facingX;
            dashDirectionY = facingY;
        }
        // 朝向理论上不会为零向量，兜底避免"原地冲刺"这种什么都看不见的手感。
        if (dashDirectionX == 0.0 && dashDirectionY == 0.0) dashDirectionX = 1.0;
        dashTimeRemaining = GameConfig.DASH_DURATION;
        return true;
    }

    /**
     * 推进冲刺计时：冲刺中递减剩余时间（归零时开始算内置冷却），否则递减冷却；
     * 同时老化拖尾残影，让冲刺结束后拖尾自然消散。
     */
    public void updateDash(double dt) {
        double step = Math.max(0.0, dt);
        for (int i = 0; i < dashTrail.size(); i++) dashTrail.set(i, dashTrail.get(i).aged(step));
        dashTrail.removeIf(DashTrailPoint::expired);
        if (dashTimeRemaining > 0.0) {
            dashTimeRemaining = Math.max(0.0, dashTimeRemaining - step);
            if (dashTimeRemaining == 0.0) dashCooldownRemaining = GameConfig.DASH_COOLDOWN;
            return;
        }
        dashCooldownRemaining = Math.max(0.0, dashCooldownRemaining - step);
    }

    /** 在当前位置留下一段拖尾残影；只在冲刺中记录，所以拖尾形状与冲刺轨迹一致。 */
    public void recordDashTrail() {
        if (!isDashing()) return;
        if (dashTrail.size() >= GameConfig.DASH_TRAIL_MAX) dashTrail.remove(0);
        dashTrail.add(new DashTrailPoint(x, y,
                GameConfig.DASH_TRAIL_LIFETIME, GameConfig.DASH_TRAIL_LIFETIME));
    }

    /** 是否正在冲刺（伤害免疫与渲染拖尾都以此为准）。 */
    public boolean isDashing() {
        return dashTimeRemaining > 0.0;
    }

    /** 冲刺剩余时间（秒）。 */
    public double getDashTimeRemaining() { return dashTimeRemaining; }

    /** 下一次冲刺还要等多久（秒）：冲刺剩余时间与内置冷却取较大者，0 表示随时可用。 */
    public double getDashCooldownRemaining() {
        return Math.max(dashTimeRemaining, dashCooldownRemaining);
    }

    /**
     * 冲刺是否已经可用。
     *
     * <p>冷却是内置的：界面不显示进度，冷却没走完时按键就是不生效，节奏由玩家自己掌握。
     * 这个方法只用于可用性判定（测试与后续需要读状态的系统），不是给 HUD 用的。
     */
    public boolean isDashReady() {
        return hp > 0 && !isDashing() && dashCooldownRemaining <= 0.0;
    }

    /** 冲刺方向（单位向量）：冲刺位移与拖尾朝向都用它，且不受冲刺途中的输入影响。 */
    public double getDashDirectionX() { return dashDirectionX; }
    public double getDashDirectionY() { return dashDirectionY; }

    /** 冲刺拖尾残影（按记录顺序，最早的在前）；渲染层只读。 */
    public List<DashTrailPoint> getDashTrail() {
        return Collections.unmodifiableList(dashTrail);
    }

    public void restorePhaseEnergy(double amount) {
        phaseEnergy = clamp(phaseEnergy + Math.max(0.0, amount), 0.0, GameConfig.PHASE_ENERGY_MAX);
    }

    public void consumePhaseEnergy(double amount) {
        phaseEnergy = clamp(phaseEnergy - Math.max(0.0, amount), 0.0, GameConfig.PHASE_ENERGY_MAX);
    }

    public void restoreAttackCharges(int amount) { attackCharges = Math.min(maxAttackCharges, attackCharges + Math.max(0, amount)); }

    /**
     * 触发切界后的攻击充能回复加速：持续 {@link GameConfig#WORLD_SWITCH_CHARGE_BOOST_DURATION} 秒。
     *
     * <p>切界会清空相位能量，玩家常在没攻击充能时切界回能；这段时间蓝条回复翻倍，
     * 缩短「切界等回能」的空窗。重复触发只延长到更大值，不会叠加。
     */
    public void boostAttackChargeRecovery(double seconds) {
        attackChargeBoostRemaining = Math.max(attackChargeBoostRemaining, Math.max(0.0, seconds));
    }

    /** 切界后攻击充能回复加速的剩余时间（秒）；0 表示已结束。 */
    public double getAttackChargeBoostRemaining() { return attackChargeBoostRemaining; }

    public void updateAttackCharges(double dt) {
        double step = Math.max(0.0, dt);
        attackChargeBoostRemaining = Math.max(0.0, attackChargeBoostRemaining - step);
        double speed = attackChargeBoostRemaining > 0.0
                ? GameConfig.WORLD_SWITCH_CHARGE_BOOST_MULTIPLIER : 1.0;
        if (attackCharges >= maxAttackCharges) { attackChargeRecoveryTimer = 0.0; return; }
        attackChargeRecoveryTimer += step * speed;
        while (attackChargeRecoveryTimer >= GameConfig.ATTACK_CHARGE_RECOVERY_TIME
                && attackCharges < maxAttackCharges) {
            attackCharges++;
            attackChargeRecoveryTimer -= GameConfig.ATTACK_CHARGE_RECOVERY_TIME;
        }
    }
    public void increaseAttackChargeCapacity(int amount) {
        maxAttackCharges = Math.max(GameConfig.ATTACK_CHARGE_MAX, maxAttackCharges + Math.max(0, amount));
        attackCharges = maxAttackCharges;
    }
    public void addItem(ItemType item) {
        if (item == null || items.contains(item)) return;
        items.add(item);
        if (item == ItemType.UNIVERSAL || item == ItemType.DUAL) increaseAttackChargeCapacity(1);
    }
    public void equip(EquipmentType item) {
        if (item == null || equipment.contains(item)) return;
        equipment.removeIf(existing -> existing.affinity().equals(item.affinity())
                && !existing.affinity().equals("双界") && !existing.affinity().equals("通用"));
        equipment.add(item);
        while (equipment.size() > 3) equipment.remove(0);
        // 护盾类装备会撑大容量：只同步容量、保留剩余量，
        // 否则“在商店换一件饰品”就白送一整条护盾。
        if (shield != null) shield.setCapacity(shieldCapacity());
    }
    public List<EquipmentType> getEquipment() { return Collections.unmodifiableList(equipment); }
    public boolean consumeAttackCharge() {
        if (attackCharges <= 0) return false;
        attackCharges--; return true;
    }

    public void toggleWorld() {
        currentWorld = currentWorld == WorldType.LIGHT ? WorldType.SHADOW : WorldType.LIGHT;
    }

    public void updateAnimation(double dt, double movementX, double movementY, boolean attacking,
                                boolean shifting) {
        hitInvulnerability = Math.max(0.0, hitInvulnerability - Math.max(0.0, dt));
        hitFlashRemaining = Math.max(0.0, hitFlashRemaining - Math.max(0.0, dt));
        animationTime += Math.max(0.0, dt);
        // 冲刺中朝向锁在冲刺方向上：拖尾与角色朝向必须一致，否则拖尾会"横着飘"。
        double lookX = isDashing() ? dashDirectionX : movementX;
        double lookY = isDashing() ? dashDirectionY : movementY;
        if (lookX != 0.0 || lookY != 0.0) {
            double length = Math.hypot(lookX, lookY);
            facingX = lookX / length;
            facingY = lookY / length;
        }
        PlayerAnimationState next = hp <= 0 ? PlayerAnimationState.DOWN
                : isDashing() ? PlayerAnimationState.DASHING
                : shifting ? PlayerAnimationState.SHIFTING
                : attacking ? PlayerAnimationState.ATTACKING
                : movementX != 0.0 || movementY != 0.0 ? PlayerAnimationState.MOVING
                : PlayerAnimationState.IDLE;
        if (next != animationState) animationTime = 0.0;
        animationState = next;
    }

    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** 受到伤害且不知道伤害来源：击退方向按角色朝向的反方向算。 */
    public boolean takeDamage(int damage) {
        return takeDamage(damage, Double.NaN, Double.NaN);
    }

    /**
     * 受到伤害：扣血、进入短暂无敌、贴图变淡，并按来源反方向登记一次击退。
     *
     * <p>两条免伤路径：受击后的短暂无敌时间（避免一帧内被重叠弹幕重复扣血），
     * 以及冲刺全程的无敌（闪避的位移必须配得上"躲开"这个词）。免疫时不产生任何受击反应。
     *
     * <p>击退只在这里登记方向，实际位移由 {@link #consumeKnockback()} 交给房间导航执行——
     * 角色模型不知道墙在哪，硬改坐标会把人塞进墙里。
     *
     * @param damage  伤害值
     * @param sourceX 伤害来源的横坐标；未知时传 {@link Double#NaN}
     * @param sourceY 伤害来源的纵坐标；未知时传 {@link Double#NaN}
     * @return 是否真的掉血；免疫时返回 {@code false}
     */
    public boolean takeDamage(int damage, double sourceX, double sourceY) {
        // 冲刺全程免伤：闪避就是"用无敌帧换位移"，否则这段位移只是跑得快一点。
        if (damage <= 0 || isDashing() || hitInvulnerability > 0.0 || hp <= 0) return false;
        hp = Math.max(0, hp - damage);
        hitInvulnerability = GameConfig.PLAYER_HIT_INVULNERABILITY;
        hitFlashRemaining = GameConfig.PLAYER_HIT_FLASH_TIME;
        beginHitKnockback(sourceX, sourceY);
        return true;
    }

    /**
     * 结算一次敌人攻击的伤害。
     *
     * <p>顺序是固定的：<b>先扣护盾，扣穿之后剩下的才进生命值</b>。
     * 护盾是临时生命值而不是减伤，所以它只顶“点数”，一记 40 点重击打穿 30 点护盾后，
     * 依然会把剩下的 10 点实打实送进生命条。
     *
     * <p>受击后有一段短暂无敌，避免同帧的重叠弹幕重复扣血；
     * 无敌期间连护盾也不会被消耗。被击会获得少量相位能量（§3.4）。
     *
     * @param damage 伤害点数（可以带小数；打进生命值时向上取整，保证任何非零伤害都至少掉 1 点）
     * @return 本次受击的结算明细；未生效时返回 {@code null}
     */
    public DamageResult takeDamage(double damage, DamageType type, double sourceX, double sourceY) {
        if (damage <= 0.0 || isDashing() || hitInvulnerability > 0.0 || hp <= 0) return null;
        // Shield.absorb 返回的是“护盾没挡下、要继续打进生命值”的溢出量。
        double overflow = shield.absorb(damage);
        double absorbed = damage - overflow;
        int healthLost = (int) Math.ceil(overflow - 1e-9);
        if (healthLost <= 0 && absorbed <= 0.0) return null;
        hp = Math.max(0, hp - healthLost);
        hitInvulnerability = GameConfig.PLAYER_HIT_INVULNERABILITY;
        hitFlashRemaining = GameConfig.PLAYER_HIT_FLASH_TIME;
        beginHitKnockback(sourceX, sourceY);
        restorePhaseEnergy(GameConfig.PHASE_ENERGY_ON_HIT);
        return new DamageResult(absorbed, healthLost, shield.getCurrent(),
                type == null ? DamageType.PHYSICAL : type);
    }

    public DamageResult takeDamage(double damage, DamageType type) {
        return takeDamage(damage, type, Double.NaN, Double.NaN);
    }

    /** 无属性伤害的便捷入口（测试与不含相位的来源使用）。 */
    public DamageResult takeDamage(double damage) {
        return takeDamage(damage, DamageType.PHYSICAL);
    }

    /** 把击退方向定成"从伤害来源指向角色"；来源未知或正好压在角色身上时退化为朝向的反方向。 */
    private void beginHitKnockback(double sourceX, double sourceY) {
        double dx = x - sourceX;
        double dy = y - sourceY;
        double length = Math.hypot(dx, dy);
        if (length > 0.0) {
            hitKnockbackX = dx / length;
            hitKnockbackY = dy / length;
        } else {
            hitKnockbackX = -facingX;
            hitKnockbackY = -facingY;
        }
        hitKnockbackPending = true;
    }

    /**
     * 取出并清除这次受击的击退方向（单位向量）。
     *
     * @return 击退方向；这一帧没有待处理的击退时返回 {@code null}
     */
    public double[] consumeKnockback() {
        if (!hitKnockbackPending) return null;
        hitKnockbackPending = false;
        return new double[]{hitKnockbackX, hitKnockbackY};
    }

    /**
     * 受击变淡的剩余强度（0~1，1 表示刚挨打）。
     *
     * <p>渲染层据此把角色贴图往白色虚化一档：这是"挨了一下"的即时反馈，
     * 和无敌时间分开计时，所以变淡会比无敌更早消失。
     */
    public double getHitFlash() {
        return GameConfig.PLAYER_HIT_FLASH_TIME <= 0.0 ? 0.0
                : Math.max(0.0, hitFlashRemaining) / GameConfig.PLAYER_HIT_FLASH_TIME;
    }

    public int getHp() { return hp; }

    /** 当前最大生命值；行者心核按设计提升 20%，装备时不自动补血。 */
    public int maxHp() {
        boolean hasHeart = equipment.contains(EquipmentType.WAYFARER_HEART);
        return hasHeart ? (int) Math.ceil(GameConfig.PLAYER_MAX_HP * 1.20) : GameConfig.PLAYER_MAX_HP;
    }

    public void restoreHealth(int amount) { hp = Math.min(maxHp(), hp + Math.max(0, amount)); }

    /** 按「几瓶生命恢复药剂」回血：一瓶 = {@link GameConfig#PLAYER_HEAL_PER_PICKUP} 点。 */
    public void restoreHealthByPickups(int pickups) {
        restoreHealth(Math.max(0, pickups) * GameConfig.PLAYER_HEAL_PER_PICKUP);
    }

    public double getX() { return x; }
    public double getY() { return y; }

    /** 本局金币：击杀、奖励房与宝箱获得，商店消费。 */
    public int getCoins() { return coins; }

    public void addCoins(int amount) { coins = Math.max(0, coins + Math.max(0, amount)); }

    /** 扣款；金币不足时不扣并在返回 false。 */
    public boolean spendCoins(int amount) {
        if (amount < 0 || coins < amount) return false;
        coins -= amount;
        return true;
    }
    public double getPhaseEnergy() { return phaseEnergy; }
    public int getAttackCharges() { return attackCharges; }
    public int getMaxAttackCharges() { return maxAttackCharges; }
    public int getAttackDamage() {
        int bonus = items.stream().mapToInt(item -> item == ItemType.DUAL || item == ItemType.UNIVERSAL
                || (currentWorld == WorldType.LIGHT && item == ItemType.LIGHT)
                || (currentWorld == WorldType.SHADOW && item == ItemType.SHADOW) ? 1 : 0).sum();
        bonus += equipment.stream().mapToInt(item -> item == EquipmentType.DAWN_WAND && currentWorld == WorldType.LIGHT ? 1
                : item == EquipmentType.SHADOW_FANG && currentWorld == WorldType.SHADOW ? 1
                : item == EquipmentType.RIFT_TWINBLADE || item == EquipmentType.DAWN_SEAL && currentWorld == WorldType.LIGHT ? 1
                : 0).sum();
        // 新增武器的首轮数值采用整数模型的最小可见增幅；精确多段伤害由攻击方案继续细分。
        bonus += equipment.stream().mapToInt(item -> switch (item) {
            case PRISM_FAN_WAND, SUNLANCE, MIRROR_ORB, SOLAR_BURST_STAFF ->
                    currentWorld == WorldType.LIGHT ? 1 : 0;
            case CRESCENT_REAPER, RETURNING_FANG, NIGHTFALL_GREATSWORD ->
                    currentWorld == WorldType.SHADOW ? 1 : 0;
            case ECLIPSE_RELAY -> 1;
            case FOCUS_LENS -> currentWorld == WorldType.LIGHT ? 1 : 0;
            case HUNTERS_FANG -> currentWorld == WorldType.SHADOW ? 1 : 0;
            default -> 0;
        }).sum();
        return 1 + bonus;
    }
    public boolean hasEquipment(EquipmentType item) { return item != null && equipment.contains(item); }
    public WorldType getCurrentWorld() { return currentWorld; }
    public PlayerAnimationState getAnimationState() { return animationState; }
    public double getAnimationTime() { return animationTime; }
    public double getFacingX() { return facingX; }
    public double getFacingY() { return facingY; }
    public List<ItemType> getItems() { return Collections.unmodifiableList(items); }
    public boolean isHitInvulnerable() { return hitInvulnerability > 0.0; }

    // ---- 护盾 ----

    /** 护盾容量：基础容量 + 装备加成（相位容器会额外撑盾）。 */
    public double shieldCapacity() {
        return GameConfig.PLAYER_SHIELD_CAPACITY
                + equipment.stream().mapToDouble(EquipmentType::shieldCapacityBonus).sum();
    }

    /** 当前护盾剩余点数（0 表示没有护盾）。 */
    public double getShield() { return shield.getCurrent(); }

    /** 护盾容量。 */
    public double getMaxShield() { return shield.getCapacity(); }

    /** 是否还剩护盾。 */
    public boolean hasShield() { return shield.isActive(); }

    /** 护盾剩余比例（0～1），供 HUD 画条。 */
    public double getShieldRatio() { return shield.getRatio(); }

    /**
     * 护盾换算成“血条上的等价长度比例”（0～1）。
     *
     * <p>护盾条是叠在血条上的，所以必须先换算到**生命刻度**上：
     * 30 点护盾对着 100 点血就是血条长度的 30%，而不是护盾自己容量的 100%。
     * 换算放在模型层而不是渲染层，是为了让这个比例能被测试直接钉住。
     */
    public double getShieldBarRatio() {
        int maxHp = maxHp();
        if (maxHp <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, shield.getCurrent() / maxHp));
    }


    /** 回满护盾（换层时调用）。 */
    public void refillShield() { shield.reset(shieldCapacity()); }

    /** 补充护盾（不超过容量）。 */
    public void restoreShield(double amount) { shield.restore(amount); }

    /** 清空护盾：只清剩余量，容量不变。 */
    public void clearShield() { shield.clear(); }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
