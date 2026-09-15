package com.phantomcorridor.model.dungeon;

import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.room.Direction;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.util.RandomUtil;
import java.util.*;

/** 生成无环、带分支且以 Boss 收尾的可复现房间节点图。 */
public final class MapGenerator {
    private static final RoomType[] RANDOM_TYPES = {RoomType.BATTLE, RoomType.REWARD, RoomType.SHOP, RoomType.EVENT};
    private static final double[] WEIGHTS = {RoomConfig.WEIGHT_BATTLE, RoomConfig.WEIGHT_REWARD,
            RoomConfig.WEIGHT_SHOP, RoomConfig.WEIGHT_EVENT};
    /** 剔除商店后的权重：入口附近重roll 时使用，避免开局第一间就是商店。 */
    private static final RoomType[] EARLY_TYPES = {RoomType.BATTLE, RoomType.REWARD, RoomType.EVENT};
    private static final double[] EARLY_WEIGHTS = {RoomConfig.WEIGHT_BATTLE, RoomConfig.WEIGHT_REWARD,
            RoomConfig.WEIGHT_EVENT};

    public DungeonMap generate(long seed) {
        Random random = new Random(seed);
        List<Room> rooms = new ArrayList<>();
        rooms.add(new Room(0, RoomType.ENTRANCE, 0, 0));
        Map<Integer, Integer> depths = new HashMap<>();
        depths.put(0, 0);
        Set<String> occupied = new HashSet<>();
        occupied.add("0,0");
        for (int id = 1; id < RoomConfig.DEFAULT_ROOM_COUNT; id++) {
            boolean hidden = id == RoomConfig.DEFAULT_ROOM_COUNT - 2;
            Room preferred = id == RoomConfig.DEFAULT_ROOM_COUNT - 1
                    ? rooms.stream().filter(room -> room.id() > 0 && !room.isHiddenRoute()
                            && hasFreeDirection(room, occupied))
                    .findFirst().orElseThrow()
                    : id <= 5 ? rooms.get(id - 1) : rooms.get(RandomUtil.nextInt(random, 1, id - 2));
            if (hidden && preferred.isHiddenRoute()) {
                preferred = rooms.stream().filter(room -> room.id() > 0 && !room.isHiddenRoute()
                        && hasFreeDirection(room, occupied)).findFirst().orElseThrow();
            }
            Room parent = hasFreeDirection(preferred, occupied) ? preferred : rooms.stream()
                    .filter(room -> !room.isHiddenRoute() && hasFreeDirection(room, occupied))
                    .findFirst().orElseThrow();
            Direction direction = findFreeDirection(random, parent, occupied);
            int x = parent.mapX() + direction.dx();
            int y = parent.mapY() + direction.dy();
            int depth = depths.getOrDefault(parent.id(), 0) + 1;
            RoomType type = id == RoomConfig.DEFAULT_ROOM_COUNT - 1 ? RoomType.BOSS
                    : hidden ? RoomType.HIDDEN : rollType(random, depth);
            Room room = new Room(id, type, x, y, random.nextLong());
            parent.connect(direction, id);
            room.connect(direction.opposite(), parent.id());
            if (hidden) {
                parent.setHiddenExit(direction, id);
                // 隐藏路线要求哪种形态由种子决定：光与影都可能，不再写死成影。
                // 这里**不复用**上面的 random 流——多消耗一个随机数会让后面每个房间的
                // 布局与类型整体错位，已有种子的地图会被改写；所以另开一个由种子派生的流。
                boolean lightForm = new Random(seed * 0x9E3779B97F4A7C15L + id).nextBoolean();
                room.configureHiddenRoute(lightForm ? WorldType.LIGHT : WorldType.SHADOW,
                        direction.opposite());
            }
            rooms.add(room);
            depths.put(id, depth);
            occupied.add(x + "," + y);
        }
        return new DungeonMap(rooms);
    }

    /**
     * 抽取房间类型。
     *
     * <p>距离入口不足 {@link RoomConfig#SHOP_MIN_DEPTH} 的房间不生成商店：
     * 那时玩家既没金币也没得挑，商店开在出生点旁边没有意义。
     */
    private RoomType rollType(Random random, int depth) {
        if (depth >= RoomConfig.SHOP_MIN_DEPTH) {
            return RANDOM_TYPES[RandomUtil.weightedIndex(random, WEIGHTS)];
        }
        return EARLY_TYPES[RandomUtil.weightedIndex(random, EARLY_WEIGHTS)];
    }

    private boolean hasFreeDirection(Room room, Set<String> occupied) {
        return Arrays.stream(Direction.values()).anyMatch(direction ->
                !occupied.contains((room.mapX() + direction.dx()) + "," + (room.mapY() + direction.dy())));
    }

    private Direction findFreeDirection(Random random, Room parent, Set<String> occupied) {
        List<Direction> directions = new ArrayList<>(List.of(Direction.values()));
        Collections.shuffle(directions, random);
        for (Direction direction : directions) {
            String key = (parent.mapX() + direction.dx()) + "," + (parent.mapY() + direction.dy());
            if (!occupied.contains(key) && !parent.hasDoor(direction)) return direction;
        }
        throw new IllegalStateException("地图生成无法找到空闲出口");
    }

    public static long parseSeed(String configuredSeed) {
        if (configuredSeed == null || configuredSeed.isBlank()) return new Random().nextLong();
        try { return Long.parseLong(configuredSeed.trim()); }
        catch (NumberFormatException ignored) { return configuredSeed.trim().hashCode(); }
    }
}
