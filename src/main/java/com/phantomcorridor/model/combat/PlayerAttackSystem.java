package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.RoomNavigationSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 根据当前世界在远程光弹与快速影刃之间切换。 */
public final class PlayerAttackSystem {
    private final List<Projectile> projectiles = new ArrayList<>();
    private double cooldownRemaining;
    private double meleeVisibleRemaining;
    private double meleeAngleRadians;
    private int meleeAttackId;

    public void reset() {
        projectiles.clear();
        cooldownRemaining = 0.0;
        meleeVisibleRemaining = 0.0;
        meleeAngleRadians = 0.0;
        meleeAttackId = 0;
    }

    public void update(double dt) {
        update(dt, null);
    }

    public void update(double dt, RoomNavigationSystem navigation) {
        cooldownRemaining = Math.max(0.0, cooldownRemaining - dt);
        meleeVisibleRemaining = Math.max(0.0, meleeVisibleRemaining - dt);
        projectiles.forEach(projectile -> {
            double oldX = projectile.getX();
            double oldY = projectile.getY();
            projectile.update(dt);
            if (navigation != null && (!navigation.canProjectileOccupy(
                    projectile.getX(), projectile.getY(), projectile.getRadius(), projectile.getWorld())
                    || !navigation.isSegmentClear(oldX, oldY, projectile.getX(), projectile.getY(),
                    projectile.getRadius(), projectile.getWorld()))) projectile.expire();
        });
        projectiles.removeIf(Projectile::isExpired);
    }

    public void clearTransientAttacks() {
        projectiles.clear();
        meleeVisibleRemaining = 0.0;
    }

    public boolean tryAttack(Player player, double targetX, double targetY) {
        if (cooldownRemaining > 0.0) {
            return false;
        }
        double dx = targetX - player.getX();
        double dy = targetY - player.getY();
        double length = Math.hypot(dx, dy);
        if (length < 0.0001) {
            dx = 1.0;
            dy = 0.0;
            length = 1.0;
        }
        double unitX = dx / length;
        double unitY = dy / length;
        meleeAngleRadians = Math.atan2(unitY, unitX);
        if (player.getCurrentWorld() == WorldType.LIGHT) {
            if (!player.consumeAttackCharge()) return false;
            double offset = GameConfig.PLAYER_RADIUS + GameConfig.LIGHT_PROJECTILE_RADIUS + 3.0;
            double cooldown = GameConfig.LIGHT_ATTACK_COOLDOWN;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.FOCUS_LENS)) cooldown *= 1.15;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.PHASE_GYROSCOPE)) cooldown *= 0.85;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.PRISM_FAN_WAND)) {
                // 三叉杖：三发独立弹体，各自接受墙体和命中判定。
                addLightProjectile(player, unitX, unitY, offset, 0.0);
                addLightProjectile(player, unitX, unitY, offset, Math.toRadians(-14.0));
                addLightProjectile(player, unitX, unitY, offset, Math.toRadians(14.0));
                cooldown *= 1.10;
            } else if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.ECLIPSE_RELAY)) {
                addLightProjectile(player, unitX, unitY, offset, 0.0);
                addLightProjectile(player, unitX, unitY, offset, 0.0);
                cooldown *= 1.20;
            } else {
                addLightProjectile(player, unitX, unitY, offset, 0.0);
            }
            cooldownRemaining = cooldown;
        } else {
            if (!player.consumeAttackCharge()) return false;
            meleeVisibleRemaining = GameConfig.SHADOW_MELEE_VISIBLE_TIME;
            meleeAttackId++;
            double cooldown = GameConfig.SHADOW_ATTACK_COOLDOWN;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.FOCUS_LENS)) cooldown *= 1.15;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.PHASE_GYROSCOPE)) cooldown *= 0.85;
            if (player.hasEquipment(com.phantomcorridor.model.EquipmentType.NIGHTFALL_GREATSWORD)) cooldown *= 1.65;
            cooldownRemaining = cooldown;
        }
        return true;
    }

    private void addLightProjectile(Player player, double unitX, double unitY, double offset, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double rotatedX = unitX * cos - unitY * sin;
        double rotatedY = unitX * sin + unitY * cos;
        projectiles.add(new Projectile(
                player.getX() + rotatedX * offset, player.getY() + rotatedY * offset,
                rotatedX * GameConfig.LIGHT_PROJECTILE_SPEED,
                rotatedY * GameConfig.LIGHT_PROJECTILE_SPEED,
                GameConfig.LIGHT_PROJECTILE_RADIUS, WorldType.LIGHT,
                GameConfig.LIGHT_PROJECTILE_LIFETIME));
    }

    public List<Projectile> getProjectiles() {
        return Collections.unmodifiableList(projectiles);
    }

    public boolean isMeleeVisible() { return meleeVisibleRemaining > 0.0; }
    public double getMeleeAngleRadians() { return meleeAngleRadians; }
    public int getMeleeAttackId() { return meleeAttackId; }
    public double getCooldownRemaining() { return cooldownRemaining; }
}
