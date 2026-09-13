package com.phantomcorridor.model.entity;

import java.io.IOException;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;

/** Manifest-derived animation timing and fixed canvas geometry; independent of JavaFX. */
public final class PlayerAnimationCatalog {
    public static final String ROOT = "/com/phantomcorridor/sprites/player_v3/";
    private static final Properties DATA = load();
    private static final Map<String, Clip> CLIPS = clips();
    public static final double SCALE = 0.5;
    public static final double WIDTH = number("width") * SCALE;
    public static final double HEIGHT = number("height") * SCALE;
    public static final double ANCHOR_X = number("anchorX") * SCALE;
    public static final double ANCHOR_Y = number("anchorY") * SCALE;

    public record Clip(double fps, int frames, boolean loop) {
        public double duration() { return frames / fps; }
        public int frame(double seconds) {
            int index = (int) (Math.max(0, seconds) * fps);
            return loop ? index % frames : Math.min(frames - 1, index);
        }
    }

    public static Clip clip(String form, String action, String direction) {
        String key = form + "/" + action + "/" + direction;
        Clip value = CLIPS.get(key);
        if (value == null) throw new IllegalArgumentException("Missing player clip: " + key);
        return value;
    }

    private static Map<String, Clip> clips() {
        Map<String, Clip> clips = new HashMap<>();
        for (String key : DATA.stringPropertyNames()) {
            if (!key.contains("/")) continue;
            String[] fields = DATA.getProperty(key).split(",");
            clips.put(key, new Clip(Double.parseDouble(fields[0]), Integer.parseInt(fields[1]), Boolean.parseBoolean(fields[2])));
        }
        return Map.copyOf(clips);
    }

    public static String direction(double x, double y) {
        // Actual diagonal movement accumulates tiny floating-point differences in x/y.
        // Treat ties consistently so the sprite cannot alternate between side and back.
        return Math.abs(x) + 1e-6 >= Math.abs(y) ? (x < 0 ? "left" : "right") : (y < 0 ? "up" : "down");
    }

    private static double number(String key) { return Double.parseDouble(DATA.getProperty(key)); }
    private static Properties load() {
        Properties result = new Properties();
        try (var stream = PlayerAnimationCatalog.class.getResourceAsStream(ROOT + "animations.properties")) {
            if (stream == null) throw new IllegalStateException("Missing player V3 animation catalog");
            result.load(stream);
            return result;
        } catch (IOException e) { throw new IllegalStateException("Cannot load player animations", e); }
    }

    private PlayerAnimationCatalog() { }
}
