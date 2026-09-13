package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.*;
import com.phantomcorridor.model.entity.*;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.room.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DifficultyAndPhaseWallTest {
    private RoomNavigationSystem navigation(WorldType wallWorld) {
        Room room = new Room(0, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, 1000, 700)),
                List.of(new Wall(450, 100, 20, 500, wallWorld)));
        var nav = new RoomNavigationSystem();
        nav.reset(new DungeonMap(List.of(room)));
        return nav;
    }

    @Test void projectileCollisionUsesActiveTerrainWithoutChangingDamageWorld() {
        for (WorldType shotWorld : WorldType.values()) {
            for (WorldType wallWorld : WorldType.values()) {
                for (WorldType activeWorld : WorldType.values()) {
                    EnemyAttack shot = new EnemyAttack(400, 300, 600, 0, 6, shotWorld, EnemyKind.LANTERN, 3);
                    shot.update(0.2); // Cross the entire thin wall in one simulation step.
                    boolean alive = new EnemySystem().advanceOrBounce(shot, 400, 300, 0.2,
                            navigation(wallWorld), activeWorld);
                    assertEquals(wallWorld != activeWorld, alive);
                    assertEquals(shotWorld, shot.getWorld());
                    assertEquals(DamageType.ofWorld(shotWorld), shot.getDamageType());
                }
            }
        }
    }

    @Test void bouncingShotsIgnoreInactiveWallsButStillBounceOnSharedWalls() {
        for (WorldType wallWorld : new WorldType[]{WorldType.LIGHT, WorldType.SHADOW, null}) {
            EnemyAttack shot = new EnemyAttack(420, 300, 200, 0, 6, WorldType.LIGHT,
                    EnemyKind.LANTERN, 3, "", 0, 0, 10, null, 0, 2);
            shot.update(0.2);
            assertTrue(new EnemySystem().advanceOrBounce(shot, 420, 300, 0.2,
                    navigation(wallWorld), WorldType.SHADOW));
            assertEquals(wallWorld == WorldType.LIGHT ? 2 : 1, shot.getBouncesRemaining());
        }
    }

    @Test void allBossesLoseThirtyPercentInsaneHealthWithoutWeakeningOrdinaryEnemies() {
        for (EnemyKind kind : EnemyKind.values()) {
            for (int floor = 1; floor <= 5; floor++) {
                int hp = new Enemy(kind, WorldType.LIGHT, 0, 0, floor, Difficulty.INSANE).getMaxHp();
                double oldHp = kind.hitPoints() * (1 + (floor - 1) * 0.25) * 2;
                assertEquals(Math.round(oldHp * (kind.boss() ? 0.7 : 1)), hp);
            }
        }
    }

    @Test void equipmentBenefitAppliesToBothFormsAndDisappearsWhenUnequippedOrRestarted() {
        var session = new GameSession();
        session.newRun("balance", Difficulty.INSANE);
        Player player = session.getPlayer();
        assertEquals(0, player.equipmentDamageBonus(WorldType.LIGHT));
        player.equip(EquipmentType.DAWN_WAND);
        assertEquals(0.3, player.equipmentDamageBonus(WorldType.LIGHT), 1e-9);
        assertEquals(0.1, player.equipmentDamageBonus(WorldType.SHADOW), 1e-9);
        player.equip(EquipmentType.PHASE_VESSEL);
        player.equip(EquipmentType.WAYFARER_HEART);
        assertEquals(0.5, player.equipmentDamageBonus(WorldType.LIGHT), 1e-9);
        player.removeEquipment(0);
        assertEquals(0.2, player.equipmentDamageBonus(WorldType.LIGHT), 1e-9);
        session.newRun("balance", Difficulty.NORMAL);
        player.equip(EquipmentType.DAWN_WAND);
        assertEquals(0.2, player.equipmentDamageBonus(WorldType.LIGHT), 1e-9);
    }

    @Test void actualEnemyProjectilesUseIndependentDifficultyDamage() {
        for (Difficulty difficulty : Difficulty.values()) {
            var system = new EnemySystem();
            system.setDifficulty(difficulty);
            Player player = new Player(700, 300);
            var enemy = system.spawnForTest(EnemyKind.LANTERN, WorldType.LIGHT, 1, difficulty);
            enemy.setPosition(200, 300);
            system.castForTest(enemy, player, EnemySkill.LANTERN_SEEKER);
            var nav = navigation(WorldType.SHADOW);
            for (int i = 0; i < 200 && system.getAttacks().isEmpty(); i++) {
                system.update(0.01, player, new PlayerAttackSystem(), nav);
            }
            assertFalse(system.getAttacks().isEmpty());
            assertEquals(EnemySkill.LANTERN_SEEKER.damage() * difficulty.enemyDamageMultiplier(),
                    system.getAttacks().getFirst().getDamage(), 1e-9);
        }
    }

    @Test void anAlreadyFlyingLightShotPassesLightWallAfterPlayerChangesPhase() {
        for (boolean shift : new boolean[]{false, true}) {
            var system = new EnemySystem();
            Player player = new Player(800, 300);
            var enemy = system.spawnForTest(EnemyKind.LANTERN, WorldType.LIGHT, 1, Difficulty.NORMAL);
            enemy.setPosition(200, 300);
            system.castForTest(enemy, player, EnemySkill.LANTERN_SEEKER);
            var nav = navigation(WorldType.LIGHT);
            for (int i = 0; i < 200 && system.getAttacks().isEmpty(); i++) {
                system.update(0.01, player, new PlayerAttackSystem(), nav);
            }
            assertFalse(system.getAttacks().isEmpty());
            var shot = system.getAttacks().getFirst();
            shot.setPosition(430, 300);
            if (shift) player.toggleWorld();
            system.update(0.3, player, new PlayerAttackSystem(), nav);
            assertEquals(shift, system.getAttacks().contains(shot));
            if (shift) assertTrue(shot.getX() > 470, "The live shot must cross the wall, not just remain before it");
            assertEquals(WorldType.LIGHT, shot.getWorld());
        }
    }
}
