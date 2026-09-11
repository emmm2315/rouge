/**
 * 《双界行者》模块信息。
 *
 * <p>游戏画面使用 Canvas 代码绘制（见 {@code core.GameLoop} 与 {@code view.GameView}），
 * 模块仅依赖 javafx.controls（其透传 javafx.graphics / javafx.base），不使用 FXML（已于重构移除）。
 * 导出根包（启动入口与主类）与 core 包；model / util / config 等内部实现包不对外导出。
 */
module com.phantomcorridor {
    requires javafx.controls;
    requires javafx.media;
    requires java.prefs;
    requires java.desktop;

    exports com.phantomcorridor;
    exports com.phantomcorridor.core;
}
