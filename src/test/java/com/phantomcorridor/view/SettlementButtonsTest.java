package com.phantomcorridor.view;

import com.phantomcorridor.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结算界面按钮的几何：渲染与点击命中共用同一份坐标，这里把它钉住。
 *
 * <p>针对一类真实会发生的错位——按钮画在一处、点击判定写在另一处，结果是
 * "看得到的按钮点不中、点得中的地方看不见按钮"。只要两个矩形不重叠、都落在画布内、
 * 中心点可命中、并且对称排在结算文案下方，这类错位就会被拦下来。
 */
class SettlementButtonsTest {

    private static final double CENTER_X = AppConfig.VIEW_WIDTH / 2.0;
    private static final double CENTER_Y = AppConfig.VIEW_HEIGHT / 2.0;
    private static final List<SettlementButtons.Button> BUTTONS =
            List.of(SettlementButtons.RESTART, SettlementButtons.MAIN_MENU);

    @Test
    void eachButtonIsHitAtItsOwnCentre() {
        for (SettlementButtons.Button button : BUTTONS) {
            assertTrue(button.contains(button.x() + button.width() / 2.0,
                            button.y() + button.height() / 2.0),
                    "按钮中心必须能点中");
        }
    }

    @Test
    void theTwoButtonsNeverOverlapAndLeaveAGap() {
        SettlementButtons.Button restart = SettlementButtons.RESTART;
        SettlementButtons.Button menu = SettlementButtons.MAIN_MENU;
        assertTrue(restart.x() + restart.width() < menu.x(), "两颗按钮之间必须留空隙");

        // 两颗按钮正中间是空隙：点这里不该触发任何一颗。
        double gapX = (restart.x() + restart.width() + menu.x()) / 2.0;
        double gapY = restart.y() + restart.height() / 2.0;
        assertFalse(restart.contains(gapX, gapY), "空隙处不该命中「重新开始」");
        assertFalse(menu.contains(gapX, gapY), "空隙处不该命中「返回主菜单」");
    }

    @Test
    void theTwoButtonsAreSymmetricAboutTheCentreLine() {
        double leftGap = CENTER_X - (SettlementButtons.RESTART.x() + SettlementButtons.RESTART.width());
        double rightGap = SettlementButtons.MAIN_MENU.x() - CENTER_X;

        assertEquals(leftGap, rightGap, 1e-9, "两颗按钮应当以屏幕中线对称");
    }

    @Test
    void everyButtonStaysInsideTheCanvas() {
        for (SettlementButtons.Button button : BUTTONS) {
            assertTrue(button.x() >= 0.0 && button.y() >= 0.0, "按钮不能跑到画布左上角外面");
            assertTrue(button.x() + button.width() <= AppConfig.VIEW_WIDTH, "按钮不能超出右边界");
            assertTrue(button.y() + button.height() <= AppConfig.VIEW_HEIGHT, "按钮不能超出下边界");
        }
    }

    @Test
    void theButtonsSitBelowTheCentreLine() {
        // 结算文案（阵亡三行、通关三行）都排在中心线以上，按钮压在下面才不会盖住字。
        for (SettlementButtons.Button button : BUTTONS) {
            assertTrue(button.y() > CENTER_Y, "按钮必须在中心线下方，给结算文案留位置");
        }
    }
}
