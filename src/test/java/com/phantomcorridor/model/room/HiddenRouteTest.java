package com.phantomcorridor.model.room;

import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.dungeon.MapGenerator;
import com.phantomcorridor.model.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HiddenRouteTest {
    @Test
    void generatedHiddenRouteIsStableAndDoesNotReplaceBoss() {
        DungeonMap first = new MapGenerator().generate(4281L);
        DungeonMap second = new MapGenerator().generate(4281L);
        Room hidden = first.rooms().stream().filter(Room::isHiddenRoute).findFirst().orElseThrow();
        Room source = first.rooms().stream().filter(Room::hasHiddenExit).findFirst().orElseThrow();
        assertEquals(RoomType.BOSS, first.rooms().getLast().type());
        assertEquals(WorldType.SHADOW, hidden.requiredEntryForm());
        assertEquals(hidden.id(), source.hiddenExitTargetId());
        assertEquals(first.rooms().stream().map(Room::type).toList(),
                second.rooms().stream().map(Room::type).toList());
    }

    @Test
    void wrongFormDoesNotEnterAndCorrectFormCrossesTheHiddenDoor() {
        Room source = openRoom(0, RoomType.ENTRANCE);
        Room hidden = openRoom(1, RoomType.HIDDEN);
        source.connect(Direction.EAST, hidden.id());
        hidden.connect(Direction.WEST, source.id());
        source.setHiddenExit(Direction.EAST, hidden.id());
        hidden.configureHiddenRoute(WorldType.SHADOW, Direction.WEST);

        RoomNavigationSystem navigation = new RoomNavigationSystem();
        navigation.reset(new DungeonMap(List.of(source, hidden)));
        Player player = new Player(640, 480);
        navigation.placeAtEntrance(player);

        for (int i = 0; i < 180; i++) navigation.move(player, 1, 0, 1.0 / 60);
        assertSame(source, navigation.getCurrentRoom(), "错误形态只能被门拒绝，不能锁死或传送");

        player.toggleWorld();
        for (int i = 0; i < 180; i++) navigation.move(player, 1, 0, 1.0 / 60);
        assertSame(hidden, navigation.getCurrentRoom(), "正确形态必须在真实跨门时触发");
    }

    @Test
    void hiddenRewardIsRolledOnceAndPersistsWhenPlayerReenters() {
        Room hidden = openRoom(1, RoomType.HIDDEN);
        RoomContentSystem content = new RoomContentSystem();
        Player player = new Player(640, 480);

        content.enterRoom(hidden, player);
        assertTrue(hidden.loot().isHiddenRewardGranted());
        assertEquals(1, hidden.loot().pickups().size());
        content.enterRoom(hidden, player);
        assertEquals(1, hidden.loot().pickups().size(), "离房重进不能刷第二份隐藏奖励");
    }

    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, id, 0, RoomShape.SQUARE,
                List.of(new RoomArea(0, 0, 1280, 960)), List.of());
    }
}
