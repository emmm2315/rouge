package com.phantomcorridor.model.effect;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.entity.Player;

/** 双档切界：普通切界减速，满能量切界消耗能量并提供脉冲与无敌。 */
public final class WorldShiftSystem {

    private double cooldownRemaining;
    private boolean pulsePending;

    public void reset() {
        cooldownRemaining = 0.0;
        pulsePending = false;
    }

    public void update(double dt) {
        cooldownRemaining = Math.max(0.0, cooldownRemaining - Math.max(0.0, dt));
    }

    public boolean tryShift(Player player) {
        if (cooldownRemaining > 0.0 || player.getHp() <= 0) {
            return false;
        }
        boolean empowered = player.getPhaseEnergy() >= GameConfig.PHASE_ENERGY_PER_SWITCH;
        player.toggleWorld();
        if (empowered) player.consumePhaseEnergy(GameConfig.PHASE_ENERGY_PER_SWITCH);
        player.applyShiftProtection(empowered);
        cooldownRemaining = GameConfig.WORLD_SWITCH_COOLDOWN;
        pulsePending = empowered;
        return true;
    }

    public boolean consumePulse() {
        boolean result = pulsePending;
        pulsePending = false;
        return result;
    }

    public double getCooldownRemaining() {
        return cooldownRemaining;
    }
}
