package com.phantomcorridor.model;

import com.phantomcorridor.model.entity.EnemyKind;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.prefs.Preferences;

/** 玩家已发现装备、已解锁成就和首领记录的本地进度。每个昵称拥有独立档案。 */
public final class PlayerProgress {
    private static final String NODE = "com/phantomcorridor/player-progress";
    private static final String EQUIPMENT_KEY = "equipment";
    private static final String ACHIEVEMENTS_KEY = "achievements";
    private static final String BOSSES_KEY = "bosses";

    private final EnumSet<EquipmentType> discoveredEquipment = EnumSet.noneOf(EquipmentType.class);
    private final EnumSet<Achievement> achievements = EnumSet.noneOf(Achievement.class);
    private final EnumSet<EnemyKind> defeatedBosses = EnumSet.noneOf(EnemyKind.class);
    private String nickname = "";

    /** 读取旧版全局进度，保留给兼容调用；正常登录请使用 {@link #loadForNickname(String)}。 */
    public static PlayerProgress loadLocal() {
        PlayerProgress progress = new PlayerProgress();
        progress.loadFrom(Preferences.userRoot().node(NODE));
        return progress;
    }

    /** 切换当前旅者并读取其独立的图鉴、成就和首领记录。 */
    public void loadForNickname(String nickname) {
        this.nickname = PlayerProfile.normalizeNickname(nickname);
        discoveredEquipment.clear();
        achievements.clear();
        defeatedBosses.clear();
        if (!this.nickname.isEmpty()) {
            Preferences root = Preferences.userRoot().node(NODE);
            Preferences profileNode = root.node(profileKey(this.nickname));
            if (hasStoredValues(profileNode)) {
                loadFrom(profileNode);
            } else if (PlayerProfile.loadLocal().getNickname().equals(this.nickname)) {
                // 旧版本仅有一个全局进度；首次以原昵称登录时将其迁移到该昵称名下。
                loadFrom(root);
                saveTo(profileNode);
            }
        }
    }

    public String getNickname() { return nickname; }

    public void saveLocal() {
        if (nickname.isEmpty()) return;
        saveTo(Preferences.userRoot().node(NODE).node(profileKey(nickname)));
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

    private void loadFrom(Preferences preferences) {
        load(preferences.get(EQUIPMENT_KEY, ""), EquipmentType.class, discoveredEquipment);
        load(preferences.get(ACHIEVEMENTS_KEY, ""), Achievement.class, achievements);
        load(preferences.get(BOSSES_KEY, ""), EnemyKind.class, defeatedBosses);
    }

    private void saveTo(Preferences preferences) {
        preferences.put(EQUIPMENT_KEY, join(discoveredEquipment));
        preferences.put(ACHIEVEMENTS_KEY, join(achievements));
        preferences.put(BOSSES_KEY, join(defeatedBosses));
    }

    private static boolean hasStoredValues(Preferences preferences) {
        return preferences.get(EQUIPMENT_KEY, null) != null
                || preferences.get(ACHIEVEMENTS_KEY, null) != null
                || preferences.get(BOSSES_KEY, null) != null;
    }

    private static <E extends Enum<E>> void load(String value, Class<E> type, Set<E> target) {
        if (value == null || value.isBlank()) return;
        for (String name : value.split(",")) try { target.add(Enum.valueOf(type, name)); }
        catch (IllegalArgumentException ignored) { /* 旧版或损坏的单项不影响其他进度 */ }
    }

    private static String join(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    /** Preferences 节点只使用 URL 安全字符，避免昵称中的空格或 Unicode 影响路径。 */
    private static String profileKey(String nickname) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nickname.getBytes(StandardCharsets.UTF_8));
    }
}
