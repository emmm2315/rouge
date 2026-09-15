package com.phantomcorridor.controller;

import com.phantomcorridor.config.Settings;
import com.phantomcorridor.model.PlayerProfile;
import com.phantomcorridor.model.PlayerProgress;
import com.phantomcorridor.view.MainMenuView;

/** 主菜单事件编排，视图不再直接持有应用状态。 */
public final class MainMenuController {

    private final MainMenuView view;

    public MainMenuController(Runnable onStart, Runnable onQuit, Settings settings, PlayerProfile profile,
                              PlayerProgress progress) {
        view = new MainMenuView(onStart, onQuit, settings, profile::getNickname, progress);
    }

    public MainMenuView getView() {
        return view;
    }
}
