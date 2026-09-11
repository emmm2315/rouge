package com.phantomcorridor;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.Settings;
import com.phantomcorridor.audio.AudioManager;
import com.phantomcorridor.controller.GameController;
import com.phantomcorridor.controller.LoginController;
import com.phantomcorridor.controller.MainMenuController;
import com.phantomcorridor.controller.SceneManager;
import com.phantomcorridor.core.GameState;
import com.phantomcorridor.model.PlayerProfile;
import com.phantomcorridor.view.GameView;
import com.phantomcorridor.view.LoginView;
import com.phantomcorridor.view.MainMenuView;
import com.phantomcorridor.view.PauseView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * JavaFX 应用主类：负责窗口创建与场景流转编排（对应《双界行者》需求 §9.1 的顶层编排层）。
 *
 * <p>职责（§4.1 界面流程）：
 * <ul>
 *   <li>创建 1280×960 固定逻辑分辨率主窗口，支持 <b>F11 / Alt+Enter</b> 切换全屏；</li>
 *   <li>加载全局样式表 {@code ui/ui.css}（双界旅人主题）；</li>
 *   <li>初始化 {@link SceneManager}，并把各场景面板装入根容器；</li>
 *   <li>场景流转编排：登录 → 主菜单 → 游戏 → 暂停 → 主菜单/结算，对应需求 §4.1。</li>
 * </ul>
 *
 * <p>本类承担「顶层控制器」角色，负责把 view 层回调转发为场景切换（调 {@link SceneManager}）；
 * 具体的游戏业务逻辑不放在这里（§9.2）。
 */
public class App extends Application {

    /** 全屏切换快捷A键 1：F11 */
    private static final KeyCombination FULLSCREEN_F11 = new KeyCodeCombination(KeyCode.F11);

    /** 全屏切换快捷键 2：Alt+Enter */
    private static final KeyCombination FULLSCREEN_ALT_ENTER =
            new KeyCodeCombination(KeyCode.ENTER, KeyCombination.ALT_DOWN);

    /** 主窗口（"退出"操作与全屏切换需要） */
    private Stage stage;

    /** 所有界面面板的根节点（由 SceneManager 持有并切换可见性） */
    private final StackPane root = new StackPane();

    private LoginView loginView;
    private MainMenuView mainMenuView;
    private GameView gameView;
    private PauseView pauseView;
    private SceneManager sceneManager;
    private GameController gameController;
    private AudioManager audio;
    private double windowedWidth = AppConfig.VIEW_WIDTH;
    private double windowedHeight = AppConfig.VIEW_HEIGHT;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        root.setMinSize(0, 0);
        root.setPrefSize(AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);

        // 玩家档案与偏好设置分离，恢复默认设置不会再清空玩家身份。
        Settings settings = new Settings();
        audio = new AudioManager(settings);
        PlayerProfile profile = PlayerProfile.loadLocal();
        sceneManager = new SceneManager(root);

        LoginController loginController = new LoginController(profile, this::showMainMenu);
        MainMenuController mainMenuController = new MainMenuController(
                this::showGame, stage::close, settings, profile);
        loginView = loginController.getView();
        mainMenuView = mainMenuController.getView();
        gameView = new GameView();
        gameController = new GameController(gameView, this::showPause, settings, this::showMainMenu,
                audio::syncGameMusic);
        pauseView = new PauseView(this::resumeGame, this::showMainMenu);
        root.getChildren().addAll(loginView, mainMenuView, gameView, pauseView);

        Scene scene = new Scene(root, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("ui/ui.css").toExternalForm());
        scene.setOnKeyPressed(this::handleGlobalKeys);

        stage.setTitle(AppConfig.APP_TITLE);
        stage.setResizable(true);
        stage.setFullScreenExitHint("按 F11 或 Alt+Enter 退出全屏");
        // 屏蔽 JavaFX 默认的 Esc 退出全屏，避免与游戏暂停快捷键（Esc/P）冲突
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setScene(scene);
        stage.show();
        stage.widthProperty().addListener((obs, oldValue, newValue) -> {
            if (!stage.isFullScreen()) windowedWidth = newValue.doubleValue();
        });
        stage.heightProperty().addListener((obs, oldValue, newValue) -> {
            if (!stage.isFullScreen()) windowedHeight = newValue.doubleValue();
        });
        stage.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> {
            if (!isFullScreen) {
                Platform.runLater(() -> {
                    stage.setWidth(windowedWidth);
                    stage.setHeight(windowedHeight);
                    root.requestLayout();
                    gameView.requestLayout();
                });
            }
        });

        // 应用启动后进入第一个场景：登录界面（§8.1）
        showLogin();
    }

    /** 全局快捷键处理：F11 / Alt+Enter 切换全屏 */
    private void handleGlobalKeys(KeyEvent event) {
        if (FULLSCREEN_F11.match(event) || FULLSCREEN_ALT_ENTER.match(event)) {
            if (!stage.isFullScreen()) {
                windowedWidth = stage.getWidth();
                windowedHeight = stage.getHeight();
            }
            stage.setFullScreen(!stage.isFullScreen());
            event.consume();
        }
    }


    /** 进入登录界面（应用启动默认） */
    private void showLogin() {
        audio.playMenu();
        sceneManager.switchTo(GameState.LOGIN, loginView);
    }

    /** 切换到主菜单（登录成功，或从暂停界面/结算返回） */
    public void showMainMenu() {
        audio.playMenu();
        sceneManager.switchTo(GameState.MAIN_MENU, mainMenuView);
    }

    /** 进入游戏界面（主菜单点击"开始游戏"） */
    public void showGame() {
        gameController.newRun();
        audio.playExploration();
        sceneManager.switchTo(GameState.PLAYING, gameView);
    }

    /** 打开暂停界面（游戏中按 Esc/P 或点击暂停） */
    public void showPause() {
        sceneManager.showOverlay(GameState.PAUSED, pauseView);
    }

    /** 恢复游戏（暂停界面点击"继续游戏"或按 Esc/P） */
    public void resumeGame() {
        sceneManager.switchTo(GameState.PLAYING, gameView);
    }
}
