package com.phantomcorridor.model.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WalkCycleTest {
    @Test void gaitFollowsDistanceAndSurvivesStopsAndTurns() {
        Player player = new Player(0, 0);
        player.advanceWalkDistance(25);
        player.updateAnimation(0.1, 25, 0, false);
        assertEquals(0.25, player.getBodyAnimationTime(), 1e-9);
        player.updateAnimation(5, 0, 0, false);
        player.updateAnimation(0, 0, -1, false);
        assertEquals(0.25, player.getBodyAnimationTime(), 1e-9);
        player.advanceWalkDistance(75);
        assertEquals(0, player.getBodyAnimationTime(), 1e-9);
        player.advanceWalkDistance(-100);
        assertEquals(0, player.getBodyAnimationTime(), 1e-9);
    }
}
