package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;

/** 管理双形态的右键动作及按键边沿，不依赖控制器或 JavaFX。 */
public final class PlayerStanceSystem {
    private boolean secondaryHeld;
    private boolean secondaryWasHeld;
    private boolean attackWasHeld;

    public void setSecondaryHeld(boolean held) { secondaryHeld = held; }

    public void reset() {
        secondaryHeld = false;
        secondaryWasHeld = false;
        attackWasHeld = false;
    }

    /**
     * 推进形态右键机制：光形态蓄力、影形态格挡。
     *
     * <p>右键的语义按当前形态切换：
     * <ul>
     *   <li><b>光形态</b>：按住蓄力（伤害 1 → 5 倍线性增长、移速 ×0.5），
     *       松开右键 / 蓄满 / 按下左键 三种时机都能把这一发打出去；</li>
     *   <li><b>影形态</b>：按下即开启 1.5 秒的格挡窗口，窗口内完全免伤，
     *       首次挡下伤害会攒下一次"强化暗影普攻"（×10）。</li>
     * </ul>
     *
     * <p>按下/松开的边沿检测放在这里而不是输入层：控制器只该上报"右键是否按着"，
     * "松开才发射"属于玩法规则，不属于输入转发。
     */
    public void update(Player player, PlayerAttackSystem attackSystem, boolean attacking,
                       double aimX, double aimY) {
        boolean pressed = secondaryHeld && !secondaryWasHeld;
        boolean released = !secondaryHeld && secondaryWasHeld;
        secondaryWasHeld = secondaryHeld;
        boolean attackPressed = attacking && !attackWasHeld;
        attackWasHeld = attacking;

        if (player.getCurrentWorld() != WorldType.LIGHT) {
            // 影形态：一次按下开一次窗口，长按不会反复触发（窗口本身最长 1.5 秒）。
            if (pressed) player.beginBlock();
            return;
        }
        if (pressed) player.beginCharge();
        if (!player.isCharging()) return;
        // 蓄满自动发射：玩家不需要盯着进度条掐点松手，蓄满就是"已经准备好"。
        if (released || player.isChargeFull() || attackPressed) fireChargedShot(player, attackSystem, aimX, aimY);
    }

    /** 把当前蓄力打出去；不足最短蓄力时间视为误触，直接放弃且不扣能量。 */
    private void fireChargedShot(Player player, PlayerAttackSystem attackSystem, double aimX, double aimY) {
        if (!player.isChargeFireable()) {
            player.cancelCharge();
            return;
        }
        // 是否蓄满要在扣能量之前取：蓄满那一发带无限穿透与叠满灼痕。
        boolean fullCharge = player.isChargeFull();
        double multiplier = player.consumeCharge();
        if (multiplier <= 0.0) return;
        if (!attackSystem.fireChargedShot(player, aimX, aimY, multiplier, fullCharge)) {
            // 打不出去（施法中 / 冲刺中）：把已经扣掉的能量退回来，不能白花蓝量。
            player.restoreAttackCharges(GameConfig.LIGHT_CHARGE_ENERGY_COST);
        }
    }

}
