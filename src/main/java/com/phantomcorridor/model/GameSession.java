package com.phantomcorridor.model;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.effect.WorldShiftSystem;
import com.phantomcorridor.model.entity.Player;
import com.phantomcorridor.model.combat.PlayerAttackSystem;
import com.phantomcorridor.model.combat.EnemyProjectileSystem;
import com.phantomcorridor.model.combat.EnemySystem;
import com.phantomcorridor.model.combat.SummonRift;
import com.phantomcorridor.model.dungeon.MapGenerator;
import com.phantomcorridor.model.room.RoomContentSystem;
import com.phantomcorridor.model.room.RoomNavigationSystem;
import com.phantomcorridor.model.room.Room;
import java.util.List;

/** 一局游戏的聚合状态。后续房间、敌人、掉落都从这里接入。 */
public final class GameSession {

    private final Player player = new Player(AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0);
    private final WorldShiftSystem worldShift = new WorldShiftSystem();
    private final PlayerAttackSystem attackSystem = new PlayerAttackSystem();
    private final EnemyProjectileSystem enemyProjectiles = new EnemyProjectileSystem();
    private final EnemySystem enemies = new EnemySystem();
    private final RoomNavigationSystem navigation = new RoomNavigationSystem();
    private final RoomContentSystem roomContent = new RoomContentSystem();
    private long runSeed;
    private long dungeonSeed;
    private int floor = 1;
    private Difficulty difficulty = Difficulty.NORMAL;
    private boolean runCleared;
    private double phasePulseVisibleRemaining;
    private double aimX;
    private double aimY;
    private String roomAnnouncement = "";
    private double roomAnnouncementRemaining;
    private boolean floorAnnouncement;
    // 未被引用（IDE 的 Unused 检查会报）：只被赋值、从未被读取，也没有对外暴露 getter。
    // 需要“是否在战斗中”时直接问 enemies.isRoomCleared() 即可，先注释保留。
    // private boolean combatActive;
    private boolean interactRequested;
    private boolean dashRequested;

    public void newRun() { newRun(""); }

    public void newRun(String configuredSeed) { newRun(configuredSeed, Difficulty.NORMAL); }

    /**
     * 开始新的一局。
     *
     * @param configuredSeed 开发种子（空串表示随机）
     * @param difficulty     本局难度：只影响敌人的基础属性
     */
    public void newRun(String configuredSeed, Difficulty difficulty) {
        player.reset(AppConfig.VIEW_WIDTH / 2.0, AppConfig.VIEW_HEIGHT / 2.0);
        worldShift.reset();
        runSeed = MapGenerator.parseSeed(configuredSeed);
        this.difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
        floor = 1;
        runCleared = false;
        dashRequested = false;
        aimX = player.getX() + 1.0;
        aimY = player.getY();
        startFloor();
    }

    /**
     * 生成当前层的地图并把玩家放到入口。
     *
     * <p>玩家自身的状态（生命、金币、装备、道具）跨层保留，只有地图、敌人与房间内容重新生成；
     * 每层地图种子由本局种子加层数偏移得到，所以同一种子下每层都不同但可复现。
     */
    private void startFloor() {
        dungeonSeed = runSeed + (floor - 1) * GameConfig.FLOOR_SEED_STEP;
        attackSystem.reset();
        enemyProjectiles.reset();
        enemies.reset();
        enemies.setFloor(floor);
        enemies.setDifficulty(difficulty);
        roomContent.reset(dungeonSeed, floor);
        navigation.reset(new MapGenerator().generate(dungeonSeed));
        navigation.placeAtEntrance(player);
        roomContent.enterRoom(navigation.getCurrentRoom(), player);
        enemies.enterRoom(navigation.getCurrentRoom(), dungeonSeed, player, navigation);
        // 护盾是“一层一条”的临时生命：进新一层时重置为一整条，层内打完就没有，
        // 既不会像回血那样无限续航，也保证每层开局都有一条可用的容错。
        player.refillShield();
        phasePulseVisibleRemaining = 0.0;
        // combatActive = false;   // 见字段处的说明：这个状态没有任何读取点
        roomAnnouncement = floorAnnouncement();
        roomAnnouncementRemaining = 2.6;
        floorAnnouncement = true;
    }

