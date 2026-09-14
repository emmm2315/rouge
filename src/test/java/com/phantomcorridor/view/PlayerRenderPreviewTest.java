package com.phantomcorridor.view;

import com.phantomcorridor.model.GameSession;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Optional real JavaFX render verification: -Dplayer.preview=true. No visible window is opened. */
@EnabledIfSystemProperty(named = "player.preview", matches = "true")
class PlayerRenderPreviewTest {
    @Test void renderGameplayAndAllBodyClips() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable render = () -> {
            try {
                GameSession session = new GameSession();
                session.newRun("v3-preview");
                session.update(0.05, -1, 0, 300, 300, true);
                Canvas game = new Canvas(com.phantomcorridor.config.AppConfig.VIEW_WIDTH,
                        com.phantomcorridor.config.AppConfig.VIEW_HEIGHT);
                new GameRenderer().render(game.getGraphicsContext2D(), session, 60);
                save(game, "target/player-v3-gameplay.png");
                session.update(3.0, 0, 0, 900, 450, false);
                session.getPlayer().toggleWorld();
                session.update(0.3, 0, 0, 900, 450, false);
                session.update(0.01, 0, 0, 900, session.getPlayer().getY(), true);
                new GameRenderer().render(game.getGraphicsContext2D(), session, 60);
                save(game, "target/player-v3-shadow-attack.png");
                for (boolean light : new boolean[]{true, false}) {
                    for (double[] direction : new double[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
                        var rest = PlayerSprites.verticalBounds(PlayerSprites.frame(light, "idle", direction[0], direction[1], 0));
                        var walk = PlayerSprites.verticalBounds(PlayerSprites.frame(light, "move", direction[0], direction[1], 0));
                        org.junit.jupiter.api.Assertions.assertEquals(walk[0], rest[0], 2);
                        org.junit.jupiter.api.Assertions.assertEquals(walk[1], rest[1], 2);
                    }
                    var idle = PlayerSprites.frame(light, "idle", 1, 0, 0);
                    for (double time : new double[]{0.09, 0.18, 0.27, 1.4}) {
                        org.junit.jupiter.api.Assertions.assertSame(idle,
                                PlayerSprites.frame(light, "idle", 1, 0, time));
                    }
                }
                Canvas sheet = new Canvas(1280, 8 * 150);
                var g = sheet.getGraphicsContext2D();
                g.setFill(Color.web("#282630"));
                g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                String[] actions = {"idle", "move", "attack", "hit", "dodge", "invulnerable", "transition", "finisher_cast"};
                for (int row = 0; row < actions.length; row++) {
                    for (int col = 0; col < 8; col++) {
                        boolean light = col < 4;
                        int direction = col % 4;
                        double x = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                        double y = direction == 0 ? 1 : direction == 1 ? -1 : 0;
                        g.drawImage(PlayerSprites.frame(light, actions[row], x, y, 0.09), col * 160, row * 150 + 20, 160, 128);
                        g.setFill(Color.WHITE);
                        g.fillText((light ? "light/" : "shadow/") + actions[row] + "/" + direction, col * 160 + 3, row * 150 + 15);
                    }
                }
                save(sheet, "target/player-v3-contact-sheet.png");
                Canvas portal = new Canvas(720, 420);
                var pg = portal.getGraphicsContext2D();
                pg.setFill(Color.web("#28252d")); pg.fillRect(0, 0, 720, 420);
                PortalRenderer.draw(pg, 190, 235, 3.2, 1, 5);
                PortalRenderer.draw(pg, 530, 235, 3.2, 5, 5);
                save(portal, "target/portal-style-preview.png");
                Canvas walk = new Canvas(640, 8 * 150);
                var wg = walk.getGraphicsContext2D();
                wg.setFill(Color.web("#282630")); wg.fillRect(0, 0, 640, 1200);
                for (int row = 0; row < 8; row++) {
                    int direction = row % 4;
                    double wx = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                    double wy = direction == 0 ? 1 : direction == 1 ? -1 : 0;
                    for (int col = 0; col < 4; col++) {
                        wg.drawImage(PlayerSprites.frame(row < 4, "move", wx, wy, col / 4.0),
                                col * 160, row * 150 + 20, 160, 128);
                        wg.setFill(Color.WHITE);
                        wg.fillText((row < 4 ? "light" : "shadow") + " / " + direction + " / " + col,
                                col * 160 + 4, row * 150 + 15);
                    }
                }
                save(walk, "target/player-walk-cycle-preview.png");
                for (boolean light : new boolean[]{true, false}) {
                    for (boolean finisher : new boolean[]{false, true}) {
                        session.newRun("v3-preview");
                        session.update(3, 0, 0, 800, 480, false);
                        if (!light) session.getPlayer().toggleWorld();
                        session.update(0.3, 0, 0, 800, 480, false);
                        session.getPlayer().restorePhaseEnergy(100);
                        var p = session.getPlayer();
                        org.junit.jupiter.api.Assertions.assertTrue(session.tryUseAbility(finisher, p.getX() + 80, p.getY()));
                        String prefix = "target/player-v3-" + (light ? "light-" : "shadow-") + (finisher ? "finisher" : "skill");
                        session.update(finisher ? 0.2 : 0.1, 0, 0, 800, 480, false);
                        new GameRenderer().render(game.getGraphicsContext2D(), session, 60);
                        save(game, prefix + "-cast.png");
                        session.update(0.3, 0, 0, 800, 480, false);
                        new GameRenderer().render(game.getGraphicsContext2D(), session, 60);
                        save(game, prefix + ".png");
                    }
                }
                done.complete(null);
            } catch (Throwable error) { done.completeExceptionally(error); }
        };
        try { Platform.startup(render); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(render); }
        done.get(30, TimeUnit.SECONDS);
    }

    private static void save(Canvas canvas, String output) throws Exception {
        var image = canvas.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        Files.createDirectories(Path.of(output).getParent());
        ImageIO.write(png, "png", Path.of(output).toFile());
    }
}
