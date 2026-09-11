package com.phantomcorridor.model;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomShape;
import com.phantomcorridor.model.room.Wall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 闪避冲刺接进一局游戏后的行为：位移距离、碰撞与输入方向。 */
class DashIntegrationTest {

    @Test
    void dashCoversTheConfiguredDistance() {
        GameSession session = sessionIn(openRoom(List.of()));
        Player player = session.getPlayer();
        player.setPosition(500, 480);

        session.requestDash();
        runFrames(session, 40, 1.0, 0.0);

        assertFalse(player.isDashing(), "冲刺应当在 0.18 秒内结束");
        assertEquals(GameConfig.DASH_DISTANCE, player.getX() - 500, 0.5,
                "冲刺距离是固定值，不能随帧对齐漂移");
        assertEquals(480, player.getY());
    }

    @Test
    void dashIsBlockedBySolidWallsInsteadOfTunnellingThroughThem() {
        // 墙面在玩家右侧 120 像素处：150 像素的冲刺本可以穿过去。
        Room room = openRoom(List.of(new Wall(600, 0, 60, AppConfig.VIEW_HEIGHT, WorldType.LIGHT)));
        GameSession session = sessionIn(room);
        Player player = session.getPlayer();
        player.setPosition(480, 480);

        session.requestDash();
        runFrames(session, 40, 1.0, 0.0);

        assertTrue(player.getX() > 480, "冲刺应当真的移动了");
        assertTrue(player.getX() + GameConfig.PLAYER_RADIUS <= 600.5,
                "冲刺不能把角色送进墙里，实际 x = " + player.getX());
    }

    @Test
    void dashFollowsTheMovementInputRatherThanTheFacing() {
        GameSession session = sessionIn(openRoom(List.of()));
        Player player = session.getPlayer();
        player.setPosition(500, 480);
        player.updateAnimation(0.0, 1.0, 0.0, false, false);

        session.requestDash();
        // 面朝右、瞄准右侧，但按着左方向键冲：冲刺方向必须听输入。
        session.update(AppConfig.FIXED_DT, -1.0, 0.0, 900, 480, false);

        assertTrue(player.isDashing());
        assertEquals(-1.0, player.getDashDirectionX(), 1e-9);
        assertEquals(0.0, player.getDashDirectionY(), 1e-9);
    }

    @Test
    void dashRequestDuringCooldownDoesNotStartASecondDash() {
        GameSession session = sessionIn(openRoom(List.of()));
        Player player = session.getPlayer();
        player.setPosition(400, 480);

        session.requestDash();
        runFrames(session, 40, 1.0, 0.0);
        assertFalse(player.isDashing());

        session.requestDash();
        session.update(AppConfig.FIXED_DT, 0.0, 0.0, 0, 0, false);

        assertFalse(player.isDashing(), "内置冷却没走完就按空格不该再冲一次");
        assertTrue(player.getDashCooldownRemaining() > 0.0);
    }

    private static void runFrames(GameSession session, int frames, double movementX, double movementY) {
        for (int frame = 0; frame < frames; frame++) {
            if (!session.getPlayer().isDashing() && frame > 0) return;
            session.update(AppConfig.FIXED_DT, movementX, movementY, 0, 0, false);
        }
    }

    private static GameSession sessionIn(Room room) {
        GameSession session = new GameSession();
        session.newRun("2024");
        session.getNavigation().reset(new DungeonMap(List.of(room)));
        session.getNavigation().placeAtEntrance(session.getPlayer());
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);
        return session;
    }

    private static Room openRoom(List<Wall> walls) {
        return new Room(0, RoomType.ENTRANCE, 0, 0, RoomShape.RECTANGLE,
                List.of(new RoomArea(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT)), walls);
    }
}
