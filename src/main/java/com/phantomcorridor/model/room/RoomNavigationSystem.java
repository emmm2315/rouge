package com.phantomcorridor.model.room;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.dungeon.DungeonMap;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.util.CollisionUtil;

/** 处理真实房间轮廓、障碍碰撞、安全切界、门禁与节点切换。 */
public final class RoomNavigationSystem {
    private static final double EDGE_TOLERANCE = 4.0;
    private DungeonMap map;
    private Room currentRoom;
    private boolean roomChanged;

    public void reset(DungeonMap map) {
        this.map = map;
        this.currentRoom = map.entrance();
        this.currentRoom.visit();
        discoverNeighbors(currentRoom);
        this.roomChanged = false;
    }

    public void placeAtEntrance(Player player) {
        player.setPosition(centerX(currentRoom), centerY(currentRoom));
        double[] safe = findNearestSafePosition(player.getX(), player.getY(), player.getCurrentWorld());
        if (safe != null) player.setPosition(safe[0], safe[1]);
    }

    public void move(Player player, double directionX, double directionY, double dt) {
        double speed = GameConfig.PLAYER_BASE_SPEED
                * (player.getCurrentWorld() == WorldType.SHADOW ? GameConfig.SHADOW_SPEED_MULTIPLIER : 1.0);
        displace(player, directionX, directionY, dt, speed);
    }

    /**
     * 冲刺位移：与常规移动共用同一套碰撞、房间轮廓与切换门判定，只有速度换成冲刺速度。
     *
     * <p>冲刺不能走"直接改坐标"的捷径——那会让玩家穿墙、穿相位墙甚至直接穿过关着的门。
     */
    public void dash(Player player, double directionX, double directionY, double dt) {
        displace(player, directionX, directionY, dt, GameConfig.DASH_SPEED);
    }

    /**
     * 受击击退：沿指定方向把角色推开固定距离，撞墙/撞房间轮廓就地停住。
     *
     * <p>与移动共用同一套落点判定，但**不做房间切换**：被打得穿过一扇开着的门、
     * 直接飞进隔壁没清完的房间，既打断操作也不合直觉，击退只在当前房间内生效。
     */
    public void knockback(Player player, double directionX, double directionY, double distance) {
        double length = Math.hypot(directionX, directionY);
        if (length == 0.0 || distance <= 0.0) return;
        double dx = directionX / length * distance;
        double dy = directionY / length * distance;
        double nextX = player.getX() + dx;
        if (canOccupy(nextX, player.getY(), GameConfig.PLAYER_RADIUS, player.getCurrentWorld())) {
            player.setPosition(nextX, player.getY());
        }
        double nextY = player.getY() + dy;
        if (canOccupy(player.getX(), nextY, GameConfig.PLAYER_RADIUS, player.getCurrentWorld())) {
            player.setPosition(player.getX(), nextY);
        }
    }

    private void displace(Player player, double directionX, double directionY, double dt, double speed) {
        roomChanged = false;
        double length = Math.hypot(directionX, directionY);
        if (length == 0.0) return;
        double dx = directionX / length * speed * dt;
        double dy = directionY / length * speed * dt;
        double nextX = player.getX() + dx;
        if (canOccupy(nextX, player.getY(), GameConfig.PLAYER_RADIUS, player.getCurrentWorld())) {
            player.setPosition(nextX, player.getY());
        }
        double nextY = player.getY() + dy;
        if (canOccupy(player.getX(), nextY, GameConfig.PLAYER_RADIUS, player.getCurrentWorld())) {
            player.setPosition(player.getX(), nextY);
        }
        attemptTransition(player, directionX, directionY);
    }

    public boolean canOccupy(double x, double y, double radius, WorldType world) {
        if (!insideRoomShape(x, y, radius)) return false;
        for (Wall wall : currentRoom.walls()) {
            if (wall.activeIn(world) && CollisionUtil.circleIntersectsRect(
                    x, y, radius, wall.x(), wall.y(), wall.width(), wall.height())) return false;
        }
        return true;
    }

    public boolean canProjectileOccupy(double x, double y, double radius, WorldType world) {
        return canOccupy(x, y, radius, world);
    }

