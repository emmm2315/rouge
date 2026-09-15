package com.phantomcorridor.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProgressProfileTest {

    @Test
    void progressIsRestoredOnlyForTheMatchingNickname() throws BackingStoreException {
        String firstNickname = "测试旅者-" + UUID.randomUUID();
        String secondNickname = "另一旅者-" + UUID.randomUUID();
        try {
            PlayerProgress first = new PlayerProgress();
            first.loadForNickname(firstNickname);
            first.unlock(Achievement.BOSS_SLAYER);

            PlayerProgress second = new PlayerProgress();
            second.loadForNickname(secondNickname);
            assertFalse(second.isUnlocked(Achievement.BOSS_SLAYER));

            PlayerProgress restored = new PlayerProgress();
            restored.loadForNickname(firstNickname);
            assertTrue(restored.isUnlocked(Achievement.BOSS_SLAYER));
        } finally {
            removeProfileNode(firstNickname);
            removeProfileNode(secondNickname);
        }
    }

    private static void removeProfileNode(String nickname) throws BackingStoreException {
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nickname.getBytes(StandardCharsets.UTF_8));
        Preferences.userRoot().node("com/phantomcorridor/player-progress").node(key).removeNode();
    }
}
