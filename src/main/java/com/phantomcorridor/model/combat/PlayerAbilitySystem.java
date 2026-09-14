package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.RoomNavigationSystem;

/** One queued cast and its visual lifetime; all timers advance only with simulation time. */
public final class PlayerAbilitySystem {
    public record Strike(WorldType world, boolean finisher, double x, double y,
                         double originX, double originY, double angle, double radius,
                         double arc, double coefficient, double castTime) {
        public String name() {
            return world == WorldType.LIGHT ? (finisher ? "黎明裁决" : "凝光爆破")
                    : (finisher ? "永夜葬刃" : "裂影斩");
        }
        public boolean covers(double targetX, double targetY, double targetRadius) {
            double dx = targetX - x, dy = targetY - y;
            double distance = Math.hypot(dx, dy);
            if (distance > radius + targetRadius) return false;
            if (arc >= 360 || distance <= targetRadius) return true;
            double delta = Math.atan2(Math.sin(Math.atan2(dy, dx) - angle),
                    Math.cos(Math.atan2(dy, dx) - angle));
            return Math.abs(delta) <= Math.toRadians(arc / 2)
                    + Math.asin(Math.min(1, targetRadius / distance));
        }
    }

    private Strike pending, visual;
    private double castRemaining, visualRemaining, skillCooldown, finisherCooldown;
    private String feedback = "";
    private double feedbackRemaining;

    public void reset() {
        clearRoomEffects();
        skillCooldown = finisherCooldown = 0;
    }
    /** Leaving a room cancels queued damage, but never resets paid costs or cooldowns. */
    public void clearRoomEffects() {
        pending = visual = null;
        castRemaining = visualRemaining = feedbackRemaining = 0;
        feedback = "";
    }
    public boolean tryCast(Player player, boolean finisher, double aimX, double aimY,
                           RoomNavigationSystem navigation) {
        if (player.getHp() <= 0 || player.isDashing() || isCasting()) return reject("当前动作无法施法");
        if ((finisher ? finisherCooldown : skillCooldown) > 0) return reject("招式冷却中");
        if (finisher && player.getPhaseEnergy() < GameConfig.FINISHER_PHASE_COST) return reject("终结技需要 100 相位");
        if (!finisher && player.getSkillEnergy() < GameConfig.SKILL_ENERGY_COST) return reject("技能需要 6 点蓝量");
        double dx = aimX - player.getX(), dy = aimY - player.getY();
        double distance = Math.hypot(dx, dy);
        if (distance < 0.001) { dx = player.getFacingX(); dy = player.getFacingY(); distance = 1; }
        double angle = Math.atan2(dy, dx);
        boolean light = player.getCurrentWorld() == WorldType.LIGHT;
        double x = player.getX(), y = player.getY();
        if (light && !finisher) {
            double reach = Math.min(260, distance);
            x += dx / distance * reach;
            y += dy / distance * reach;
            if (!navigation.isSegmentClear(player.getX(), player.getY(), x, y, 1, player.getCurrentWorld())) {
                return reject("目标被实体墙遮挡");
            }
        }
        double radius = finisher ? (light ? 330 : 280) : (light ? 100 : 210);
        double castTime = finisher ? 0.45 : 0.24;
        pending = new Strike(player.getCurrentWorld(), finisher, x, y, player.getX(), player.getY(),
                angle, radius, !finisher && !light ? 140 : 360,
                finisher ? (light ? 8 : 10) : (light ? 3 : 4), castTime);
        if (finisher) {
            player.consumePhaseEnergy(GameConfig.FINISHER_PHASE_COST);
            finisherCooldown = GameConfig.FINISHER_COOLDOWN;
        } else {
            player.consumeSkillEnergy(GameConfig.SKILL_ENERGY_COST);
            skillCooldown = GameConfig.SKILL_COOLDOWN;
        }
        castRemaining = castTime;
        visual = null;
        visualRemaining = 0;
        player.startAbilityAnimation(finisher, dx, dy, castTime);
        feedback = pending.name();
        feedbackRemaining = 1.2;
        return true;
    }
    private boolean reject(String reason) { feedback = reason; feedbackRemaining = 1.2; return false; }

    public void update(double dt, Player player, EnemySystem enemies, RoomNavigationSystem navigation) {
        double step = Math.max(0, dt);
        skillCooldown = Math.max(0, skillCooldown - step);
        finisherCooldown = Math.max(0, finisherCooldown - step);
        visualRemaining = Math.max(0, visualRemaining - step);
        feedbackRemaining = Math.max(0, feedbackRemaining - step);
        if (player.getHp() <= 0) { clearRoomEffects(); return; }
        if (pending == null) return;
        if (pending.world() != player.getCurrentWorld()) { pending = null; castRemaining = 0; return; }
        castRemaining = Math.max(0, castRemaining - step);
        if (castRemaining > 0) return;
        Strike strike = pending;
        pending = null; // Clear before resolving so the damage event can never replay.
        enemies.resolveAbility(player, strike, navigation);
        if (strike.finisher()) enemies.clearPulseClearableWithin(strike.x(), strike.y(), strike.radius());
        visual = strike;
        visualRemaining = 0.5;
    }
    public boolean isCasting() { return pending != null; }
    public double skillCooldown() { return skillCooldown; }
    public double finisherCooldown() { return finisherCooldown; }
    public Strike visual() { return pending != null ? pending : visualRemaining > 0 ? visual : null; }
    public double visualProgress() { return pending != null ? 1 - castRemaining / pending.castTime() : 1 - visualRemaining / 0.5; }
    public String feedback() { return feedbackRemaining > 0 ? feedback : ""; }
}
