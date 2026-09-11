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

    /** 装备栏容量固定为三个槽位；满栏时由交互层要求玩家显式选择替换或取消。 */
    public static final int EQUIPMENT_CAPACITY = 3;

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
    /** 相位陀螺：切界后攻速窗口的剩余时间（秒）。 */
    private double gyroscopeRemaining;
    /** 夜行披风：影斩后加速的剩余时间（秒）。 */
    private double nightstepRemaining;
    /** 曜纹披肩：光界减伤的冷却剩余时间（秒）。 */
    private double sunweaveCooldownRemaining;

    public Player(double x, double y) {
        reset(x, y);
    }

    public void reset(double x, double y) {
        // 先清理上一局的装备，再计算初始生命上限与护盾容量：此时装备栏为空，
        // 所以新局不会继承上一局的「行者心核」生命上限或「相位容器」护盾容量。
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
        this.gyroscopeRemaining = 0.0;
        this.nightstepRemaining = 0.0;
        this.sunweaveCooldownRemaining = 0.0;
    }

    /**
     * 当前移动速度（像素/秒）。
     *
     * <p>影界本身更快，穿着「暮色斗篷」还要再快一档；同名斗篷按件数叠加。
     * 实际位移由房间导航执行（它才知道墙在哪），但速度必须由这里算——
     * 只有玩家模型看得见自己的装备栏，把公式写在导航层就会让装备效果静默失效。
     */
    public double movementSpeed() {
        double speed = GameConfig.PLAYER_BASE_SPEED;
        if (currentWorld != WorldType.SHADOW) return speed;
        speed *= GameConfig.SHADOW_SPEED_MULTIPLIER
                * (1.0 + equipmentCount(EquipmentType.DUSK_CLOAK) * 0.10);
        // 夜行披风：影斩后的短时加速（不叠层，只刷新时间）。
        if (nightstepRemaining > 0.0) speed *= GameConfig.NIGHTSTEP_CLOAK_SPEED;
        return speed;
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
    /**
     * 把一件装备放进装备栏。
     *
     * <p>同名装备允许重复占用槽位，增益按件数叠加，所以这里不再按属性去重、也不再挤掉旧装备。
     *
     * <p>装备栏满时**什么都不做**：任何“自动腾位置”的策略都会让某一件装备凭空消失，
     * 而规则要求装备只能在玩家看得见的地方转移。满栏的调用方必须先用
     * {@link #isEquipmentFull()} 判断，转入替换选择流程（见
     * {@code RoomContentSystem#collectEquipment}）。
     */
    public void equip(EquipmentType item) {
        if (item == null || isEquipmentFull()) return;
        equipment.add(item);
        syncEquipmentDerivedStats();
    }

    public List<EquipmentType> getEquipment() { return Collections.unmodifiableList(equipment); }
    public boolean isEquipmentFull() { return equipment.size() >= EQUIPMENT_CAPACITY; }
    public int equipmentCapacity() { return EQUIPMENT_CAPACITY; }

    /** 用新装备替换指定槽位，并把被替换的那件返回给交互层落到地上。 */
    public EquipmentType replaceEquipment(int slot, EquipmentType item) {
        if (item == null || slot < 0 || slot >= equipment.size()) return null;
        EquipmentType discarded = equipment.set(slot, item);
        syncEquipmentDerivedStats();
        return discarded;
    }

    /** 丢弃指定槽位；地面落点由房间内容系统决定。 */
    public EquipmentType removeEquipment(int slot) {
        if (slot < 0 || slot >= equipment.size()) return null;
        EquipmentType discarded = equipment.remove(slot);
        syncEquipmentDerivedStats();
        return discarded;
    }

    /**
     * 装备变化后同步派生属性。
     *
     * <p>生命上限会随「行者心核」的件数变化：摘掉时把当前生命截到新上限，
     * 装上时不补血（心核是“上限装备”而不是治疗道具）。
     *
     * <p>护盾只同步容量、保留剩余量，否则“在商店换一件饰品”就白送一整条护盾。
     */
    private void syncEquipmentDerivedStats() {
        hp = Math.min(hp, maxHp());
        if (shield != null) shield.setCapacity(shieldCapacity());
    }
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
        updateTemporaryEffects(dt);
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
        damage = applySunweaveMitigation(damage);
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

    /**
     * 曜纹披肩：光界受到一次有效伤害时降低 30%，随后进入 6 秒冷却。
     *
     * <p>三条约束来自设计文档 §五-09：
     * <ul>
     *   <li>只在光界生效；<b>影界不消耗已经就绪的减伤机会</b>，所以影界挨打时直接原样返回；</li>
     *   <li>冷却只随未暂停的游戏时间推进，切界不重置；</li>
     *   <li>最终实际伤害仍至少为 1 点——减伤不新增免疫，所以这里用 {@code max(1, ...)} 兜底。</li>
     * </ul>
     */
    private double applySunweaveMitigation(double damage) {
        if (currentWorld != WorldType.LIGHT) return damage;
        int count = equipmentCount(EquipmentType.SUNWEAVE_MANTLE);
        if (count == 0 || sunweaveCooldownRemaining > 0.0) return damage;
        // 同名多件只按“有没有就绪”算一次：减伤是状态而不是可叠加的数值。
        sunweaveCooldownRemaining = GameConfig.SUNWEAVE_MANTLE_COOLDOWN;
        double reduced = damage * (1.0 - GameConfig.SUNWEAVE_MANTLE_REDUCTION);
        // 「至少 1 点」是在伤害真正落到生命值时保证的，所以只保证不被减到 0 以下。
        return Math.max(1.0, reduced);
    }

    /** 曜纹披肩的冷却剩余时间（秒）；就绪时为 0。 */
    public double getSunweaveCooldownRemaining() { return sunweaveCooldownRemaining; }

    /** 曜纹披肩此刻是否能挡下这一击。 */
    public boolean isSunweaveReady() {
        return currentWorld == WorldType.LIGHT
                && equipmentCount(EquipmentType.SUNWEAVE_MANTLE) > 0
                && sunweaveCooldownRemaining <= 0.0;
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
        return (int) Math.ceil(GameConfig.PLAYER_MAX_HP
                * (1.0 + equipmentCount(EquipmentType.WAYFARER_HEART) * 0.20));
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
    /**
     * 角色的基础攻击力（不含任何加成）：HUD 用它把“本身”和“装备加上去的”分开显示。
     *
     * <p><b>战斗刻度重构</b>：旧值是 1，而敌人防御最多能到 3——也就是说在最深几层，
     * 一次基础攻击被防御吃光后只能靠「至少 1 点」的兜底打出 1 点，防御、武器系数、
     * 伤害加成全部失去意义（首领也只有几十点血，一局下来全是 1 和 2 的数字）。
     * 现在基础攻击是 10，敌人生命同步上了一个量级（小怪几十、精英上百、首领数百），
     * 防御回到「每次减免 1～3 点」的本意，也就是一次基础攻击的 10%～30%，
     * 武器系数（0.45～1.60）与装备加成重新变成可读的百分比。
     */
    public static final int BASE_ATTACK_DAMAGE = 10;

    /**
     * 某界的基础单次攻击伤害（设计文档里的 {@code D光 / D影}）。
     *
     * <p>所有武器系数都以它为基准：三叉杖每发 {@code 0.45 D光}、重剑 {@code 1.60 D影}。
     */
    public double baseDamage(WorldType world) {
        return BASE_ATTACK_DAMAGE;
    }

    /**
     * 当前世界的基础伤害。
     *
     * @see #baseDamage(WorldType)
     */
    public double getCurrentBaseDamage() {
        return baseDamage(currentWorld);
    }

    /** 该界的伤害加成（同类相加的百分比之和，0.45 表示 +45%）。 */
    public double damageBonus(WorldType world) {
        return equipmentDamageBonus(world) + itemDamageBonus(world);
    }

    /** 当前世界的伤害加成。 */
    public double getDamageBonus() {
        return damageBonus(currentWorld);
    }

    /**
     * 该界最终的防御前伤害倍率：{@code 1 + 同类加成之和}。
     *
     * <p>设计文档 §三 的口径是「单个伤害包的防御前伤害 = 基础伤害 × 段系数 ×（1 + 同类加成之和）」，
     * 所以这里给的是那个括号，武器自己的段系数在 {@link com.phantomcorridor.model.combat.AttackProfile} 里。
     */
    public double damageMultiplier(WorldType world) {
        return 1.0 + damageBonus(world);
    }

    /** 装备提供的该界伤害加成。一件装备只在它所属的那一界计入。 */
    public double equipmentDamageBonus(WorldType world) {
        double bonus = 0.0;
        for (EquipmentType item : equipment) {
            bonus += switch (item) {
                // 光界：晨曦法杖 +20%、晨曦圣印 +10%。
                case DAWN_WAND -> world == WorldType.LIGHT ? 0.20 : 0.0;
                case DAWN_SEAL -> world == WorldType.LIGHT ? 0.10 : 0.0;
                // 影牙短刃：影界 +20%。
                case SHADOW_FANG -> world == WorldType.SHADOW ? 0.20 : 0.0;
                // 双界武器两界都 +10%。
                case RIFT_TWINBLADE -> 0.10;
                // 凝光透镜：光界全部玩家攻击 +25%（含饰品附加光伤）。
                case FOCUS_LENS -> world == WorldType.LIGHT ? 0.25 : 0.0;
                default -> 0.0;
            };
        }
        return bonus;
    }

    /** 道具提供的该界伤害加成。 */
    public double itemDamageBonus(WorldType world) {
        double bonus = 0.0;
        for (ItemType item : items) {
            bonus += switch (item) {
                case UNIVERSAL -> world == WorldType.LIGHT ? 0.25 : 0.0;
                case DUAL -> 0.25;
                case LIGHT -> world == WorldType.LIGHT ? 0.25 : 0.0;
                case SHADOW -> world == WorldType.SHADOW ? 0.25 : 0.0;
            };
        }
        return bonus;
    }

    /**
     * 攻击力加成（道具 + 装备，按当前世界结算），保留旧入口给 HUD 与既有测试使用。
     *
     * <p>数值口径已改成百分比的整数近似：{@code round(基础伤害 × 加成之和)}，
     * 这样「凝光透镜 +25%」在一击 1 点伤害的刻度下正好读成 +0（不足一点），
     * 而真正的伤害结算走 {@link #damageMultiplier(WorldType)}，不会因为取整丢掉那 25%。
     */
    public int getAttackBonus() {
        return (int) Math.round(BASE_ATTACK_DAMAGE * getDamageBonus());
    }

    /**
     * HUD 用的伤害文本：{@code 1 ×1.45}。
     *
     * <p>改百分比口径以后「+1」这种整数加成会经常显示成 +0，读不出透镜那 25%，
     * 所以直接把最终倍率写出来。
     */
    public String damageText(WorldType world) {
        return formatMultiplier(baseDamage(world) * damageMultiplier(world));
    }

    /** 当前世界的伤害文本。 */
    public String getDamageText() {
        return damageText(currentWorld);
    }

    private static String formatMultiplier(double damage) {
        return Math.abs(damage - Math.rint(damage)) < 0.005
                ? String.valueOf((int) Math.rint(damage))
                : String.format("%.2f", damage);
    }

    /** 装备提供的伤害加成（HUD 装备栏那一行用）。 */
    public double getEquipmentDamageBonus() {
        return equipmentDamageBonus(currentWorld);
    }

    /**
     * 该界的攻击间隔倍率（饰品 + 临时效果，设计文档 §三 的「饰品间隔倍率 × 临时效果间隔倍率」）。
     *
     * <ul>
     *   <li><b>凝光透镜</b>：光界攻击间隔 ×1.15。<b>只在光界生效</b>——它的伤害加成同样只给光界，
     *       如果间隔惩罚也压到影界，就等于让影界玩家白背一个负面效果；</li>
     *   <li><b>相位陀螺</b>：成功切界后 2 秒内，新世界的攻击间隔 ×0.85。</li>
     * </ul>
     */
    public double getAttackCooldownMultiplier(WorldType world) {
        double multiplier = 1.0;
        if (world == WorldType.LIGHT) {
            multiplier *= Math.pow(1.15, equipmentCount(EquipmentType.FOCUS_LENS));
        }
        return multiplier * gyroscopeMultiplier();
    }

    /**
     * 相位陀螺的临时攻速窗口倍率。
     *
     * <p>切界成功才开窗、持续 2 秒，且**只作用于此后启动的新攻击**——已经开始的前摇与
     * 飞行中的弹体不受影响（间隔是在下一轮出手时才读这个值）。
     */
    private double gyroscopeMultiplier() {
        if (gyroscopeRemaining <= 0.0 || equipmentCount(EquipmentType.PHASE_GYROSCOPE) == 0) return 1.0;
        return Math.pow(0.85, equipmentCount(EquipmentType.PHASE_GYROSCOPE));
    }

    /** 相位陀螺的攻速窗口剩余时间（秒）；0 表示没在窗口里。 */
    public double getGyroscopeRemaining() { return gyroscopeRemaining; }

    /** 是否正处在相位陀螺的攻速窗口里（HUD/视觉表现可用）。 */
    public boolean isGyroscopeActive() { return gyroscopeMultiplier() < 1.0; }

    /**
     * 记录一次成功切界：打开相位陀螺的攻速窗口。
     *
     * <p>重复获得只刷新持续时间、不叠层（窗口长度是固定 2 秒，不看件数）；
     * 没装陀螺时什么都不做，免得白占一个计时器。
     */
    public void onWorldShifted() {
        if (equipmentCount(EquipmentType.PHASE_GYROSCOPE) > 0) {
            gyroscopeRemaining = GameConfig.PHASE_GYROSCOPE_WINDOW;
        }
        // 切入光界会结束夜行披风的加速（它只在影界出刀后触发）。
        if (currentWorld == WorldType.LIGHT) nightstepRemaining = 0.0;
    }

    /**
     * 夜行披风：每次影界攻击**实际释放**时给 0.45 秒的 +18% 移速。
     *
     * <p>重复触发刷新时间、不叠层；切界或卸下立即结束。重剑的前摇不算释放，
     * 所以由攻击系统在真正结算那一刻调用（见 {@code PlayerAttackSystem}）。
     */
    public void onShadowAttackReleased() {
        if (equipmentCount(EquipmentType.NIGHTSTEP_CLOAK) > 0) {
            nightstepRemaining = GameConfig.NIGHTSTEP_CLOAK_DURATION;
        }
    }

    /** 夜行披风的加速剩余时间（秒）。 */
    public double getNightstepRemaining() { return nightstepRemaining; }

    /**
     * 推进装备带来的临时效果计时（每帧调用）。
     *
     * <p>计时全部由 {@code dt} 推进，所以暂停时不会偷偷走完——见需求文档
     * 「暂停不推进特效或冷却」。卸下装备立即结束效果，不留残余加成。
     */
    private void updateTemporaryEffects(double dt) {
        double step = Math.max(0.0, dt);
        gyroscopeRemaining = Math.max(0.0, gyroscopeRemaining - step);
        sunweaveCooldownRemaining = Math.max(0.0, sunweaveCooldownRemaining - step);
        // 夜行披风只在影界有效；切入光界立即结束（卸下同理）。
        if (currentWorld == WorldType.LIGHT || equipmentCount(EquipmentType.NIGHTSTEP_CLOAK) == 0) {
            nightstepRemaining = 0.0;
        } else {
            nightstepRemaining = Math.max(0.0, nightstepRemaining - step);
        }
    }

    /** 道具提供的伤害加成。 */
    public double getItemDamageBonus() {
        return itemDamageBonus(currentWorld);
    }

    /** 当前世界的基础伤害（旧的整数入口，等价于 {@code (int) getCurrentBaseDamage()}）。 */
    public int getAttackDamage() {
        return (int) Math.round(getCurrentBaseDamage() * damageMultiplier(currentWorld));
    }

    /**
     * 战斗里的最终伤害乘区（难度倍率由 {@code EnemySystem} 在结算时乘上）。
     *
     * @see #damageMultiplier(WorldType)
     */
    public double getAttackDamageMultiplier() {
        return damageMultiplier(currentWorld);
    }

    /**
     * 装备栏里是否有这件装备（数量无关的“有没有”查询）。
     *
     * <p>只为开关型效果准备（例如「棱光三叉杖」把光弹改成三向散射，装两把并不会变成六向）。
     * 凡是数值增益都必须走 {@link #equipmentCount(EquipmentType)}，否则多带一件就白带。
     */
    public boolean hasEquipment(EquipmentType item) { return equipmentCount(item) > 0; }
    /** 同名装备允许占用多个槽位；所有调用方都应按数量结算可叠加增益。 */
    public int equipmentCount(EquipmentType item) {
        if (item == null) return 0;
        return (int) equipment.stream().filter(item::equals).count();
    }
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
