package com.phantomcorridor.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentCompletionTest {
    @Test
    void requiresEveryDistinctEquipmentAndUnlocksOnlyOnce() {
        PlayerProgress progress = new PlayerProgress();
        EquipmentType[] equipment = EquipmentType.values();
        assertFalse(progress.hasDiscoveredEveryEquipment());
        for (int i = 0; i < equipment.length - 1; i++) {
            progress.discover(equipment[i]);
            progress.discover(equipment[i]);
        }
        assertFalse(progress.hasDiscoveredEveryEquipment());
        progress.discover(equipment[equipment.length - 1]);
        assertTrue(progress.hasDiscoveredEveryEquipment());
        assertTrue(progress.unlock(Achievement.ALL_EQUIPMENT));
        assertFalse(progress.unlock(Achievement.ALL_EQUIPMENT));
    }

    @Test
    void completionIconIsPackaged() {
        assertNotNull(getClass().getResource("/com/phantomcorridor/ui/achievements/"
                + Achievement.ALL_EQUIPMENT.iconId() + ".png"));
    }
}
