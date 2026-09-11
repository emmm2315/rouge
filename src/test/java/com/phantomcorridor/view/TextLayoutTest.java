package com.phantomcorridor.view;

import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「图标 + 一行文字」的排版算术。
 *
 * <p>针对一个真实出现过的 bug：换装面板整体是 {@code TextAlignment.CENTER}，
 * {@code fillText} 的 x 是**文字中心**而不是左边界，但布局代码按“左边界”去推图标位置，
 * 于是文字左半边直接压在图标上（拾取某某装备的那一行最明显）。
 * {@link TextLayout#iconWithText} 是那段算术的唯一出口，把它钉住就能防止回归。
 */
class TextLayoutTest {

    private static final Font HEADLINE =
            Font.font("Microsoft YaHei UI", FontWeight.BOLD, 15);

    @Test
    void iconAndTextNeverOverlapForAnyHeadlineLength() {
        // 覆盖“拾取「X」”和更长的“花 N 金币买下「X」”两类文案，以及长短不一的装备名。
        String[] texts = {
                "拾取「行者心核」",
                "拾取「棱光三叉杖」",
                "拾取「相位陀螺」",
                "花 18 金币买下「暮色斗篷」",
                "花 120 金币买下「夜坠重剑」",
                "花 999 金币买下「曜纹披肩」",
                "花 1000 金币买下「炽核权杖」"
        };
        for (String text : texts) {
            TextLayout.IconText layout = TextLayout.iconWithText(640.0, 30.0, 18.0, text, HEADLINE);

            double iconRight = layout.iconX() + 30.0;
            double textLeft = layout.textCenterX() - layout.textWidth() / 2.0;

            assertEquals(18.0, textLeft - iconRight, 0.5,
                    "图标右缘与文字左缘之间必须正好是设定的间距：" + text);
            assertTrue(textLeft >= iconRight,
                    "文字不能压到图标上（" + text + "）：文字左缘 " + textLeft + " vs 图标右缘 " + iconRight);

            double blockLeft = layout.iconX();
            double blockRight = layout.textCenterX() + layout.textWidth() / 2.0;
            assertEquals(640.0, (blockLeft + blockRight) / 2.0, 0.5,
                    "整块（图标 + 间距 + 文字）应当以 centerX 居中：" + text);
        }
    }

    @Test
    void longerHeadlinePushesTheIconFurtherLeft() {
        TextLayout.IconText shortOne = TextLayout.iconWithText(640.0, 30.0, 18.0, "拾取「行者心核」", HEADLINE);
        TextLayout.IconText longOne = TextLayout.iconWithText(640.0, 30.0, 18.0, "花 120 金币买下「夜坠重剑」", HEADLINE);

        assertTrue(longOne.iconX() < shortOne.iconX(),
                "标题更长时图标必须往左让，否则右边会顶出面板");
    }

    @Test
    void widthIsMeasuredNotGuessed() {
        // 量宽度必须是实测：多一个全角字，宽度就得真的变大。
        assertTrue(TextLayout.width("拾取「行者心核」！", HEADLINE) > TextLayout.width("拾取「行者心核」", HEADLINE));
        assertTrue(TextLayout.width("拾取「行者心核」", HEADLINE) > 0.0);
    }

    @Test
    void fitShrinksOnlyWhenItHasTo() {
        String shortText = "拾取「行者心核」";
        assertEquals(shortText, TextLayout.fit(shortText, 1000.0, HEADLINE), "放得下就原样返回");

        String longText = "花 999 金币买下「曜纹披肩」以及一些额外的说明文字";
        String fitted = TextLayout.fit(longText, 120.0, HEADLINE);
        assertNotEquals(longText, fitted);
        assertTrue(fitted.endsWith("…"), "截断要带省略号");
        assertTrue(TextLayout.width(fitted, HEADLINE) <= 120.0, "截断后必须真的放得下");
    }
}
