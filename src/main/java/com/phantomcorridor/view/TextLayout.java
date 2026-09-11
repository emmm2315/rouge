package com.phantomcorridor.view;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * 文字排版工具：实测宽度、按宽度截断，以及「图标 + 一行文字」的整体定位。
 *
 * <p>单独成类有两个原因：
 * <ul>
 *   <li><b>正确性</b>：{@code Canvas} 的 {@code fillText} 只接受一个 x，
 *       它到底是左边界还是中心取决于当前的 {@code TextAlignment}。把这段算术集中到一处，
 *       就不会再出现“按左边界算、实际按中心画”从而压到图标上的问题（这个 bug 真的发生过）；</li>
 *   <li><b>可测试</b>：{@link GameRenderer} 的静态初始化会去加载贴图，需要 JavaFX 工具包
 *       才能跑；这里只依赖 {@link Text}/{@link Font} 的排版计算，单元测试可以直接调用。</li>
 * </ul>
 *
 * <p>不依赖 JavaFX 工具包之外的任何状态：{@link Text} 只是用来量尺寸的临时节点，不进场景图。
 */
final class TextLayout {

    /** 量宽度用的临时节点；渲染全在 JavaFX 线程上，所以单个实例安全。 */
    private static final Text MEASURE = new Text();

    private TextLayout() { }

    /** 实测一段文字在给定字体下的渲染宽度（像素）。 */
    static double width(String text, Font font) {
        MEASURE.setFont(font);
        MEASURE.setText(text);
        return MEASURE.getLayoutBounds().getWidth();
    }

    /** 按可用宽度实测截断：放不下时从尾部砍字并补省略号。 */
    static String fit(String text, double maxWidth, Font font) {
        if (maxWidth <= 0) return "";
        if (width(text, font) <= maxWidth) return text;
        for (int length = text.length() - 1; length > 0; length--) {
            String candidate = text.substring(0, length) + "…";
            if (width(candidate, font) <= maxWidth) return candidate;
        }
        return "…";
    }

    /** 「图标 + 一行文字」的排版结果。 */
    record IconText(double iconX, double textCenterX, double textWidth) { }

    /**
     * 把一个图标和它右侧的文字作为一整块居中摆放。
     *
     * <p>返回的 {@code textCenterX} 是文字的**中心**，配合 {@code TextAlignment.CENTER} 使用：
     * 文字会以该点为中心向两侧各展开半个宽度。若把它当成左边界，文字左半边就会压到图标上。
     *
     * @param centerX  整块（图标 + 间距 + 文字）的中心
     * @param iconSize 图标边长
     * @param gap      图标右缘与文字左缘之间的间距
     */
    static IconText iconWithText(double centerX, double iconSize, double gap, String text, Font font) {
        double textWidth = width(text, font);
        double blockX = centerX - (iconSize + gap + textWidth) / 2.0;
        return new IconText(blockX, blockX + iconSize + gap + textWidth / 2.0, textWidth);
    }
}
