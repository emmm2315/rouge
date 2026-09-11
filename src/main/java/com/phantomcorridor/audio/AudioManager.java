package com.phantomcorridor.audio;

import com.phantomcorridor.config.Settings;
import com.phantomcorridor.model.GameSession;
import com.phantomcorridor.model.RoomType;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.net.URL;
import java.util.EnumSet;

/**
 * 游戏背景音乐调度器。
 *
 * <p>音乐按场景而不是按帧重新创建：主菜单使用 {@code menu.flac}，普通探索使用
 * {@code exploration.flac}，未清空的首领房使用 {@code boss.flac}。切换时短暂淡入淡出，
 * 避免房间切换或从菜单开始游戏时产生突兀的断音。
 */
public final class AudioManager {
    private enum Track {
        MENU("/com/phantomcorridor/audio/menu.flac"),
        EXPLORATION("/com/phantomcorridor/audio/exploration.flac"),
        BOSS("/com/phantomcorridor/audio/boss.flac");

        private final String resource;

        Track(String resource) { this.resource = resource; }
    }

    private static final Duration FADE_OUT = Duration.millis(360);
    private static final Duration FADE_IN = Duration.millis(620);

    private final Settings settings;
    private final EnumSet<Track> unavailable = EnumSet.noneOf(Track.class);
    private MediaPlayer player;
    private Track activeTrack;
    private Timeline transition;

    public AudioManager(Settings settings) {
        this.settings = settings;
    }

    /** 登录与主菜单共用主界面 BGM。 */
    public void playMenu() { play(Track.MENU); }

    /** 开始新一局或离开首领房后恢复探索 BGM。 */
    public void playExploration() { play(Track.EXPLORATION); }

    /** 每次逻辑更新后同步一次即可；同一首曲目时只更新音量，不会重复播放。 */
    public void syncGameMusic(GameSession session) {
        if (session == null || session.getPlayer().getHp() <= 0 || session.isRunCleared()) return;
        boolean bossFight = session.getNavigation().getCurrentRoom().type() == RoomType.BOSS
                && !session.getNavigation().getCurrentRoom().isCleared();
        play(bossFight ? Track.BOSS : Track.EXPLORATION);
    }

    /** 音乐滑条修改后可立即调用；播放中也会更新。 */
    public void refreshVolume() {
        if (player != null) player.setVolume(settings.getMusicVolume());
    }

    /** 应用退出时释放原生媒体资源。 */
    public void dispose() {
        if (transition != null) transition.stop();
        if (player != null) {
            player.stop();
            player.dispose();
        }
        player = null;
        activeTrack = null;
    }

    private void play(Track track) {
        if (track == activeTrack && player != null) {
            refreshVolume();
            return;
        }
        if (unavailable.contains(track)) return;
        MediaPlayer next = createPlayer(track);
        if (next == null) return;
        MediaPlayer previous = player;
        if (transition != null) transition.stop();
        player = next;
        activeTrack = track;
        double targetVolume = settings.getMusicVolume();
        next.setVolume(0.0);
        next.setCycleCount(MediaPlayer.INDEFINITE);
        next.play();

        if (previous == null) {
            transition = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(next.volumeProperty(), 0.0)),
                    new KeyFrame(FADE_IN, new KeyValue(next.volumeProperty(), targetVolume)));
        } else {
            transition = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(previous.volumeProperty(), previous.getVolume()),
                            new KeyValue(next.volumeProperty(), 0.0)),
                    new KeyFrame(FADE_OUT, event -> {
                        previous.stop();
                        previous.dispose();
                    }, new KeyValue(previous.volumeProperty(), 0.0)),
                    new KeyFrame(FADE_IN, new KeyValue(next.volumeProperty(), targetVolume)));
        }
        transition.play();
    }

    private MediaPlayer createPlayer(Track track) {
        URL resource = AudioManager.class.getResource(track.resource);
        if (resource == null) {
            unavailable.add(track);
            return null;
        }
        try {
            MediaPlayer result = new MediaPlayer(new Media(resource.toExternalForm()));
            // 编解码器或文件异常不能拖垮游戏循环；禁用该曲目后仍可继续运行其他系统。
            result.setOnError(() -> unavailable.add(track));
            return result;
        } catch (RuntimeException exception) {
            unavailable.add(track);
            return null;
        }
    }
}
