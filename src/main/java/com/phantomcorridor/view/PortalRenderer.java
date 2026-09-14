package com.phantomcorridor.view;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** A stone threshold with a two-world seam, anchored to the portal's interaction point. */
final class PortalRenderer {
    static void draw(GraphicsContext g, double x, double y, double time, int floor, int total) {
        g.save();
        g.translate(Math.rint(x), Math.rint(y));
        double breath = 0.7 + 0.15 * Math.sin(time * 1.6);
        Color gold = Color.web("#d9b776"), violet = Color.web("#aa79d8");
        g.setFill(Color.rgb(0, 0, 0, 0.4));
        g.fillOval(-78, 24, 156, 36);
        g.setFill(Color.web("#211e28"));
        g.fillPolygon(new double[]{-70, 70, 79, -79}, new double[]{27, 27, 47, 47}, 4);
        g.setStroke(Color.web("#665a63"));
        g.setLineWidth(2);
        g.strokeLine(-69, 28, 69, 28);
        double[] outerX = {-62, -62, -44, 0, 44, 62, 62};
        double[] outerY = {30, -68, -111, -137, -111, -68, 30};
        g.setFill(new LinearGradient(0, -140, 30, 45, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5b5261")), new Stop(0.45, Color.web("#38323f")),
                new Stop(1, Color.web("#231f2b"))));
        g.fillPolygon(outerX, outerY, 7);
        g.setStroke(Color.web("#14111c"));
        g.setLineWidth(4); g.strokePolygon(outerX, outerY, 7);
        double[] innerX = {-42, -42, -28, 0, 28, 42, 42};
        double[] innerY = {29, -66, -94, -112, -94, -66, 29};
        g.setFill(Color.web("#080912"));
        g.fillPolygon(innerX, innerY, 7);
        g.setStroke(Color.web("#756474")); g.setLineWidth(1);
        g.strokePolyline(outerX, outerY, 7);
        // Separate stone blocks, with restrained gold/cyan and violet inlays.
        for (int side : new int[]{-1, 1}) {
            g.setStroke(side < 0 ? gold : violet); g.setLineWidth(2);
            g.strokePolyline(new double[]{side * 49, side * 49, side * 34, 0},
                    new double[]{23, -69, -103, -124}, 4);
            for (int row = 0; row < 3; row++) {
                double yy = -53 + row * 29;
                g.setStroke(Color.web("#17141f"));
                g.strokeLine(side * 44, yy + 13, side * 60, yy + 16);
                g.setStroke(side < 0 ? gold : violet);
                double xx = side * 54;
                g.strokePolyline(new double[]{xx, xx - 3, xx, xx + 3, xx},
                        new double[]{yy - 5, yy, yy + 5, yy, yy - 5}, 5);
            }
        }
        g.save();
        g.beginPath(); g.moveTo(innerX[0], innerY[0]);
        for (int i = 1; i < innerX.length; i++) g.lineTo(innerX[i], innerY[i]);
        g.closePath(); g.clip();
        g.setFill(new LinearGradient(-40, 0, 40, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#111b22")), new Stop(0.42, Color.web("#28494e")),
                new Stop(0.51, Color.web("#d4bfdf")), new Stop(0.6, Color.web("#42304f")),
                new Stop(1, Color.web("#15101f"))));
        g.setGlobalAlpha(breath);
        g.fillPolygon(new double[]{-5, -22, -12, -28, -8, 0, 11, 28, 14, 20, 4},
                new double[]{-125, -85, -53, -15, 34, 43, 30, -8, -45, -81, -125}, 11);
        g.setGlobalAlpha(0.9);
        g.setStroke(Color.web("#e9e0c8")); g.setLineWidth(1.5);
        g.strokePolyline(new double[]{0, -3, 4, -2, 3, 0}, new double[]{-115, -78, -49, -15, 8, 35}, 6);
        for (int i = 0; i < 10; i++) {
            double py = 34 - ((time * 15 + i * 17) % 150);
            double px = Math.sin(i * 2.7 + time * 0.3) * (12 + i % 3 * 7);
            g.setFill(i % 2 == 0 ? Color.web("#8bbfc0") : violet);
            g.fillRect(Math.rint(px), Math.rint(py), 2, 3);
        }
        g.restore();
        g.setFill(gold);
        g.fillPolygon(new double[]{0, 6, 0, -6}, new double[]{-143, -134, -125, -134}, 4);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 15));
        g.setFill(Color.web("#e4cc9a"));
        g.fillText(floor >= total ? "终途裂隙 · 通关" : "界隙之门 · 第 " + (floor + 1) + " 层", 0, 72);
        g.setFont(Font.font("Microsoft YaHei UI", 12));
        g.setFill(Color.web("#b6a5bf"));
        g.fillText("走近按 F 穿越", 0, 92);
        g.restore();
    }
    private PortalRenderer() { }
}
