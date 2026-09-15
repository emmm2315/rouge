package com.phantomcorridor.controller;

import com.phantomcorridor.model.PlayerProfile;
import com.phantomcorridor.model.PlayerProgress;
import com.phantomcorridor.view.LoginView;

/** 登录页输入校验与本地档案更新。 */
public final class LoginController {

    private static final int MAX_NICKNAME_LENGTH = 16;

    private final PlayerProfile profile;
    private final PlayerProgress progress;
    private final LoginView view;
    private final Runnable onLoggedIn;

    public LoginController(PlayerProfile profile, PlayerProgress progress, Runnable onLoggedIn) {
        this.profile = profile;
        this.progress = progress;
        this.onLoggedIn = onLoggedIn;
        this.view = new LoginView(this::submit);
    }

    private void submit(String nickname) {
        String normalized = PlayerProfile.normalizeNickname(nickname);
        if (normalized.isEmpty()) {
            view.showError("请输入旅者昵称");
            return;
        }
        if (normalized.length() > MAX_NICKNAME_LENGTH) {
            view.showError("昵称最多 16 个字符");
            return;
        }
        // 每个昵称都对应独立档案；输入已有昵称会恢复其进度，输入新昵称则从空档案开始。
        progress.loadForNickname(normalized);
        profile.selectNickname(normalized);
        profile.saveLocal();
        view.clearError();
        onLoggedIn.run();
    }

    public LoginView getView() {
        return view;
    }
}
