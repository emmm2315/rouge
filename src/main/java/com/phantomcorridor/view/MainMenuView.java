package com.phantomcorridor.view;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.Settings;
import com.phantomcorridor.controller.SceneLifecycle;
import com.phantomcorridor.model.Difficulty;
import com.phantomcorridor.model.Achievement;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.PlayerProgress;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Rectangle2D;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 主菜单面板（对应《双界行者》需求 §8.2 主菜单）。
 *
 * <p><b>「破碎回廊」主题</b>：背景复用 {@link DualWorldBackdrop}，以左侧旧金圣辉、
 * 右侧幽紫影域和中央动态裂隙表达两界并存。
 *
 * <p><b>按钮动效</b>：悬停轻微放大 + 前置符文光标 ✦ 淡入；菜单入场标题/分隔线/按钮依次
 * 淡入上浮；覆盖层滑入滑出；开始/退出游戏时双界遮罩渐入。
 *
 * <p><b>菜单按钮顺序</b>（§8.2）：开始游戏 / 道具图鉴 / 设置 / 退出。
 */
public class MainMenuView extends StackPane implements SceneLifecycle {

    /** 入场动画：相邻元素错峰间隔（毫秒） */
    private static final double ENTER_STEP_MS = 70.0;

    /** 入场动画：单个元素淡入时长（毫秒） */
    private static final double ENTER_FADE_MS = 320.0;

    /** 覆盖层（道具图鉴/设置）滑入淡入时长（毫秒） */
    private static final double OVERLAY_MS = 260.0;

    /** 开始游戏过渡时长（毫秒） */
    private static final double START_TRANSITION_MS = 520.0;

    /** 符文光标 ✦ 固定宽度（像素），与其右侧隐形占位等宽，保证按钮文本严格居中 */
    private static final double STAR_WIDTH = 22.0;

    /** 主菜单标题文本 */
    private static final String TITLE = "双界行者";

    /** 副标题文本 */
    private static final String SUBTITLE = "以光为刃 · 借影而行";

    /** 底部版本信息文本 */
    private static final String FOOTER = "v0.3.1 · 双界行者 · JavaFX Roguelike 可玩原型";

    /** 标题节点（开始过渡动画需要引用） */
    private final Label title = new Label(TITLE);

    /** 开始按钮（覆盖层打开时要让出 Enter 默认键） */
    private Button startButton;

    /** 难度面板里的「标准」按钮：难度面板打开时接管 Enter 默认键 */
    private Button normalDifficultyButton;

    /** 主菜单内容（标题组 + 分隔线 + 按钮），与覆盖层互斥显示 */
    private final VBox menuContent;

    /** 道具图鉴覆盖层（对应 §8.2，占位展示道具分类） */
    private VBox galleryContent;
    private VBox achievementContent;
    private final PlayerProgress progress;

    /** 难度选择覆盖层（点击「开始游戏」后弹出） */
    private final VBox difficultyContent;

    /** 设置覆盖层（构造时赋值，回调中引用自身，故不可为 final） */
    private VBox settingsContent;

    /** 双界过渡遮罩（开始游戏/退出时渐入） */
    private final Region overlay;

    /** 夜空双辉背景（星空 / 白金光弧 / 紫影倒影与涟漪动效） */
    private final DualWorldBackdrop backdrop;

    /** 防止快速重复点击启动多组互相竞争的过渡动画。 */
    private boolean transitionLocked;

