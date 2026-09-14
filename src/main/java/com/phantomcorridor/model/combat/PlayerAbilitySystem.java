package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.RoomNavigationSystem;

/** One queued cast and its visual lifetime; all timers advance only with simulation time. */
public final class PlayerAbilitySystem {
    /** 玩家主动招式的槽位。每种形态有两个技能，终结技单独消耗相位碎片。 */
    public enum AbilitySlot {
        SKILL_1(0), SKILL_2(1);

        private final int index;
        AbilitySlot(int index) { this.index = index; }
        public int index() { return index; }
    }

    public record Strike(WorldType world, boolean finisher, int skillIndex, double x, double y,
                         double originX, double originY, double angle, double radius,
                         double arc, double coefficient, double castTime) {
        /** 保留旧构造签名，外部模型/测试默认使用第一技能。 */
        public Strike(WorldType world, boolean finisher, double x, double y,
                      double originX, double originY, double angle, double radius,
                      double arc, double coefficient, double castTime) {
            this(world, finisher, 0, x, y, originX, originY, angle, radius,
                    arc, coefficient, castTime);
        }
        public String name() {
            if (finisher) return world == WorldType.LIGHT ? "黎明裁决" : "永夜葬刃";
            if (world == WorldType.LIGHT) return skillIndex == 0 ? "凝光爆破" : "星辉棱刺";
            return skillIndex == 0 ? "裂影斩" : "幽冥锁链";
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
    private double castRemaining, visualRemaining, finisherCooldown;
    private final double[] skillCooldowns = new double[2];
    private String feedback = "";
    private double feedbackRemaining;

    public void reset() {
        clearRoomEffects();
        skillCooldowns[0] = skillCooldowns[1] = finisherCooldown = 0;
    }
    /** Leaving a room cancels queued damage, but never resets paid costs or cooldowns. */
    public void clearRoomEffects() {
        pending = visual = null;
        castRemaining = visualRemaining = feedbackRemaining = 0;
        feedback = "";
    }
    public boolean tryCast(Player player, boolean finisher, double aimX, double aimY,
                           RoomNavigationSystem navigation) {
        return tryCast(player, finisher ? -1 : 0, finisher, aimX, aimY, navigation);
    }

    /** 尝试释放指定技能槽；技能槽 0 对应 Q，技能槽 1 对应 F。 */
    public boolean tryCast(Player player, int skillIndex, double aimX, double aimY,
                           RoomNavigationSystem navigation) {
        return tryCast(player, skillIndex, false, aimX, aimY, navigation);
    }

    public boolean tryCast(Player player, AbilitySlot slot, double aimX, double aimY,
                           RoomNavigationSystem navigation) {
        return tryCast(player, slot == null ? 0 : slot.index(), aimX, aimY, navigation);
    }

    /** 终结技入口，统一走同一套相位碎片检查和施法状态机。 */
    public boolean tryCastFinisher(Player player, double aimX, double aimY,
                                   RoomNavigationSystem navigation) {
        return tryCast(player, -1, true, aimX, aimY, navigation);
    }

    private boolean tryCast(Player player, int skillIndex, boolean finisher,
                            double aimX, double aimY, RoomNavigationSystem navigation) {
        if (player.getHp() <= 0 || player.isDashing() || isCasting()) return reject("当前动作无法施法");
        if (!finisher && (skillIndex < 0 || skillIndex >= skillCooldowns.length)) return reject("未知技能");
        if ((finisher ? finisherCooldown : skillCooldowns[skillIndex]) > 0) return reject("招式冷却中");
        if (finisher && player.getPhaseEnergy() < GameConfig.FINISHER_PHASE_COST) {
            return reject("终结技需要 " + (int) GameConfig.FINISHER_PHASE_COST + " 点相位能量");
        }
        if (!finisher && player.getSkillEnergy() < GameConfig.SKILL_ENERGY_COST) return reject("技能需要 6 点蓝量");
        double dx = aimX - player.getX(), dy = aimY - player.getY();
        double distance = Math.hypot(dx, dy);
        if (distance < 0.001) { dx = player.getFacingX(); dy = player.getFacingY(); distance = 1; }
        double angle = Math.atan2(dy, dx);
        boolean light = player.getCurrentWorld() == WorldType.LIGHT;
        double x = player.getX(), y = player.getY();
        if (light && !finisher) {
            double reach = skillIndex == 0 ? Math.min(260, distance) : Math.min(190, distance);
            x += dx / distance * reach;
            y += dy / distance * reach;
            if (!navigation.isSegmentClear(player.getX(), player.getY(), x, y, 1, player.getCurrentWorld())) {
                return reject("目标被实体墙遮挡");
            }
        }
        double radius = finisher ? (light ? 330 : 280)
                : light ? (skillIndex == 0 ? 100 : 145) : (skillIndex == 0 ? 210 : 175);
        double arc = finisher ? 360 : light ? (skillIndex == 0 ? 360 : 110)
                : (skillIndex == 0 ? 140 : 85);
        double coefficient = finisher ? (light ? 8 : 10)
                : light ? (skillIndex == 0 ? 3 : 2.4) : (skillIndex == 0 ? 4 : 3.2);
        double castTime = finisher ? 0.45 : skillIndex == 0 ? 0.24 : 0.32;
        pending = new Strike(player.getCurrentWorld(), finisher, Math.max(0, skillIndex), x, y,
                player.getX(), player.getY(), angle, radius, arc, coefficient, castTime);
        if (finisher) {
            player.consumePhaseEnergy(GameConfig.FINISHER_PHASE_COST);
            finisherCooldown = GameConfig.FINISHER_COOLDOWN;
        } else {
            player.consumeSkillEnergy(GameConfig.SKILL_ENERGY_COST);
            skillCooldowns[skillIndex] = GameConfig.SKILL_COOLDOWN;
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
        for (int i = 0; i < skillCooldowns.length; i++) {
            skillCooldowns[i] = Math.max(0, skillCooldowns[i] - step);
        }
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
    /** 第一技能冷却（兼容旧 HUD/调用方）。 */
    public double skillCooldown() { return skillCooldowns[0]; }
    public double skillCooldown(int skillIndex) {
        return skillIndex < 0 || skillIndex >= skillCooldowns.length ? 0.0 : skillCooldowns[skillIndex];
    }
    public double[] skillCooldowns() { return skillCooldowns.clone(); }
    public double finisherCooldown() { return finisherCooldown; }
    public Strike visual() { return pending != null ? pending : visualRemaining > 0 ? visual : null; }
    public double visualProgress() { return pending != null ? 1 - castRemaining / pending.castTime() : 1 - visualRemaining / 0.5; }
    public String feedback() { return feedbackRemaining > 0 ? feedback : ""; }
}
