package com.phantomcorridor.view;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.config.RoomConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.GameSession;
import com.phantomcorridor.model.Pickup;
import com.phantomcorridor.model.RoomType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.entity.PlayerAnimationState;
import com.phantomcorridor.model.entity.DashTrailPoint;
import com.phantomcorridor.model.combat.Projectile;
import com.phantomcorridor.model.combat.EnemyAttack;
import com.phantomcorridor.model.combat.EnemyVisualEffect;
// 未使用（IDE 的 Unused import 会报）：唯一用到它的 drawEnemyProjectiles 已注释（见文件下方），
// 敌方弹体现在由 drawEnemyAttacks 统一绘制。恢复那个方法时把这行一起放开即可。
// import com.phantomcorridor.model.combat.EnemyProjectile;
import com.phantomcorridor.model.combat.SummonRift;
import com.phantomcorridor.model.combat.EnemySystem;
import com.phantomcorridor.model.combat.EnemyTelegraph;
import com.phantomcorridor.model.combat.RootWall;
import com.phantomcorridor.model.entity.Enemy;
import com.phantomcorridor.model.entity.EnemyKind;
import com.phantomcorridor.model.room.Direction;
import com.phantomcorridor.model.room.Room;
import com.phantomcorridor.model.room.RoomArea;
import com.phantomcorridor.model.room.Wall;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Canvas 游戏画面渲染器。只读取模型，不修改游戏状态。 */
public final class GameRenderer {
    private List<RoomArea> outlinedAreas = List.of();
    private java.awt.geom.Area roomOutline = new java.awt.geom.Area();
    private Image lightFloorTexture;
    private Image shadowFloorTexture;

