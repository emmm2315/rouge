package com.phantomcorridor.controller;

import com.phantomcorridor.core.GameLoop;
import com.phantomcorridor.model.GameSession;
import com.phantomcorridor.view.GameView;
import javafx.scene.input.KeyCode;
import com.phantomcorridor.config.AppConfig;
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
    private boolean dashHeld;
    private double aimX;
    private double aimY;
    private final Settings settings;
    private final Runnable onMainMenu;
    private final Consumer<GameSession> onSessionUpdated;

    public GameController(GameView view, Runnable onPauseRequested, Settings settings) {
        this(view, onPauseRequested, settings, () -> { }, session -> { });
    }

    public GameController(GameView view, Runnable onPauseRequested, Settings settings, Runnable onMainMenu) {
        this(view, onPauseRequested, settings, onMainMenu, session -> { });
    }

    /** @param onSessionUpdated 用于同步音乐等只读的表现层状态。 */
    public GameController(GameView view, Runnable onPauseRequested, Settings settings, Runnable onMainMenu,
                          Consumer<GameSession> onSessionUpdated) {
        this.view = view;
        this.onPauseRequested = onPauseRequested;
        this.settings = settings;
        this.onMainMenu = onMainMenu;
        this.onSessionUpdated = onSessionUpdated == null ? session -> { } : onSessionUpdated;
        this.loop = new GameLoop() {
            @Override
            protected void update(double dt) {
                session.update(dt, input.horizontal(), input.vertical(), aimX, aimY, attackHeld);
                GameController.this.onSessionUpdated.accept(session);
            }
            @Override
            protected void render(double frameDelta) {
                view.render(session, getSmoothedFps());
            }
        };
        view.bindInput(this::keyPressed, this::keyReleased);
        view.bindPointer(this::pointerMoved, held -> attackHeld = held);
        view.bindClick(this::pointerClicked);
        view.bindLifecycle(this::start, this::stop);
        session.newRun(settings.getDevSeed());
    }

    public void newRun() {
        session.newRun(settings.getDevSeed(), settings.getDifficulty());
        input.clear();
        shiftHeld = false;
        interactHeld = false;
        attackHeld = false;
        dashHeld = false;
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
        input.clear();
        shiftHeld = false;
        interactHeld = false;
        attackHeld = false;
        dashHeld = false;
    }

    private void keyPressed(KeyCode key) {
        if (session.getPlayer().getHp() <= 0) {
            // 死亡结算保持“只能点击按钮”的交互约定。
            return;
        }
        // 通关结算沿用上游的快捷重开/返回主菜单。
        if (session.isRunCleared()) {
            if (key == KeyCode.R) newRun();
            else if (key == KeyCode.M) onMainMenu.run();
            return;
        }
        switch (key) {
            case W, UP -> input.setUp(true);
            case S, DOWN -> input.setDown(true);
            case A, LEFT -> input.setLeft(true);
            case D, RIGHT -> input.setRight(true);
            case TAB -> {
                if (!shiftHeld && session.tryShiftWorld()) {
                    view.playWorldShift(session.getPlayer().getCurrentWorld());
                }
                shiftHeld = true;
            }
            case E -> {
                // 长按会连发 keyPressed：交互（尤其商店的二次确认）必须一次按下只算一次。
                if (!interactHeld) session.requestInteract();
                interactHeld = true;
            }
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
            case W, UP -> input.setUp(false);
            case S, DOWN -> input.setDown(false);
            case A, LEFT -> input.setLeft(false);
            case D, RIGHT -> input.setRight(false);
            case TAB -> shiftHeld = false;
            case E -> interactHeld = false;
            case SPACE -> dashHeld = false;
            default -> { }
        }
    }

    private void pointerMoved(double x, double y) {
        aimX = x;
        aimY = y;
    }

    private void pointerClicked(double x, double y) {
        if (session.getPlayer().getHp() > 0) return;
        double centerX = AppConfig.VIEW_WIDTH / 2.0;
        double centerY = AppConfig.VIEW_HEIGHT / 2.0;
        double buttonY = centerY + 44.0;
        if (y < buttonY || y > buttonY + 50.0) return;
        if (x >= centerX - 170.0 && x <= centerX - 30.0) {
            newRun();
        } else if (x >= centerX + 30.0 && x <= centerX + 170.0) {
            onMainMenu.run();
        }
    }
}
