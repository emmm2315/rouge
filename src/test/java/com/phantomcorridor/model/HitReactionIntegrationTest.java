package com.phantomcorridor.model;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.room.Direction;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.RoomShape;
import com.phantomcorridor.model.room.Wall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 受击击退接进一局游戏后的行为：按配置距离推开、撞墙停住、不把人推过门。 */
class HitReactionIntegrationTest {

    @Test
    void knockbackPushesThePlayerAwayByTheConfiguredDistance() {
        GameSession session = sessionIn(openRoom(List.of()));
        Player player = session.getPlayer();
        player.setPosition(500, 480);

        // 攻击来自左侧：应当被推向右边，且正好是配置里的击退距离。
        assertTrue(player.takeDamage(1, 460, 480));
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);

        assertEquals(GameConfig.PLAYER_HIT_KNOCKBACK, player.getX() - 500, 1e-9);
        assertEquals(480, player.getY());

        // 击退只在受击那一帧结算：站着不动不会继续被推走。
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);
        assertEquals(GameConfig.PLAYER_HIT_KNOCKBACK, player.getX() - 500, 1e-9);
    }

    @Test
    void knockbackStopsAtWalls() {
        // 墙紧贴玩家右侧：10 像素的击退会把角色顶进墙里，必须被判定拦下。
        Room room = openRoom(List.of(new Wall(515, 0, 60, AppConfig.VIEW_HEIGHT, WorldType.LIGHT)));
        GameSession session = sessionIn(room);
        Player player = session.getPlayer();
        player.setPosition(480, 480);

        assertTrue(player.takeDamage(1, 440, 480));
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);

        assertEquals(480, player.getX(), "撞墙时击退就地停住，不能把人塞进墙里");
        assertTrue(player.getX() + GameConfig.PLAYER_RADIUS <= 515.0);
    }

    @Test
    void knockbackNeverMovesThePlayerThroughADoor() {
        Room entrance = new Room(0, RoomType.ENTRANCE, 0, 0);
        Room reward = new Room(1, RoomType.REWARD, 0, -1);
        entrance.connect(Direction.NORTH, 1);
        reward.connect(Direction.SOUTH, 0);
        GameSession session = new GameSession();
        session.newRun("2024");
        session.getNavigation().reset(new DungeonMap(List.of(entrance, reward)));
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);
        Player player = session.getPlayer();

        // 站到北门口的极限位置，再被从下方打向北方：门是开的，但击退不该把人送进下一间房。
        player.setPosition(entrance.doorCenter(Direction.NORTH), entrance.minY() + GameConfig.PLAYER_RADIUS);
        assertTrue(player.takeDamage(1, player.getX(), player.getY() + 40));
        double yBefore = player.getY();
        session.update(AppConfig.FIXED_DT, 0, 0, 0, 0, false);

        assertTrue(player.getY() <= yBefore, "击退方向应当朝门口那侧");
        assertEquals(entrance.id(), session.getNavigation().getCurrentRoom().id(),
                "击退不能把玩家推进隔壁房间");
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
