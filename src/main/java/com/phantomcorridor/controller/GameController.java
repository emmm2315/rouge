package com.phantomcorridor.controller;

import com.phantomcorridor.core.GameLoop;
import com.phantomcorridor.model.GameSession;
import com.phantomcorridor.model.Achievement;
import com.phantomcorridor.model.PlayerProgress;
import com.phantomcorridor.view.GameView;
import com.phantomcorridor.view.SettlementButtons;
import javafx.scene.input.KeyCode;
import com.phantomcorridor.config.Settings;
import java.util.function.Consumer;

/** 游戏输入、模型更新和渲染调度。 */
public final class GameController {

    private final GameSession session = new GameSession();
    private final InputState input = new InputState();
    private final GameView view;
    private final Runnable onPauseRequested;
    private final GameLoop loop;
    private boolean running;
    private boolean shiftHeld;
    private boolean interactHeld;
    private boolean attackHeld;
    /** 右键按住状态：光形态=蓄力，影形态=按下瞬间开启格挡。 */
    private boolean secondaryHeld;
    private boolean dashHeld;
    private boolean skillHeld, skill2Held, finisherHeld, modifierHeld;
    private double aimX;
    private double aimY;
    private final Settings settings;
    private final Runnable onMainMenu;
    private final Consumer<GameSession> onSessionUpdated;
    private final PlayerProgress progress;

    public GameController(GameView view, Runnable onPauseRequested, Settings settings) {
        this(view, onPauseRequested, settings, () -> { }, session -> { });
    }

    public GameController(GameView view, Runnable onPauseRequested, Settings settings, Runnable onMainMenu) {
        this(view, onPauseRequested, settings, onMainMenu, session -> { });
    }

    /** @param onSessionUpdated 用于同步音乐等只读的表现层状态。 */
    public GameController(GameView view, Runnable onPauseRequested, Settings settings, Runnable onMainMenu,
                          Consumer<GameSession> onSessionUpdated) {
        this(view, onPauseRequested, settings, onMainMenu, onSessionUpdated, new PlayerProgress());
    }

    public GameController(GameView view, Runnable onPauseRequested, Settings settings, Runnable onMainMenu,
                          Consumer<GameSession> onSessionUpdated, PlayerProgress progress) {
        this.view = view;
        this.onPauseRequested = onPauseRequested;
        this.settings = settings;
        this.onMainMenu = onMainMenu;
        this.onSessionUpdated = onSessionUpdated == null ? session -> { } : onSessionUpdated;
        this.progress = progress == null ? new PlayerProgress() : progress;
        this.loop = new GameLoop() {
            @Override
            protected void update(double dt) {
                // 替换选择面板是模态的：面板开着时不推进攻击，玩家的手不需要在“选槽位”和“松开鼠标”之间分心。
                boolean panelOpen = session.getPendingEquipment() != null;
                boolean attacking = attackHeld && !panelOpen;
                session.setSecondaryHeld(secondaryHeld && !panelOpen);
                session.update(dt, input.horizontal(), input.vertical(), aimX, aimY, attacking);
                updateProgress();
                GameController.this.onSessionUpdated.accept(session);
            }
            @Override
            protected void render(double frameDelta) {
                view.render(session, getSmoothedFps());
            }
        };
        view.bindInput(this::keyPressed, this::keyReleased);
        view.bindPointer(this::pointerMoved, held -> attackHeld = held);
        view.bindSecondary(held -> secondaryHeld = held);
        view.bindClick(this::pointerClicked);
        view.bindLifecycle(this::start, this::stop);
        newRun();
    }

    private void updateProgress() {
        session.getCollectedEquipment().forEach(progress::discover);
        if (progress.hasDiscoveredEveryEquipment() && progress.unlock(Achievement.ALL_EQUIPMENT)) {
            view.showAchievementUnlocked(Achievement.ALL_EQUIPMENT);
        }
        session.getDefeatedBossKinds().forEach(progress::recordBossDefeat);
        if (session.hasDefeatedBoss() && progress.unlock(Achievement.BOSS_SLAYER)) {
            view.showAchievementUnlocked(Achievement.BOSS_SLAYER);
        }
        if (progress.hasDefeatedEveryBoss() && progress.unlock(Achievement.ALL_BOSSES)) {
            view.showAchievementUnlocked(Achievement.ALL_BOSSES);
        }
        if (!session.isRunCleared()) return;
        Achievement clearAchievement = switch (session.getDifficulty()) {
            case EASY -> Achievement.EASY_CLEAR;
            case NORMAL -> Achievement.NORMAL_CLEAR;
            case HARD -> Achievement.HARD_CLEAR;
            case INSANE -> Achievement.INSANE_CLEAR;
        };
        if (progress.unlock(clearAchievement)) view.showAchievementUnlocked(clearAchievement);
    }

