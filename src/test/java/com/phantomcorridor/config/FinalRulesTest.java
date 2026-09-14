package com.phantomcorridor.config;

import com.phantomcorridor.model.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定最终答辩口径的轻量配置测试。
 * 这些测试不依赖 JavaFX，避免把稳定的数值规则交给人工记忆。
 */
class FinalRulesTest {

    @Test
    void finalResourceRulesAreExplicit() {
        assertEquals(16, GameConfig.ATTACK_CHARGE_MAX);
        assertEquals(6, GameConfig.SKILL_ENERGY_COST);
        assertEquals(1.5, GameConfig.ATTACK_CHARGE_RECOVERY_TIME, 1e-9);
        assertEquals(80.0, GameConfig.FINISHER_PHASE_COST, 1e-9);
        assertEquals(30.0, GameConfig.FINISHER_COOLDOWN, 1e-9);
    }

    @Test
    void settingsClampValuesAndNotifyMusicChanges() {
        Settings settings = new Settings();
        assertEquals(Settings.DEFAULT_MOUSE_SENSITIVITY, settings.getMouseSensitivity(), 1e-9);
        assertEquals(Settings.DEFAULT_SFX_VOLUME, settings.getSfxVolume(), 1e-9);
        assertEquals(Settings.DEFAULT_MUSIC_VOLUME, settings.getMusicVolume(), 1e-9);

        settings.setMouseSensitivity(-1);
        assertEquals(Settings.MIN_MOUSE_SENSITIVITY, settings.getMouseSensitivity(), 1e-9);
        settings.setMouseSensitivity(99);
        assertEquals(Settings.MAX_MOUSE_SENSITIVITY, settings.getMouseSensitivity(), 1e-9);
        settings.setSfxVolume(2);
        assertEquals(1.0, settings.getSfxVolume(), 1e-9);

        boolean[] notified = {false};
        Runnable listener = () -> notified[0] = true;
        settings.addMusicVolumeListener(listener);
        settings.setMusicVolume(0.25);
        assertTrue(notified[0]);
        assertEquals(0.25, settings.getMusicVolume(), 1e-9);
        assertSame(Difficulty.NORMAL, settings.getDifficulty());
    }
}
