package com.phantomcorridor.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.prefs.Preferences;

/** 本地玩家档案。档案身份与音量、灵敏度等游戏设置相互独立。 */
public final class PlayerProfile {

    private static final String PREF_NODE = "com/phantomcorridor/player-profile";
    private static final String NICKNAME_KEY = "nickname";
    private static final String PASSWORD_HASH_KEY = "passwordHash";

    private String nickname = "";
    private String passwordHash = "";

    public String getNickname() {
        return nickname;
    }

    public boolean hasPassword() {
        return !passwordHash.isEmpty();
    }

    public boolean exists() {
        return !nickname.isEmpty();
    }

    /** 选择当前登录的旅者；局外进度由 PlayerProgress 按昵称分别读取。 */
    public void selectNickname(String nickname) {
        this.nickname = normalizeNickname(nickname);
        this.passwordHash = "";
    }

    /** 更新本地凭据；密码允许留空，且只保存摘要。 */
    public void updateCredentials(String nickname, String password) {
        this.nickname = normalizeNickname(nickname);
        this.passwordHash = password == null || password.isBlank() ? "" : hash(password);
    }

    public boolean passwordMatches(String password) {
        String candidate = password == null || password.isBlank() ? "" : hash(password);
        return MessageDigest.isEqual(passwordHash.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** 从当前系统用户的本地偏好中读取唯一玩家档案。 */
    public static PlayerProfile loadLocal() {
        Preferences preferences = Preferences.userRoot().node(PREF_NODE);
        PlayerProfile profile = new PlayerProfile();
        profile.nickname = normalizeNickname(preferences.get(NICKNAME_KEY, ""));
        profile.passwordHash = preferences.get(PASSWORD_HASH_KEY, "");
        return profile;
    }

    /** 保存昵称与密码摘要，不保存明文密码。 */
    public void saveLocal() {
        Preferences preferences = Preferences.userRoot().node(PREF_NODE);
        preferences.put(NICKNAME_KEY, nickname);
        preferences.put(PASSWORD_HASH_KEY, passwordHash);
    }

    public static String normalizeNickname(String nickname) {
        return nickname == null ? "" : nickname.trim();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }
}
