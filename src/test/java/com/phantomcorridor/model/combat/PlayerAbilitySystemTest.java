package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.*;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.entity.PlayerAnimationState;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.room.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlayerAbilitySystemTest {
    private RoomNavigationSystem navigation(Wall... walls) {
        var nav = new RoomNavigationSystem();
        nav.reset(new DungeonMap(List.of(new Room(0, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, 1280, 720)), List.of(walls)))));
        return nav;
    }
    private Enemy target(EnemySystem enemies, double x, double y) {
        Enemy enemy = enemies.spawnForTest(EnemyKind.WATCHER, WorldType.LIGHT, 1, Difficulty.NORMAL);
        enemy.setPosition(x, y + enemy.getY() - enemy.getHitboxCenterY());
        return enemy;
    }

    @Test void skillSpendsSixEnergyRecoversSlowlyAndBasicAttackWorksWhenEmpty() {
        Player player = new Player(300, 300);
        var abilities = new PlayerAbilitySystem();
        var enemies = new EnemySystem(); var nav = navigation();
        assertTrue(abilities.tryCast(player, false, 450, 300, nav));
        assertEquals(10, player.getSkillEnergy());
        assertFalse(abilities.tryCast(player, false, 450, 300, nav));
        assertEquals(10, player.getSkillEnergy());
        player.updateSkillEnergy(1.49);
        assertEquals(10, player.getSkillEnergy());
        player.updateSkillEnergy(0.01);
        assertEquals(11, player.getSkillEnergy());
        abilities.update(4, player, enemies, nav);
        player.updateAnimation(4, 0, 0, false);
        assertTrue(abilities.tryCast(player, false, 450, 300, nav));
        assertEquals(5, player.getSkillEnergy());
        abilities.update(4, player, enemies, nav);
        player.updateAnimation(4, 0, 0, false);
        assertFalse(abilities.tryCast(player, false, 450, 300, nav));
        assertEquals(0, abilities.skillCooldown());
        assertTrue(player.consumeSkillEnergy(5));
        assertTrue(new PlayerAttackSystem().tryAttack(player, 450, 300));
        assertEquals(0, player.getSkillEnergy());
    }

    @Test void lightSkillHasWindupLockedTargetAndOnlyHitsOnce() {
        Player player = new Player(300, 300); var nav = navigation();
        var enemies = new EnemySystem(); var abilities = new PlayerAbilitySystem();
        Enemy enemy = target(enemies, 500, 300); int before = enemy.getHp();
        assertTrue(abilities.tryCast(player, false, 500, 300, nav));
        assertEquals(PlayerAnimationState.SKILL, player.getAnimationState());
        assertFalse(new PlayerAttackSystem().tryAttack(player, 500, 300));
        assertFalse(player.tryStartDash(1, 0));
        abilities.update(0.23, player, enemies, nav);
        assertEquals(before, enemy.getHp());
        player.setPosition(200, 200);
        abilities.update(0.02, player, enemies, nav);
        assertTrue(enemy.getHp() < before);
        int after = enemy.getHp();
        abilities.update(1, player, enemies, nav);
        assertEquals(after, enemy.getHp());
        assertEquals(1, enemy.getScorchStacks());
    }

    @Test void shadowConeExcludesEnemiesBehindPlayerAndRespectsPhaseWalls() {
        for (WorldType wallWorld : WorldType.values()) {
            Player player = new Player(300, 300); player.toggleWorld();
            var enemies = new EnemySystem(); var abilities = new PlayerAbilitySystem();
            var nav = navigation(new Wall(390, 100, 20, 500, wallWorld));
            Enemy front = target(enemies, 460, 300), rear = target(enemies, 140, 300);
            int before = front.getHp();
            assertTrue(abilities.tryCast(player, false, 500, 300, nav));
            abilities.update(0.25, player, enemies, nav);
            assertEquals(wallWorld == WorldType.LIGHT, front.getHp() < before);
            assertEquals(before, rear.getHp());
        }
    }

    @Test void blockedLightTargetCostsNothing() {
        var player = new Player(300, 300); var abilities = new PlayerAbilitySystem();
        assertFalse(abilities.tryCast(player, false, 500, 300, navigation(new Wall(390, 100, 20, 500, null))));
        assertEquals(16, player.getSkillEnergy());
        assertEquals(0, abilities.skillCooldown());
    }

    @Test void finisherNeedsFullPhaseAndCooldownAndDoesNotRefundScorchEnergy() {
        for (WorldType world : WorldType.values()) {
            Player player = new Player(400, 300);
            if (world == WorldType.SHADOW) player.toggleWorld();
            var abilities = new PlayerAbilitySystem(); var enemies = new EnemySystem(); var nav = navigation();
            assertFalse(abilities.tryCast(player, true, 600, 300, nav));
            player.restorePhaseEnergy(99);
            assertFalse(abilities.tryCast(player, true, 600, 300, nav));
            assertEquals(99, player.getPhaseEnergy());
            player.restorePhaseEnergy(1);
            Enemy target = target(enemies, 550, 300);
            target.addScorch(); target.addScorch(); target.addScorch();
            assertTrue(abilities.tryCast(player, true, 600, 300, nav));
            assertEquals(PlayerAnimationState.FINISHER, player.getAnimationState());
            assertEquals(0, player.getPhaseEnergy());
            assertEquals(16, player.getSkillEnergy());
            abilities.update(0.46, player, enemies, nav);
            assertTrue(target.getHp() < target.getMaxHp());
            assertEquals(0, player.getPhaseEnergy());
            player.restorePhaseEnergy(100);
            assertFalse(abilities.tryCast(player, true, 600, 300, nav));
            assertEquals(100, player.getPhaseEnergy());
            abilities.update(30, player, enemies, nav);
            player.updateAnimation(30, 0, 0, false);
            assertTrue(abilities.tryCast(player, true, 600, 300, nav));
        }
    }

    @Test void cancellationAndDeathDoNotDeliverDelayedDamageOrResetCooldowns() {
        for (boolean death : new boolean[]{false, true}) {
            Player player = new Player(300, 300); var abilities = new PlayerAbilitySystem();
            var enemies = new EnemySystem(); var nav = navigation();
            Enemy target = target(enemies, 450, 300);
            player.restorePhaseEnergy(100);
            assertTrue(abilities.tryCast(player, true, 450, 300, nav));
            if (death) { player.clearShield(); player.takeDamage(10000); }
            else abilities.clearRoomEffects();
            abilities.update(0.5, player, enemies, nav);
            assertEquals(target.getMaxHp(), target.getHp());
            assertTrue(abilities.finisherCooldown() > 29);
            assertEquals(0, player.getPhaseEnergy());
        }
    }

    @Test void sessionUsesFreeShiftAndKeepsTimersFrozenWithoutSimulationTime() {
        var session = new GameSession(); session.newRun("abilities");
        Player player = session.getPlayer(); player.restorePhaseEnergy(100);
        assertTrue(session.tryShiftWorld());
        assertEquals(100, player.getPhaseEnergy());
        assertTrue(session.tryUseAbility(true, player.getX() + 100, player.getY()));
        assertFalse(session.tryShiftWorld());
        session.update(0, 0, 0, player.getX(), player.getY(), false);
        assertEquals(30, session.getAbilities().finisherCooldown());
        assertTrue(session.getAbilities().isCasting());
        session.newRun("abilities");
        assertEquals(0, session.getAbilities().finisherCooldown());
        assertEquals(0, player.getPhaseEnergy());
        assertEquals(16, player.getSkillEnergy());
    }
}
