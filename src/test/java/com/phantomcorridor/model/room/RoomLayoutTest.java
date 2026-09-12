package com.phantomcorridor.model.room;

import com.phantomcorridor.model.*;
import com.phantomcorridor.model.dungeon.DungeonMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomLayoutTest {
    @Test void generatedCoverKeepsBothWorldsConnectedForEliteSizedActors() {
        int wallCount = 0;
        for (int seed = 0; seed < 100; seed++) {
            Room room = new Room(0, RoomType.BATTLE, 0, 0, seed);
            assertEquals(room.walls(), new Room(0, RoomType.BATTLE, 0, 0, seed).walls());
            wallCount += room.walls().size();
            RoomNavigationSystem nav = new RoomNavigationSystem();
            nav.reset(new DungeonMap(List.of(room)));
            for (WorldType world : WorldType.values()) {
                RoomFlowField field = new RoomFlowField(room);
                field.rebuild(nav, 640, 480, world, 34);
                for (int col = 0; col < field.columns(); col++) {
                    for (int row = 0; row < field.rows(); row++) {
                        double x = field.centerOfColumn(col), y = field.centerOfRow(row);
                        if (nav.canOccupy(x, y, 34, world))
                            assertTrue(field.isReachable(x, y), "seed=" + seed + " " + world + " x=" + x + " y=" + y);
                    }
                }
            }
        }
        assertTrue(wallCount >= 400, "rooms should contain multiple cover pieces: " + wallCount);
    }
}