    /** 使用首领房里的传送门：进入下一层；已经是最后一层则通关。 */
    private void usePortal() {
        if (floor >= GameConfig.TOTAL_FLOORS) {
            runCleared = true;
            roomAnnouncement = "穿越裂隙 · 五层走尽";
            roomAnnouncementRemaining = 3.0;
            return;
        }
        floor++;
        startFloor();
    }

    public void update(double dt, double movementX, double movementY,
                       double targetX, double targetY, boolean attacking) {
        worldShift.update(dt);
        phasePulseVisibleRemaining = Math.max(0.0, phasePulseVisibleRemaining - dt);
        roomAnnouncementRemaining = Math.max(0.0, roomAnnouncementRemaining - Math.max(0.0, dt));
        if (player.getHp() <= 0 || runCleared) {
            player.updateDash(dt);
            player.updateAnimation(dt, 0.0, 0.0, false, false);
            return;
        }
        aimX = targetX;
        aimY = targetY;
        // 冲刺优先于普通移动：冲刺期间忽略方向输入，位移完全由冲刺方向决定。
        if (dashRequested) {
            dashRequested = false;
            player.tryStartDash(movementX, movementY);
        }
        if (player.isDashing()) {
            // 只走"这一段冲刺还剩的时间"：否则最后一帧会按整帧位移，固定 150 像素会随帧对齐漂移。
            double dashStep = Math.min(dt, player.getDashTimeRemaining());
            navigation.dash(player, player.getDashDirectionX(), player.getDashDirectionY(), dashStep);
            player.recordDashTrail();
        } else {
            navigation.move(player, movementX, movementY, dt);
        }
        player.updateDash(dt);
        if (navigation.consumeRoomChanged()) {
            attackSystem.clearTransientAttacks();
            Room entered = navigation.getCurrentRoom();
            roomContent.enterRoom(entered, player);
            enemies.enterRoom(entered, dungeonSeed, player, navigation);
            roomAnnouncement = roomAnnouncement(entered);
            roomAnnouncementRemaining = 2.2;
            floorAnnouncement = false;
        }
        // 房间内容只在第一次进入时生成，进出不会重刷；这里只维护“待确认商品”的有效性。
        roomContent.update(navigation.getCurrentRoom(), player);
        attackSystem.update(dt, navigation);
        enemies.update(dt, player, attackSystem, navigation);
        applyHitKnockback();
        // 首领起手召唤时给一条即时提示：裂隙本身画在地上，但玩家常常正盯着首领看。
        if (enemies.consumeSummonCalls() > 0) {
            roomAnnouncement = "守望者撕开裂隙 · 召唤增援";
            roomAnnouncementRemaining = 2.0;
        }
        int kills = enemies.consumeKills();
        Room current = navigation.getCurrentRoom();
        if (current.type() == RoomType.BATTLE || current.type() == RoomType.BOSS
                || current.type() == RoomType.EVENT) {
            current.setCleared(enemies.isRoomCleared());
        }
        if (kills > 0) player.restorePhaseEnergy(kills * GameConfig.PHASE_ENERGY_PER_FRAGMENT);
        if (kills > 0) player.addCoins(kills + Math.floorMod((int) (dungeonSeed + kills * 13L), kills * 3 + 1));
        // combatActive = !enemies.isRoomCleared();   // 同上：这个状态没有任何读取点
        if (interactRequested) {
            interactRequested = false;
            interact(current);
        }
        player.restorePhaseEnergy(phaseEnergyRegenRate() * dt);
        player.updateAttackCharges(dt);
        if (attacking) {
            attackSystem.tryAttack(player, aimX, aimY);
        }
        player.updateAnimation(dt, movementX, movementY, attacking,
                phasePulseVisibleRemaining > 0.0);
    }

