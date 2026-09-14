package com.phantomcorridor.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.prefs.Preferences;
import com.phantomcorridor.model.entity.EnemyKind;

/** 玩家已发现装备和已解锁成就的本地持久化进度。 */
public final class PlayerProgress {
    private static final String NODE = "com/phantomcorridor/player-progress";
    private static final String EQUIPMENT_KEY = "equipment";
    private static final String ACHIEVEMENTS_KEY = "achievements";
    private static final String BOSSES_KEY = "bosses";
    private final EnumSet<EquipmentType> discoveredEquipment = EnumSet.noneOf(EquipmentType.class);
    private final EnumSet<Achievement> achievements = EnumSet.noneOf(Achievement.class);
    private final EnumSet<EnemyKind> defeatedBosses = EnumSet.noneOf(EnemyKind.class);

    public static PlayerProgress loadLocal() {
        PlayerProgress progress = new PlayerProgress();
        Preferences preferences = Preferences.userRoot().node(NODE);
        load(preferences.get(EQUIPMENT_KEY, ""), EquipmentType.class, progress.discoveredEquipment);
        load(preferences.get(ACHIEVEMENTS_KEY, ""), Achievement.class, progress.achievements);
        load(preferences.get(BOSSES_KEY, ""), EnemyKind.class, progress.defeatedBosses);
        return progress;
    }

    public void saveLocal() {
        Preferences preferences = Preferences.userRoot().node(NODE);
        preferences.put(EQUIPMENT_KEY, join(discoveredEquipment));
        preferences.put(ACHIEVEMENTS_KEY, join(achievements));
        preferences.put(BOSSES_KEY, join(defeatedBosses));
    }

    public boolean discover(EquipmentType equipment) {
        boolean changed = discoveredEquipment.add(equipment);
        if (changed) saveLocal();
        return changed;
    }
    public boolean unlock(Achievement achievement) {
        boolean changed = achievements.add(achievement);
        if (changed) saveLocal();
        return changed;
    }
    public boolean recordBossDefeat(EnemyKind boss) {
        if (boss == null || !boss.boss()) return false;
        boolean changed = defeatedBosses.add(boss);
        if (changed) saveLocal();
        return changed;
    }
    public boolean isDiscovered(EquipmentType equipment) { return discoveredEquipment.contains(equipment); }
    public boolean isUnlocked(Achievement achievement) { return achievements.contains(achievement); }
    public Set<EquipmentType> discoveredEquipment() { return Collections.unmodifiableSet(discoveredEquipment); }
    public Set<Achievement> achievements() { return Collections.unmodifiableSet(achievements); }
    public Set<EnemyKind> defeatedBosses() { return Collections.unmodifiableSet(defeatedBosses); }
    public boolean hasDefeatedEveryBoss() {
        return EnumSet.allOf(EnemyKind.class).stream().filter(EnemyKind::boss).allMatch(defeatedBosses::contains);
    }

    private static <E extends Enum<E>> void load(String value, Class<E> type, Set<E> target) {
        if (value == null || value.isBlank()) return;
        for (String name : value.split(",")) try { target.add(Enum.valueOf(type, name)); }
        catch (IllegalArgumentException ignored) { /* 旧版或损坏的单项不影响其他进度 */ }
    }
    private static String join(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }
}