    /**
     * 构建主菜单面板。
     *
     * @param onStart  "开始游戏"回调 —— 过渡结束后进入游戏界面
     * @param onQuit   "退出"回调 —— 过渡结束后关闭主窗口
     * @param settings 全局设置对象（设置面板读写）
     */
    public MainMenuView(Runnable onStart, Runnable onQuit, Settings settings,
                        Supplier<String> nicknameSupplier, PlayerProgress progress) {
        this.progress = progress;
        getStyleClass().add("main-menu-pane");

        // 背景置于最底层（夜空光弧 + 水面动效）；菜单内容等叠加其上
        backdrop = new DualWorldBackdrop();
        backdrop.setManaged(false);
        getChildren().add(backdrop);

        title.getStyleClass().add("menu-title");
        menuContent = createMenuContent(onQuit);

        // 底部版本信息（常驻）
        Label footer = new Label(FOOTER);
        footer.getStyleClass().add("menu-footer");
        footer.setPadding(new Insets(0, 0, 18, 0));
        StackPane.setAlignment(footer, Pos.BOTTOM_CENTER);

        // 覆盖层：道具图鉴、难度选择与设置（默认隐藏）
        galleryContent = createGalleryContent(progress);
        achievementContent = createAchievementContent(progress);
        difficultyContent = createDifficultyContent(settings, onStart);
        settingsContent = new SettingsOverlay(settings, nicknameSupplier,
                () -> hideOverlay(settingsContent, this::fadeInMenu));
        settingsContent.setVisible(false);
        settingsContent.setManaged(false);

        // 双界过渡遮罩（最顶层，默认隐藏；显式指定逻辑尺寸，避免 Region 在 StackPane 中
        // 按 0×0 的 pref 尺寸渲染导致遮罩不铺满屏幕）
        overlay = new Region();
        overlay.getStyleClass().add("menu-overlay");
        overlay.setMinSize(AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        overlay.setPrefSize(AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        overlay.setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        overlay.setVisible(false);
        overlay.setManaged(false);

        // 层叠顺序：背景画布 → 菜单内容 → 底部信息 → 覆盖层 → 双界遮罩
        getChildren().addAll(menuContent, footer, galleryContent, achievementContent, difficultyContent, settingsContent, overlay);

        // Esc 收起覆盖层（§4.3：Esc = 返回）
        setOnKeyPressed(event -> {
            if (event.getCode() != KeyCode.ESCAPE) {
                return;
            }
            if (settingsContent.isVisible()) {
                hideOverlay(settingsContent, this::fadeInMenu);
            } else if (difficultyContent.isVisible()) {
                hideOverlay(difficultyContent, this::fadeInMenu);
            } else if (galleryContent.isVisible()) {
                hideOverlay(galleryContent, this::fadeInMenu);
            } else if (achievementContent.isVisible()) {
                hideOverlay(achievementContent, this::fadeInMenu);
            }
        });

        resetForReentry();
    }

    /** 构建菜单主体：标题 + 副标题 + 分隔线 + 操作按钮（§8.2） */
    private VBox createMenuContent(Runnable onQuit) {
        Label subtitle = new Label(SUBTITLE);
        subtitle.getStyleClass().add("menu-subtitle");

        VBox header = new VBox(10.0, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // 白金→紫渐变分隔线（纯样式 Region，见 ui.css .menu-divider）
        Region divider = new Region();
        divider.getStyleClass().add("menu-divider");

        Button startButton = createMenuButton("开始游戏", () -> showOverlay(difficultyContent));
        this.startButton = startButton;
        startButton.setDefaultButton(true); // Enter 快捷开始（打开难度面板）

        Button galleryButton = createMenuButton("道具图鉴", () -> showOverlay(galleryContent));
        Button achievementButton = createMenuButton("成就", () -> showOverlay(achievementContent));
        Button settingsButton = createMenuButton("设置", () -> showOverlay(settingsContent));
        Button quitButton = createMenuButton("退出", () -> playExitTransition(onQuit));

        VBox box = new VBox(18.0, header, divider, startButton, galleryButton, achievementButton, settingsButton, quitButton);
        box.getStyleClass().add("menu-panel");
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        box.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        box.setPadding(new Insets(40.0));
        return box;
    }

    /** 构建道具图鉴覆盖层：占位展示道具分类（§8.2；详细图鉴内容随第 6 天道具系统完善） */
    private VBox createGalleryContent(PlayerProgress progress) {
        Label galleryTitle = new Label("道具图鉴");
        galleryTitle.getStyleClass().add("overlay-title");
        GridPane grid = new GridPane();
        grid.setHgap(14.0); grid.setVgap(12.0); grid.setAlignment(Pos.CENTER);
        EquipmentType[] equipment = EquipmentType.values();
        for (int index = 0; index < equipment.length; index++) grid.add(equipmentCard(equipment[index], progress), index % 3, index / 3);
        ScrollPane scroll = new ScrollPane(grid);
        // 三列卡片在 760px 内完整排开；图鉴只需要纵向滚动，禁止无意义的横向滚动条。
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportWidth(760);
        scroll.setMaxWidth(760);
        scroll.setPrefViewportHeight(510);
        scroll.getStyleClass().add("progress-scroll");
        Label note = new Label("只展示本档案实际拾取过的装备；同名装备会叠加，强化规则在每张卡片中列出。");
        note.getStyleClass().add("hint-text");

        Button backButton = createMenuButton("返回菜单", () -> hideOverlay(galleryContent, this::fadeInMenu));

        VBox box = new VBox(16.0, galleryTitle, scroll, note, backButton);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        box.setMaxWidth(VBox.USE_PREF_SIZE);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    private VBox createAchievementContent(PlayerProgress progress) {
        Label title = new Label("成就"); title.getStyleClass().add("overlay-title");
        VBox rows = new VBox(12.0); rows.setAlignment(Pos.CENTER_LEFT); rows.setMaxWidth(560);
        for (Achievement achievement : Achievement.values()) {
            boolean unlocked = progress.isUnlocked(achievement);
            Label row = new Label(unlocked ? achievement.title() + "  —  " + achievement.description() : "未解锁成就");
            ImageView icon = new ImageView(unlocked ? achievementIcon(achievement) : null);
            icon.setFitWidth(38); icon.setFitHeight(38); icon.setPreserveRatio(true);
            HBox entry = new HBox(12, unlocked ? icon : new Label("◇"), row);
            entry.setAlignment(Pos.CENTER_LEFT);
            entry.getStyleClass().add(unlocked ? "achievement-unlocked" : "achievement-locked");
            rows.getChildren().add(entry);
        }
        Label note = new Label("未解锁的成就不会透露达成条件。击败首领或完成对应难度通关后将永久记录。");
        note.getStyleClass().add("hint-text");
        Button back = createMenuButton("返回菜单", () -> hideOverlay(achievementContent, this::fadeInMenu));
        VBox box = new VBox(24, title, rows, note, back); box.setAlignment(Pos.CENTER); box.setVisible(false); box.setManaged(false);
        return box;
    }

    private VBox equipmentCard(EquipmentType equipment, PlayerProgress progress) {
        boolean discovered = progress.isDiscovered(equipment);
        Node icon = discovered ? equipmentIcon(equipment) : new Label("?");
        if (icon instanceof ImageView image) {
            image.setFitWidth(46); image.setFitHeight(46); image.setPreserveRatio(true);
        } else icon.getStyleClass().add("equipment-card-title");
        Label title = new Label(discovered ? equipment.displayName() + " · " + equipment.affinity() : "未发现装备");
        title.getStyleClass().add("equipment-card-title");
        String details = discovered ? equipment.description() + "\n同名强化：" + stackDescription(equipment) : "拾取后解锁图标与属性说明";
        Label detail = new Label(details); detail.setWrapText(true); detail.setMaxWidth(210); detail.getStyleClass().add("equipment-card-detail");
        VBox card = new VBox(5, icon, title, detail); card.setAlignment(Pos.CENTER); card.getStyleClass().add(discovered ? "equipment-card" : "equipment-card-locked");
        return card;
    }

    private static String stackDescription(EquipmentType equipment) {
        return switch (equipment) {
            case PHASE_VESSEL -> "每件再 +12 护盾上限";
            case WAYFARER_HEART -> "每件再 +20% 最大生命";
            default -> "数值效果按件数叠加；武器同轮同时触发";
        };
    }

    private static ImageView equipmentIcon(EquipmentType equipment) {
        String name = equipment.assetId() == null
                ? (equipment.ordinal() < 3 ? "weapons_v1.png" : "equipment_v1.png")
                : "equipment/" + equipment.assetId() + ".png";
        var resource = MainMenuView.class.getResource("/com/phantomcorridor/ui/" + name);
        Image image = resource == null ? null : new Image(resource.toExternalForm(), false);
        ImageView view = new ImageView(image);
        if (image != null && equipment.assetId() == null) {
            double cellWidth = image.getWidth() / 3.0;
            view.setViewport(new Rectangle2D((equipment.ordinal() % 3) * cellWidth, 0, cellWidth, image.getHeight()));
        }
        return view;
    }

    private static Image achievementIcon(Achievement achievement) {
        var resource = MainMenuView.class.getResource("/com/phantomcorridor/ui/achievements/"
                + achievement.iconId() + ".png");
        return resource == null ? null : new Image(resource.toExternalForm(), false);
    }

    /**
     * 构建难度选择覆盖层：点击「开始游戏」后先选难度再进游戏。
     *
     * <p>难度分别控制敌伤、Boss 耐久、波次数量和装备收益；选好后开始新局。
     */
    private VBox createDifficultyContent(Settings settings, Runnable onStart) {
        Label heading = new Label("选择难度");
        heading.getStyleClass().add("overlay-title");

        Label hint = new Label("高难度增加精英压力，也提高装备收益；困难与屌炸天的 Boss 耐久已下调。");
        hint.getStyleClass().add("hint-text");

        VBox options = new VBox(14.0);
        options.setAlignment(Pos.CENTER);
        for (Difficulty difficulty : Difficulty.values()) {
            Button option = createMenuButton(difficulty.displayName(),
                    () -> chooseDifficulty(settings, difficulty, onStart));
            if (difficulty == settings.getDifficulty()) {
                option.getStyleClass().add("menu-button-current");
            }
            if (difficulty == Difficulty.NORMAL) {
                normalDifficultyButton = option;
            }
            options.getChildren().add(option);
        }

        Label detail = new Label(describeDifficulties());
        detail.getStyleClass().add("hint-text");
        detail.setWrapText(true);
        detail.setMaxWidth(720.0);
        detail.setTextAlignment(TextAlignment.CENTER);

        Button backButton = createMenuButton("返回菜单", () -> hideOverlay(difficultyContent, this::fadeInMenu));

        VBox box = new VBox(22.0, heading, hint, options, detail, backButton);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        box.setMaxWidth(VBox.USE_PREF_SIZE);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    /** 四档难度的一句话说明，拼成面板底部那行小字。 */
    private static String describeDifficulties() {
        return Arrays.stream(Difficulty.values())
                .map(value -> value.displayName() + "：" + value.description())
                .collect(Collectors.joining("　·　"));
    }

    /** 选定难度：写回设置后按原来的开始过渡进入游戏。 */
    private void chooseDifficulty(Settings settings, Difficulty difficulty, Runnable onStart) {
        settings.setDifficulty(difficulty);
        playStartTransition(onStart);
    }

    /** 向两列表格追加一行（名称 + 说明；两列内容均在列内水平居中，见 ui.css） */
    private int addKeyRow(GridPane grid, int row, String name, String description) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("keyboard-key");
        GridPane.setHalignment(nameLabel, HPos.CENTER);
        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("help-desc");
        GridPane.setHalignment(descLabel, HPos.CENTER);
        grid.add(nameLabel, 0, row);
        grid.add(descLabel, 1, row);
        return row + 1;
    }

    /** 统一创建菜单按钮：绑定动作 + 悬停动效（放大 + 前置符文光标淡入）。
     *  <p>文字严格居中：图形内容为「光标 + 文本 + 等宽隐形占位」的对称组合，
     *  光标淡入/淡出不改变文本位置，仅增加氛围。 */
    private Button createMenuButton(String text, Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("menu-button");
        button.setOnAction(event -> action.run());

        // 前置符文光标 ✦：固定宽度，默认隐藏，悬停时淡入
        Label cursor = new Label("✦");
        cursor.getStyleClass().add("menu-cursor");
        cursor.setMinWidth(STAR_WIDTH);
        cursor.setMaxWidth(STAR_WIDTH);
        cursor.setOpacity(0.0);

        // 按钮文本：置于光标与右侧隐形占位之间，保证文本居中
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("menu-button-label");

        Region spacer = new Region();
        spacer.setMinWidth(STAR_WIDTH);
        spacer.setMaxWidth(STAR_WIDTH);

        HBox content = new HBox(6.0, cursor, textLabel, spacer);
        content.setAlignment(Pos.CENTER);
        button.setGraphic(content);
        button.setGraphicTextGap(0.0);

        // 悬停：按钮轻微放大 + 光标淡入；移出：反向恢复
        ScaleTransition grow = new ScaleTransition(Duration.millis(120), button);
        grow.setToX(1.05);
        grow.setToY(1.05);
        ScaleTransition shrink = new ScaleTransition(Duration.millis(120), button);
        shrink.setToX(1.0);
        shrink.setToY(1.0);
        FadeTransition cursorIn = new FadeTransition(Duration.millis(120), cursor);
        cursorIn.setToValue(1.0);
        FadeTransition cursorOut = new FadeTransition(Duration.millis(120), cursor);
        cursorOut.setToValue(0.0);
        button.hoverProperty().addListener((obs, oldValue, hovered) -> {
            if (hovered) {
                cursorIn.play();
                grow.play();
            } else {
                cursorOut.play();
                shrink.play();
            }
        });
        return button;
    }

    /** 菜单入场动画：标题组/分隔线/按钮依次淡入上浮 */
    private void playEnterAnimation() {
        List<Node> items = new ArrayList<>(menuContent.getChildren());
        for (Node node : items) {
            node.setOpacity(0.0);
            node.setTranslateY(18.0);
        }
        for (int i = 0; i < items.size(); i++) {
            Duration delay = Duration.millis(i * ENTER_STEP_MS);
            FadeTransition fade = new FadeTransition(Duration.millis(ENTER_FADE_MS), items.get(i));
            fade.setFromValue(0.0);
            fade.setToValue(1.0);
            fade.setDelay(delay);
            TranslateTransition slide = new TranslateTransition(Duration.millis(ENTER_FADE_MS), items.get(i));
            slide.setFromY(18.0);
            slide.setToY(0.0);
            slide.setDelay(delay);
            new ParallelTransition(fade, slide).play();
        }
    }

    /** 打开覆盖层：菜单内容淡出 → 覆盖层滑入淡入 */
    private void showOverlay(VBox panel) {
        if (transitionLocked) {
            return;
        }
        transitionLocked = true;
        if (panel == galleryContent) {
            panel = replaceProgressPanel(galleryContent, createGalleryContent(progress));
            galleryContent = panel;
        } else if (panel == achievementContent) {
            panel = replaceProgressPanel(achievementContent, createAchievementContent(progress));
            achievementContent = panel;
        }
        final VBox displayedPanel = panel;
        if (displayedPanel instanceof SettingsOverlay settingsOverlay) {
            settingsOverlay.refreshProfile();
        }
        // 覆盖层打开时「开始游戏」不再吃 Enter，避免在设置/图鉴里按回车直接开局。
        startButton.setDefaultButton(false);
        if (normalDifficultyButton != null) {
            normalDifficultyButton.setDefaultButton(displayedPanel == difficultyContent);
        }
        FadeTransition out = new FadeTransition(Duration.millis(150.0), menuContent);
        out.setToValue(0.0);
        out.setOnFinished(event -> {
            menuContent.setVisible(false);
            menuContent.setManaged(false);
            displayedPanel.setVisible(true);
            displayedPanel.setManaged(true);
            displayedPanel.setOpacity(0.0);
            displayedPanel.setTranslateY(26.0);
            FadeTransition in = new FadeTransition(Duration.millis(OVERLAY_MS), displayedPanel);
            in.setToValue(1.0);
            TranslateTransition slide = new TranslateTransition(Duration.millis(OVERLAY_MS), displayedPanel);
            slide.setToY(0.0);
            ParallelTransition transition = new ParallelTransition(in, slide);
            transition.setOnFinished(done -> transitionLocked = false);
            transition.play();
        });
        out.play();
    }

    /** 每次打开进度页都重建，确保刚解锁的成就和装备立即可见。 */
    private VBox replaceProgressPanel(VBox previous, VBox replacement) {
        int index = getChildren().indexOf(previous);
        if (index >= 0) getChildren().set(index, replacement);
        return replacement;
    }

    /** 收起覆盖层：覆盖层淡出 → 菜单内容重新显示（可指定淡入回调） */
    private void hideOverlay(VBox panel, Runnable onFinished) {
        if (transitionLocked) {
            return;
        }
        transitionLocked = true;
        FadeTransition out = new FadeTransition(Duration.millis(160.0), panel);
        out.setToValue(0.0);
        out.setOnFinished(event -> {
            panel.setVisible(false);
            panel.setManaged(false);
            menuContent.setVisible(true);
            menuContent.setManaged(true);
            // 回到主菜单：Enter 恢复为「开始游戏」。
            if (normalDifficultyButton != null) normalDifficultyButton.setDefaultButton(false);
            startButton.setDefaultButton(true);
            onFinished.run();
            transitionLocked = false;
        });
        out.play();
    }

    /** 菜单内容淡入（覆盖层收起后调用） */
    private void fadeInMenu() {
        menuContent.setOpacity(0.0);
        FadeTransition in = new FadeTransition(Duration.millis(220.0), menuContent);
        in.setToValue(1.0);
        in.play();
    }

    /** 开始游戏过渡：双界遮罩渐入 + 标题放大淡出；动画结束后调用 {@code onStart} 切入游戏界面 */
    private void playStartTransition(Runnable onStart) {
        if (transitionLocked) {
            return;
        }
        transitionLocked = true;
        overlay.setVisible(true);
        overlay.setManaged(true);

        FadeTransition overlayIn = new FadeTransition(Duration.millis(START_TRANSITION_MS), overlay);
        overlayIn.setFromValue(0.0);
        overlayIn.setToValue(1.0);

        ScaleTransition titleGrow = new ScaleTransition(Duration.millis(START_TRANSITION_MS * 0.9), title);
        titleGrow.setFromX(1.0);
        titleGrow.setFromY(1.0);
        titleGrow.setToX(1.3);
        titleGrow.setToY(1.3);

        FadeTransition titleFade = new FadeTransition(Duration.millis(START_TRANSITION_MS * 0.9), title);
        titleFade.setToValue(0.0);

        FadeTransition contentFade = new FadeTransition(Duration.millis(START_TRANSITION_MS * 0.8), menuContent);
        contentFade.setToValue(0.0);

        ParallelTransition transition = new ParallelTransition(
                overlayIn, titleGrow, titleFade, contentFade);
        transition.setOnFinished(event -> onStart.run());
        transition.play();
    }

    /** 退出过渡：双界遮罩渐入后关闭窗口 */
    private void playExitTransition(Runnable onQuit) {
        if (transitionLocked) {
            return;
        }
        transitionLocked = true;
        overlay.setVisible(true);
        overlay.setManaged(true);
        FadeTransition fade = new FadeTransition(Duration.millis(300.0), overlay);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setOnFinished(event -> onQuit.run());
        fade.play();
    }

    /** 从游戏/暂停返回主菜单时复位所有动画状态（遮罩、标题、覆盖层、菜单内容） */
    private void resetForReentry() {
        transitionLocked = false;
        overlay.setVisible(false);
        overlay.setManaged(false);
        overlay.setOpacity(0.0);
        title.setScaleX(1.0);
        title.setScaleY(1.0);
        title.setOpacity(1.0);
        menuContent.setVisible(true);
        menuContent.setManaged(true);
        menuContent.setOpacity(1.0);
        galleryContent.setVisible(false);
        galleryContent.setManaged(false);
        difficultyContent.setVisible(false);
        difficultyContent.setManaged(false);
        settingsContent.setVisible(false);
        settingsContent.setManaged(false);
        if (normalDifficultyButton != null) normalDifficultyButton.setDefaultButton(false);
        startButton.setDefaultButton(true);
        playEnterAnimation();
    }

    @Override
    public void onEnter() {
        resetForReentry();
        backdrop.onEnter();
        requestFocus();
    }

    @Override
    public void onExit() {
        backdrop.onExit();
    }
}
