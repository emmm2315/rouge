package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.*;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BattleWaveTest {
    private Room room() {
        return new Room(0, RoomType.BATTLE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, 1280, 720)), List.of());
    }
    private RoomNavigationSystem navigation(Room room) {
        var nav = new RoomNavigationSystem();
        nav.reset(new DungeonMap(List.of(room)));
        return nav;
    }
    @Test void allWaveCountsAreBoundedAndEachWaveMustBeCleared() {
        Set<Integer> counts = new HashSet<>();
        for (Difficulty difficulty : Difficulty.values()) {
        Set<Integer> sizes = new HashSet<>();
        int min = difficulty == Difficulty.INSANE ? 3 : 2;
        int max = difficulty == Difficulty.HARD || difficulty == Difficulty.INSANE ? 4 : 3;
        for (int seed = 0; seed < 100; seed++) {
            Room room = room();
            var nav = navigation(room);
            var system = new EnemySystem();
            var player = new Player(640, 360);
            var attacks = new PlayerAttackSystem();
            system.setFloor(5);
            system.setDifficulty(difficulty);
            system.enterRoom(room, seed, player, nav);
            int total = system.getTotalWaves();
            counts.add(total);
            assertTrue(total >= 1 && total <= 3);
            for (int wave = 1; wave <= total; wave++) {
                assertEquals(wave, system.getCurrentWave());
                int size = system.getEnemies().size();
                sizes.add(size);
                assertTrue(size >= min && size <= max, difficulty + " wave size " + size);
                system.getEnemies().forEach(e -> e.damage(99999));
                system.update(0, player, attacks, nav);
                assertEquals(wave == total, system.isRoomCleared());
                if (wave < total) {
                    assertTrue(system.isBetweenWaves());
                    system.update(0, player, attacks, nav);
                    assertTrue(system.getEnemies().isEmpty());
                    system.update(GameConfig.BATTLE_WAVE_INTERVAL / 2, player, attacks, nav);
                    assertTrue(system.getEnemies().isEmpty());
                    assertFalse(system.isRoomCleared());
                    system.update(GameConfig.BATTLE_WAVE_INTERVAL / 2, player, attacks, nav);
                }
            }
            system.update(5, player, attacks, nav);
            assertTrue(system.isRoomCleared());
        }
        assertTrue(sizes.contains(min) && sizes.contains(max), difficulty + " should cover both endpoints");
        }
        assertEquals(Set.of(1, 2, 3), counts);
    }
    @Test void newWaveEnemiesSpawnAwayFromPlayerAndWaitBeforeMoving() {
        var system = new EnemySystem(); Room room = room();
        var nav = navigation(room); var player = new Player(640, 360);
        system.enterRoom(room, 123, player, nav);
        var target = system.getEnemies().getFirst();
        double x = target.getX(), y = target.getY();
        for (var enemy : system.getEnemies()) {
            assertTrue(Math.hypot(enemy.getX() - player.getX(), enemy.getY() - player.getY())
                    >= GameConfig.ENEMY_SPAWN_SAFE_DISTANCE);
            assertTrue(enemy.isSpawning());
        }
        system.update(GameConfig.ENEMY_SPAWN_GRACE / 2, player, new PlayerAttackSystem(), nav);
        assertEquals(x, target.getX()); assertEquals(y, target.getY());
        assertTrue(system.getAttacks().isEmpty());
        system.update(GameConfig.ENEMY_SPAWN_GRACE, player, new PlayerAttackSystem(), nav);
        assertFalse(target.isSpawning());
    }
    @Test void sameSeedReproducesWaveCountAndFirstRoster() {
        var a = new EnemySystem(); var b = new EnemySystem();
        Room room = room(); var nav = navigation(room); var player = new Player(640, 360);
        a.enterRoom(room, 123, player, nav); b.enterRoom(room, 123, player, nav);
        assertEquals(a.getTotalWaves(), b.getTotalWaves());
        assertEquals(a.getEnemies().stream().map(e -> e.getKind()).toList(),
                b.getEnemies().stream().map(e -> e.getKind()).toList());
    }
    @Test void resetAndEventEntryDiscardPendingWaves() {
        var system = new EnemySystem(); Room room = room();
        var nav = navigation(room); var player = new Player(640, 360);
        system.enterRoom(room, 123, player, nav);
        system.spawnEventEnemies(room, 123, player, nav);
        assertEquals(0, system.getTotalWaves());
        assertTrue(system.getEnemies().size() <= 3);
        system.reset();
        assertEquals(0, system.getCurrentWave());
        assertFalse(system.isBetweenWaves());
        assertTrue(system.isRoomCleared());
    }
}
