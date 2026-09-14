package com.phantomcorridor.model.room;

import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.dungeon.MapGenerator;
import com.phantomcorridor.model.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HiddenRouteTest {
    @Test
    void hiddenDoorStaysClosedUntilBattleRoomIsCleared() {
        Room battle = openRoom(0, RoomType.BATTLE);
        Room hidden = openRoom(1, RoomType.HIDDEN);
        battle.connect(Direction.EAST, hidden.id());
        hidden.connect(Direction.WEST, battle.id());
        battle.setHiddenExit(Direction.EAST, hidden.id());
        hidden.configureHiddenRoute(WorldType.LIGHT, Direction.WEST);
        RoomNavigationSystem navigation = new RoomNavigationSystem();
        Player player = new Player(100, 100);
        navigation.reset(new DungeonMap(List.of(battle, hidden)));
        navigation.placeAtEntrance(player);

        moveToDoor(navigation, player, Direction.EAST);
        assertSame(battle, navigation.getCurrentRoom(), "战斗未清空时不能走入隐藏房");

        battle.setCleared(true);
        moveToDoor(navigation, player, Direction.EAST);
        assertSame(hidden, navigation.getCurrentRoom());
    }
    @Test
    void generatedHiddenRouteIsStableAndDoesNotReplaceBoss() {
        DungeonMap first = new MapGenerator().generate(4281L);
        DungeonMap second = new MapGenerator().generate(4281L);
        Room hidden = hiddenRoomOf(first);
        Room source = first.rooms().stream().filter(Room::hasHiddenExit).findFirst().orElseThrow();
        assertEquals(RoomType.BOSS, first.rooms().getLast().type());
        assertNotNull(hidden.requiredEntryForm(), "隐藏路线必须指定一个进入形态");
        assertEquals(hidden.requiredEntryForm(), hiddenRoomOf(second).requiredEntryForm(),
                "同一个种子必须得到同一种进入形态");
        assertEquals(hidden.id(), source.hiddenExitTargetId());
        assertEquals(first.rooms().stream().map(Room::type).toList(),
                second.rooms().stream().map(Room::type).toList());
    }

    @Test
    void hiddenRoutesRequireBothFormsAcrossSeeds() {
        Set<WorldType> forms = EnumSet.noneOf(WorldType.class);
        for (long seed = 1; seed <= 60; seed++) {
            DungeonMap map = new MapGenerator().generate(seed);
            map.rooms().stream().filter(Room::isHiddenRoute)
                    .forEach(room -> forms.add(room.requiredEntryForm()));
        }
        assertEquals(EnumSet.allOf(WorldType.class), forms,
                "隐藏路线不该永远只认影形态：光与影都要随种子出现");
    }

    @Test
    void wrongFormDoesNotEnterAndCorrectFormCrossesTheHiddenDoor() {
        // 两种形态各跑一遍：要求光时另一种是影，要求影时另一种是光。
        for (WorldType required : WorldType.values()) {
            Room source = openRoom(0, RoomType.ENTRANCE);
            Room hidden = openRoom(1, RoomType.HIDDEN);
            source.connect(Direction.EAST, hidden.id());
            hidden.connect(Direction.WEST, source.id());
            source.setHiddenExit(Direction.EAST, hidden.id());
            hidden.configureHiddenRoute(required, Direction.WEST);

            RoomNavigationSystem navigation = new RoomNavigationSystem();
            navigation.reset(new DungeonMap(List.of(source, hidden)));
            Player player = new Player(640, 480);
            navigation.placeAtEntrance(player);

            // 先切到"不对的那个形态"再撞门。
            if (player.getCurrentWorld() == required) player.toggleWorld();
            for (int i = 0; i < 180; i++) navigation.move(player, 1, 0, 1.0 / 60);
            assertSame(source, navigation.getCurrentRoom(),
                    "需要 " + required + " 时，另一种形态只能被门拒绝，不能锁死或传送");

            player.toggleWorld();
            for (int i = 0; i < 180; i++) navigation.move(player, 1, 0, 1.0 / 60);
            assertSame(hidden, navigation.getCurrentRoom(),
                    "需要 " + required + " 时，切到该形态必须在真实跨门时进去");
        }
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

    /** 一张图里唯一的隐藏路线房。 */
    private static Room hiddenRoomOf(DungeonMap map) {
        return map.rooms().stream().filter(Room::isHiddenRoute).findFirst().orElseThrow();
    }

    private static Room openRoom(int id, RoomType type) {
        return new Room(id, type, id, 0, RoomShape.SQUARE,
                List.of(new RoomArea(0, 0, 1280, 960)), List.of());
    }
    private static void moveToDoor(RoomNavigationSystem navigation, Player player, Direction direction) {
        for (int i = 0; i < 180; i++) navigation.move(player, direction.dx(), direction.dy(), 1.0 / 60);
    }
}
