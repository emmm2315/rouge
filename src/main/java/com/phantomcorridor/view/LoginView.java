package com.phantomcorridor.view;

import com.phantomcorridor.controller.SceneLifecycle;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** 登录视图：只收集输入并展示校验结果，校验规则由 LoginController 负责。 */
public final class LoginView extends StackPane implements SceneLifecycle {

    private final DualWorldBackdrop backdrop = new DualWorldBackdrop();
    private final TextField nicknameField = new TextField();
    private final Label errorLabel = new Label();

    public LoginView(Consumer<String> onSubmit) {
        getStyleClass().add("login-pane");
        backdrop.setManaged(false);

        Label lightMark = domainMark("光", "domain-mark-light");
        Label shadowMark = domainMark("影", "domain-mark-shadow");

        Label eyebrow = new Label("PHANTOM CORRIDOR");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("双界行者");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("以光为刃 · 借影而行");
        subtitle.getStyleClass().add("login-subtitle");

        VBox titleGroup = new VBox(8.0, eyebrow, title, subtitle);
        titleGroup.setAlignment(Pos.CENTER);
        HBox titleRow = new HBox(22.0, lightMark, titleGroup, shadowMark);
        titleRow.setAlignment(Pos.CENTER);

        nicknameField.getStyleClass().add("login-input");
        nicknameField.setPromptText("旅者昵称（必填）");
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setMinHeight(22.0);

        Button enterButton = new Button("踏入回廊");
        enterButton.getStyleClass().addAll("menu-button", "primary-button");
        enterButton.setDefaultButton(true);
        enterButton.setOnAction(event -> onSubmit.accept(nicknameField.getText()));

        Label privacy = new Label("仅用于本地档案识别 · 不连接服务器");
        privacy.getStyleClass().add("hint-text");

        VBox fields = new VBox(14.0, nicknameField, errorLabel, enterButton, privacy);
        fields.setAlignment(Pos.CENTER);
        fields.setMaxWidth(360.0);

        VBox card = new VBox(34.0, titleRow, fields);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(620.0, VBox.USE_PREF_SIZE);

        getChildren().addAll(backdrop, card);
    }

    private static Label domainMark(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("domain-mark", styleClass);
        return label;
    }

    public void setNickname(String nickname) {
        nicknameField.setText(nickname == null ? "" : nickname);
    }

    public void showError(String message) {
        errorLabel.setText(message);
        nicknameField.requestFocus();
    }

    public void clearError() {
        errorLabel.setText("");
    }

    @Override
    public void onEnter() {
        backdrop.onEnter();
        nicknameField.requestFocus();
    }

    @Override
    public void onExit() {
        backdrop.onExit();
    }
}