    /**
     * 相位能量自然回复速率（点/秒）。
     *
     * <p>当前世界已经没有活着的敌人时按倍率翻倍：玩家不小心切到一个没敌人的世界，
     * 只能干等相位能量回满，这里把这段白等缩短一半。
     */
    private double phaseEnergyRegenRate() {
        double multiplier = enemies.getCount(player.getCurrentWorld()) == 0
                ? GameConfig.PHASE_ENERGY_EMPTY_WORLD_REGEN_MULTIPLIER : 1.0;
        return GameConfig.PHASE_ENERGY_REGEN_PER_SEC * multiplier;
    }

    public boolean tryShiftWorld() {
        WorldType targetWorld = player.getCurrentWorld() == WorldType.LIGHT
                ? WorldType.SHADOW : WorldType.LIGHT;
        double[] safePosition = navigation.findNearestSafePosition(
                player.getX(), player.getY(), targetWorld);
        if (safePosition == null) return false;
        if (!worldShift.tryShift(player)) return false;
        player.setPosition(safePosition[0], safePosition[1]);
        // 切界后给攻击充能一个短时加速：玩家常在没充能时切界回能，这里缩短那段空窗。
        player.boostAttackChargeRecovery(GameConfig.WORLD_SWITCH_CHARGE_BOOST_DURATION);
        if (worldShift.consumePulse()) {
            enemyProjectiles.clearWithin(player.getX(), player.getY(), GameConfig.PHASE_PULSE_RADIUS);
            phasePulseVisibleRemaining = GameConfig.PHASE_PULSE_VISIBLE_TIME;
        }
        enemies.onWorldChanged(player.getCurrentWorld());
        return true;
    }

    public Player getPlayer() { return player; }
    public WorldShiftSystem getWorldShift() { return worldShift; }
    public PlayerAttackSystem getAttackSystem() { return attackSystem; }
    public EnemyProjectileSystem getEnemyProjectiles() { return enemyProjectiles; }
    public EnemySystem getEnemies() { return enemies; }
    public boolean isPhasePulseVisible() { return phasePulseVisibleRemaining > 0.0; }
    public double getAimX() { return aimX; }
    public double getAimY() { return aimY; }
    public int getLightEnemyCount() { return enemies.getCount(WorldType.LIGHT); }
    public int getShadowEnemyCount() { return enemies.getCount(WorldType.SHADOW); }
    public int getCoins() { return player.getCoins(); }
    public RoomNavigationSystem getNavigation() { return navigation; }
    // 未被引用（IDE 的 Unused 检查会报）：本局种子只在 GameSession 内部使用
    // （房间内容、敌人生成、金币掉落），外面没有任何读取点。需要时放开即可。
    // public long getDungeonSeed() { return dungeonSeed; }
    public boolean isRoomAnnouncementVisible() { return roomAnnouncementRemaining > 0.0; }
    public String getRoomAnnouncement() { return roomAnnouncement; }
    public double getRoomAnnouncementRemaining() { return roomAnnouncementRemaining; }
    public boolean isFloorAnnouncement() { return floorAnnouncement; }
    public void requestInteract() { interactRequested = true; }

    /**
     * 请求一次闪避冲刺（空格）。
     *
     * <p>这里只登记意图，真正的起手判定（冷却、是否已在冲刺、阵亡）由
     * {@link Player#tryStartDash(double, double)} 在下一逻辑帧统一处理：
     * 输入层不该知道冲刺规则，也不该绕过冷却。
     */
    public void requestDash() { dashRequested = true; }

    /**
     * 结算这一帧登记下来的受击击退。
     *
     * <p>放在敌人系统之后：伤害是在那里判定的，击退必须和扣血同一帧生效，
     * 否则玩家会看到"先掉血、下一帧才被推开"的脱节感。
     */
    private void applyHitKnockback() {
        double[] direction = player.consumeKnockback();
        if (direction == null) return;
        navigation.knockback(player, direction[0], direction[1], GameConfig.PLAYER_HIT_KNOCKBACK);
    }