    public void newRun() {
        session.newRun(settings.getDevSeed(), settings.getDifficulty());
        resetInput();
        aimX = session.getPlayer().getX() + 1.0;
        aimY = session.getPlayer().getY();
        onSessionUpdated.accept(session);
        view.render(session, 0.0);
    }

    private void start() {
        if (!running) {
            loop.start();
            running = true;
        }
    }

    private void stop() {
        if (running) {
            loop.stop();
            running = false;
        }
        resetInput();
    }

    private void resetInput() {
        input.clear();
        shiftHeld = false;
        interactHeld = false;
        attackHeld = false;
        secondaryHeld = false;
        session.clearPendingInput();
        dashHeld = false;
        skillHeld = skill2Held = finisherHeld = modifierHeld = false;
    }

    private void keyPressed(KeyCode key) {
        // 结算界面（阵亡 / 通关）统一：R 重开一局、M 返回主菜单，键盘与鼠标两种方式都能选。
        if (isRunOver()) {
            if (key == KeyCode.R) newRun();
            else if (key == KeyCode.M) onMainMenu.run();
            return;
        }
        // 装备栏满时，数字键只用于替换选择。
        // ESC 是两级语义：面板开着时先关掉面板（装备留在原地），关掉之后再按才是暂停。
        if (session.getPendingEquipment() != null) {
            if (key == KeyCode.ESCAPE) {
                session.resolveEquipmentSelection(-1);
                return;
            }
            if (key == KeyCode.P) {
                onPauseRequested.run();
                return;
            }
            switch (key) {
                case DIGIT1, NUMPAD1 -> session.resolveEquipmentSelection(0);
                case DIGIT2, NUMPAD2 -> session.resolveEquipmentSelection(1);
                case DIGIT3, NUMPAD3 -> session.resolveEquipmentSelection(2);
                default -> { }
            }
            return;
        }
        switch (key) {
            case SHIFT -> modifierHeld = true;
            case CONTROL -> {
                if (!shiftHeld && session.tryShiftWorld(modifierHeld)) {
                    view.playWorldShift(session.getPlayer().getCurrentWorld());
                }
                shiftHeld = true;
            }
            case Q -> {
                if (!skillHeld) session.tryUseAbility(false, aimX, aimY);
                skillHeld = true;
            }
            case E -> {
                if (!skill2Held) session.tryUseSkill(1, aimX, aimY);
                skill2Held = true;
            }
            case R -> {
                if (!finisherHeld) session.tryUseAbility(true, aimX, aimY);
                finisherHeld = true;
            }
            case W, UP -> input.setUp(true);
            case S, DOWN -> input.setDown(true);
            case A, LEFT -> input.setLeft(true);
            case D, RIGHT -> input.setRight(true);
            case F -> {
                // 长按会连发 keyPressed：交互（尤其商店的二次确认）必须一次按下只算一次。
                if (!interactHeld) session.requestInteract();
                interactHeld = true;
            }
            // 非选择状态下，数字键丢弃对应装备槽位；掉落物保留在脚下，可再次拾取。
            case DIGIT1, NUMPAD1 -> session.dropEquipment(0);
            case DIGIT2, NUMPAD2 -> session.dropEquipment(1);
            case DIGIT3, NUMPAD3 -> session.dropEquipment(2);
            case SPACE -> {
                // 同 E：长按会连发 keyPressed，冲刺必须一次按下只算一次（冷却由玩家模型把关）。
                if (!dashHeld) session.requestDash();
                dashHeld = true;
            }
            case ESCAPE, P -> onPauseRequested.run();
            default -> { }
        }
    }

    private void keyReleased(KeyCode key) {
        switch (key) {
            case SHIFT -> modifierHeld = false;
            case CONTROL -> shiftHeld = false;
            case Q -> skillHeld = false;
            case E -> skill2Held = false;
            case R -> finisherHeld = false;
            case W, UP -> input.setUp(false);
            case S, DOWN -> input.setDown(false);
            case A, LEFT -> input.setLeft(false);
            case D, RIGHT -> input.setRight(false);
            case F -> interactHeld = false;
            case SPACE -> dashHeld = false;
            default -> { }
        }
    }

    private void pointerMoved(double x, double y) {
        aimX = x;
        aimY = y;
    }

    /**
     * 本局是否已经结束（阵亡或通关）。
     *
     * <p>结算界面同时接受键盘（R / M）与鼠标点击，两种方式等价——
     * 玩家不需要先猜"这一屏认哪个输入"。
     */
    private boolean isRunOver() {
        return session.isRunCleared() || session.getPlayer().getHp() <= 0;
    }

    private void pointerClicked(double x, double y) {
        if (!isRunOver()) return;
        // 命中判定与渲染共用 SettlementButtons 里同一份矩形，避免"看到的按钮"与"点得中的位置"错位。
        if (SettlementButtons.RESTART.contains(x, y)) {
            newRun();
        } else if (SettlementButtons.MAIN_MENU.contains(x, y)) {
            onMainMenu.run();
        }
    }
}
