package com.phantomcorridor.model.entity;

import com.phantomcorridor.model.combat.PlayerAttackSystem;
import com.phantomcorridor.model.WorldType;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimationTest {
    @Test void allManifestSequencesHaveTransparentFixedCanvasFrames() throws Exception {
        int count = 0;
        for (String form : new String[]{"light", "shadow"}) {
            for (String action : new String[]{"idle", "move", "attack", "hit", "dodge", "invulnerable", "transition", "finisher_cast"}) {
                for (String direction : new String[]{"down", "up", "left", "right"}) {
                    var clip = PlayerAnimationCatalog.clip(form, action, direction);
                    assertEquals(4, clip.frames());
                    for (int i = 0; i < clip.frames(); i++) {
                        String path = PlayerAnimationCatalog.ROOT + "body/" + form + "/" + action + "/" + direction + "/frame_%02d.png".formatted(i);
                        try (var stream = getClass().getResourceAsStream(path)) {
                            assertNotNull(stream, path);
                            var image = ImageIO.read(stream);
                            assertEquals(320, image.getWidth(), path);
                            assertEquals(256, image.getHeight(), path);
                            assertTrue(image.getColorModel().hasAlpha(), path);
                            assertEquals(0, image.getRGB(0, 0) >>> 24, path);
                            count++;
                        }
                    }
                }
            }
        }
        assertEquals(256, count);
        assertEquals(80, PlayerAnimationCatalog.ANCHOR_X);
        assertEquals(70, PlayerAnimationCatalog.ANCHOR_Y);
    }

    @Test void loopingAndOneShotClipsUseTheirOwnTiming() {
        var idle = PlayerAnimationCatalog.clip("light", "idle", "down");
        assertEquals(1, idle.frame(1 / idle.fps()));
        assertEquals(0, idle.frame(idle.duration()));
        var attack = PlayerAnimationCatalog.clip("shadow", "attack", "left");
        assertEquals(3, attack.frame(attack.duration() * 2));
        assertEquals(0, attack.frame(-1));
        assertEquals("left", PlayerAnimationCatalog.direction(-1, 0.5));
        assertEquals("up", PlayerAnimationCatalog.direction(0.5, -1));
    }

    @Test void successfulAttacksLockAimFinishAndRestartWhileEmptyInputDoesNothing() {
        Player player = new Player(0, 0);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        assertTrue(attacks.tryAttack(player, -100, 0));
        player.updateAnimation(0.02, 1, 0, true, false);
        assertEquals(PlayerAnimationState.ATTACKING, player.getAnimationState());
        assertEquals(-1, player.getFacingX());
        assertFalse(attacks.tryAttack(player, 100, 0));
        player.updateAnimation(0.2, 1, 0, true, false);
        assertEquals(PlayerAnimationState.MOVING, player.getAnimationState());
        attacks.update(2);
        assertTrue(attacks.tryAttack(player, 0, -100));
        assertEquals(0, player.getAnimationTime());
        assertEquals(-1, player.getFacingY());
        player.updateAnimation(0.3, 0, 0, false, false);
        while (player.consumeAttackCharge()) { }
        attacks.update(2);
        assertFalse(attacks.tryAttack(player, 1, 0));
        player.updateAnimation(0.01, 0, 0, true, false);
        assertEquals(PlayerAnimationState.IDLE, player.getAnimationState());
    }

    @Test void damageThenInvulnerabilityAndDashHaveIndependentActions() {
        Player player = new Player(0, 0);
        assertTrue(player.takeDamage(1));
        player.updateAnimation(0.01, 0, 0, false);
        assertEquals(PlayerAnimationState.HIT, player.getAnimationState());
        player.updateAnimation(0.34, 0, 0, false);
        assertEquals(PlayerAnimationState.INVULNERABLE, player.getAnimationState());
        assertTrue(player.tryStartDash(0, -1));
        player.updateAnimation(0.01, 1, 0, false);
        assertEquals(PlayerAnimationState.DASHING, player.getAnimationState());
        assertFalse(new PlayerAttackSystem().tryAttack(player, 1, 0));
        player.recordDashTrail();
        var trail = player.getDashTrail().getFirst();
        player.toggleWorld();
        var aged = trail.aged(0.01);
        assertEquals(WorldType.LIGHT, aged.world());
        assertEquals(-1, aged.directionY());
        assertEquals(trail.animationTime(), aged.animationTime());
    }

    @Test void ordinaryShiftPlaysOnceWithoutRequiringAnEmpoweredPulse() {
        Player player = new Player(0, 0);
        player.toggleWorld();
        player.updateAnimation(0.01, 0, 0, false);
        assertEquals(PlayerAnimationState.SHIFTING, player.getAnimationState());
        player.updateAnimation(0.3, 1, 0, false);
        assertEquals(PlayerAnimationState.MOVING, player.getAnimationState());
    }

    @Test void heavyWeaponWindupHoldsPreparationAndPauseDoesNotAdvanceIt() {
        Player player = new Player(0, 0);
        player.startAttackAnimation(1, 0, 0.18);
        player.updateAnimation(0.1, 0, 0, false);
        assertEquals(0, player.getBodyAnimationTime());
        player.updateAnimation(0, 0, 0, false);
        assertEquals(0.1, player.getAnimationTime(), 1e-9);
        player.updateAnimation(0.1, 0, 0, false);
        assertEquals(0.02, player.getBodyAnimationTime(), 1e-9);
        assertEquals(PlayerAnimationState.ATTACKING, player.getAnimationState());
        player.updateAnimation(0.2, 0, 0, false);
        assertEquals(PlayerAnimationState.IDLE, player.getAnimationState());
    }
}
