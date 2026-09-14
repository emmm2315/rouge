package com.phantomcorridor.view;

import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.entity.PlayerAnimationCatalog;
import javafx.scene.image.Image;
import javafx.scene.canvas.Canvas;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;
import java.util.HashMap;
import java.util.Map;

/** V3 body layer: original RGBA, four authored directions, one fixed anchor. */
final class PlayerSprites {
    private static final Map<String, Image[]> CACHE = new HashMap<>();

    static Image frame(boolean light, String action, double x, double y, double time) {
        String form = light ? "light" : "shadow";
        String direction = PlayerAnimationCatalog.direction(x, y);
        var clip = PlayerAnimationCatalog.clip(form, action, direction);
        String key = form + "/" + action + "/" + direction;
        Image[] frames = CACHE.computeIfAbsent(key, ignored -> {
            Image[] images = new Image[clip.frames()];
            for (int i = 0; i < images.length; i++) {
                String path = PlayerAnimationCatalog.ROOT + "body/" + key + "/frame_%02d.png".formatted(i);
                var resource = PlayerSprites.class.getResource(path);
                if (resource == null) throw new IllegalStateException("Missing player frame: " + path);
                images[i] = new Image(resource.toExternalForm(), false);
                if (images[i].isError()) throw new IllegalStateException("Invalid player frame: " + path);
            }
            if (action.equals("move")) {
                double[] reference = headAnchor(images[0]);
                for (int i = 1; i < images.length; i++) {
                    double[] anchor = headAnchor(images[i]);
                    Canvas canvas = new Canvas(320, 256);
                    // Translation only, calibrated at load time: stabilize the hood without
                    // resizing the body or flattening the leg/cape silhouette into a box.
                    canvas.getGraphicsContext2D().drawImage(images[i],
                            Math.rint(reference[0] - anchor[0]), Math.rint(reference[1] - anchor[1]));
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setFill(Color.TRANSPARENT);
                    images[i] = canvas.snapshot(parameters, null);
                }
            }
            if (action.equals("idle")) {
                String referencePath = PlayerAnimationCatalog.ROOT + "body/" + form
                        + "/move/" + direction + "/frame_00.png";
                var referenceUrl = PlayerSprites.class.getResource(referencePath);
                if (referenceUrl == null) throw new IllegalStateException("Missing movement reference: " + referencePath);
                Image reference = new Image(referenceUrl.toExternalForm(), false);
                // Calibrate the entire idle clip once, not each animation frame: moving
                // bounds must never become a per-frame zoom or a shifting render anchor.
                int[] resting = verticalBounds(images[0]);
                int[] walking = verticalBounds(reference);
                double scale = (walking[1] - walking[0] + 1.0) / (resting[1] - resting[0] + 1.0);
                double offsetY = walking[1] + 1 - (resting[1] + 1) * scale;
                for (int i = 0; i < images.length; i++) {
                    Canvas canvas = new Canvas(320, 256);
                    canvas.getGraphicsContext2D().drawImage(images[i], 160 * (1 - scale), offsetY,
                            320 * scale, 256 * scale);
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setFill(Color.TRANSPARENT);
                    images[i] = canvas.snapshot(parameters, null);
                }
            }
            return images;
        });
        // The supplied idle keyframes drift in silhouette and pose. Keep a stable resting
        // pose instead of cycling them at 12 fps; movement and action clips still animate.
        int index = clip.frame(time);
        if (action.equals("idle")) index = 0;
        if (action.equals("move")) {
            // For locomotion the model supplies travelled cycles, not elapsed seconds.
            // Preserve the authored forward footfall order; never run a gait backwards.
            index = (int) (Math.max(0, time) * frames.length) % frames.length;
        }
        return frames[index];
    }

    static int[] verticalBounds(Image image) {
        int top = (int) image.getHeight(), bottom = -1;
        var pixels = image.getPixelReader();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((pixels.getArgb(x, y) >>> 24) > 100) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (bottom < top) throw new IllegalStateException("Player pose has no visible body");
        return new int[]{top, bottom};
    }

    private static double[] headAnchor(Image image) {
        int top = verticalBounds(image)[0];
        double sumX = 0, count = 0;
        var pixels = image.getPixelReader();
        for (int y = top + 4; y < Math.min(image.getHeight(), top + 34); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((pixels.getArgb(x, y) >>> 24) > 100) { sumX += x; count++; }
            }
        }
        return new double[]{count == 0 ? 160 : sumX / count, top};
    }

    static Image body(Player player, boolean light) {
        String action = switch (player.getAnimationState()) {
            case MOVING -> "move";
            case DASHING -> "dodge";
            case ATTACKING -> "attack";
            case SKILL -> "attack";
            case FINISHER -> "finisher_cast";
            case HIT -> "hit";
            case INVULNERABLE -> "invulnerable";
            case SHIFTING -> "transition";
            default -> "idle";
        };
        return frame(light, action, player.getFacingX(), player.getFacingY(), player.getBodyAnimationTime());
    }

    private PlayerSprites() { }
}
