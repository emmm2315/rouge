package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 闪避冲刺的状态机：免伤、冷却、拖尾记录与消散。 */
class PlayerDashTest {

    private static final double FRAME = AppConfig.FIXED_DT;

    @Test
    void dashMakesThePlayerImmuneToDamage() {
        Player player = new Player(100, 100);
        assertTrue(player.tryStartDash(1, 0));

        assertFalse(player.takeDamage(1), "冲刺中不该掉血");
        assertEquals(GameConfig.PLAYER_MAX_HP, player.getHp());
        assertTrue(player.isDashing());
    }

    @Test
    void dashImmunityEndsWithTheDash() {
        Player player = new Player(100, 100);
        assertTrue(player.tryStartDash(1, 0));
        for (int frame = 0; frame <= framesInDash(); frame++) player.updateDash(FRAME);

        assertFalse(player.isDashing(), "冲刺时间走完就该恢复常态");
        assertTrue(player.takeDamage(1), "冲刺结束后照常受击");
        assertEquals(GameConfig.PLAYER_MAX_HP - 1, player.getHp());
    }

    @Test
    void dashNeedsACooldownBetweenUses() {
        Player player = new Player(100, 100);
        assertTrue(player.tryStartDash(1, 0));
        assertFalse(player.tryStartDash(1, 0), "冲刺中不能再次起手");
        for (int frame = 0; frame < framesInDash(); frame++) player.updateDash(FRAME);

        assertFalse(player.isDashReady());
        assertFalse(player.tryStartDash(1, 0), "刚冲完必须等冷却");
        assertEquals(GameConfig.DASH_COOLDOWN, player.getDashCooldownRemaining(), 0.3);

        // 冷却过半仍然不可用，超过 1 秒后恢复。
        for (int frame = 0; frame < 30; frame++) player.updateDash(FRAME);
        assertFalse(player.tryStartDash(1, 0));
        for (int frame = 0; frame < 40; frame++) player.updateDash(FRAME);

        assertTrue(player.isDashReady());
        assertTrue(player.tryStartDash(1, 0), "冷却走完后应当可以再次冲刺");
    }

    @Test
    void dashWithoutMovementInputUsesTheFacingDirection() {
        Player player = new Player(100, 100);
        player.updateAnimation(0.0, -1.0, 0.0, false, false);

        assertTrue(player.tryStartDash(0.0, 0.0));

        assertEquals(-1.0, player.getDashDirectionX(), 1e-9);
        assertEquals(0.0, player.getDashDirectionY(), 1e-9);
    }

    @Test
    void dashDirectionIsNormalizedSoDiagonalsAreNotFaster() {
        Player player = new Player(100, 100);

        assertTrue(player.tryStartDash(1, 1));

        double length = Math.hypot(player.getDashDirectionX(), player.getDashDirectionY());
        assertEquals(1.0, length, 1e-9);
    }

    @Test
    void trailIsRecordedWhileDashingAndFadesOutAfterwards() {
        Player player = new Player(100, 100);
        assertTrue(player.getDashTrail().isEmpty(), "没冲刺时不该有拖尾");

        player.recordDashTrail();
        assertTrue(player.getDashTrail().isEmpty(), "站着不动按空格以外的情况不会留下残影");

        assertTrue(player.tryStartDash(1, 0));
        player.recordDashTrail();
        assertEquals(1, player.getDashTrail().size());
        assertEquals(1.0, player.getDashTrail().getFirst().life(), 1e-9, "刚生成的残影最不透明");

        player.updateDash(FRAME);
        assertTrue(player.getDashTrail().getFirst().life() < 1.0, "残影随着时间淡出");

        // 冲刺结束、残影寿命走完后拖尾必须清空，不能留在场上。
        for (int frame = 0; frame < 60; frame++) player.updateDash(FRAME);
        assertFalse(player.isDashing());
        assertTrue(player.getDashTrail().isEmpty(), "残影消散后要清出拖尾列表");
    }

    @Test
    void deadPlayerCannotDash() {
        Player player = new Player(100, 100);
        while (player.takeDamage(1)) {
            player.updateAnimation(GameConfig.PLAYER_HIT_INVULNERABILITY, 0, 0, false, false);
        }
        assertEquals(0, player.getHp());

        assertFalse(player.tryStartDash(1, 0));
        assertFalse(player.isDashReady());
    }

    /** 一段冲刺固定要跑满的帧数（向上取整，最后一帧是"补零头"的半帧）。 */
    private static int framesInDash() {
        return (int) Math.ceil(GameConfig.DASH_DURATION / FRAME);
    }
}