    /** 当前层数（从 1 开始）。 */
    public int getFloor() { return floor; }

    /** 本局难度。 */
    public Difficulty getDifficulty() { return difficulty; }

    /** 一局总共多少层。 */
    public int getTotalFloors() { return GameConfig.TOTAL_FLOORS; }

    /** 是否已经打通最后一层：渲染层据此显示通关界面。 */
    public boolean isRunCleared() { return runCleared; }

    /** 当前房间是否站着通往下一层的传送门。 */
    public boolean isPortalVisible() { return navigation.getCurrentRoom().hasPortal(); }

    /** 守望者裂隙闪现的视觉残留；没有时返回 null。 */
    public EnemySystem.BlinkFlash getBlinkFlash() { return enemies.getBlinkFlash(); }

    /** 受击飘字（玩家挨打、敌人掉血）：渲染层只读地画在头顶。 */
    public List<EnemySystem.DamageFlash> getDamageFlashes() { return enemies.getDamageFlashes(); }

    /** 首领召唤裂隙（还没放出召唤物的预警圈）：渲染层画在角色之下。 */
    public List<SummonRift> getSummonRifts() { return enemies.getSummonRifts(); }

    /** 场上还活着的首领召唤物数量。 */
    public int getSummonedCount() { return enemies.getSummonedCount(); }

    /** 当前房间地面上的拾取物：挂在房间上，所以离开再回来东西还在。 */
    public List<Pickup> getPickups() { return navigation.getCurrentRoom().loot().pickupsView(); }

    /** 当前房间是否有没打开的宝箱。 */
    public boolean isChestVisible() { return navigation.getCurrentRoom().hasUnopenedChest(); }

    public String getInteractionPrompt() {
        return roomContent.prompt(navigation.getCurrentRoom(), player);
    }

    /** 当前交互目标（地面拾取物）；渲染层用它给最近的那件物品画名称标签。 */
    public Pickup getInteractionTarget() {
        return roomContent.currentTarget(navigation.getCurrentRoom(), player);
    }

    /** 商店商品的售价（金币）；当前房间不是商店或物品不可售时返回 -1。 */
    public int getShopPrice(Pickup pickup) {
        return navigation.getCurrentRoom().type() == RoomType.SHOP
                ? RoomContentSystem.priceOf(pickup) : -1;
    }

    /** 该商品是否已被选中、正等待二次确认（渲染层用它高亮）。 */
    public boolean isShopOfferSelected(Pickup pickup) { return roomContent.isOfferSelected(pickup); }

    /** 金币是否够买这件商品。 */
    public boolean canAfford(Pickup pickup) { return RoomContentSystem.canAfford(player, pickup); }

    private void interact(Room current) {
        RoomContentSystem.Outcome outcome = roomContent.interact(current, player);
        if (outcome == RoomContentSystem.Outcome.EVENT_AMBUSH) {
            // 事件房抽到伏击：房间里重新刷怪并重新关门，清空后宝箱照常出现。
            current.setCleared(false);
            enemies.spawnEventEnemies(current, dungeonSeed, player, navigation);
        } else if (outcome == RoomContentSystem.Outcome.PORTAL) {
            usePortal();
        }
    }

    /** 进入初始房间时提示层数，其余房间只报类型。 */
    private String roomAnnouncement(Room room) {
        return room.type() == RoomType.ENTRANCE ? floorAnnouncement() : roomTypeLabel(room.type());
    }

    private String floorAnnouncement() {
        return "第 " + floor + " 层 · " + difficulty.displayName() + " · 入口房";
    }

    private static String roomTypeLabel(RoomType type) {
        return switch (type) {
            case ENTRANCE -> "入口房";
            case BATTLE -> "战斗房";
            case REWARD -> "奖励房";
            case SHOP -> "商店房";
            case EVENT -> "事件房";
            case BOSS -> "Boss 房";
        };
    }
}
