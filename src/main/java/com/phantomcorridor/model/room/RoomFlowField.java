package com.phantomcorridor.model.room;

import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.WorldType;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 房间内的粗粒度距离场：以玩家所在格为起点做一次广度优先遍历，记录每格到玩家的步数。
 *
 * <p>敌人被墙挡住时朝“步数更小的相邻格”走，就能真正绕开墙体、窄缝和死角。
 * 只靠“朝玩家方向 + 沿切线滑动”的局部规则会在长墙和过不去的缝前原地打转，
 * 明明已经索敌成功却永远走不到玩家身边。
 *
 * <p>只在玩家换格、换房间或换界时重建；一格 {@link RoomConfig#NAV_CELL_SIZE} 像素时，
 * 整间房也只有几百个格子，重建代价可以忽略。
 */
public final class RoomFlowField {
    private static final int UNREACHABLE = Integer.MAX_VALUE;

    /** 取方向时沿梯度前瞻的格数：看得远一点，跨格边界时才不会来回翻转。 */
    private static final int LOOK_AHEAD_CELLS = 2;

    private final int roomId;
    private final double originX;
    private final double originY;
    private final int columns;
    private final int rows;
    private final int[] steps;
    private int targetColumn = -1;
    private int targetRow = -1;

    public RoomFlowField(Room room) {
        double cell = RoomConfig.NAV_CELL_SIZE;
        this.roomId = room.id();
        this.originX = room.minX() - cell / 2.0;
        this.originY = room.minY() - cell / 2.0;
        this.columns = (int) Math.ceil((room.maxX() - room.minX()) / cell) + 2;
        this.rows = (int) Math.ceil((room.maxY() - room.minY()) / cell) + 2;
        this.steps = new int[columns * rows];
    }

    public int roomId() { return roomId; }

    public int columns() { return columns; }

    public int rows() { return rows; }

    /** 第 column 列格心的 X 坐标（调试与寻路诊断用）。 */
    public double centerOfColumn(int column) { return centerX(column); }

    /** 第 row 行格心的 Y 坐标（调试与寻路诊断用）。 */
    public double centerOfRow(int row) { return centerY(row); }

    /** 该点所在格是否与玩家连通（不可达说明这一块和玩家之间没有可走的路）。 */
    public boolean isReachable(double x, double y) {
        int column = Math.min(columns - 1, Math.max(0, columnOf(x)));
        int row = Math.min(rows - 1, Math.max(0, rowOf(y)));
        return steps[index(column, row)] != UNREACHABLE;
    }

    /** 距离场是否已经以给定坐标所在格为目标；为 false 时需要调用 {@link #rebuild}。 */
    public boolean isTargeting(double x, double y) {
        return targetColumn == columnOf(x) && targetRow == rowOf(y);
    }

    /** 以 (targetX, targetY) 为起点重建距离场；不可达的格子记为 {@link #UNREACHABLE}。 */
    public void rebuild(RoomNavigationSystem navigation, double targetX, double targetY,
                        WorldType world, double radius) {
        this.targetColumn = columnOf(targetX);
        this.targetRow = rowOf(targetY);
        Arrays.fill(steps, UNREACHABLE);
        int start = nearestWalkableCell(navigation, world, radius, targetColumn, targetRow);
        if (start < 0) return;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        steps[start] = 0;
        queue.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            int column = cell % columns;
            int row = cell / columns;
            int next = steps[cell] + 1;
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    if (offsetX == 0 && offsetY == 0) continue;
                    int neighbourColumn = column + offsetX;
                    int neighbourRow = row + offsetY;
                    if (!inside(neighbourColumn, neighbourRow)) continue;
                    int neighbour = index(neighbourColumn, neighbourRow);
                    if (steps[neighbour] != UNREACHABLE) continue;
                    if (!navigation.canOccupy(centerX(neighbourColumn), centerY(neighbourRow), radius, world)) continue;
                    steps[neighbour] = next;
                    queue.add(neighbour);
                }
            }
        }
    }

    /**
     * 返回从 (x, y) 朝玩家推进的单位方向；已经在玩家所在格、或所在区域与玩家不连通时返回 null。
     *
     * <p>方向指向沿梯度前进 {@link #LOOK_AHEAD_CELLS} 格之后的格子中心，而不是紧紧相邻的那一格：
     * 只盯相邻格时，敌人一跨过格子边界方向就可能翻转 180°，表现为原地来回抖动走不出障碍区。
     */
    public double[] directionFrom(double x, double y) {
        return directionTo(x, y, LOOK_AHEAD_CELLS);
    }

    /** 只看相邻一格的推进方向；远处目标被挡住时作为兜底。 */
    public double[] neighbourDirectionFrom(double x, double y) {
        return directionTo(x, y, 1);
    }

    private double[] directionTo(double x, double y, int cellsAhead) {
        int column = Math.min(columns - 1, Math.max(0, columnOf(x)));
        int row = Math.min(rows - 1, Math.max(0, rowOf(y)));
        int walkColumn = column;
        int walkRow = row;
        for (int ahead = 0; ahead < cellsAhead; ahead++) {
            int[] next = bestNeighbour(walkColumn, walkRow);
            if (next == null) break;
            walkColumn = next[0];
            walkRow = next[1];
        }
        if (walkColumn == column && walkRow == row) return null;
        double dx = centerX(walkColumn) - x;
        double dy = centerY(walkRow) - y;
        double length = Math.hypot(dx, dy);
        if (length < 0.001) return null;
        return new double[]{dx / length, dy / length};
    }

    /**
     * 找出步数严格更少的邻格，返回其格子坐标；没有则返回 null。
     *
     * <p>先挑不贴墙角斜穿的邻格，找不到时再放宽到任意更近的邻格：
     * 绕行路线常常正好要贴着墙角斜着走，如果直接放弃，敌人就只能在原地打转。
     * 放宽是安全的——真正的落点与步进仍由 {@link RoomNavigationSystem#canOccupy} 与
     * {@link RoomNavigationSystem#isSegmentClear} 按敌人身位校验。
     */
    private int[] bestNeighbour(int column, int row) {
        int current = steps[index(column, row)];
        int bestSteps = current;
        int bestColumn = -1;
        int bestRow = -1;
        for (int pass = 0; pass < 2 && bestColumn < 0; pass++) {
            boolean allowCornerCut = pass == 1;
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    if (offsetX == 0 && offsetY == 0) continue;
                    int neighbourColumn = column + offsetX;
                    int neighbourRow = row + offsetY;
                    if (!inside(neighbourColumn, neighbourRow)) continue;
                    int value = steps[index(neighbourColumn, neighbourRow)];
                    if (value == UNREACHABLE || value >= bestSteps) continue;
                    if (!allowCornerCut && offsetX != 0 && offsetY != 0
                            && (steps[index(column + offsetX, row)] == UNREACHABLE
                            || steps[index(column, row + offsetY)] == UNREACHABLE)) continue;
                    bestSteps = value;
                    bestColumn = neighbourColumn;
                    bestRow = neighbourRow;
                }
            }
        }
        return bestColumn < 0 ? null : new int[]{bestColumn, bestRow};
    }

    /** 从目标格向外寻找最近的可行走格：玩家贴墙或站在窄缝里时，格心本身可能站不住。 */
    private int nearestWalkableCell(RoomNavigationSystem navigation, WorldType world,
                                    double radius, int targetColumn, int targetRow) {
        boolean[] seen = new boolean[columns * rows];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int target = index(targetColumn, targetRow);
        seen[target] = true;
        queue.add(target);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            int column = cell % columns;
            int row = cell / columns;
            if (navigation.canOccupy(centerX(column), centerY(row), radius, world)) return cell;
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    if (offsetX == 0 && offsetY == 0) continue;
                    int neighbourColumn = column + offsetX;
                    int neighbourRow = row + offsetY;
                    if (!inside(neighbourColumn, neighbourRow)) continue;
                    int neighbour = index(neighbourColumn, neighbourRow);
                    if (seen[neighbour]) continue;
                    seen[neighbour] = true;
                    queue.add(neighbour);
                }
            }
        }
        return -1;
    }

    /**
     * 距离场树上「更靠近玩家一格」的格心，按步数从少到多排列（最多 4 个）。
     *
     * <p>给脱困兜底用：敌人可能卡在「格心走得通、它自己的身位却迈不开步」的粗网格缝隙里
     * （格边长 {@link RoomConfig#NAV_CELL_SIZE} 像素），此时沿梯度给不出可用方向。
     * 直接把它挪到树上前驱格的格心，是唯一能保证「挪过去之后确实离玩家更近」的做法。
     *
     * @return 候选落点坐标；当前格已经是最优（或整块区域都不可达）时返回空列表
     */
    public java.util.List<double[]> progressTargets(double x, double y) {
        int column = Math.min(columns - 1, Math.max(0, columnOf(x)));
        int row = Math.min(rows - 1, Math.max(0, rowOf(y)));
        int current = steps[index(column, row)];
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                if (offsetX == 0 && offsetY == 0) continue;
                int neighbourColumn = column + offsetX;
                int neighbourRow = row + offsetY;
                if (!inside(neighbourColumn, neighbourRow)) continue;
                int value = steps[index(neighbourColumn, neighbourRow)];
                if (value == UNREACHABLE || value >= current) continue;
                cells.add(new int[]{neighbourColumn, neighbourRow, value});
            }
        }
        cells.sort(java.util.Comparator.comparingInt(cell -> cell[2]));
        java.util.List<double[]> targets = new java.util.ArrayList<>();
        for (int[] cell : cells) {
            targets.add(new double[]{centerX(cell[0]), centerY(cell[1])});
            if (targets.size() >= 4) break;
        }
        return targets;
    }

    private boolean inside(int column, int row) {
        return column >= 0 && column < columns && row >= 0 && row < rows;
    }

    private int index(int column, int row) { return row * columns + column; }

    private int columnOf(double x) { return (int) Math.floor((x - originX) / RoomConfig.NAV_CELL_SIZE); }

    private int rowOf(double y) { return (int) Math.floor((y - originY) / RoomConfig.NAV_CELL_SIZE); }

    private double centerX(int column) { return originX + (column + 0.5) * RoomConfig.NAV_CELL_SIZE; }

    private double centerY(int row) { return originY + (row + 0.5) * RoomConfig.NAV_CELL_SIZE; }
}
