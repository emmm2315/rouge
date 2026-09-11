package com.phantomcorridor.model.entity;

import com.phantomcorridor.model.WorldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnemyHitboxTest {
    @Test
    void combatHitboxIsCenteredOnBodyInsteadOfFootAnchor() {
        Enemy watcher = new Enemy(EnemyKind.WATCHER, WorldType.LIGHT, 640, 480, 1);

        assertEquals(640, watcher.getHitboxCenterX());
        assertTrue(watcher.getHitboxCenterY() < watcher.getY(), "受击框应上移到首领躯干");
        assertTrue(watcher.getHitboxRadius() < 323 / 2.0, "受击框应小于首领模型宽度");
    }

    @Test
    void largerModelsReceiveLargerButStillCenteredHitboxes() {
        Enemy normal = new Enemy(EnemyKind.WOLF, WorldType.SHADOW, 0, 0, 1);
        Enemy elite = new Enemy(EnemyKind.EXECUTIONER, WorldType.SHADOW, 0, 0, 1);
        Enemy boss = new Enemy(EnemyKind.WATCHER, WorldType.SHADOW, 0, 0, 1);

        assertTrue(normal.getHitboxRadius() < elite.getHitboxRadius());
        assertTrue(elite.getHitboxRadius() < boss.getHitboxRadius());
        assertTrue(normal.getHitboxCenterY() < normal.getY());
        assertTrue(elite.getHitboxCenterY() < elite.getY());
    }
}
