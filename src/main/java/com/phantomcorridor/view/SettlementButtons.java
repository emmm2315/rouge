package com.phantomcorridor.view;

import com.phantomcorridor.config.AppConfig;

/**
 * 结算界面（阵亡 / 通关）两颗按钮的唯一几何定义。
 *
 * <p>渲染与点击命中共用同一份坐标：这两处一旦各写一遍，就会出现"看到的按钮"和
 * "点得中的按钮"对不上的经典问题——按钮的位置改了、点击判定却留在旧坐标上。
 * 把坐标收在这里，至少保证两边读的是同一个数。
 *
 * <p>纯几何、不引用 JavaFX，所以可以直接被单测钉住。
 */
public final class SettlementButtons {

    /** 一个轴对齐矩形按钮。 */
    public record Button(double x, double y, double width, double height) {

        /** 某个逻辑坐标（画布坐标系）是否落在这颗按钮内。 */
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    /** 重开一局。 */
    public static final Button RESTART = new Button(
            AppConfig.VIEW_WIDTH / 2.0 - 170.0, AppConfig.VIEW_HEIGHT / 2.0 + 44.0, 140.0, 50.0);

    /** 返回主菜单。 */
    public static final Button MAIN_MENU = new Button(
            AppConfig.VIEW_WIDTH / 2.0 + 30.0, AppConfig.VIEW_HEIGHT / 2.0 + 44.0, 140.0, 50.0);

    /** 工具类：不允许实例化。 */
    private SettlementButtons() {
    }
}
