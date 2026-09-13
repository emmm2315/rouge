package com.phantomcorridor.model.room;

import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 按房间类型生成尺寸、轮廓和障碍物；不依赖渲染层。 */
public record RoomLayout(RoomShape shape, List<RoomArea> areas, List<Wall> walls) {
    private static final double CENTER_X = 640;
    private static final double CENTER_Y = 480;

    /** 普通房间：障碍物离房间边缘的距离与障碍物之间的最小缝隙（像素）。 */
    private static final double OBSTACLE_CLEARANCE = 96;
    private static final double OBSTACLE_GAP = 96;

    /**
     * 首领房专用：障碍物离边缘更远、彼此间隔更宽。
     *
     * <p>首领身位直径 92，外圈通道必须宽到寻路网格采得到（40 像素格），
     * 否则它的距离场会把外圈判成不可达，玩家就能把它顶在墙角。
     */
    private static final double BOSS_CLEARANCE = 144;
    private static final double BOSS_GAP = 112;

    public RoomLayout {
        areas = List.copyOf(areas);
        walls = List.copyOf(walls);
    }

    public static RoomLayout generate(RoomType type, long seed) {
        Random random = new Random(seed ^ ((long) type.ordinal() << 40));
        if (type == RoomType.ENTRANCE) {
            return new RoomLayout(RoomShape.SQUARE,
                    List.of(centered(500, 500)), List.of());
        }

        RoomShape shape = chooseShape(type, random);
        List<RoomArea> areas = createAreas(type, shape, random);
        int obstacleCount = switch (type) {
            case REWARD, SHOP -> 0;
            case EVENT -> 3 + random.nextInt(3);
            case BOSS -> 3 + random.nextInt(3);
            default -> 6 + random.nextInt(4);
        };
        // 首领身位半径 46（直径 92），障碍物必须让出更宽的通道：
        // 离房间边缘 96 像素时，外侧只剩 4 像素的余量，寻路用的 40 像素网格根本采不到那一圈，
        // 首领的距离场就会把外圈判成不可达——于是它正好被玩家顶在墙角磨死。
        double clearance = type == RoomType.BOSS ? BOSS_CLEARANCE : OBSTACLE_CLEARANCE;
        double gap = type == RoomType.BOSS ? BOSS_GAP : OBSTACLE_GAP;
        List<Wall> walls = createObstacles(areas, obstacleCount, random, clearance, gap);
        // 密集布局必须通过真实导航网格验收；回退末尾障碍，避免窄角形成不可达袋区。
        while (!walls.isEmpty() && !connected(type, shape, areas, walls)) walls.removeLast();
        return new RoomLayout(shape, areas, walls);
    }

    private static boolean connected(RoomType type, RoomShape shape, List<RoomArea> areas, List<Wall> walls) {
        Room room = new Room(0, type, 0, 0, shape, areas, walls);
        RoomNavigationSystem navigation = new RoomNavigationSystem();
        navigation.reset(new com.phantomcorridor.model.dungeon.DungeonMap(List.of(room)));
        for (WorldType world : WorldType.values()) {
            for (double radius : new double[]{com.phantomcorridor.config.GameConfig.PLAYER_RADIUS,
                    type == RoomType.BOSS ? 46 : 34}) {
                RoomFlowField field = new RoomFlowField(room);
                field.rebuild(navigation, CENTER_X, CENTER_Y, world, radius);
                for (int col = 0; col < field.columns(); col++) {
                    for (int row = 0; row < field.rows(); row++) {
                        double x = field.centerOfColumn(col), y = field.centerOfRow(row);
                        if (navigation.canOccupy(x, y, radius, world) && !field.isReachable(x, y)) return false;
                    }
                }
            }
        }
        return true;
    }

    private static RoomShape chooseShape(RoomType type, Random random) {
        if (type == RoomType.BOSS) return random.nextBoolean() ? RoomShape.CROSS : RoomShape.RECTANGLE;
        if (type == RoomType.SHOP) return RoomShape.RECTANGLE;
        RoomShape[] options = {RoomShape.SQUARE, RoomShape.RECTANGLE, RoomShape.L_SHAPE, RoomShape.CROSS};
        return options[random.nextInt(options.length)];
    }