    /** 固定种子的无缝石砖贴图，仅首次使用时生成，避免逐帧绘制材质细节。 */
    private Image floorTexture(boolean light) {
        Image cached = light ? lightFloorTexture : shadowFloorTexture;
        if (cached != null) return cached;
        int size = 384;
        var canvas = new javafx.scene.canvas.Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Color stone = Color.web(light ? "#36332d" : "#2c2938");
        g.setFill(Color.web(light ? "#24231f" : "#1d1b26"));
        g.fillRect(0, 0, size, size);
        for (int row = 0; row < 6; row++) {
            for (int col = -1; col < 4; col++) {
                // 周期边缘使用同一块砖的数据，跨贴图边界时色差与纹理保持连续。
                var random = new java.util.Random(7919L * row + 104729L * Math.floorMod(col, 4) + 37);
                double x = col * 96 + (row % 2) * 48 + 1;
                double y = row * 64 + 1;
                Color base = stone.deriveColor(0, 0.92, 0.91 + random.nextDouble() * 0.18, 1);
                g.setFill(new LinearGradient(0, 0, 0.3, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, base.interpolate(Color.WHITE, 0.025)),
                        new Stop(1, base.interpolate(Color.BLACK, 0.09))));
                g.fillRoundRect(x, y, 94, 62, 3, 3);
                g.setLineWidth(1);
                g.setStroke(Color.rgb(207, 199, 184, 0.055));
                g.strokeLine(x + 3, y + 1.5, x + 90, y + 1.5);
                g.strokeLine(x + 1.5, y + 3, x + 1.5, y + 58);
                g.setStroke(Color.rgb(0, 0, 0, 0.16));
                g.strokeLine(x + 3, y + 60.5, x + 91, y + 60.5);
                for (int grain = 0; grain < 65; grain++) {
                    g.setFill(grain % 3 == 0 ? Color.rgb(222, 212, 198, 0.035)
                            : Color.rgb(0, 0, 0, 0.055));
                    g.fillRect(x + 4 + random.nextInt(85), y + 4 + random.nextInt(53),
                            1 + random.nextInt(3), 1);
                }
                if (random.nextDouble() < 0.28) {
                    double crackX = x + 18 + random.nextInt(48);
                    double crackY = y + 12 + random.nextInt(26);
                    g.setStroke(Color.rgb(0, 0, 0, 0.16));
                    g.beginPath();
                    g.moveTo(crackX, crackY);
                    g.lineTo(crackX + 7, crackY + 4);
                    g.lineTo(crackX + 11, crackY + 3);
                    g.lineTo(crackX + 18, crackY + 9);
                    g.stroke();
                }
            }
        }
        Image texture = canvas.snapshot(null, null);
        if (light) lightFloorTexture = texture;
        else shadowFloorTexture = texture;
        return texture;
    }

    private void roomPath(GraphicsContext g) {
        g.beginPath();
        double[] point = new double[6];
        var iterator = roomOutline.getPathIterator(null);
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(point)) {
                case java.awt.geom.PathIterator.SEG_MOVETO -> g.moveTo(point[0], point[1]);
                case java.awt.geom.PathIterator.SEG_LINETO -> g.lineTo(point[0], point[1]);
                case java.awt.geom.PathIterator.SEG_CLOSE -> g.closePath();
                default -> throw new IllegalStateException("Room outline must be rectilinear");
            }
            iterator.next();
        }
    }

    /** 小地图：面板边长与每格房间的像素间距（间距要够大，房间之间才看得出连接关系）。 */
    // 原 280px 面板在战斗外也会过度抢占画面；缩至约 2/3，仍能保留中心跟随与裁剪效果。
    private static final double MINI_MAP_PANEL_SIZE = 187.0;
    private static final double MINI_MAP_SCALE = 27.0;

    private static final Color LIGHT_GOLD = Color.web("#e8bd68");
    private static final Color SHADOW_VIOLET = Color.web("#9b65dc");
    /** 护盾（临时生命值）的主题色：与光/影都不撞色的冷青，一眼能认出“这是盾”。 */
    private static final Color SHIELD_BLUE = Color.web("#66d9f0");

    /**
     * 左上角信息面板的版面。
     *
     * <p>整块面板原本是 470×164，占了四分之一个屏幕：玩家真正需要的只有“还剩多少血、
     * 还打几次、够不够切界”，所以整体缩到一半左右。
     *
     * <p>宽度留到 350 是为了底行的「光残敌 / 影残敌 / 金币 / 盾」在放大的字号下仍然一行放得下——
     * 字号放大之后最容易出的问题就是这里被迫换行，把面板撑高、挤到装备栏上。
     * 所有坐标都从这里取，改版面只要改这一处。
     */
    private static final double HUD_X = 20.0;
    private static final double HUD_Y = 18.0;
    private static final double HUD_WIDTH = 350.0;
    private static final double HUD_HEIGHT = 130.0;
    /** 数值列的起点与宽度：生命条、攻击格、相位条共用同一条基线，视觉上对齐。 */
    private static final double HUD_VALUE_X = 92.0;
    private static final double HUD_VALUE_WIDTH = 190.0;

    /** 装备栏版面：贴在信息面板正下方，三行竖排，名字与说明才有位置。 */
    private static final double EQUIP_PANEL_X = HUD_X;
    private static final double EQUIP_PANEL_Y = HUD_Y + HUD_HEIGHT + 8.0;
    private static final double EQUIP_PANEL_WIDTH = 350.0;
    /** 每一行装备（编号牌 + 图标 + 名字）的高度与间距。 */
    private static final double EQUIP_ROW_HEIGHT = 28.0;
    private static final double EQUIP_ROW_GAP = 5.0;

    /** 小地图面板的左上角：排在信息面板与光/影徽章下方，不与它们抢位置。 */
    private static final double MINI_MAP_X = 1050.0;
    private static final double MINI_MAP_Y = 130.0;

    /**
     * 飘字停留时间（秒）。
     *
     * <p>必须与 {@code EnemySystem.DAMAGE_FLASH_TIME} 一致：渲染层用它把
     * 「还剩多久」换算成透明度与上浮距离，两处不一致会导致数字提前消失或一直挂着。
     */
    private static final double DAMAGE_FLASH_TIME = 0.85;
    /** 小地图：已清空的怪物房标记色与“还有东西可拿”的金点色。 */
    private static final Color CLEARED_GREEN = Color.web("#7fbf7a");
    private static final Color LOOT_GOLD = Color.web("#f7d56e");
    private static final Map<String, Image[]> LIGHT_FRAMES = loadCharacterFrames("white_cyan");
    private static final Map<String, Image[]> SHADOW_FRAMES = loadCharacterFrames("black_magenta");
    private static final Image[] LIGHT_BULLETS = loadSeries("white_cyan", "projectiles", "bullet_fly_right", 1);
    private static final Image[] SHADOW_BULLETS = loadSeries("black_magenta", "projectiles", "bullet_fly_right", 1);
    // 未被引用（IDE 的 Unused 检查会报）：斩击素材只有影界近战在用（见 SHADOW_SLASHES），
    // 光界走弹体，因此这份光界斩击帧既不会被绘制、也不会被加载。暂时注释保留。
    // private static final Image[] LIGHT_SLASHES = loadSeries("white_cyan", "effects_aligned", "slash_arc_right", 4);
    private static final Image[] SHADOW_SLASHES = loadSeries("black_magenta", "effects_aligned", "slash_arc_right", 4);
    private static final Image[] LIGHT_AURA = loadSeries("white_cyan", "effects_aligned", "aura", 3);
    private static final Image[] SHADOW_AURA = loadSeries("black_magenta", "effects_aligned", "aura", 3);
    private static final Image REWARD_ICONS = loadUiImage("reward_icons_v1.png");
    private static final Image WEAPON_ICONS = loadUiImage("weapons_v1.png");
    private static final Image EQUIPMENT_ICONS = loadUiImage("equipment_v1.png");
    /** 受击闪白用的"纯白剪影"缓存：按需生成，同一张贴图只算一次。 */
    private static final Map<Image, Image> WHITE_SILHOUETTES = new HashMap<>();
    private static final Map<String, Image> EQUIPMENT_ASSETS = loadEquipmentAssets();
    private static final Map<String, Image> MONSTER_IMAGES = new HashMap<>();
    private static final Map<String, Image[]> MONSTER_FRAME_SETS = new HashMap<>();

    public void render(GraphicsContext g, GameSession session, double fps) {
        g.setImageSmoothing(false);
        Player player = session.getPlayer();
        boolean light = player.getCurrentWorld() == WorldType.LIGHT;
        drawFloor(g, light);
        drawRoom(g, session, light);
        drawPhaseWalls(g, session);
        drawBlinkFlash(g, session);
        // 地面层：根篱与敌方预警都画在地板上、角色之下，玩家的脚不会被盖住。
        drawRootWalls(g, session, player.getCurrentWorld());
        drawEnemyTelegraphs(g, session, player.getCurrentWorld());
        drawSummonRifts(g, session, player.getCurrentWorld());
        drawEnemies(g, session, player.getCurrentWorld());
        drawEnemyAttacks(g, session, player.getCurrentWorld());
        drawPortal(g, session);
        drawPickups(g, session);
        drawInteractionPrompt(g, session);
        // 拖尾垫在角色之下：残影只该在身后露出来，不能糊在自己脸上。
        drawDashTrail(g, player, light);
        drawPlayer(g, player, light);
        // 攻击层在角色之后绘制，避免角色把斩击和投射物遮住。
        drawAttacks(g, session, light);
        drawPhasePulse(g, session, light);
        drawAim(g, session, light);
        drawHud(g, session, fps, light);
        drawMiniMap(g, session, light);
        drawRoomAnnouncement(g, session);
        drawControls(g, light);
        drawEquipmentSelection(g, session);
        // 伤害飘字画在所有面板之上：挨打的即时反馈不该被 HUD 盖住。
        drawDamageFlashes(g, session);
        if (player.getHp() <= 0) drawDeathOverlay(g, session);
        else if (session.isRunCleared()) drawVictoryOverlay(g, session);
    }

    /**
     * 首领房清空后出现的层间传送门：一圈旋转的裂隙 + 目标层数。
     *
     * <p>用玩家的动画计时做旋转，不额外引入渲染状态。
     */
    private void drawPortal(GraphicsContext g, GameSession session) {
        if (!session.isPortalVisible()) return;
        Room room = session.getNavigation().getCurrentRoom();
        double x = (room.minX() + room.maxX()) / 2.0;
        double y = (room.minY() + room.maxY()) / 2.0;
        double time = session.getPlayer().getAnimationTime();
        double pulse = 1.0 + Math.sin(time * 2.6) * 0.06;
        double outer = 86.0 * pulse;
        double inner = 54.0 * pulse;
        g.setFill(Color.rgb(6, 4, 12, 0.9));
        g.fillOval(x - outer, y - outer, outer * 2, outer * 2);
        for (int i = 0; i < 3; i++) {
            double start = Math.toDegrees(time * (2.2 + i * 0.7) + i * 120.0);
            g.setStroke(Color.color(0.72, 0.42, 1.0, 0.9 - i * 0.18));
            g.setLineWidth(7.0 - i * 1.6);
            g.strokeArc(x - outer, y - outer, outer * 2, outer * 2, start, 108, ArcType.OPEN);
        }
        g.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#fff3c4")), new Stop(0.55, Color.web("#a86bff")),
                new Stop(1.0, Color.color(0.25, 0.10, 0.4, 0.15))));
        g.fillOval(x - inner, y - inner, inner * 2, inner * 2);
        g.setStroke(Color.web("#ffe9a8"));
        g.setLineWidth(3.0);
        g.strokeOval(x - inner, y - inner, inner * 2, inner * 2);
        boolean lastFloor = session.getFloor() >= session.getTotalFloors();
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#241033"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 22));
        g.fillText(lastFloor ? "通关" : "第 " + (session.getFloor() + 1) + " 层",
                x, y + 8);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.rgb(255, 240, 200, 0.9));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 14));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(session.getFloor() + " / " + session.getTotalFloors() + " 层已通", x, y + outer + 26);
        g.fillText("走近按 E 传送", x, y + outer + 46);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawPhasePulse(GraphicsContext g, GameSession session, boolean light) {
        if (!session.isPhasePulseVisible()) return;
        Player player = session.getPlayer();
        double radius = GameConfig.PHASE_PULSE_RADIUS;
        Color color = light ? LIGHT_GOLD : SHADOW_VIOLET;
        g.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.74));
        g.setLineWidth(4.0);
        g.strokeRect(player.getX() - radius, player.getY() - radius, radius * 2.0, radius * 2.0);
    }

    /**
     * 守望者裂隙闪现的视觉：起点与终点各一道扩散的裂隙，中间连一道残影。
     *
     * <p>传送必须看得见——否则玩家只会觉得首领“瞬移卡了”。
     */
    private void drawBlinkFlash(GraphicsContext g, GameSession session) {
        var flash = session.getBlinkFlash();
        if (flash == null) return;
        double life = Math.max(0.001, GameConfig.WATCHER_BLINK_FLASH_TIME);
        double progress = Math.min(1.0, Math.max(0.0, 1.0 - flash.remaining() / life));
        double alpha = Math.max(0.0, 1.0 - progress);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(Color.color(0.78, 0.48, 1.0, alpha * 0.9));
        g.setLineWidth(6.0 * (1.0 - progress * 0.5));
        g.strokeLine(flash.fromX(), flash.fromY(), flash.toX(), flash.toY());
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        drawRiftBurst(g, flash.fromX(), flash.fromY(), 40.0 + progress * 70.0, alpha * 0.9);
        drawRiftBurst(g, flash.toX(), flash.toY(), 26.0 + progress * 110.0, alpha);
    }

    private void drawRiftBurst(GraphicsContext g, double x, double y, double radius, double alpha) {
        g.setFill(Color.color(0.42, 0.16, 0.62, alpha * 0.5));
        g.fillOval(x - radius * 0.55, y - radius * 0.55, radius * 1.1, radius * 1.1);
        g.setStroke(Color.color(0.88, 0.7, 1.0, alpha));
        g.setLineWidth(3.0);
        g.strokeOval(x - radius, y - radius * 0.72, radius * 2, radius * 1.44);
        g.setStroke(Color.color(1.0, 0.95, 0.75, alpha * 0.9));
        g.setLineWidth(1.6);
        g.strokeOval(x - radius * 0.62, y - radius * 0.42, radius * 1.24, radius * 0.84);
    }

    /**
     * 首领的召唤裂隙：一圈收缩的预警圈 + 正在张开的传送门。
     *
     * <p>预警是这段机制的核心——召唤物落地前玩家必须看得见“哪里要出怪、还剩多久”，
     * 否则召唤就只是凭空多出来两只怪。素材包为这个用途准备了 spawn_circle 预警图，
     * 这里再叠一道几何收缩环：素材缺失或裁切异常时预警依然成立。
     */
    private void drawSummonRifts(GraphicsContext g, GameSession session, WorldType currentWorld) {
        for (SummonRift rift : session.getSummonRifts()) {
            double progress = rift.progress();
            String form = rift.world() == WorldType.LIGHT ? "light" : "shadow";
            double size = 150.0 + 60.0 * progress;
            Image telegraph = animationFrame(telegraphFrames(form, "spawn_circle"),
                    rift.age(), rift.duration(), false, 4.0);
            if (telegraph != null) {
                g.save();
                g.setGlobalAlpha(0.34 + 0.5 * progress);
                g.drawImage(telegraph, rift.x() - size / 2.0, rift.y() - size / 2.0, size, size);
                g.restore();
            }
            drawMonsterEffect(g, rift.summoner(), rift.world(), "summon_portal", rift.x(), rift.y(),
                    0.0, 120.0 + 110.0 * progress, rift.age(), rift.duration());
            // 预警素材那 4 帧只是把 r=100/128 的圈逐渐点亮、加粗，本身不会收缩；
            // 这里再补一道从预警圈收拢到孔隙的环，让“还要多久出怪”一眼可见。
            Color ring = rift.world() == WorldType.LIGHT ? LIGHT_GOLD : SHADOW_VIOLET;
            double warningRadius = size * 0.39;
            double radius = warningRadius * (1.0 - progress) + 26.0;
            g.setStroke(Color.color(ring.getRed(), ring.getGreen(), ring.getBlue(), 0.35 + 0.5 * progress));
            g.setLineWidth(3.0);
            g.strokeOval(rift.x() - radius, rift.y() - radius * 0.62, radius * 2.0, radius * 1.24);
        }
    }

    /** 几何预警图：旧包 4 帧、新包 1 帧（512×512，锚点在正中）。 */
    private static Image[] telegraphFrames(String form, String shape) {
        String key = "telegraphs/" + form + "/" + shape;
        Image[] cached = MONSTER_FRAME_SETS.get(key);
        if (cached != null) return cached;
        List<Image> frames = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            // v2 扩展包的模板放在 sprites/monsters/telegraphs 下，旧包仍在 enemies/telegraphs；
            // 两个根目录都找一遍，旧包资产不必搬家。
            var resource = GameRenderer.class.getResource("/com/phantomcorridor/sprites/monsters/telegraphs/"
                    + form + "/" + shape + "/" + String.format("%02d", i) + ".png");
            if (resource == null) {
                resource = GameRenderer.class.getResource("/com/phantomcorridor/enemies/telegraphs/" + form
                        + "/" + shape + "/" + String.format("%02d", i) + ".png");
            }
            if (resource == null) break;
            frames.add(new Image(resource.toExternalForm(), false));
        }
        Image[] result = frames.toArray(Image[]::new);
        MONSTER_FRAME_SETS.put(key, result);
        return result;
    }

    /** 非当前世界的敌人及攻击完全不绘制，与模型的同界碰撞规则保持一致。 */
    private void drawEnemies(GraphicsContext g, GameSession session, WorldType currentWorld) {
        List<Enemy> visible = session.getEnemies().getEnemies().stream()
                .sorted(Comparator.comparingDouble(Enemy::getY)).toList();
        for (Enemy enemy : visible) {
            g.save();
            g.setGlobalAlpha(1.0);
            Image[] frames = enemyFrames(enemy, enemy.getWorld() == WorldType.LIGHT ? "light" : "shadow");
            Image body = animationFrame(frames, enemy.getAnimationTime(), enemy.getAnimationDuration(), enemy.isAnimationLooping(), 6.0);
            double size = displayWidth(enemy.getKind());
            double x = Math.rint(enemy.getX() - size / 2.0);
            if (body != null) {
                double height = size * body.getHeight() / Math.max(1.0, body.getWidth());
                // 素材包根锚点统一在画布高度 87.5% 处，不能按可见包围盒重新计算，
                // 否则挥臂、扑击和死亡帧会在地面上来回跳动。
                g.drawImage(body, x, Math.rint(enemy.getY() - height * .875), size, height);
            } else {
                g.setFill(enemy.getWorld() == WorldType.LIGHT ? LIGHT_GOLD : SHADOW_VIOLET);
                g.fillRect(x, enemy.getY() - size / 2.0, size, size);
            }
            drawEnemyHealth(g, enemy, enemy.getWorld() == WorldType.LIGHT ? LIGHT_GOLD : SHADOW_VIOLET);
            g.restore();
            g.setFill(Color.WHITE);
            g.setFont(Font.font("Microsoft YaHei UI", 11));
            g.fillText(enemy.isSpawning() ? "现身中" : enemy.getKind().affinity().label(), enemy.getX() - 24, enemy.getHitboxCenterY() - 50);
            if (enemy.getScorchStacks() > 0) {
                g.setFill(LIGHT_GOLD);
                g.setFont(Font.font("Microsoft YaHei UI", 12));
                g.fillText("灼痕 ×" + enemy.getScorchStacks(), enemy.getX() - 25, enemy.getHitboxCenterY() - 35);
            }
        }
    }

    /**
     * 敌人本体帧的查找顺序。
     *
     * <p>v2 扩展包只画了右向三分之四视角并镜像出左向，没有独立的正面与背面，
     * 所以朝向帧缺失时要退到本界的左右向帧，而不是留下一块空白或色块——
     * 同时 {@code Enemy.setFacingFromVector} 已经不会把这些物种切到 front/back。
     */
    private static Image[] enemyFrames(Enemy enemy, String form) {
        String base = "enemies/" + enemy.getKind().assetId() + "/" + form + "/";
        Image[] frames = monsterFrames(base + enemy.getAnimationAction() + "/" + enemy.getFacing());
        if (frames.length == 0) frames = monsterFrames(base + "idle/" + enemy.getFacing());
        if (frames.length == 0) frames = monsterFrames(base + enemy.getAnimationAction() + "/right");
        if (frames.length == 0) frames = monsterFrames(base + "idle/right");
        if (frames.length == 0) frames = monsterFrames(base + "idle/left");
        return frames;
    }

    /** 物种本体的显示宽度：画布边长 × 统一缩放刻度（v1 与 v2 两套素材同一把尺子）。 */
    private static double displayWidth(EnemyKind kind) {
        return kind.canvasSize() * GameConfig.MONSTER_RENDER_SCALE;
    }

    private void drawEnemyHealth(GraphicsContext g, Enemy enemy, Color domain) {
        double width = enemy.isBoss() ? 126 : 58;
        double x = enemy.getX() - width / 2.0;
        // 血条贴模型头顶：用物种的实测身高而不是画布高度，树冠与镰臂的留白不会把血条顶飞。
        double y = enemy.getY() - enemy.getKind().overheadHeight() - 10.0;
        g.setFill(Color.rgb(0, 0, 0, 0.68));
        g.fillRect(x, y, width, 6);
        g.setFill(Color.color(domain.getRed(), domain.getGreen(), domain.getBlue(), 0.92));
        g.fillRect(x + 1, y + 1, (width - 2) * enemy.getHp() / enemy.getMaxHp(), 4);
    }

    /**
     * 敌方预警层：v2 扩展包的扇面、安全楔、环带缺口、横带与地面标记都画在这里。
     *
     * <p>几何一律按技能参数现画，而不是把素材包里的通用模板当成判定范围——素材包文档也明确要求
     * 「实际缺口角度、标记数量、位置和宽度应按技能参数绘制」。假的圆圈另用虚线空心，
     * 所以术士的三镜校时在灰度下同样能分辨真假。
     */
    private void drawEnemyTelegraphs(GraphicsContext g, GameSession session, WorldType currentWorld) {
        List<EnemyTelegraph> telegraphs = session.getEnemies().getTelegraphs();
        if (telegraphs.isEmpty()) return;
        for (EnemyTelegraph telegraph : telegraphs) {
            Color base = telegraph.world() == WorldType.LIGHT ? LIGHT_GOLD : SHADOW_VIOLET;
            if (!telegraph.isVisible()) continue;
            double progress = telegraph.progress();
            double alpha;
            if (telegraph.isTriggered()) {
                // 判定之后的残留：爆开的一瞬最亮，然后淡出。
                alpha = Math.max(0.0, 0.70 * (1.0 - telegraph.residualProgress()));
            } else {
                alpha = telegraph.isFake() ? 0.18 + 0.10 * progress : 0.14 + 0.28 * progress;
            }
            drawTelegraph(g, telegraph, base, alpha);
        }
    }

    /** 按形状画一道预警：填充随倒计时变浓，边缘始终清晰。 */
    private void drawTelegraph(GraphicsContext g, EnemyTelegraph telegraph, Color base, double alpha) {
        Color fill = Color.color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(0.85, alpha));
        g.save();
        if (telegraph.isFake()) {
            // 假圈：只有虚线空心，永远不填充——「实线齿边是真、虚线是假」。
            g.setLineDashes(10.0, 8.0);
            g.setStroke(Color.color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(0.9, alpha + 0.30)));
            g.setLineWidth(2.0);
        } else {
            g.setStroke(Color.color(1.0, 1.0, 1.0, Math.min(0.95, alpha + 0.18)));
            g.setLineWidth(1.8);
        }
        switch (telegraph.shape()) {
            case CIRCLE -> {
                double r = telegraph.radius();
                if (!telegraph.isFake()) g.setFill(fill);
                if (!telegraph.isFake()) g.fillOval(telegraph.x() - r, telegraph.y() - r, r * 2, r * 2);
                else g.strokeOval(telegraph.x() - r, telegraph.y() - r, r * 2, r * 2);
                if (!telegraph.isFake()) g.strokeOval(telegraph.x() - r, telegraph.y() - r, r * 2, r * 2);
                // 中心十字：地面标记要能看清落点，而不是一团糊在地上的色块。
                g.strokeLine(telegraph.x() - 8, telegraph.y(), telegraph.x() + 8, telegraph.y());
                g.strokeLine(telegraph.x(), telegraph.y() - 8, telegraph.x(), telegraph.y() + 8);
            }
            case SECTOR -> {
                wedge(g, telegraph.x(), telegraph.y(), telegraph.radius(),
                        telegraph.angleRadians(), telegraph.angleDegrees(), fill, telegraph.isFake());
            }
            case SECTOR_SPLIT -> {
                double gapHalf = Math.toRadians(telegraph.safeGapDegrees() / 2.0);
                double sideHalf = Math.toRadians(telegraph.angleDegrees() / 2.0);
                for (int side = -1; side <= 1; side += 2) {
                    wedge(g, telegraph.x(), telegraph.y(), telegraph.radius(),
                            telegraph.angleRadians() + side * (gapHalf + sideHalf),
                            telegraph.angleDegrees(), fill, telegraph.isFake());
                }
            }
            case LINE -> {
                g.save();
                g.translate(telegraph.x(), telegraph.y());
                g.rotate(Math.toDegrees(telegraph.angleRadians()));
                if (!telegraph.isFake()) {
                    g.setFill(fill);
                    g.fillRect(0, -telegraph.width() / 2.0, telegraph.length(), telegraph.width());
                }
                g.strokeRect(0, -telegraph.width() / 2.0, telegraph.length(), telegraph.width());
                // 分段标记：根刺是三根、封线是三段，玩家要能看出这一招铺了几节。
                for (int i = 1; i < telegraph.segments(); i++) {
                    double x = telegraph.length() * i / telegraph.segments();
                    g.strokeLine(x, -telegraph.width() / 2.0, x, telegraph.width() / 2.0);
                }
                g.restore();
            }
            case RING_GAP -> {
                double radius = (telegraph.innerRadius() + telegraph.outerRadius()) / 2.0;
                double thickness = Math.max(6.0, telegraph.outerRadius() - telegraph.innerRadius());
                g.setLineWidth(thickness);
                g.setStroke(Color.color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(0.8, alpha + 0.10)));
                // 缺口朝向 angleRadians：环上留的“门”必须真的走得出去。
                double startDeg = -Math.toDegrees(telegraph.angleRadians() + Math.toRadians(telegraph.safeGapDegrees() / 2.0));
                g.strokeArc(telegraph.x() - radius, telegraph.y() - radius, radius * 2, radius * 2,
                        startDeg, 360.0 - telegraph.safeGapDegrees(), ArcType.OPEN);
            }
            case BAND -> {
                g.save();
                g.translate(telegraph.x(), telegraph.y());
                g.rotate(Math.toDegrees(telegraph.angleRadians()));
                double half = telegraph.bandLength() / 2.0;
                double gapHalf = telegraph.safeGap() / 2.0;
                double near = Math.max(-half, telegraph.gapOffset() - gapHalf);
                double far = Math.min(half, telegraph.gapOffset() + gapHalf);
                if (!telegraph.isFake()) g.setFill(fill);
                drawBandPiece(g, -half, near, telegraph.width(), telegraph.isFake());
                drawBandPiece(g, far, half, telegraph.width(), telegraph.isFake());
                g.restore();
            }
        }
        g.restore();
    }

    /** 横带的一段：以带子自身为轴（x 是厚度方向、y 是长度方向）。 */
    private static void drawBandPiece(GraphicsContext g, double from, double to, double thickness, boolean outlineOnly) {
        double length = to - from;
        if (length <= 1.0) return;
        if (outlineOnly) g.strokeRect(-thickness / 2.0, from, thickness, length);
        else {
            g.fillRect(-thickness / 2.0, from, thickness, length);
            g.strokeRect(-thickness / 2.0, from, thickness, length);
        }
    }

    /**
     * 一块扇形预警。
     *
     * <p>角度换算要注意坐标系：模型用 {@code atan2(dy, dx)}（y 向下），而 Canvas 的
     * {@code arc()} 角度以 3 点钟为 0、逆时针为正（屏幕上表现为向上为正），两者符号相反。
     */
    private static void wedge(GraphicsContext g, double cx, double cy, double radius,
                              double centreRadians, double degrees, Color fill, boolean outlineOnly) {
        double half = Math.toRadians(degrees / 2.0);
        double startDeg = -Math.toDegrees(centreRadians + half);
        g.beginPath();
        g.moveTo(cx, cy);
        g.arc(cx, cy, radius, radius, startDeg, degrees);
        g.closePath();
        if (!outlineOnly) {
            g.setFill(fill);
            g.fill();
        }
        g.stroke();
    }

    /**
     * 根冠古树的根篱：只阻挡、不伤害的临时地形。
     *
     * <p>可达性校验失败的根篱是「装饰性」的，用虚线描边标出来——玩家如果看到实线根篱挡路、
     * 却发现能穿过去，会以为碰撞坏了；虚线与实线的区别正好说明「这道不管用」。
     */
    private void drawRootWalls(GraphicsContext g, GameSession session, WorldType currentWorld) {
        List<RootWall> walls = session.getEnemies().getRootWalls();
        if (walls.isEmpty()) return;
        Color bark = currentWorld == WorldType.LIGHT ? Color.web("#7a6a35") : Color.web("#4a3260");
        Color rim = currentWorld == WorldType.LIGHT ? Color.web("#d8c07a") : Color.web("#b98bff");
        for (RootWall wall : walls) {
            if (wall.world() != currentWorld) continue;
            double progress = wall.progress();
            // 前 1/4 时长出来、最后 1/5 时塌回去：地形招也要有起手与收招。
            double grow = Math.min(1.0, progress * 4.0);
            double fade = progress > 0.8 ? Math.max(0.0, (1.0 - progress) / 0.2) : 1.0;
            if (grow <= 0.0 || fade <= 0.0) continue;
            double height = wall.height() * grow;
            double width = wall.width() * grow;
            double x = wall.x() + (wall.width() - width) / 2.0;
            double y = wall.y() + (wall.height() - height) / 2.0;
            g.save();
            g.setGlobalAlpha(fade);
            g.setFill(Color.color(bark.getRed(), bark.getGreen(), bark.getBlue(), 0.88));
            g.fillRoundRect(x, y, width, height, 8, 8);
            g.setStroke(Color.color(rim.getRed(), rim.getGreen(), rim.getBlue(), 0.85));
            g.setLineWidth(1.6);
            if (wall.blocking()) {
                g.strokeRoundRect(x, y, width, height, 8, 8);
            } else {
                g.setLineDashes(8.0, 6.0);
                g.strokeRoundRect(x, y, width, height, 8, 8);
            }
            g.restore();
        }
    }

    private void drawEnemyAttacks(GraphicsContext g, GameSession session, WorldType currentWorld) {
        for (EnemyAttack attack : session.getEnemies().getAttacks()) {
            if (!attack.isActive()) continue;
            // 飞行攻击的碰撞半径保持原数值；只扩大独立美术帧，避免“看不见的小弹”
            // 与实际判定不一致。不同弹种按轮廓复杂度给出不同的清晰显示尺寸。
            double size = attack.isMoving() ? projectileVisualSize(attack) : Math.max(74, attack.getRadius() * 3.5);
            if (!drawMonsterEffect(g, attack.getSource(), attack.getWorld(), attack.getEffectId(), attack.getX(), attack.getY(),
                    attack.getAngleRadians(), size, 0.20, .36)) {
                g.setFill(currentWorld == WorldType.LIGHT ? Color.web("#fff0a2") : Color.web("#d59aff"));
                g.fillOval(attack.getX() - attack.getRadius(), attack.getY() - attack.getRadius(),
                        attack.getRadius() * 2, attack.getRadius() * 2);
            }
        }
        for (EnemyVisualEffect effect : session.getEnemies().getVisualEffects()) {
            if (effect.isBodyAnimation()) drawMonsterBodyEffect(g, effect);
            else drawMonsterEffect(g, effect.source(), effect.world(), effect.effectId(),
                    effect.x(), effect.y(), effect.angleRadians(), effect.size(), effect.age(), effect.duration());
        }
    }

    // 未被引用（IDE 的 Unused 检查会报）：攻击特效 id 现在已经直接挂在 EnemyAttack 上
    // （见 EnemySkill.effect），渲染时不必再按物种 + 世界反推一次。暂时注释保留。
    // private static String enemyEffect(EnemyKind kind, WorldType world) {
    //     boolean light = world == WorldType.LIGHT;
    //     return switch (kind) {
    //         case LANTERN -> light ? "seeker_orb" : "dusk_needle";
    //         case WOLF -> light ? "bite_flash" : "bite_arc";
    //         case GOLEM -> light ? "ground_crack" : "slam_sector";
    //         case MAGE -> light ? "fan_pellet" : "mirror_arc";
    //         case EXECUTIONER -> light ? "spear_projectile" : "cleave_arc";
    //         case BELL -> light ? "bell_pellet" : "annular_burst";
    //         case WATCHER -> light ? "rift_spear" : "slash_arc";
    //     };
    // }

    private static Image monsterImage(String relativePath) {
        if (MONSTER_IMAGES.containsKey(relativePath)) return MONSTER_IMAGES.get(relativePath);
        var resource = GameRenderer.class.getResource("/com/phantomcorridor/sprites/monsters/" + relativePath);
        Image image = resource == null ? null : new Image(resource.toExternalForm(), false);
        MONSTER_IMAGES.put(relativePath, image);
        return image;
    }

    private void drawAttacks(GraphicsContext g, GameSession session, boolean light) {
        Player player = session.getPlayer();
        for (Projectile projectile : session.getAttackSystem().getProjectiles()) {
            // 弹体按方案分成四种形态：光核（慢速大团）、光矛（细长梭）、
            // 影刃（旋刃）、基础光弹。外观必须跟得上模型，否则玩家看不出换了武器。
            switch (projectile.getShape()) {
                case CORE -> drawBurstCore(g, projectile);
                case LANCE -> drawLightLance(g, projectile);
                case FANG -> drawShadowFang(g, projectile);
                case ORB -> drawLightOrb(g, projectile, light, session);
            }
        }
        if (!light && session.getAttackSystem().isMeleeVisible()) {
            drawMeleeArc(g, session, player);
        }
        if (player.getAnimationState() == PlayerAnimationState.SHIFTING) {
            Image[] aura = light ? LIGHT_AURA : SHADOW_AURA;
            if (aura.length > 0) {
                Image frame = aura[Math.min(aura.length - 1,
                        (int) (player.getAnimationTime() * 10.0) % aura.length)];
                double size = 180.0;
                g.setGlobalBlendMode(BlendMode.ADD);
                g.drawImage(frame, player.getX() - size / 2.0, player.getY() - size * 0.5,
                        size, size * frame.getHeight() / Math.max(1.0, frame.getWidth()));
                g.setGlobalBlendMode(BlendMode.SRC_OVER);
            }
        }
    }

    /** 基础光弹与三叉杖的每一发：旧的子弹贴图，尺寸跟着半径走。 */
    private void drawLightOrb(GraphicsContext g, Projectile projectile, boolean light, GameSession session) {
        double radius = projectile.getRadius();
        Image[] bullets = light ? LIGHT_BULLETS : SHADOW_BULLETS;
        if (bullets.length > 0) {
            Image bullet = bullets[0];
            double size = Math.max(28.0, radius * 7.0);
            double height = size * bullet.getHeight() / Math.max(1.0, bullet.getWidth());
            boolean reverse = projectile.getVelocityX() < 0;
            g.drawImage(bullet, reverse ? projectile.getX() + size / 2.0 : projectile.getX() - size / 2.0,
                    projectile.getY() - height / 2.0, reverse ? -size : size, height);
            return;
        }
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(Color.rgb(255, 220, 138, 0.30));
        g.fillRect(projectile.getX() - radius * 2.5, projectile.getY() - radius * 2.5,
                radius * 5.0, radius * 5.0);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(Color.web("#fff2bd"));
        g.fillRect(projectile.getX() - radius, projectile.getY() - radius,
                radius * 2.0, radius * 2.0);
    }

    /**
     * 贯日长杖的细长光矛：沿飞行方向拉长的金色光梭。
     *
     * <p>「尾光不增加碰撞宽度」——所以这里只把**视觉**拉长，命中半径仍然用模型给的细半径。
     */
    private void drawLightLance(GraphicsContext g, Projectile projectile) {
        double angle = Math.toDegrees(Math.atan2(projectile.getVelocityY(), projectile.getVelocityX()));
        double length = 54.0;
        double width = Math.max(6.0, projectile.getRadius() * 2.0);
        g.save();
        g.translate(projectile.getX(), projectile.getY());
        g.rotate(angle);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(Color.rgb(255, 226, 150, 0.34));
        g.fillRoundRect(-length, -width, length * 2, width * 2, width, width);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(Color.web("#fff6cf"));
        g.fillRoundRect(-length * 0.7, -width / 2.0, length * 1.4, width, width / 2.0, width / 2.0);
        g.setFill(Color.web("#ffffff"));
        g.fillRoundRect(length * 0.2, -width / 3.0, length * 0.5, width * 0.66, width / 3.0, width / 3.0);
        g.restore();
    }

    /** 炽核权杖的慢速光核：闭合日轮裹着橙金核心。 */
    private void drawBurstCore(GraphicsContext g, Projectile projectile) {
        double radius = projectile.getRadius();
        double pulse = 1.0 + Math.sin(projectile.getRemainingLifetime() * 18.0) * 0.08;
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(Color.rgb(255, 176, 88, 0.26));
        g.fillOval(projectile.getX() - radius * 3.0 * pulse, projectile.getY() - radius * 3.0 * pulse,
                radius * 6.0 * pulse, radius * 6.0 * pulse);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setStroke(Color.web("#ffb45c"));
        g.setLineWidth(2.5);
        g.strokeOval(projectile.getX() - radius * 1.7, projectile.getY() - radius * 1.7,
                radius * 3.4, radius * 3.4);
        g.setFill(Color.web("#ffd489"));
        g.fillOval(projectile.getX() - radius, projectile.getY() - radius, radius * 2.0, radius * 2.0);
        g.setFill(Color.web("#fff6dd"));
        g.fillOval(projectile.getX() - radius * 0.45, projectile.getY() - radius * 0.45,
                radius * 0.9, radius * 0.9);
    }

    /** 归影双刃的旋刃：合拢成梭形的紫刃，返程时更暗一些表示已经在回家。 */
    private void drawShadowFang(GraphicsContext g, Projectile projectile) {
        double angle = Math.toDegrees(Math.atan2(projectile.getVelocityY(), projectile.getVelocityX()));
        double size = Math.max(22.0, projectile.getRadius() * 2.6);
        double alpha = projectile.isReturning() ? 0.62 : 0.92;
        g.save();
        g.translate(projectile.getX(), projectile.getY());
        g.rotate(angle);
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setFill(Color.rgb(168, 96, 236, 0.30 * alpha));
        g.fillOval(-size, -size * 0.6, size * 2, size * 1.2);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setFill(Color.web("#c98df5"));
        g.fillPolygon(new double[]{-size * 0.8, size, size * 0.35, -size * 0.2},
                new double[]{0, 0, size * 0.42, size * 0.42}, 4);
        g.setFill(Color.web("#efe0ff"));
        g.fillPolygon(new double[]{-size * 0.8, size, size * 0.35, -size * 0.2},
                new double[]{0, 0, -size * 0.42, -size * 0.42}, 4);
        g.restore();
    }

    /**
     * 影界近战弧。
     *
     * <p>扇形角度与距离都读模型里那一轮的实际值：环月镰是 360° 环斩、重剑是 40° 窄劈，
     * 用固定常量画就会让玩家看到的范围和实际结算范围对不上。
     */
    private void drawMeleeArc(GraphicsContext g, GameSession session, Player player) {
        var attacks = session.getAttackSystem();
        double angle = Math.toDegrees(attacks.getMeleeAngleRadians());
        double arc = attacks.getMeleeArcDegrees();
        double range = attacks.getMeleeRange();
        // 环月镰是整圈，用 slash 贴图会缺一角，所以整圈一律走圆弧画法。
        if (arc < 360.0 && SHADOW_SLASHES.length > 0) {
            Image slash = SHADOW_SLASHES[Math.min(SHADOW_SLASHES.length - 1,
                    (int) (player.getAnimationTime() * 12.0) % SHADOW_SLASHES.length)];
            double size = Math.max(120.0, range * 1.05);
            g.save();
            g.translate(player.getX(), player.getY());
            g.rotate(angle);
            g.drawImage(slash, -size / 2.0, -size * 0.5,
                    size, size * slash.getHeight() / Math.max(1.0, slash.getWidth()));
            g.restore();
            return;
        }
        g.setStroke(Color.rgb(190, 132, 242, 0.82));
        g.setLineWidth(10.0);
        g.strokeArc(player.getX() - range, player.getY() - range, range * 2.0, range * 2.0,
                -angle - arc / 2.0, arc, javafx.scene.shape.ArcType.OPEN);
        g.setStroke(Color.rgb(239, 216, 255, 0.86));
        g.setLineWidth(2.0);
        g.strokeArc(player.getX() - range, player.getY() - range, range * 2.0, range * 2.0,
                -angle - arc / 2.0, arc, javafx.scene.shape.ArcType.OPEN);
    }

    // 以下两个方法当前没有任何调用点（IDE 的 Unused 检查会报）：敌人与敌方弹体都改由
    // drawEnemies(WorldType) / drawEnemyAttacks(WorldType) 绘制——同界过滤用世界枚举而不是布尔值，
    // 免得“非当前世界的敌人不绘制”这条规则被两个重载各写一遍。暂时整体注释保留。
    //
    // private void drawEnemyProjectiles(GraphicsContext g, GameSession session, boolean light) {
    //     for (EnemyProjectile p : session.getEnemyProjectiles().getProjectiles()) {
    //         if ((p.getWorld() == WorldType.LIGHT) != light) continue;
    //         double r = p.getRadius();
    //         g.setGlobalBlendMode(BlendMode.ADD);
    //         g.setFill(light ? Color.rgb(255, 231, 151, .75) : Color.rgb(218, 105, 255, .78));
    //         g.fillRect(p.getX() - r, p.getY() - r, r * 2, r * 2);
    //         g.setGlobalBlendMode(BlendMode.SRC_OVER);
    //     }
    // }
    //
    // private void drawEnemies(GraphicsContext g, GameSession session, boolean light) {
    //     for (Enemy enemy : session.getEnemies().getEnemies()) {
    //         if ((enemy.getWorld() == WorldType.LIGHT) != light) continue;
    //         String action = enemy.getAlertRemaining() > 0.0 ? "attack_windup" : "idle";
    //         String direction = "front";
    //         var resource = getClass().getResource("/com/phantomcorridor/enemies/atlases/" + enemy.getKind().assetId()
    //                 + "/" + (light ? "light" : "shadow") + "/body/" + action + "/" + direction + ".png");
    //         if (resource == null) resource = getClass().getResource("/com/phantomcorridor/enemies/atlases/" + enemy.getKind().assetId()
    //                 + "/" + (light ? "light" : "shadow") + "/body/idle/front.png");
    //         if (resource == null) continue;
    //         Image atlas = new Image(resource.toExternalForm(), false);
    //         double frameW = atlas.getWidth() / 4.0;
    //         int frame = 0;
    //         double size = enemy.isBoss() ? 260 : enemy.getKind().elite() ? 180 : 132;
    //         double h = size * atlas.getHeight() / Math.max(1.0, atlas.getWidth() / 4.0);
    //         g.drawImage(atlas, frame * frameW, 0, frameW, atlas.getHeight(),
    //                     enemy.getX() - size / 2.0, enemy.getY() - h * .875, size, h);
    //         g.setFill(Color.rgb(20, 10, 18, .8)); g.fillRect(enemy.getX() - 28, enemy.getY() - h * .95, 56, 5);
    //         g.setFill(light ? Color.web("#f1c56e") : Color.web("#d783ff"));
    //         g.fillRect(enemy.getX() - 28, enemy.getY() - h * .95, 56.0 * enemy.getHp() / enemy.getMaxHp(), 5);
    //         if (enemy.getAlertRemaining() > 0.0) {
    //             g.setStroke(light ? Color.web("#ffe89a") : Color.web("#ed8cff"));
    //             g.setLineWidth(3); g.strokeOval(enemy.getX() - 28, enemy.getY() - 28, 56, 56);
    //         }
    //     }
    // }

    private void drawPickups(GraphicsContext g, GameSession session) {
        for (var pickup : session.getPickups()) {
            Color color = switch (pickup.type()) {
                case COIN -> Color.web("#f7d56e");
                case PHASE_FRAGMENT -> Color.web("#b882ff");
                case HEALTH -> Color.web("#e86b70");
                case ITEM -> Color.web("#70d8ff");
                case EQUIPMENT -> Color.web("#f0c86e");
            };
            // 商店商品额外画出“名称 + 价格”，并标出“已选中、等待确认”的那一件。
            int price = session.getShopPrice(pickup);
            if (price >= 0) drawPriceTag(g, session, pickup, price);

            int icon = pickup.type() == Pickup.Type.COIN ? 1
                    : pickup.type() == Pickup.Type.ITEM ? 2 + Math.floorMod(pickup.amount(), 6) : -1;
            if (pickup.type() == Pickup.Type.EQUIPMENT) {
                // 地面装备与装备栏、替换面板共用一套画法：独立贴图优先，旧三格图集兜底。
                EquipmentType type = EquipmentType.values()[Math.floorMod(
                        pickup.amount(), EquipmentType.values().length)];
                drawEquipmentIcon(g, type, pickup.x() - 24, pickup.y() - 24, 48);
            } else if (pickup.type() == Pickup.Type.HEALTH) {
                drawHealthPack(g, pickup.x(), pickup.y());
            } else if (icon >= 0 && REWARD_ICONS != null) {
                double cellW = REWARD_ICONS.getWidth() / 4.0, cellH = REWARD_ICONS.getHeight() / 2.0;
                double sx = (icon % 4) * cellW, sy = (icon / 4) * cellH;
                g.drawImage(REWARD_ICONS, sx, sy, cellW, cellH, pickup.x() - 22, pickup.y() - 22, 44, 44);
            } else {
                g.setFill(color);
                g.fillRect(pickup.x() - 7, pickup.y() - 7, 14, 14);
            }
            if (price >= 0 && session.isShopOfferSelected(pickup)) {
                g.setStroke(Color.web("#fff2b0"));
                g.setLineWidth(3.0);
                g.strokeRect(pickup.x() - 26, pickup.y() - 26, 52, 52);
            }
            g.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), .45));
            g.strokeRect(pickup.x() - 11, pickup.y() - 11, 22, 22);
            // 走到物品旁边时写出名称，避免“地上一个红包不知道是什么”。
            if (price < 0 && pickup == session.getInteractionTarget()) {
                drawPickupLabel(g, pickup.displayName(), pickup.x(), pickup.y() + 30);
            }
        }
        if (session.isChestVisible()) {
            Room room = session.getNavigation().getCurrentRoom();
            double x = room.doorCenter(Direction.NORTH), y = room.minY() + RoomConfig.CHEST_OFFSET_Y;
            if (REWARD_ICONS != null) {
                double cellW = REWARD_ICONS.getWidth() / 4.0, cellH = REWARD_ICONS.getHeight() / 2.0;
                g.drawImage(REWARD_ICONS, 0, 0, cellW, cellH, x - 32, y - 32, 64, 64);
            } else {
                g.setFill(Color.web("#8d542e")); g.fillRect(x - 22, y - 16, 44, 28);
                g.setFill(Color.web("#f3cf6b")); g.fillRect(x - 4, y - 4, 8, 10);
                g.setStroke(Color.web("#f0b858")); g.strokeRect(x - 22, y - 16, 44, 28);
            }
        }
    }

    private void drawDeathOverlay(GraphicsContext g, GameSession session) {
        g.setFill(Color.rgb(8, 4, 12, 0.78));
        g.fillRect(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        double centerX = AppConfig.VIEW_WIDTH / 2.0;
        double centerY = AppConfig.VIEW_HEIGHT / 2.0;
        g.setFill(Color.rgb(18, 12, 28, .97));
        g.fillRoundRect(centerX - 300, centerY - 160, 600, 330, 24, 24);
        g.setStroke(Color.web("#a878c7")); g.setLineWidth(2.0);
        g.strokeRoundRect(centerX - 300, centerY - 160, 600, 330, 24, 24);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#f0d7e8"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 42));
        g.fillText("倒下了", AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0 - 24);
        g.setFont(Font.font("Microsoft YaHei UI", 18));
        g.setFill(Color.web("#c9b4ca"));
        g.fillText("第 " + session.getFloor() + " 层探索结束 · 金币 " + session.getCoins(),
                AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0 + 18);
        drawDeathButton(g, session, centerX - 170, centerY + 44, 140, 50, "重新开始", false);
        drawDeathButton(g, session, centerX + 30, centerY + 44, 140, 50, "返回主菜单", true);
        g.setTextAlign(TextAlignment.LEFT);
    }

    /** 从独立 PNG 帧加载，不依赖旧版“固定四格图集”的假设。 */
    private static Image[] monsterFrames(String directory) {
        Image[] cached = MONSTER_FRAME_SETS.get(directory);
        if (cached != null) return cached;
        List<Image> frames = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Image image = monsterImage(directory + "_" + String.format("%02d", i) + ".png");
            if (image == null) break;
            frames.add(image);
        }
        cached = frames.toArray(Image[]::new);
        MONSTER_FRAME_SETS.put(directory, cached);
        return cached;
    }

    private static Image animationFrame(Image[] frames, double age, double duration, boolean loop, double fps) {
        if (frames.length == 0) return null;
        int index = loop ? (int) Math.floor(age * fps) % frames.length
                : Math.min(frames.length - 1, (int) Math.floor(Math.min(1.0, age / Math.max(.01, duration)) * frames.length));
        return frames[Math.max(0, index)];
    }

    private boolean drawMonsterEffect(GraphicsContext g, EnemyKind source, WorldType world, String effectId,
                                      double x, double y, double angle, double size, double age, double duration) {
        if (effectId == null || effectId.isBlank()) return false;
        String form = world == WorldType.LIGHT ? "light" : "shadow";
        Image sprite = animationFrame(monsterFrames("effects/" + source.assetId() + "/" + form + "/" + effectId + "/right"),
                age, duration, false, 12.0);
        if (sprite == null) return false;
        double height = size * sprite.getHeight() / Math.max(1.0, sprite.getWidth());
        g.save();
        g.translate(x, y);
        g.rotate(Math.toDegrees(angle));
        g.setGlobalBlendMode(BlendMode.ADD);
        g.drawImage(sprite, -size / 2.0, -height / 2.0, size, height);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.restore();
        return true;
    }

    private static double projectileVisualSize(EnemyAttack attack) {
        return switch (attack.getSource()) {
            case LANTERN -> attack.getEffectId().equals("dusk_needle") ? 126 : 148;
            case MAGE -> 128;
            case EXECUTIONER -> 184;
            case BELL -> 122;
            case WATCHER -> attack.getEffectId().equals("rift_spear") ? 210 : 134;
            // v2 弹体：孢子与晶羽是小团小片，炮弹与丝梭要大一圈才读得出方向。
            case SPORE -> 116;
            case RAYBAT -> 104;
            case BEETLE -> 110;
            case PRISM_CRAB -> attack.getEffectId().equals("prism_shell") ? 168 : 118;
            case MANTIS -> 124;
            case WEAVER -> 118;
            case ROOTKING -> 112;
            case HOURGLASS -> 140;
            default -> Math.max(120, attack.getRadius() * 6.0);
        };
    }

    /** 敌人死亡后实体可立即退出战斗逻辑，尸体仍以本体 death 帧完成一次播放。 */
    private void drawMonsterBodyEffect(GraphicsContext g, EnemyVisualEffect effect) {
        String form = effect.world() == WorldType.LIGHT ? "light" : "shadow";
        Image[] frames = monsterFrames("enemies/" + effect.source().assetId() + "/" + form + "/"
                + effect.effectId() + "/" + effect.facing());
        if (frames.length == 0) {
            frames = monsterFrames("enemies/" + effect.source().assetId() + "/" + form + "/"
                    + effect.effectId() + "/right");
        }
        Image sprite = animationFrame(frames, effect.age(), effect.duration(), false, 6.0);
        if (sprite == null) return;
        double height = effect.size() * sprite.getHeight() / Math.max(1.0, sprite.getWidth());
        double left = Math.rint(effect.x() - effect.size() / 2.0);
        double top = Math.rint(effect.y() - height * .875);
        if (!effect.isGhost()) {
            g.drawImage(sprite, left, top, effect.size(), height);
            return;
        }
        // 幻象：半透明本体 + 空心虚线描边。玩家要能一眼看出「这一幅不是真的」，
        // 而不是打上去才发现不掉血。
        g.save();
        g.setGlobalAlpha(0.42);
        g.drawImage(sprite, left, top, effect.size(), height);
        g.restore();
        g.save();
        g.setStroke(Color.color(0.86, 0.94, 1.0, 0.55));
        g.setLineWidth(1.4);
        g.setLineDashes(6.0, 5.0);
        g.strokeRoundRect(left + 4, top + 4, effect.size() - 8, height - 8, 12, 12);
        g.restore();
    }

    private void drawDeathButton(GraphicsContext g, GameSession session, double x, double y,
                                 double width, double height, String label, boolean menu) {
        boolean hover = session.getAimX() >= x && session.getAimX() <= x + width
                && session.getAimY() >= y && session.getAimY() <= y + height;
        g.setFill(Color.rgb(0, 0, 0, .35)); g.fillRoundRect(x + 4, y + 5, width, height, 10, 10);
        Color base = menu ? Color.web("#6b4a92") : Color.web("#9a5d72");
        g.setFill(hover ? base.brighter() : base);
        g.fillRoundRect(x, y - (hover ? 2 : 0), width, height, 10, 10);
        g.setStroke(hover ? Color.web("#fff0bd") : Color.web("#dcb8e5")); g.setLineWidth(2.0);
        g.strokeRoundRect(x, y - (hover ? 2 : 0), width, height, 10, 10);
        g.setFill(Color.web("#fff6e8")); g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 16));
        g.fillText(label, x + width / 2.0, y + 31 - (hover ? 2 : 0));
    }

    /** 打通第五层、穿过最后一道裂隙后的通关界面。 */
    private void drawVictoryOverlay(GraphicsContext g, GameSession session) {
        g.setFill(Color.rgb(10, 6, 18, 0.82));
        g.fillRect(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#ffeaa7"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 44));
        g.fillText("穿 越 完 成", AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0 - 34);
        g.setFont(Font.font("Microsoft YaHei UI", 19));
        g.setFill(Color.web("#e5d3f5"));
        g.fillText(session.getTotalFloors() + " 层裂隙全部走尽 · 金币 " + session.getCoins(),
                AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0 + 12);
        g.setFont(Font.font("Microsoft YaHei UI", 16));
        g.setFill(Color.web("#c9b4ca"));
        g.fillText("[R] 再来一局        [M] 返回主菜单",
                AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0 + 58);
        g.setTextAlign(TextAlignment.LEFT);
    }

    /**
     * 装备图标的唯一画法：地面掉落物、装备栏与替换面板共用。
     *
     * <p>优先用装备自己的独立贴图（{@code ui/equipment/*.png}），没有时才退回旧的三格图集——
     * 旧图集只有三张图，新增的武器如果都退回它，玩家会看到「三叉杖」长得像「法杖」。
     */
    private void drawEquipmentIcon(GraphicsContext g, EquipmentType item, double x, double y, double size) {
        Image dedicated = item.assetId() == null ? null : EQUIPMENT_ASSETS.get(item.assetId());
        if (dedicated != null) {
            g.drawImage(dedicated, 0, 0, dedicated.getWidth(), dedicated.getHeight(), x, y, size, size);
            return;
        }
        drawAtlasIcon(g, item.iconIndex() % 3, x, y, size, Color.web("#f0c86e"));
    }

    /** 旧图集回退：三格横排图集里取第 {@code cell} 格；图集缺失时画一个纯色方块兜底。 */
    private void drawAtlasIcon(GraphicsContext g, int cell, double x, double y, double size, Color fallback) {
        Image source = cell < 3 ? WEAPON_ICONS : EQUIPMENT_ICONS;
        if (source == null) {
            g.setFill(fallback);
            g.fillRect(x + size / 2.0 - 8, y + size / 2.0 - 8, 16, 16);
            return;
        }
        double cellWidth = source.getWidth() / 3.0;
        g.drawImage(source, cell * cellWidth, 0, cellWidth, source.getHeight(), x, y, size, size);
    }

    /** 生命恢复药剂：红包 + 白十字，比一个纯色小方块更容易认。 */
    private void drawHealthPack(GraphicsContext g, double x, double y) {
        g.setFill(Color.web("#c8454c"));
        g.fillRoundRect(x - 15, y - 12, 30, 24, 6, 6);
        g.setStroke(Color.web("#f2909a"));
        g.setLineWidth(1.5);
        g.strokeRoundRect(x - 15, y - 12, 30, 24, 6, 6);
        g.setFill(Color.web("#ffe9ec"));
        g.fillRect(x - 2.5, y - 8, 5, 16);
        g.fillRect(x - 8, y - 2.5, 16, 5);
        g.setFill(Color.web("#7d2b31"));
        g.fillRect(x - 6, y - 17, 12, 5);
    }

    /** 名称标签：与提示框同色系的小牌子，画在物品下方。 */
    private void drawPickupLabel(GraphicsContext g, String text, double centerX, double topY) {
        double width = 18 + text.length() * 13.0;
        double x = Math.max(8, Math.min(centerX - width / 2.0, AppConfig.VIEW_WIDTH - width - 8));
        g.setFill(Color.rgb(8, 6, 12, .86));
        g.fillRoundRect(x, topY, width, 22, 7, 7);
        g.setStroke(Color.rgb(242, 210, 122, .8));
        g.setLineWidth(1.2);
        g.strokeRoundRect(x, topY, width, 22, 7, 7);
        g.setFill(Color.web("#ffeec2"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));
        g.fillText(text, x + 9, topY + 16);
    }

    /** 商店商品的价格牌：买得起显示金色，买不起显示灰红色并写明状态。 */
    private void drawPriceTag(GraphicsContext g, GameSession session, Pickup pickup, int price) {
        boolean affordable = session.canAfford(pickup);
        boolean selected = session.isShopOfferSelected(pickup);
        String text = pickup.displayName() + "  " + price + " 金币";
        double width = 26 + text.length() * 13.0;
        double x = pickup.x() - width / 2.0;
        double y = pickup.y() + 30;
        g.setFill(Color.rgb(8, 6, 12, selected ? 0.95 : 0.82));
        g.fillRoundRect(x, y, width, 22, 7, 7);
        g.setStroke(selected ? Color.web("#fff2b0")
                : affordable ? Color.web("#f0c86e") : Color.web("#8a5a60"));
        g.setLineWidth(selected ? 2.5 : 1.5);
        g.strokeRoundRect(x, y, width, 22, 7, 7);
        g.setFill(affordable ? Color.web("#ffe6a6") : Color.web("#c98f93"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));
        g.fillText(text, x + 13, y + 16);
        if (selected) {
            g.setFill(Color.web("#fff2b0"));
            g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 12));
            g.fillText(affordable ? "再按 E 确认" : "金币不足", x - 2, y + 40);
        }
    }

    private void drawInteractionPrompt(GraphicsContext g, GameSession session) {
        String prompt = session.getInteractionPrompt();
        if (prompt.isEmpty()) return;
        Player p = session.getPlayer();
        boolean confirming = prompt.startsWith("E  确认") || prompt.startsWith("金币不足");
        double width = 24 + prompt.length() * 13.0;
        // 提示框贴着角色，但不能顶出画布：商店确认文案比旧提示长不少。
        double x = Math.max(8, Math.min(p.getX() + 24, AppConfig.VIEW_WIDTH - width - 8));
        double y = Math.max(8, p.getY() - 58);
        g.setFill(Color.rgb(8, 6, 12, .92));
        g.fillRoundRect(x, y, width, 32, 8, 8);
        g.setStroke(confirming ? Color.web("#ffd766") : Color.web("#f2d27a"));
        g.setLineWidth(confirming ? 2.5 : 1.0);
        g.strokeRoundRect(x, y, width, 32, 8, 8);
        g.setFill(confirming ? Color.web("#fff6cf") : Color.web("#fff0bb"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 14));
        g.fillText(prompt, x + 12, y + 21);
    }

    /**
     * 满栏拾取/购买时的换装面板。
     *
     * <p>版面上分四段：标题 → 这次要装上的是什么 → 三个可替换的槽位 → 操作提示。
     *
     * <p>每行按固定栏位排版（键位牌 / 图标 / 名字），栏位之间留出明确间距：
     * 上一版把图标和名字分别放在 50 与 84，图标宽 22 像素再加上视觉留白就贴到字上了，
     * 看起来像两件东西挤在一起。同名件在这一版仍然标「×N」——这里要按编号选替换哪一格，
     * 三行并排显示同名装备时必须能区分“第几件”，否则玩家不知道该按哪个键。
     *
     * <p>面板之外压了一层暗幕：换装是即时战场上的决策，压暗背景能让视线落在三个槽位上。
     */
    private void drawEquipmentSelection(GraphicsContext g, GameSession session) {
        Pickup incoming = session.getPendingEquipment();
        if (incoming == null) return;
        Player player = session.getPlayer();
        int price = session.getPendingEquipmentPrice();
        boolean purchase = session.isPendingEquipmentPurchase();
        boolean affordable = !purchase || player.getCoins() >= price;
        EquipmentType incomingType = EquipmentType.values()[Math.floorMod(
                incoming.amount(), EquipmentType.values().length)];

        double panelWidth = 540.0;
        // 版面高度由内容推导，避免再出现“提示文字压在第三行上”：
        // 标题 44 → 说明块 58 → 三行装备 58+3×(40+8) → 提示 52，合计 268，取 282 留余量。
        double rowHeight = 40.0;
        double rowGap = 8.0;
        double rowsTop = 116.0;
        double footerTop = rowsTop + 3 * (rowHeight + rowGap) + 10.0;
        double panelHeight = footerTop + 52.0;
        double x = AppConfig.VIEW_WIDTH / 2.0 - panelWidth / 2.0;
        double y = AppConfig.VIEW_HEIGHT / 2.0 - panelHeight / 2.0;
        double centerX = AppConfig.VIEW_WIDTH / 2.0;

        g.setFill(Color.rgb(3, 2, 7, .62));
        g.fillRect(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        g.setFill(Color.rgb(6, 5, 12, .96));
        g.fillRoundRect(x, y, panelWidth, panelHeight, 16, 16);
        g.setStroke(Color.web("#f2d27a"));
        g.setLineWidth(2.0);
        g.strokeRoundRect(x, y, panelWidth, panelHeight, 16, 16);
        // 顶部一道金色细线：纯黑底上只靠描边会显得发闷，加一条亮线把标题托起来。
        g.setStroke(Color.rgb(242, 210, 122, .38));
        g.setLineWidth(1.0);
        g.strokeLine(x + 18, y + 44, x + panelWidth - 18, y + 44);

        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#fff6d8"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 22));
        g.fillText("装备栏已满", centerX, y + 31);

        // 要装上的那一件：图标 + 标题。这里有个容易踩的坑——本方法前面把对齐设成了 CENTER，
        // 所以 fillText 的 x 是**文字中心**而不是左边界。上一版按“左边界”去算图标位置，
        // 实际文字左缘比图标还靠左半个标题宽，于是直接压在图标上。
        // 现在交给 layoutIconWithText 把文字宽度量准后再定位（见该方法的说明）。
        Font headlineFont = Font.font("Microsoft YaHei UI", FontWeight.BOLD, 15);
        g.setFont(headlineFont);
        String headline = purchase
                ? "花 " + price + " 金币买下「" + incomingType.displayName() + "」"
                : "拾取「" + incomingType.displayName() + "」";
        double iconSize = 30.0;
        double iconGap = 18.0;
        // 标题先把宽度限制在「面板内宽 - 图标 - 间距」，再据此定位，长文案（带价格的那种）
        // 会先被截短，不会把整块挤到面板外或压回图标上。
        String fitted = fitText(headline, panelWidth - 70.0 - iconSize - iconGap, headlineFont);
        TextLayout.IconText layout =
                TextLayout.iconWithText(centerX, iconSize, iconGap, fitted, headlineFont);
        drawEquipmentIcon(g, incomingType, layout.iconX(), y + 56, iconSize);
        g.setFill(Color.web("#ffe9b0"));
        g.fillText(fitted, layout.textCenterX(), y + 78);

        g.setFont(Font.font("Microsoft YaHei UI", 11));
        g.setFill(Color.web("#bcb0c8"));
        // 说明行按“面板内宽”实测截断，不再按字数猜：中英混排的字宽差异很大。
        g.fillText(fitText(incomingType.description(), panelWidth - 56.0, Font.font("Microsoft YaHei UI", 11)),
                centerX, y + 100);

        // 三个槽位：键位牌 / 图标 / 名字，三栏固定位置，栏与栏之间留白。
        //
        // 这一段的文字统一改成左对齐：面板整体是 CENTER 对齐，行内文字若也跟着居中，
        // x 就变成“文字中心”，短名字会往左漂、长名字会往右伸，怎么调栏位都对不齐图标。
        // 左对齐 + 实测宽度之后，图标右缘与文字左缘之间的间距才是真正固定的。
        double rowX = x + 28;
        double rowWidth = panelWidth - 56;
        double badgeX = rowX + 12;
        double iconX = rowX + 66;
        double nameX = iconX + 30.0 + 20.0;
        double metaFontSize = 9;
        Font metaFont = Font.font("Microsoft YaHei UI", metaFontSize);
        for (int slot = 0; slot < player.equipmentCapacity(); slot++) {
            double rowY = y + rowsTop + slot * (rowHeight + rowGap);
            boolean occupied = slot < player.getEquipment().size();
            g.setFill(Color.rgb(30, 24, 44, .96));
            g.fillRoundRect(rowX, rowY, rowWidth, rowHeight, 9, 9);
            g.setStroke(occupied ? Color.web("#b48ef0") : Color.rgb(104, 97, 116, 0.7));
            g.setLineWidth(1.0);
            g.strokeRoundRect(rowX, rowY, rowWidth, rowHeight, 9, 9);

            // 行内一切文字都用左对齐，键位牌与名字各占自己那一栏。
            g.setTextAlign(TextAlignment.LEFT);

            // 键位牌固定在行首：玩家扫一眼左边就知道该按哪个键。
            g.setFill(occupied ? Color.rgb(88, 66, 128, .98) : Color.rgb(38, 33, 52, .92));
            g.fillRoundRect(badgeX, rowY + 7, 40, rowHeight - 14, 6, 6);
            g.setFill(occupied ? Color.web("#fff6d4") : Color.web("#89819a"));
            g.setFont(Font.font("Consolas", FontWeight.BOLD, 15));
            g.fillText("[" + (slot + 1) + "]", badgeX + 8, rowY + rowHeight / 2.0 + 5.0);

            if (!occupied) {
                g.setFill(Color.web("#89819a")); g.setFont(Font.font("Microsoft YaHei UI", 12));
                g.fillText("空槽", nameX, rowY + rowHeight / 2.0 + 4.0);
                continue;
            }
            EquipmentType equipped = player.getEquipment().get(slot);
            drawEquipmentIcon(g, equipped, iconX, rowY + 5, 30);
            int copies = player.equipmentCount(equipped);
            Font nameFont = Font.font("Microsoft YaHei UI", FontWeight.BOLD, 14);
            String label = equipped.displayName() + (copies > 1 ? " ×" + copies : "");
            // 名字与右侧的「同名叠加」注释共用一行，各自的可用宽度都实测过。
            double metaWidth = copies > 1 ? textWidth("同名叠加", metaFont) + 10.0 : 0.0;
            g.setFill(Color.web("#f6f0ff")); g.setFont(nameFont);
            double nameRoom = rowX + rowWidth - 14.0 - nameX - metaWidth;
            g.fillText(fitText(label, nameRoom, nameFont), nameX, rowY + rowHeight / 2.0 + 5.0);
            if (copies > 1) {
                g.setFill(Color.web("#c0a8dc")); g.setFont(metaFont);
                g.fillText("同名叠加", rowX + rowWidth - 14.0 - metaWidth + 10.0, rowY + rowHeight / 2.0 + 3.0);
            }
        }
        // 下面几行提示本来就在 CENTER 对齐下画的，恢复回去。
        g.setTextAlign(TextAlignment.CENTER);

        // 操作提示：操作键写亮，语义后缀写暗；「换下的会留在地上」这类解释交给
        // 地面上的实物去说明，面板里不再重复一遍。
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 14));
        g.setFill(Color.web("#f0e6cc"));
        g.fillText("按 1 / 2 / 3 替换对应装备", centerX, y + footerTop + 12.0);
        g.setFont(Font.font("Microsoft YaHei UI", 12));
        if (!affordable) {
            g.setFill(Color.web("#f2838a"));
            g.fillText("金币不足：需 " + price + "，当前 " + player.getCoins(), centerX, y + footerTop + 34.0);
        } else {
            g.setFill(Color.web("#a99bb2"));
            g.fillText("按 ESC 取消", centerX, y + footerTop + 34.0);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    /**
     * 生命条与护盾条。
     *
     * <p>三点约束，缺一个就会读错：
     * <ul>
     *   <li><b>不占满整行</b>：条长只有 {@link GameConfig#HUD_HEALTH_BAR_WIDTH}，
     *       右端留给同一行的层数/难度文字，也不至于让“还差多少才满”变得看不出来；</li>
     *   <li><b>分段 + 数字</b>：每 {@link GameConfig#HUD_HEALTH_SEGMENT_VALUE} 点一段画一根刻度，
     *       条内直接写「当前/上限」，掉 7 点血也读得出，不必再靠像素长度猜；</li>
     *   <li><b>护盾叠在血条下沿</b>：护盾是临时生命值，不是第二条并列的血。
     *       它的长度按剩余点数的绝对值画（30 点盾 = 条长的 30%），
     *       并夹在血条的已填充范围内——盾再多也不会甩出血条之外。</li>
     * </ul>
     */
    private void drawHealthBar(GraphicsContext g, Player player) {
        // 条跟着信息面板一起定尺寸；剩余宽度留给右侧的盾量胶囊。
        double x = HUD_VALUE_X;
        double y = HUD_Y + 12;
        double width = GameConfig.HUD_HEALTH_BAR_WIDTH * 0.85;
        double height = GameConfig.HUD_HEALTH_BAR_HEIGHT * 0.87;
        double ratio = Math.max(0.0, Math.min(1.0, player.getHp() / (double) player.maxHp()));
        double filled = width * ratio;

        g.setFill(Color.rgb(0, 0, 0, 0.72));
        g.fillRoundRect(x, y, width, height, 5, 5);
        // 低血时条体本身变暗红，配合条内数字一起给“快死了”的信号。
        Color blood = ratio > 0.5 ? Color.web("#d75b54")
                : ratio > 0.25 ? Color.web("#c4453f") : Color.web("#8e2b2b");
        if (filled > 0.5) {
            g.setFill(blood);
            g.fillRoundRect(x, y, filled, height, 5, 5);
        }
        // 每格一根刻度：20 点一格，正好 5 格，和旧版 5 颗心的读法对得上。
        int segments = Math.max(1, (player.maxHp() + GameConfig.HUD_HEALTH_SEGMENT_VALUE - 1)
                / Math.max(1, GameConfig.HUD_HEALTH_SEGMENT_VALUE));
        g.setStroke(Color.rgb(0, 0, 0, 0.62));
        g.setLineWidth(1.0);
        for (int i = 1; i < segments; i++) {
            double divider = x + width * i / segments;
            g.strokeLine(divider, y + 1.5, divider, y + height - 1.5);
        }
        g.setStroke(Color.rgb(255, 255, 255, 0.28));
        g.strokeRoundRect(x, y, width, height, 5, 5);

        // 盾是独立的一小截胶囊，排在血条右边。
        //
        // 胶囊宽度按「护盾上限 / 生命上限」定，填充按「当前盾 / 护盾上限」——两把尺子必须分开：
        // 旧版把填充算成 当前盾/生命上限，却把胶囊画成固定 30% 宽，于是 30 点盾配 100 点血时
        // 填充只能走到胶囊的 100%，看起来像"满格"，但只要护盾上限不是恰好 30（相位容器 +12、
        // 或者生命上限被心核抬高）就永远差一截，玩家会以为开局没给满盾。
        if (player.getMaxShield() > 0.0) {
            double shieldWidth = Math.max(16.0, width * Math.min(0.30, player.getMaxShield() / Math.max(1.0, player.maxHp())));
            double shieldX = x + width + 6.0;
            g.setFill(Color.rgb(0, 0, 0, 0.72));
            g.fillRoundRect(shieldX, y, shieldWidth, height, 5, 5);
            double shieldFilled = shieldWidth * Math.max(0.0, Math.min(1.0, player.getShieldRatio()));
            if (shieldFilled > 0.5) {
                g.setFill(Color.rgb(218, 224, 232, 0.92));
                g.fillRoundRect(shieldX, y, shieldFilled, height, 5, 5);
            }
            g.setStroke(Color.rgb(255, 255, 255, 0.34));
            g.setLineWidth(1.0);
            g.strokeRoundRect(shieldX, y, shieldWidth, height, 5, 5);
        }

        // 血量数字居中压在条上：左对齐时数字和左侧填充边界一起动，扫一眼很难读出比例。
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#fff3f4"));
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        g.fillText(player.getHp() + "/" + player.maxHp(), x + width / 2.0, y + height - 3.0);
        g.setTextAlign(TextAlignment.LEFT);
    }

    /** 伤害点数文本：整数不拖小数点，半整数（4.5）才显一位小数。 */
    private static String formatPoints(double value) {
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.valueOf((int) Math.rint(value))
                : String.format("%.1f", value);
    }

    /**
     * 受击飘字：玩家挨打与敌人掉血共用一条通道。
     *
     * <p>护盾全挡下的那一下按护盾色写「盾」，其余按攻击所属的相位上色
     * （光界攻击金色、影界紫色、无属性物理用灰白），玩家能直接从数字颜色读出
     * “这一下是哪种攻击、有没有打穿护盾”。
     */
    private void drawDamageFlashes(GraphicsContext g, GameSession session) {
        if (session.getDamageFlashes().isEmpty()) return;
        g.setTextAlign(TextAlignment.CENTER);
        for (EnemySystem.DamageFlash flash : session.getDamageFlashes()) {
            double life = Math.max(0.0, Math.min(1.0, flash.remaining() / DAMAGE_FLASH_TIME));
            double alpha = life > 0.6 ? 1.0 : Math.max(0.0, life / 0.6);
            boolean shielded = flash.onShield();
            Color color = shielded ? SHIELD_BLUE : switch (flash.type()) {
                case LIGHT -> LIGHT_GOLD;
                case SHADOW -> Color.web("#c48cff");
                case PHYSICAL -> Color.web("#e6e0ea");
            };
            double scale = shielded ? 1.0 : 1.0 + (1.0 - life) * 0.35;
            g.setFont(Font.font("Consolas", FontWeight.BOLD, 17 * scale));
            g.setFill(Color.color(0.0, 0.0, 0.0, alpha * 0.7));
            g.fillText(shielded ? "盾 " + formatPoints(flash.value()) : formatPoints(flash.value()),
                    flash.x() + 1, flash.y() - (1.0 - life) * 26.0 + 1);
            g.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            g.fillText(shielded ? "盾 " + formatPoints(flash.value()) : formatPoints(flash.value()),
                    flash.x(), flash.y() - (1.0 - life) * 26.0);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    /**
     * 装备栏：三行竖排，每行「数字牌 + 独立图标 + 名字」。
     *
     * <p>旧版把三件装备横着挤进 46×46 的格子里，名字根本没地方写，玩家看不出装了什么。
     * 竖排之后每行有 265 像素的宽度可用，名字写得下，面板本身也只用 285×126。
     *
     * <p>同名装备不再标「×N」：占了两格就画两行，件数本来就一眼能看出来，再标一遍是噪音。
     * 真正需要区分“第几件”的地方是换装面板——那里要按编号替换，所以那边保留 ×N。
     *
     * <p>鼠标悬停在某一行上时，底部的效果行换成那件装备的说明。命中判定用**该行自己的矩形**：
     * 之前用「距行中心 78 像素」的圆，三行间距只有 30 像素，圆与圆大幅重叠，
     * 鼠标停在一行上会把相邻行也算成悬停目标——谁在后谁赢，于是永远只显示最后那一件。
     */
    private void drawEquipmentBar(GraphicsContext g, GameSession session) {
        Player player = session.getPlayer();
        int capacity = player.equipmentCapacity();
        double height = EQUIP_ROW_HEIGHT + 6.0 + capacity * EQUIP_ROW_HEIGHT
                + (capacity - 1) * EQUIP_ROW_GAP + 22.0;

        g.setFill(Color.rgb(4, 4, 8, .78));
        g.fillRoundRect(EQUIP_PANEL_X, EQUIP_PANEL_Y, EQUIP_PANEL_WIDTH, height, 10, 10);
        g.setStroke(Color.rgb(169, 135, 230, 0.42));
        g.setLineWidth(1.0);
        g.strokeRoundRect(EQUIP_PANEL_X, EQUIP_PANEL_Y, EQUIP_PANEL_WIDTH, height, 10, 10);

        // 标题行：左边写清“几格”，右边给出丢弃操作，省下一整行高度。
        // 战斗中丢弃被禁用，标题改成红字直接说明，免得玩家以为按键坏了。
        boolean inCombat = session.isInCombat();
        g.setFill(Color.web("#e4d9f2")); g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 12));
        g.fillText("装备 " + capacity + " 格", EQUIP_PANEL_X + 12, EQUIP_PANEL_Y + 18);
        if (inCombat) {
            g.setFill(Color.web("#d98a92")); g.setFont(Font.font("Microsoft YaHei UI", 10));
            g.fillText("战斗中无法丢弃", EQUIP_PANEL_X + 78, EQUIP_PANEL_Y + 18);
        } else {
            g.setFill(Color.web("#a396b2")); g.setFont(Font.font("Microsoft YaHei UI", 10));
            g.fillText("按 " + slotKeys(capacity) + " 丢弃对应格", EQUIP_PANEL_X + 78, EQUIP_PANEL_Y + 18);
        }

        // 悬停目标唯一：先判定鼠标落在哪一行，再决定要不要显示说明。
        int hoveredSlot = hoveredSlot(session, capacity);
        EquipmentType hovered = hoveredSlot >= 0 && hoveredSlot < player.getEquipment().size()
                ? player.getEquipment().get(hoveredSlot) : null;

        for (int slot = 0; slot < capacity; slot++) {
            double rowY = EQUIP_PANEL_Y + EQUIP_ROW_HEIGHT + 7.0 + slot * (EQUIP_ROW_HEIGHT + EQUIP_ROW_GAP);
            boolean occupied = slot < player.getEquipment().size();
            EquipmentType item = occupied ? player.getEquipment().get(slot) : null;
            boolean hover = slot == hoveredSlot && item != null;

            g.setFill(hover ? Color.rgb(56, 43, 78, .96) : Color.rgb(24, 20, 34, .94));
            g.fillRoundRect(EQUIP_PANEL_X + 8, rowY, EQUIP_PANEL_WIDTH - 16, EQUIP_ROW_HEIGHT, 7, 7);
            g.setStroke(hover ? Color.web("#e0c8fa")
                    : occupied ? Color.rgb(180, 146, 240, 0.58) : Color.rgb(104, 97, 116, 0.50));
            g.setLineWidth(hover ? 1.6 : 1.0);
            g.strokeRoundRect(EQUIP_PANEL_X + 8, rowY, EQUIP_PANEL_WIDTH - 16, EQUIP_ROW_HEIGHT, 7, 7);

            // 数字牌：和替换面板里的 [1] [2] [3] 是同一套编号，玩家能把两处对上。
            g.setFill(occupied ? Color.rgb(74, 57, 108, .98) : Color.rgb(34, 30, 46, .9));
            g.fillRoundRect(EQUIP_PANEL_X + 12, rowY + 5, 19, 18, 4, 4);
            g.setFill(occupied ? Color.web("#fff6d4") : Color.web("#89819a"));
            g.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
            g.fillText(String.valueOf(slot + 1), EQUIP_PANEL_X + 19, rowY + 19);

            if (item == null) {
                g.setFill(Color.web("#797183")); g.setFont(Font.font("Microsoft YaHei UI", 11));
                g.fillText("空槽", EQUIP_PANEL_X + 42, rowY + 19);
                continue;
            }
            drawEquipmentIcon(g, item, EQUIP_PANEL_X + 36, rowY + 2, 24);
            g.setFill(Color.web("#f4eeff")); g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));
            g.fillText(item.displayName(), EQUIP_PANEL_X + 68, rowY + 19);
        }

        // 效果行：悬停时显示该装备说明，否则只报“装备贡献了多少攻击”。
        // 加成写在装备栏、基础值写在信息面板，两个面板各说一半，玩家才分得清来源。
        Font footerFont = Font.font("Microsoft YaHei UI", 10);
        g.setFont(footerFont);
        if (hovered != null) {
            g.setFill(Color.web("#ded0ea"));
            g.fillText(fitText(hovered.displayName() + "：" + hovered.description(),
                            EQUIP_PANEL_WIDTH - 24.0, footerFont),
                    EQUIP_PANEL_X + 12, EQUIP_PANEL_Y + height - 8.0);
        } else {
            g.setFill(Color.web("#a396b2"));
            g.fillText("伤害 " + player.getDamageText() + "（装备 +"
                            + Math.round(player.getEquipmentDamageBonus() * 100) + "%）　·　悬停查看效果",
                    EQUIP_PANEL_X + 12, EQUIP_PANEL_Y + height - 8.0);
        }
    }

    /**
     * 鼠标当前停在第几行装备上；不在装备栏范围内时返回 -1。
     *
     * <p>用行矩形而不是距离：行与行之间只隔 4 像素，任何“半径”写法都会同时命中两行。
     */
    private int hoveredSlot(GameSession session, int capacity) {
        double aimX = session.getAimX();
        double aimY = session.getAimY();
        if (aimX < EQUIP_PANEL_X || aimX > EQUIP_PANEL_X + EQUIP_PANEL_WIDTH) return -1;
        for (int slot = 0; slot < capacity; slot++) {
            double rowY = EQUIP_PANEL_Y + EQUIP_ROW_HEIGHT + 7.0 + slot * (EQUIP_ROW_HEIGHT + EQUIP_ROW_GAP);
            if (aimY >= rowY && aimY <= rowY + EQUIP_ROW_HEIGHT) return slot;
        }
        return -1;
    }

    /** 槽位快捷键文本，例如「1 / 2 / 3」；槽位数变化时说明文字自动跟上。 */
    private static String slotKeys(int capacity) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= capacity; i++) {
            if (i > 1) text.append(" / ");
            text.append(i);
        }
        return text.toString();
    }

    /**
     * 实测一段文字在当前字体下的渲染宽度（像素）。
     *
     * @see TextLayout#width(String, Font)
     */
    private static double textWidth(String text, Font font) {
        return TextLayout.width(text, font);
    }

    /** 按可用宽度实测截断：放不下时从尾部砍字并补省略号，保证不会溢出版面。 */
    private static String fitText(String text, double maxWidth, Font font) {
        return TextLayout.fit(text, maxWidth, font);
    }

    private void drawAim(GraphicsContext g, GameSession session, boolean light) {
        Player player = session.getPlayer();
        double dx = session.getAimX() - player.getX();
        double dy = session.getAimY() - player.getY();
        double length = Math.hypot(dx, dy);
        if (length < 0.0001) return;
        double cursorX = player.getX() + dx / length * 50.0;
        double cursorY = player.getY() + dy / length * 50.0;
        Color domain = light ? LIGHT_GOLD : SHADOW_VIOLET;
        g.setStroke(Color.color(domain.getRed(), domain.getGreen(), domain.getBlue(), 0.48));
        g.setLineWidth(1.0);
        g.strokeLine(player.getX(), player.getY(), cursorX, cursorY);
        g.strokeRect(cursorX - 5.0, cursorY - 5.0, 10.0, 10.0);
    }

    private void drawFloor(GraphicsContext g, boolean light) {
        g.setFill(light ? Color.web("#100f12") : Color.web("#08070d"));
        g.fillRect(0, 0, AppConfig.VIEW_WIDTH, AppConfig.VIEW_HEIGHT);
        g.setFill(light ? Color.web("#17151a") : Color.web("#0e0b16"));
        for (int y = 0; y < AppConfig.VIEW_HEIGHT; y += 16) {
            for (int x = (y / 16 % 2) * 16; x < AppConfig.VIEW_WIDTH; x += 32) {
                g.fillRect(x, y, 16, 16);
            }
        }
    }

    private void drawRoom(GraphicsContext g, GameSession session, boolean light) {
        Room room = session.getNavigation().getCurrentRoom();
        Color border = light ? Color.web("#c09145") : Color.web("#7a4eb0");
        if (!outlinedAreas.equals(room.areas())) {
            outlinedAreas = List.copyOf(room.areas());
            roomOutline = new java.awt.geom.Area();
            for (RoomArea area : outlinedAreas) {
                roomOutline.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(
                        area.x(), area.y(), area.width(), area.height())));
            }
        }
        g.save();
        roomPath(g);
        g.clip();
        // 世界坐标锚定的铺装，不随组成房间的矩形重新起排。
        g.setFill(new javafx.scene.paint.ImagePattern(floorTexture(light), 0, 0, 384, 384, false));
        g.fillRect(room.minX(), room.minY(), room.maxX() - room.minX(), room.maxY() - room.minY());
        // 柔和的中央照明与沿真实外墙的积灰阴影，为地面增加深度。
        g.setFill(new RadialGradient(0, 0, 0.5, 0.45, 0.7, true, CycleMethod.NO_CYCLE,
                new Stop(0, light ? Color.rgb(220, 185, 119, 0.045) : Color.rgb(151, 125, 210, 0.045)),
                new Stop(0.55, Color.TRANSPARENT), new Stop(1, Color.rgb(0, 0, 0, 0.20))));
        g.fillRect(room.minX(), room.minY(), room.maxX() - room.minX(), room.maxY() - room.minY());
        roomPath(g);
        for (int width = 48; width >= 16; width -= 16) {
            g.setStroke(Color.rgb(0, 0, 0, 0.065));
            g.setLineWidth(width);
            g.stroke();
        }
        g.restore();
        roomPath(g);
        g.setStroke(Color.rgb(0, 0, 0, 0.72));
        g.setLineWidth(14);
        g.stroke();
        g.setStroke(border);
        g.setLineWidth(4);
        g.stroke();

        for (Direction direction : Direction.values()) {
            if (room.hasDoor(direction)) {
                drawDoor(g, room, direction, room.isDoorOpen(direction), light);
            }
        }
    }

    private void drawDoor(GraphicsContext g, Room room, Direction direction, boolean open, boolean light) {
        double half = com.phantomcorridor.config.RoomConfig.DOOR_HALF_WIDTH;
        double center = room.doorCenter(direction);
        Color portal = open ? (light ? Color.web("#ffe08a") : Color.web("#bd78ff")) : Color.web("#d84b55");
        g.setFill(Color.web("#05050a"));
        if (direction == Direction.NORTH || direction == Direction.SOUTH) {
            double y = direction == Direction.NORTH ? room.minY() - 9 : room.maxY() - 9;
            g.fillRect(center - half, y, half * 2, 18);
            g.setFill(portal);
            for (double x = center - half + 6; x < center + half - 6; x += 16) g.fillRect(x, y + 5, 10, 8);
            g.fillRect(center - 5, y + (direction == Direction.NORTH ? 18 : -10), 10, 10);
        } else {
            double x = direction == Direction.WEST ? room.minX() - 9 : room.maxX() - 9;
            g.fillRect(x, center - half, 18, half * 2);
            g.setFill(portal);
            for (double y = center - half + 6; y < center + half - 6; y += 16) g.fillRect(x + 5, y, 8, 10);
            g.fillRect(x + (direction == Direction.WEST ? 18 : -10), center - 5, 10, 10);
        }
    }

    /**
     * 相位墙：两界共有墙画成灰色，光/影专属墙按所属世界着色。
     *
     * <p>参数 {@code light} 已经不需要了——判断某面墙当前是否生效用的是
     * {@code session.getPlayer().getCurrentWorld()}，多传一个布尔值反而容易让人以为
     * 可以画“另一个世界”的墙。按注释保留旧签名备查。
     */
    private void drawPhaseWalls(GraphicsContext g, GameSession session /*, boolean light */) {
        for (Wall wall : session.getNavigation().getCurrentRoom().walls()) {
            boolean active = wall.activeIn(session.getPlayer().getCurrentWorld());
            Color color = wall.world() == null ? Color.web("#85808a")
                    : wall.world() == WorldType.LIGHT ? LIGHT_GOLD : SHADOW_VIOLET;
            drawPhaseWall(g, wall.x(), wall.y(), wall.width(), wall.height(), color, active ? 0.86 : 0.18);
        }
    }

    private void drawMiniMap(GraphicsContext g, GameSession session, boolean light) {
        // 波次之间敌人列表会暂时为空，但房间仍处于战斗状态；
        // 必须等全部波次完成后才恢复小地图，否则玩家会误以为可以离开。
        if (!session.getEnemies().isRoomCleared()) return;
        Room current = session.getNavigation().getCurrentRoom();
        double panelSize = MINI_MAP_PANEL_SIZE;
        double panelX = MINI_MAP_X;
        double panelY = MINI_MAP_Y;
        double originX = panelX + panelSize / 2.0;
        double originY = panelY + panelSize / 2.0;
        double scale = MINI_MAP_SCALE;
        g.save();
        g.beginPath(); g.rect(panelX, panelY, panelSize, panelSize); g.closePath(); g.clip();
        g.setFill(Color.rgb(3, 3, 7, 0.88));
        g.fillRect(panelX, panelY, panelSize, panelSize);
        g.setStroke(light ? Color.web("#76572d") : Color.web("#533478"));
        g.setLineWidth(3); g.strokeRect(panelX, panelY, panelSize, panelSize);

        // 连通性：细线把相邻的已发现房间连起来；当前房间朝向的、已经打开的门用亮线标出。
        Color openLink = light ? Color.web("#e8bd68") : Color.web("#b98cff");
        for (Room room : session.getNavigation().getMap().rooms()) {
            if (!room.isDiscovered()) continue;
            for (var edge : room.neighbors().entrySet()) {
                Room neighbor = session.getNavigation().getMap().room(edge.getValue());
                if (!neighbor.isDiscovered() || room.id() > neighbor.id()) continue;
                boolean usable = (room == current && room.isDoorOpen(edge.getKey()))
                        || (neighbor == current && neighbor.isDoorOpen(edge.getKey().opposite()));
                g.setStroke(usable ? openLink : Color.rgb(212, 202, 224, 0.40));
                g.setLineWidth(usable ? 2.4 : 1.2);
                g.strokeLine(originX + (room.mapX() - current.mapX()) * scale,
                        originY + (room.mapY() - current.mapY()) * scale,
                        originX + (neighbor.mapX() - current.mapX()) * scale,
                        originY + (neighbor.mapY() - current.mapY()) * scale);
            }
        }

        for (Room room : session.getNavigation().getMap().rooms()) {
            if (!room.isDiscovered()) continue;
            double x = originX + (room.mapX() - current.mapX()) * scale;
            double y = originY + (room.mapY() - current.mapY()) * scale;
            if (room == current) {
                // 当前房间用深底 + 亮描边，中间留给类型图标（没有图标时留一个白点）。
                g.setFill(Color.rgb(16, 12, 22, 0.96));
                g.fillRect(x - 9, y - 9, 18, 18);
                g.setStroke(light ? Color.web("#fff0a8") : Color.web("#d5a0ff"));
                g.setLineWidth(3.0);
                g.strokeRect(x - 9, y - 9, 18, 18);
                if (!drawRoomGlyph(g, room, x, y, 15.0)) {
                    g.setFill(Color.web("#ffffff"));
                    g.fillRect(x - 2.5, y - 2.5, 5, 5);
                }
            } else if (room.isVisited()) {
                g.setFill(room.type() == RoomType.BOSS ? Color.web("#c54e58") : Color.web("#8e8995"));
                g.fillRect(x - 7, y - 7, 14, 14);
                drawRoomGlyph(g, room, x, y, 13.0);
            } else {
                g.setStroke(room.type() == RoomType.BOSS ? Color.web("#d94b5b") : Color.web("#77717f"));
                g.setLineWidth(2);
                g.strokeRect(x - 6, y - 6, 12, 12);
            }
            // 状态提示：已清空的怪物房套一圈绿框（不用再回去），还有东西可拿的房间点一颗金点。
            if (room.isDefeatedBattleRoom()) {
                g.setStroke(CLEARED_GREEN);
                g.setLineWidth(2.0);
                g.strokeRect(x - 11, y - 11, 22, 22);
            }
            if (room.hasRemainingLoot()) {
                g.setFill(LOOT_GOLD);
                g.fillOval(x + 5, y - 13, 8, 8);
                g.setStroke(Color.rgb(30, 20, 8, 0.85));
                g.setLineWidth(1.0);
                g.strokeOval(x + 5, y - 13, 8, 8);
            }
        }
        g.restore();
        drawMiniMapLegend(g, panelX, panelY + panelSize);
    }

    /**
     * 房间图标：商店「￥」、事件「?」、有未开宝箱的房间画宝箱、已清空的首领房画传送门环。
     *
     * @param glyphSize 文字图标字号：当前房间的格子更大，字号也跟着大一点
     * @return 是否画出了图标（没有图标时由调用方画“当前房间”标记）
     */
    private boolean drawRoomGlyph(GraphicsContext g, Room room, double x, double y, double glyphSize) {
        if (room.hasPortal()) {
            // 传送门：紫色圆环 + 中心亮点，比宝箱更该被看见。
            g.setStroke(Color.web("#b98cff"));
            g.setLineWidth(2.0);
            g.strokeOval(x - 7, y - 7, 14, 14);
            g.setFill(Color.web("#fff3c4"));
            g.fillOval(x - 2.5, y - 2.5, 5, 5);
            return true;
        }
        if (room.hasUnopenedChest()) {
            g.setFill(Color.web("#f0b858"));
            g.fillRect(x - 6, y - 4, 12, 9);
            g.setStroke(Color.rgb(38, 22, 6, 0.92));
            g.setLineWidth(1.0);
            g.strokeRect(x - 6, y - 4, 12, 9);
            g.strokeLine(x - 6, y - 1, x + 6, y - 1);
            g.setFill(Color.rgb(56, 28, 8, 0.95));
            g.fillRect(x - 1.5, y - 1, 3, 4);
            return true;
        }
        String glyph = switch (room.type()) {
            case SHOP -> "￥";
            case EVENT -> "?";
            default -> null;
        };
        if (glyph == null) return false;
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, glyphSize));
        g.setFill(room.type() == RoomType.SHOP ? Color.web("#ffe08a") : Color.web("#d9bcff"));
        g.fillText(glyph, x, y + glyphSize * 0.36);
        g.setTextAlign(TextAlignment.LEFT);
        return true;
    }

    private void drawMiniMapLegend(GraphicsContext g, double panelX, double legendY) {
        Color text = Color.rgb(235, 226, 242, 0.66);
        g.setFill(Color.rgb(235, 226, 242, 0.78));
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
        g.fillText("探索地图", panelX + 12, legendY + 18);

        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));
        g.setFill(Color.web("#ffe08a"));
        g.fillText("￥", panelX + 12, legendY + 40);
        g.setFill(text);
        g.setFont(Font.font("Microsoft YaHei UI", 12));
        g.fillText("商店", panelX + 27, legendY + 40);
        g.setFill(Color.web("#d9bcff"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));
        g.fillText("?", panelX + 74, legendY + 40);
        g.setFill(text);
        g.setFont(Font.font("Microsoft YaHei UI", 12));
        g.fillText("事件", panelX + 87, legendY + 40);
        g.setFill(Color.web("#f0b858"));
        g.fillRect(panelX + 134, legendY + 31, 12, 9);
        g.setStroke(Color.rgb(38, 22, 6, 0.92));
        g.setLineWidth(1.0);
        g.strokeRect(panelX + 134, legendY + 31, 12, 9);
        g.setFill(text);
        g.fillText("宝箱", panelX + 152, legendY + 40);

        g.setStroke(CLEARED_GREEN);
        g.setLineWidth(2.0);
        g.strokeRect(panelX + 12, legendY + 49, 12, 12);
        g.setFill(text);
        g.fillText("已清空", panelX + 30, legendY + 60);
        g.setFill(LOOT_GOLD);
        g.fillOval(panelX + 96, legendY + 51, 9, 9);
        g.setFill(text);
        g.fillText("有未拿取", panelX + 112, legendY + 60);
    }

    private void drawPhaseWall(GraphicsContext g, double x, double y, double width, double height,
                               Color color, double alpha) {
        g.save();
        g.setGlobalAlpha(g.getGlobalAlpha() * alpha);
        double bevel = 5;
        double[] xs = {x + bevel, x + width - bevel, x + width, x + width,
                x + width - bevel, x + bevel, x, x};
        double[] ys = {y, y, y + bevel, y + height - bevel,
                y + height, y + height, y + height - bevel, y + bevel};
        g.setFill(Color.rgb(0, 0, 0, 0.35));
        g.fillRoundRect(x + 3, y + 5, width, height, 8, 8);
        g.setFill(new LinearGradient(0, 0, 0.7, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, color.interpolate(Color.web("#45414a"), 0.65)),
                new Stop(1, Color.web("#201d29"))));
        g.fillPolygon(xs, ys, 8);
        g.setStroke(Color.web("#13111b"));
        g.setLineWidth(2);
        g.strokePolygon(xs, ys, 8);
        g.setStroke(color.interpolate(Color.WHITE, 0.2));
        g.setLineWidth(1);
        g.strokeLine(x + bevel, y + 2, x + width - bevel, y + 2);
        g.strokeLine(x + 2, y + bevel, x + 2, y + height - bevel);
        g.setFill(Color.rgb(8, 7, 14, 0.45));
        g.fillRect(x + 5, y + height - 7, width - 10, 4);
        // 长墙使用错开的石缝；中心镶嵌符文让短柱也有明确的视觉重心。
        g.setStroke(Color.rgb(8, 7, 14, 0.55));
        if (width > height * 1.5) {
            for (double px = x + 28; px < x + width - 12; px += 32)
                g.strokeLine(px, y + 5, px - 3, y + height - 9);
        } else if (height > width * 1.5) {
            for (double py = y + 28; py < y + height - 12; py += 32)
                g.strokeLine(x + 5, py, x + width - 5, py - 2);
        }
        double cx = x + width / 2, cy = y + height / 2 - 1;
        g.setFill(Color.web("#17141f"));
        g.fillPolygon(new double[]{cx, cx + 8, cx, cx - 8},
                new double[]{cy - 9, cy, cy + 9, cy}, 4);
        g.setStroke(color);
        g.strokePolygon(new double[]{cx, cx + 5, cx, cx - 5},
                new double[]{cy - 6, cy, cy + 6, cy}, 4);
        g.setFill(color.interpolate(Color.WHITE, 0.35));
        g.fillRect(cx - 1, cy - 2, 2, 4);
        g.restore();
    }

    private void drawPlayer(GraphicsContext g, Player player, boolean light) {
        if (!(light ? LIGHT_FRAMES : SHADOW_FRAMES).isEmpty()) {
            drawSpritePlayer(g, player, light);
            drawShieldAura(g, player, light);
            return;
        }
        Color domain = light ? LIGHT_GOLD : SHADOW_VIOLET;
        double x = Math.rint(player.getX());
        double y = Math.rint(player.getY());
        PlayerAnimationState state = player.getAnimationState();
        int frame = (int) (player.getAnimationTime() * 8) & 1;
        // 矢量兜底造型没有贴图可叠加，直接用半透明表现"变淡"。
        double flash = player.getHitFlash();
        if (flash > 0.0) g.setGlobalAlpha(1.0 - 0.75 * GameConfig.PLAYER_HIT_FLASH_STRENGTH * flash);
        if (state == PlayerAnimationState.DOWN) {
            g.setFill(Color.web("#17131d")); g.fillRect(x - 19, y + 4, 38, 10);
            g.setFill(domain); g.fillRect(x - 15, y, 24, 8);
            g.setFill(light ? Color.web("#f4e5bd") : Color.web("#cbb0e8")); g.fillRect(x + 9, y + 2, 9, 9);
            g.setGlobalAlpha(1.0);
            return;
        }
        // 矢量兜底造型也要认得冲刺：把它当成更快的移动，而不是站着不动。
        boolean stepping = state == PlayerAnimationState.MOVING || state == PlayerAnimationState.DASHING;
        double bob = stepping && frame == 1 ? -3 : 0;
        if (state == PlayerAnimationState.SHIFTING && frame == 1) {
            g.setFill(Color.rgb(255, 255, 255, 0.34)); g.fillRect(x - 24, y - 28, 48, 52);
        }
        g.setFill(Color.rgb(0, 0, 0, 0.55)); g.fillRect(x - 14, y + 14, 28, 7);
        g.setFill(Color.web("#15121a")); g.fillRect(x - 13, y - 15 + bob, 26, 28);
        g.setFill(domain); g.fillRect(x - 10, y - 18 + bob, 20, 7);
        g.fillRect(x - 14, y - 8 + bob, 5, 17); g.fillRect(x + 9, y - 8 + bob, 5, 17);
        g.setFill(light ? Color.web("#f4e5bd") : Color.web("#cbb0e8"));
        g.fillRect(x - 7, y - 10 + bob, 14, 12);
        g.setFill(Color.web("#18121d")); g.fillRect(x - 4, y - 6 + bob, 3, 3); g.fillRect(x + 3, y - 6 + bob, 3, 3);
        g.setFill(domain);
        double legOffset = stepping ? (frame == 0 ? 4 : -4) : 0;
        g.fillRect(x - 10 + legOffset, y + 11 + bob, 7, 10);
        g.fillRect(x + 3 - legOffset, y + 11 + bob, 7, 10);
        if (state == PlayerAnimationState.ATTACKING) {
            int fx = player.getFacingX() >= 0 ? 1 : -1;
            g.setFill(light ? Color.web("#fff2a3") : Color.web("#d398ff"));
            g.fillRect(x + fx * 14 - (fx < 0 ? 12 : 0), y - 3, 12, 7);
        }
        drawShieldAura(g, player, light);
        g.setGlobalAlpha(1.0);
    }

    /**
     * 身上还有护盾时，在角色外圈画一道相位护罩。
     *
     * <p>护盾是临时生命值，玩家必须一眼看得出“现在这层壳还在不在”，
     * 所以 HUD 的血条之外，角色本体也要有反馈：护罩随剩余量变淡，快碎时几乎看不见。
     */
    private void drawShieldAura(GraphicsContext g, Player player, boolean light) {
        if (!player.hasShield()) return;
        double ratio = player.getShieldRatio();
        double radius = 44.0 - ratio * 4.0;
        double alpha = 0.20 + ratio * 0.45;
        g.setStroke(Color.color(SHIELD_BLUE.getRed(), SHIELD_BLUE.getGreen(), SHIELD_BLUE.getBlue(), alpha));
        g.setLineWidth(2.0 + ratio * 1.6);
        g.strokeOval(player.getX() - radius, player.getY() - radius, radius * 2, radius * 2);
        g.setStroke(Color.color(SHIELD_BLUE.getRed(), SHIELD_BLUE.getGreen(), SHIELD_BLUE.getBlue(), alpha * 0.45));
        g.setLineWidth(6.0);
        g.strokeArc(player.getX() - radius - 4, player.getY() - radius - 4,
                (radius + 4) * 2, (radius + 4) * 2,
                player.getAnimationTime() * 90.0 % 360.0, 96.0, ArcType.OPEN);
    }

    private void drawSpritePlayer(GraphicsContext g, Player player, boolean light) {
        Map<String, Image[]> all = light ? LIGHT_FRAMES : SHADOW_FRAMES;
        String direction = spriteDirection(player.getFacingX(), player.getFacingY());
        boolean mirror = direction.equals("left");
        String assetDirection = mirror ? "right" : direction;
        String action = switch (player.getAnimationState()) {
            case ATTACKING -> "slash_" + (assetDirection.equals("right") ? "right" : "recover_front");
            case SHIFTING -> "cast_right";
            case DOWN -> "down_" + assetDirection;
            // 冲刺没有专属素材，用移动帧 + 拖尾表现"带着残影窜过去"。
            case MOVING, DASHING -> "move_" + assetDirection;
            default -> "idle_" + assetDirection;
        };
        Image[] frames = all.getOrDefault(action, all.getOrDefault("idle_front", new Image[0]));
        if (frames.length == 0) return;
        int frame = (player.getAnimationState() == PlayerAnimationState.IDLE
                || player.getAnimationState() == PlayerAnimationState.DOWN) ? 0
                : Math.min(frames.length - 1, (int) (player.getAnimationTime() * 10.0) % frames.length);
        Image sprite = frames[frame];
        double drawW = 160.0;
        double drawH = drawW * sprite.getHeight() / Math.max(1.0, sprite.getWidth());
        double left = Math.rint(player.getX() - drawW / 2);
        // v2 资源的视觉锚点落在胸口附近，而不是画布顶部或脚底。
        double top = Math.rint(player.getY() - drawH * 0.58);
        g.drawImage(sprite, mirror ? left + drawW : left, top, mirror ? -drawW : drawW, drawH);
        drawHitFlash(g, player, sprite, left, top, drawW, drawH, mirror);
    }

    /**
     * 受击变淡：在角色贴图上叠一层预先生成的"纯白剪影"，颜色被洗白，强度随受击剩余时间衰减。
     *
     * <p>这里刻意**不用 SCREEN / ADD 之类的混合模式**：窗口尺寸与逻辑分辨率不一致时，渲染前会
     * 对整个画布做缩放，而混合模式要求渲染管线读回目标像素；部分管线在这种情况下会把混合结果
     * 当整块区域重新合成，于是角色周围（以及画面中对比强的边缘）会出现一大片发暗、发黑的方块。
     * 换成"白剪影 + 普通 alpha 混合"之后，效果和缩放比例完全无关，任何管线都稳定。
     *
     * <p>剪影保留原贴图的 alpha，所以只覆盖角色自己占的像素：地板、敌人和拖尾都不受影响。
     */
    private void drawHitFlash(GraphicsContext g, Player player, Image sprite,
                              double left, double top, double drawW, double drawH, boolean mirror) {
        double flash = player.getHitFlash();
        if (flash <= 0.01) return;
        g.setGlobalAlpha(GameConfig.PLAYER_HIT_FLASH_STRENGTH * flash);
        Image silhouette = whiteSilhouette(sprite);
        g.drawImage(silhouette, mirror ? left + drawW : left, top, mirror ? -drawW : drawW, drawH);
        g.setGlobalAlpha(1.0);
    }

    /** 生成一张保留 alpha、颜色全白的贴图副本；同一张原图只生成一次，之后走缓存。 */
    private static Image whiteSilhouette(Image source) {
        return WHITE_SILHOUETTES.computeIfAbsent(source, image -> {
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            WritableImage silhouette = new WritableImage(width, height);
            PixelReader reader = image.getPixelReader();
            PixelWriter writer = silhouette.getPixelWriter();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    writer.setArgb(x, y, (reader.getArgb(x, y) & 0xff000000) | 0x00ffffff);
                }
            }
            return silhouette;
        });
    }

    /**
     * 冲刺拖尾：把冲刺途中按帧记录的残影铺在角色身后，越旧越透明，冲刺结束后自然消散。
     *
     * <p>残影固定取移动帧而不是当前动作帧——冲刺中角色只有"冲刺"这一个动作，
     * 与本体共用同一套素材，看起来才像同一道残影而不是另一只角色跟着跑。
     *
     * <p>素材缺失时退回矢量方块：拖尾是冲刺唯一的视觉反馈，不该因为图片加载失败就整个消失。
     */
    private void drawDashTrail(GraphicsContext g, Player player, boolean light) {
        List<DashTrailPoint> trail = player.getDashTrail();
        if (trail.isEmpty()) return;
        double directionX = player.getDashDirectionX();
        double directionY = player.getDashDirectionY();
        String direction = spriteDirection(directionX, directionY);
        boolean mirror = direction.equals("left");
        String assetDirection = mirror ? "right" : direction;
        Map<String, Image[]> all = light ? LIGHT_FRAMES : SHADOW_FRAMES;
        Image[] frames = all.getOrDefault("move_" + assetDirection, new Image[0]);
        Image sprite = frames.length == 0 ? null : frames[0];
        double drawW = 160.0;
        double drawH = sprite == null ? 0.0 : drawW * sprite.getHeight() / Math.max(1.0, sprite.getWidth());
        for (DashTrailPoint point : trail) {
            // life() 从 1（刚生成）衰减到 0（消散），所以越靠后的残影越淡。
            double alpha = GameConfig.DASH_TRAIL_ALPHA * point.life();
            if (alpha <= 0.01) continue;
            double left = Math.rint(point.x() - drawW / 2.0);
            double top = Math.rint(point.y() - drawH * 0.58);
            if (sprite != null) {
                g.setGlobalAlpha(alpha);
                g.drawImage(sprite, mirror ? left + drawW : left, top, mirror ? -drawW : drawW, drawH);
            } else {
                g.setGlobalAlpha(alpha);
                g.setFill(light ? LIGHT_GOLD : SHADOW_VIOLET);
                g.fillRect(Math.rint(point.x() - 13.0), Math.rint(point.y() - 18.0), 26, 36);
            }
        }
        g.setGlobalAlpha(1.0);
    }

    /** 角色素材的四方向（只有左向没有独立素材，绘制时用右向镜像）。 */
    private static String spriteDirection(double facingX, double facingY) {
        return facingY < -0.35 ? "back" : facingY > 0.35 ? "front"
                : facingX < 0 ? "left" : "right";
    }

    private void drawRoomAnnouncement(GraphicsContext g, GameSession session) {
        if (!session.isRoomAnnouncementVisible()) return;
        double remaining = session.getRoomAnnouncementRemaining();
        double alpha = remaining < 0.35 ? Math.max(0.0, remaining / 0.35) : 1.0;
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 24));
        g.setFill(Color.rgb(255, 239, 178, alpha));
        double announcementY = session.isFloorAnnouncement() ? AppConfig.VIEW_HEIGHT / 2.0 : 84.0;
        g.fillText("◆  " + session.getRoomAnnouncement() + "  ◆", AppConfig.VIEW_WIDTH / 2.0, announcementY);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static Map<String, Image[]> loadCharacterFrames(String palette) {
        Map<String, Image[]> result = new HashMap<>();
        String[][] specs = {
                {"idle_front", "2"}, {"idle_back", "1"}, {"idle_right", "1"},
                {"move_front", "2"}, {"move_back", "2"}, {"move_right", "4"},
                {"cast_right", "6"}, {"slash_right", "5"}, {"slash_recover_front", "6"},
                {"down_front", "1"}, {"down_back", "1"}, {"down_right", "2"}
        };
        for (String[] spec : specs) result.put(spec[0], loadSeries(palette, "characters", spec[0], Integer.parseInt(spec[1])));
        return result;
    }

    private static Image[] loadSeries(String palette, String folder, String prefix, int count) {
        List<Image> frames = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            var resource = GameRenderer.class.getResource("/com/phantomcorridor/sprites/v2/" + palette + "/" + folder + "/" + prefix + "_" + String.format("%02d", i) + ".png");
            if (resource != null) frames.add(new Image(resource.toExternalForm(), false));
        }
        return frames.toArray(Image[]::new);
    }

    private static Image loadUiImage(String name) {
        var resource = GameRenderer.class.getResource("/com/phantomcorridor/ui/" + name);
        return resource == null ? null : new Image(resource.toExternalForm(), false);
    }

    private static Map<String, Image> loadEquipmentAssets() {
        Map<String, Image> result = new HashMap<>();
        for (var item : com.phantomcorridor.model.EquipmentType.values()) {
            if (item.assetId() == null) continue;
            Image image = loadUiImage("equipment/" + item.assetId() + ".png");
            if (image != null) result.put(item.assetId(), image);
        }
        return result;
    }

    private void drawHud(GraphicsContext g, GameSession session, double fps, boolean light) {
        Color domain = light ? LIGHT_GOLD : SHADOW_VIOLET;
        Player player = session.getPlayer();

        g.setFill(Color.rgb(4, 4, 8, 0.76));
        g.fillRoundRect(HUD_X, HUD_Y, HUD_WIDTH, HUD_HEIGHT, 10, 10);
        g.setStroke(Color.color(domain.getRed(), domain.getGreen(), domain.getBlue(), 0.55));
        g.setLineWidth(1.0);
        g.strokeRoundRect(HUD_X, HUD_Y, HUD_WIDTH, HUD_HEIGHT, 10, 10);

        // 三行数值统一成「左侧小标签 + 右侧统一宽度的条形」。
        // 字号取 13、颜色取接近纯白的暖白：这块面板是战斗中最常被扫视的地方，
        // 之前 11 号灰字在深色底上要停下来辨认，反而拖慢读血线的速度。
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 13));

        g.setFill(Color.web("#fdf6e9"));
        g.fillText("生命", HUD_X + 14, HUD_Y + 25);
        drawHealthBar(g, player);

        g.setFill(Color.web("#8dc4ff"));
        g.fillText("攻击", HUD_X + 14, HUD_Y + 46);
        int chargeSlots = player.getMaxAttackCharges();
        double cellWidth = Math.min(16.5, HUD_VALUE_WIDTH / Math.max(1, chargeSlots) - 2.5);
        double cellGap = 2.5;
        for (int i = 0; i < chargeSlots; i++) {
            boolean filled = i < player.getAttackCharges();
            double x = HUD_VALUE_X + i * (cellWidth + cellGap);
            g.setFill(filled ? Color.web("#6cb4ff") : Color.rgb(255, 255, 255, 0.13));
            g.fillRoundRect(x, HUD_Y + 34, cellWidth, 10, 2, 2);
            g.setStroke(Color.color(0.45, 0.72, 1.0, filled ? 0.95 : 0.32));
            g.setLineWidth(1.0); g.strokeRoundRect(x, HUD_Y + 34, cellWidth, 10, 2, 2);
        }

        g.setFill(Color.web("#fdf6e9"));
        g.fillText(player.isShiftSlowed() ? "减速" : player.getPhaseEnergy() >= GameConfig.PHASE_ENERGY_MAX ? "强化" : "切界", HUD_X + 14, HUD_Y + 66);
        double phaseY = HUD_Y + 54;
        g.setFill(Color.rgb(255, 255, 255, 0.12));
        g.fillRoundRect(HUD_VALUE_X, phaseY, HUD_VALUE_WIDTH, 14, 7, 7);
        double ratio = player.getPhaseEnergy() / GameConfig.PHASE_ENERGY_MAX;
        g.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, LIGHT_GOLD), new Stop(1, SHADOW_VIOLET)));
        g.fillRoundRect(HUD_VALUE_X, phaseY, HUD_VALUE_WIDTH * ratio, 14, 7, 7);
        if (ratio >= 0.999) {
            g.setStroke(Color.web("#fff4bc")); g.setLineWidth(1.4);
            g.strokeRoundRect(HUD_VALUE_X - 1.5, phaseY - 1.5, HUD_VALUE_WIDTH + 3, 17, 9, 9);
        }

        // 底行缩写成「光残敌 / 影残敌」：字数少了才放得下，盾量接在金币后面同一行。
        // 盾量取整：护盾是临时生命值，小数（12.3）在这里只会占位置、读不出额外信息。
        g.setFill(Color.web("#e8dff2"));
        g.setFont(Font.font("Microsoft YaHei UI", 11));
        g.fillText((session.getEnemies().getTotalWaves() > 0
                ? "波次 " + session.getEnemies().getCurrentWave() + "/" + session.getEnemies().getTotalWaves()
                    + (session.getEnemies().isBetweenWaves() ? " 待刷新" : " 剩余 " + session.getEnemies().getEnemies().size())
                : "敌人 " + session.getEnemies().getEnemies().size())
                + "　金币 " + session.getCoins()
                + "　盾 " + Math.round(player.getShield()) + "/" + Math.round(player.getMaxShield()),
                HUD_X + 14, HUD_Y + 86);

        // 这一行只讲“角色本身”：基础攻击是固定的 1，加成归到装备栏去显示，
        // 两个面板各说一半，玩家才分得清哪一份是自己带的、哪一份是捡来的。
        g.setFill(Color.web("#a99bb2"));
        g.setFont(Font.font("Microsoft YaHei UI", 12));
        g.fillText("基础攻击 " + Player.BASE_ATTACK_DAMAGE + "　·　加成见装备栏", HUD_X + 14, HUD_Y + 114);

        drawWorldBadge(g, domain, light);
        drawEquipmentBar(g, session);
        drawBossBanner(g, session);

        g.setFill(Color.rgb(230, 220, 235, 0.28));
        g.setFont(Font.font("Consolas", 11));
        g.fillText(String.format("%.0f FPS", fps), AppConfig.VIEW_WIDTH - 150, 40);
    }

    /**
     * Boss 名与专属血条。
     *
     * <p>一层一个不同的首领之后，「我在打谁」必须一眼看得出来：名字 + 一条不受小怪干扰的长血条，
     * 直接挂在屏幕顶端。
     */
    private void drawBossBanner(GraphicsContext g, GameSession session) {
        Enemy boss = session.getBoss();
        if (boss == null) return;
        boolean light = boss.getWorld() == WorldType.LIGHT;
        Color domain = light ? LIGHT_GOLD : SHADOW_VIOLET;
        double width = 420.0;
        double x = (AppConfig.VIEW_WIDTH - width) / 2.0;
        double y = 26.0;
        g.setFill(Color.rgb(4, 4, 8, 0.78));
        g.fillRoundRect(x - 10, y - 18, width + 20, 44, 10, 10);
        g.setStroke(Color.color(domain.getRed(), domain.getGreen(), domain.getBlue(), 0.7));
        g.setLineWidth(1.4);
        g.strokeRoundRect(x - 10, y - 18, width + 20, 44, 10, 10);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#fdf6e9"));
        g.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 15));
        g.fillText(boss.getKind().displayName() + (light ? "　·　光形态" : "　·　影形态"), x + width / 2.0, y + 2);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Color.rgb(255, 255, 255, 0.14));
        g.fillRoundRect(x, y + 8, width, 8, 4, 4);
        double ratio = boss.getMaxHp() <= 0 ? 0.0 : boss.getHp() / (double) boss.getMaxHp();
        g.setFill(domain);
        g.fillRoundRect(x, y + 8, width * ratio, 8, 4, 4);
    }

    /** 右上角的光/影徽章：缩小后给小地图让出了纵向空间。 */
    private void drawWorldBadge(GraphicsContext g, Color domain, boolean light) {
        double badgeX = AppConfig.VIEW_WIDTH - 122.0;
        double badgeY = 52.0;
        double radius = 38.0;
        g.setFill(Color.rgb(4, 4, 8, 0.78));
        g.fillOval(badgeX - radius, badgeY - radius, radius * 2, radius * 2);
        g.setStroke(domain);
        g.setLineWidth(2.2);
        g.strokeOval(badgeX - radius, badgeY - radius, radius * 2, radius * 2);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(domain);
        g.setFont(Font.font("STKaiti", FontWeight.BOLD, 25));
        g.fillText(light ? "光" : "影", badgeX, badgeY + 9);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawControls(GraphicsContext g, boolean light) {
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(light ? Color.rgb(237, 210, 156, 0.56) : Color.rgb(198, 169, 230, 0.58));
        g.setFont(Font.font("Microsoft YaHei UI", 13));
        g.fillText("WASD / 方向键移动    ·    鼠标瞄准 / 左键攻击    ·    空格 闪避冲刺    ·    E 交互 / 换装    ·    1-3 丢弃装备    ·    TAB 穿梭双界    ·    ESC 取消 / 暂停",
                AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT - 24.0);
        g.setTextAlign(TextAlignment.LEFT);
    }
}
