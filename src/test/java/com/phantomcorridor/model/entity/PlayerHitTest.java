package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 受击反应：0.65 秒无敌、贴图变淡计时、以及击退方向。 */
class PlayerHitTest {

    private static final double FRAME = AppConfig.FIXED_DT;

    @Test
    void hitGrantsTheConfiguredInvulnerabilityWindow() {
        Player player = new Player(100, 100);

        assertTrue(player.takeDamage(1, 0, 100));
        assertEquals(GameConfig.PLAYER_MAX_HP - 1, player.getHp());
        assertTrue(player.isHitInvulnerable());

        // 无敌期内再挨打不掉血（差一帧走完时仍然免疫）。
        int framesInWindow = (int) Math.ceil(GameConfig.PLAYER_HIT_INVULNERABILITY / FRAME);
        for (int frame = 0; frame < framesInWindow - 2; frame++) {
            player.updateAnimation(FRAME, 0, 0, false, false);
        }
        assertFalse(player.takeDamage(1, 0, 100),
                GameConfig.PLAYER_HIT_INVULNERABILITY + " 秒无敌期内不该再掉血");
        assertEquals(GameConfig.PLAYER_MAX_HP - 1, player.getHp());

        // 撑过无敌时间后恢复受击。
        for (int frame = 0; frame < 4; frame++) player.updateAnimation(FRAME, 0, 0, false, false);
        assertFalse(player.isHitInvulnerable());
        assertTrue(player.takeDamage(1, 0, 100));
        assertEquals(GameConfig.PLAYER_MAX_HP - 2, player.getHp());
    }

    @Test
    void hitFlashStartsAtFullAndFadesOut() {
        Player player = new Player(100, 100);
        assertEquals(0.0, player.getHitFlash(), "没挨打时不该有变淡效果");

        assertTrue(player.takeDamage(1, 0, 100));
        assertEquals(1.0, player.getHitFlash(), 1e-9, "刚受击时变淡最强");

        for (int frame = 0; frame < 5; frame++) player.updateAnimation(FRAME, 0, 0, false, false);
        assertTrue(player.getHitFlash() < 1.0 && player.getHitFlash() > 0.0, "变淡要按时间衰减");

        for (int frame = 0; frame < 20; frame++) player.updateAnimation(FRAME, 0, 0, false, false);
        assertEquals(0.0, player.getHitFlash(), 1e-9, "变淡必须在 0.25 秒内结束");
        assertTrue(player.isHitInvulnerable(), "变淡结束时无敌还没走完，两者分开计时");
    }

    @Test
    void knockbackPushesAwayFromTheDamageSource() {
        Player player = new Player(100, 100);
        assertTrue(player.takeDamage(1, 40, 100));

        double[] knockback = player.consumeKnockback();
        assertNotNull(knockback, "受击要登记一次击退");
        assertEquals(1.0, knockback[0], 1e-9, "来源在左边，应当被推向右边");
        assertEquals(0.0, knockback[1], 1e-9);
        assertNull(player.consumeKnockback(), "击退只结算一次，不能每帧重复推开");
    }

    @Test
    void knockbackWithUnknownSourcePushesBackwardsFromTheFacing() {
        Player player = new Player(100, 100);
        player.updateAnimation(0.0, 1.0, 0.0, false, false);

        assertTrue(player.takeDamage(1));

        double[] knockback = player.consumeKnockback();
        assertNotNull(knockback);
        assertEquals(-1.0, knockback[0], 1e-9, "来源未知时应当沿朝向的反方向被推开");
        assertEquals(0.0, knockback[1], 1e-9);
    }

    @Test
    void immuneHitLeavesNoFlashAndNoKnockback() {
        Player player = new Player(100, 100);
        assertTrue(player.tryStartDash(1, 0));

        assertFalse(player.takeDamage(1, 0, 100));

        assertEquals(0.0, player.getHitFlash(), "免伤的这一次不该有受击反馈");
        assertNull(player.consumeKnockback(), "免伤的这一次不该被击退");
    }

    @Test
    void knockbackDirectionHandlesSourceOnTopOfThePlayer() {
        Player player = new Player(100, 100);
        player.updateAnimation(0.0, 0.0, 1.0, false, false);

        // 来源正好压在角色身上：方向退化，但必须仍然是单位向量。
        assertTrue(player.takeDamage(1, 100, 100));

        double[] knockback = player.consumeKnockback();
        assertNotNull(knockback);
        assertEquals(1.0, Math.hypot(knockback[0], knockback[1]), 1e-9);
    }
}