    private static List<RoomArea> createAreas(RoomType type, RoomShape shape, Random random) {
        double baseW = type == RoomType.BOSS ? 1030 : 850 + random.nextInt(190);
        double baseH = type == RoomType.BOSS ? 730 : 660 + random.nextInt(130);
        if (type == RoomType.REWARD) { baseW = 580; baseH = 470; }
        if (type == RoomType.SHOP) { baseW = 780; baseH = 520; }
        return switch (shape) {
            case SQUARE -> List.of(centered(Math.min(baseW, baseH), Math.min(baseW, baseH)));
            case RECTANGLE -> List.of(centered(baseW, baseH));
            case L_SHAPE -> {
                RoomArea main = centered(baseW, baseH);
                double cutW = baseW * (0.34 + random.nextDouble() * 0.12);
                double cutH = baseH * (0.34 + random.nextDouble() * 0.12);
                if (random.nextBoolean()) {
                    yield List.of(new RoomArea(main.x(), main.y(), baseW - cutW, baseH),
                            new RoomArea(main.x() + baseW - cutW, main.y() + baseH - cutH, cutW, cutH));
                }
                yield List.of(new RoomArea(main.x() + cutW, main.y(), baseW - cutW, baseH),
                        new RoomArea(main.x(), main.y() + baseH - cutH, cutW, cutH));
            }
            case CROSS -> {
                RoomArea main = centered(baseW, baseH);
                double armH = baseH * 0.52;
                double armW = baseW * 0.52;
                yield List.of(new RoomArea(main.x(), CENTER_Y - armH / 2, baseW, armH),
                        new RoomArea(CENTER_X - armW / 2, main.y(), armW, baseH));
            }
        };
    }

    private static List<Wall> createObstacles(List<RoomArea> areas, int count, Random random,
                                              double clearance, double gap) {
        List<Wall> walls = new ArrayList<>();
        int attempts = 0;
        int groups = 0;
        while (groups < count && attempts++ < count * 100) {
            RoomArea primary = areas.get(random.nextInt(areas.size()));
            int pattern = random.nextInt(4);
            boolean pillar = pattern == 0;
            double width = pillar ? 40 + random.nextInt(25) : 88 + random.nextInt(49);
            double height = pattern >= 2 ? 96 : pillar ? 40 + random.nextInt(25) : 32;
            if (!pillar && random.nextBoolean()) {
                double swap = width; width = height; height = swap;
            }
            double x = primary.x() + clearance
                    + random.nextDouble() * Math.max(1, primary.width() - width - clearance * 2);
            double y = primary.y() + clearance
                    + random.nextDouble() * Math.max(1, primary.height() - height - clearance * 2);
            x = snap(x); y = snap(y); width = snap(width); height = snap(height);
            double cx = x + width / 2, cy = y + height / 2;
            // 门口保留完整通道，墙边保留角色半径+缓冲，避免生成不可进入的窄缝。
            boolean nearDoor = (Math.abs(cx - CENTER_X) < 96 && (Math.abs(cy - primary.y()) < 128
                    || Math.abs(cy - (primary.y() + primary.height())) < 128))
                    || (Math.abs(cy - CENTER_Y) < 96 && (Math.abs(cx - primary.x()) < 128
                    || Math.abs(cx - (primary.x() + primary.width())) < 128));
            if (nearDoor || x < primary.x() + clearance || y < primary.y() + clearance
                    || x + width > primary.x() + primary.width() - clearance
                    || y + height > primary.y() + primary.height() - clearance) continue;
            final double candidateX = x, candidateY = y;
            final double candidateWidth = width, candidateHeight = height;
            // gap 决定两块障碍之间留多宽的缝：普通房留 96 像素供玩家与精英绕行，
            // 首领房要留得下首领的直径，否则它会卡在两块石头中间。
            boolean overlaps = walls.stream().anyMatch(w ->
                    candidateX < w.x() + w.width() + gap && candidateX + candidateWidth + gap > w.x()
                            && candidateY < w.y() + w.height() + gap && candidateY + candidateHeight + gap > w.y());
            if (overlaps) continue;
            WorldType world = switch (random.nextInt(5)) {
                case 0 -> WorldType.LIGHT;
                case 1 -> WorldType.SHADOW;
                default -> null;
            };
            // 方柱、长掩体、L 形转角与 T 形掩体；组间预留完整绕行通路。
            if (pattern < 2) {
                walls.add(new Wall(x, y, width, height, world));
            } else {
                walls.add(new Wall(x, y, width, 32, world));
                double stemX = pattern == 2 ? x : snap(x + (width - 32) / 2);
                walls.add(new Wall(stemX, y + 32, 32, height - 32, world));
            }
            groups++;
        }
        return walls;
    }

    private static RoomArea centered(double width, double height) {
        return new RoomArea(snap(CENTER_X - width / 2), snap(CENTER_Y - height / 2),
                snap(width), snap(height));
    }

    private static double snap(double value) { return Math.round(value / 8.0) * 8.0; }
}
