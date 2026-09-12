package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.*;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.effect.WorldShiftSystem;
import com.phantomcorridor.model.entity.*;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.room.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedWorldCombatTest {
    private RoomNavigationSystem navigation() {
        RoomNavigationSystem nav = new RoomNavigationSystem();
        nav.reset(new DungeonMap(List.of(new Room(0, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, 1280, 720)), List.of()))));
        return nav;
    }

    @Test void lightThenShadowDamagesTheSameEnemyAndRestoresEnergyOnlyOnce() {
        for (WorldType enemyForm : WorldType.values()) {
        Player player = new Player(300, 300);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        EnemySystem enemies = new EnemySystem();
        Enemy target = enemies.spawnForTest(EnemyKind.GOLEM, enemyForm, 1, Difficulty.NORMAL);
        target.setPosition(340, 300 + EnemyKind.GOLEM.hitboxCenterOffset());
        int hp = target.getHp();
        assertTrue(attacks.tryAttack(player, 500, 300));
        enemies.update(0, player, attacks, navigation());
        assertTrue(target.getHp() < hp);
        assertEquals(1, target.getScorchStacks());
        player.consumePhaseEnergy(100);
        WorldShiftSystem shift = new WorldShiftSystem();
        assertTrue(shift.tryShift(player));
        attacks.cancelForWorldShift();
        assertTrue(attacks.tryAttack(player, 500, 300));
        hp = target.getHp();
        enemies.update(0, player, attacks, navigation());
        assertTrue(target.getHp() < hp);
        assertEquals(0, target.getScorchStacks());
        assertEquals(GameConfig.SCORCH_ENERGY_PER_STACK, player.getPhaseEnergy());
        hp = target.getHp();
        enemies.update(0, player, attacks, navigation());
        assertEquals(hp, target.getHp());
        assertEquals(GameConfig.SCORCH_ENERGY_PER_STACK, player.getPhaseEnergy());
        assertSame(target, enemies.getEnemies().getFirst());
        }
    }

    @Test void scorchHasCapExpiryAndSurvivesFormChange() {
        Enemy enemy = new Enemy(EnemyKind.BEETLE, WorldType.LIGHT, 200, 200, 1);
        for (int i = 0; i < 10; i++) enemy.addScorch();
        assertEquals(GameConfig.SCORCH_MAX_STACKS, enemy.getScorchStacks());
        enemy.setWorld(WorldType.SHADOW);
        assertEquals(GameConfig.SCORCH_MAX_STACKS, enemy.getScorchStacks());
        enemy.updateTimers(0);
        assertEquals(GameConfig.SCORCH_MAX_STACKS, enemy.getScorchStacks());
        enemy.updateTimers(GameConfig.SCORCH_DURATION);
        assertEquals(0, enemy.consumeScorch());
    }

    @Test void weakFormsAndCooperationHaveDistinctPayoffs() {
        var light = EnemyKind.WOLF.affinity();
        var shadow = EnemyKind.MAGE.affinity();
        var coupled = EnemyKind.GOLEM.affinity();
        assertTrue(light.damageMultiplier(WorldType.LIGHT, false) > light.damageMultiplier(WorldType.SHADOW, false));
        assertTrue(shadow.damageMultiplier(WorldType.SHADOW, false) > shadow.damageMultiplier(WorldType.LIGHT, false));
        assertTrue(coupled.damageMultiplier(WorldType.SHADOW, true) > coupled.damageMultiplier(WorldType.SHADOW, false));
    }

    @Test void basicShiftKeepsEnergyAndHasOnlyTemporarySlow() {
        Player player = new Player(300, 300);
        player.consumePhaseEnergy(65);
        WorldShiftSystem shift = new WorldShiftSystem();
        assertTrue(shift.tryShift(player));
        assertEquals(35, player.getPhaseEnergy());
        assertFalse(shift.consumePulse());
        assertFalse(player.isHitInvulnerable());
        double slowed = player.movementSpeed();
        player.updateAnimation(0.3, 0, 0, false, false);
        assertFalse(player.isShiftSlowed());
        assertTrue(player.movementSpeed() > slowed);
        assertFalse(shift.tryShift(player));
    }

    @Test void empoweredShiftGrantsProtectionAndPulse() {
        Player player = new Player(300, 300);
        WorldShiftSystem shift = new WorldShiftSystem();
        assertTrue(shift.tryShift(player));
        assertTrue(shift.consumePulse());
        assertFalse(shift.consumePulse());
        assertTrue(player.isHitInvulnerable());
        assertFalse(player.isShiftSlowed());
        assertFalse(player.takeDamage(10));
        player.updateAnimation(0.3, 0, 0, false, false);
        assertFalse(player.isHitInvulnerable());
    }

    @Test void shiftCancelsRecoveryAndClearsOldProjectiles() {
        Player player = new Player(300, 300);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        assertTrue(attacks.tryAttack(player, 500, 300));
        assertFalse(attacks.tryAttack(player, 500, 300));
        attacks.cancelForWorldShift();
        player.toggleWorld();
        assertTrue(attacks.tryAttack(player, 500, 300));
        assertTrue(attacks.getProjectiles().isEmpty());
    }
}