    /** 对高速弹体分段检测，避免一帧跨过薄墙。 */
    public boolean isSegmentClear(double x1, double y1, double x2, double y2,
                                  double radius, WorldType world) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        int steps = Math.max(1, (int) Math.ceil(distance / Math.max(4.0, radius)));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            if (!canOccupy(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, radius, world)) return false;
        }
        return true;
    }

    /**
     * 从起点到终点的整段路径是否都能容纳给定半径（不检查终点本身）。
     *
     * <p>用于「能不能直线走过去」这类判断：终点往往是玩家自己，而玩家脚下只保证放得下玩家身位，
     * 若连终点也按敌人的半径要求，体型大的敌人贴到玩家身边反而会判定“过不去”。
     */
    public boolean isPathClearTo(double x1, double y1, double x2, double y2,
                                 double radius, WorldType world) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        int steps = (int) Math.ceil(distance / Math.max(4.0, radius));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            if (!canOccupy(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, radius, world)) return false;
        }
        return true;
    }

    /** 切界前寻找目标世界最近的合法落点；找不到时由调用方拒绝切换。 */
    public double[] findNearestSafePosition(double originX, double originY, WorldType targetWorld) {
        return findNearestSafePosition(originX, originY, targetWorld, GameConfig.PLAYER_RADIUS);
    }

    /**
     * 指定身位的最近合法落点。
     *
     * <p>敌人脱困时按自身半径找一个站得下的位置；首领半径 46，比玩家大得多，
     * 所以不能共用玩家半径的那套判定。
     */
    public double[] findNearestSafePosition(double originX, double originY, WorldType targetWorld, double radius) {
        if (canOccupy(originX, originY, radius, targetWorld)) {
            return new double[]{originX, originY};
        }
        for (double ring = 8; ring <= RoomConfig.SHIFT_ESCAPE_SEARCH_RADIUS; ring += 8) {
            int samples = Math.max(12, (int) (Math.PI * ring / 8));
            for (int i = 0; i < samples; i++) {
                double angle = Math.PI * 2 * i / samples;
                double x = originX + Math.cos(angle) * ring;
                double y = originY + Math.sin(angle) * ring;
                if (canOccupy(x, y, radius, targetWorld)) return new double[]{x, y};
            }
        }
        return null;
    }

    private boolean insideRoomShape(double x, double y, double radius) {
        double diagonal = radius * 0.7071;
        double[][] samples = {{0, 0}, {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius},
                {diagonal, diagonal}, {-diagonal, diagonal}, {diagonal, -diagonal}, {-diagonal, -diagonal}};
        for (double[] sample : samples) {
            boolean contained = currentRoom.areas().stream()
                    .anyMatch(area -> area.contains(x + sample[0], y + sample[1]));
            if (!contained) return false;
        }
        return true;
    }

    private void attemptTransition(Player player, double dx, double dy) {
        double r = GameConfig.PLAYER_RADIUS;
        if (dy < 0 && player.getY() <= currentRoom.minY() + r + EDGE_TOLERANCE
                && nearDoor(player.getX(), Direction.NORTH)) transition(player, Direction.NORTH);
        else if (dy > 0 && player.getY() >= currentRoom.maxY() - r - EDGE_TOLERANCE
                && nearDoor(player.getX(), Direction.SOUTH)) transition(player, Direction.SOUTH);
        else if (dx < 0 && player.getX() <= currentRoom.minX() + r + EDGE_TOLERANCE
                && nearDoor(player.getY(), Direction.WEST)) transition(player, Direction.WEST);
        else if (dx > 0 && player.getX() >= currentRoom.maxX() - r - EDGE_TOLERANCE
                && nearDoor(player.getY(), Direction.EAST)) transition(player, Direction.EAST);
    }

    private boolean nearDoor(double coordinate, Direction direction) {
        return Math.abs(coordinate - currentRoom.doorCenter(direction)) <= RoomConfig.DOOR_HALF_WIDTH;
    }

    private void transition(Player player, Direction direction) {
        if (!currentRoom.isDoorOpen(direction)) return;
        currentRoom = map.room(currentRoom.neighbor(direction));
        currentRoom.visit();
        discoverNeighbors(currentRoom);
        roomChanged = true;
        double inset = GameConfig.PLAYER_RADIUS + 12.0;
        switch (direction) {
            case NORTH -> player.setPosition(currentRoom.doorCenter(Direction.SOUTH), currentRoom.maxY() - inset);
            case SOUTH -> player.setPosition(currentRoom.doorCenter(Direction.NORTH), currentRoom.minY() + inset);
            case WEST -> player.setPosition(currentRoom.maxX() - inset, currentRoom.doorCenter(Direction.EAST));
            case EAST -> player.setPosition(currentRoom.minX() + inset, currentRoom.doorCenter(Direction.WEST));
        }
        double[] safe = findNearestSafePosition(player.getX(), player.getY(), player.getCurrentWorld());
        if (safe != null) player.setPosition(safe[0], safe[1]);
    }

    private void discoverNeighbors(Room room) {
        room.discover();
        for (int roomId : room.neighbors().values()) map.room(roomId).discover();
    }

    private static double centerX(Room room) { return (room.minX() + room.maxX()) / 2.0; }
    private static double centerY(Room room) { return (room.minY() + room.maxY()) / 2.0; }

    public boolean consumeRoomChanged() {
        boolean changed = roomChanged;
        roomChanged = false;
        return changed;
    }

    public DungeonMap getMap() { return map; }
    public Room getCurrentRoom() { return currentRoom; }
}
