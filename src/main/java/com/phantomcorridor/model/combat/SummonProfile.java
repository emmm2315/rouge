package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

import java.util.List;

/**
 * 首领召唤档案：把「谁能召唤、一次叫几只、什么时候该叫」从战斗循环里拆出来。
 *
 * <p>守望者与织巢蛛后共用同一套召唤机制（裂隙先预警、后落地；血量阶段 / 打不到玩家 / 场上空场
 * 三条触发条件），差别只在叫出来的物种与节奏数值：
 *
 * <ul>
 *   <li>守望者：光界唤灯魇＋法师，影界唤影狼＋法师——一近一远，玩家一眼分得清；</li>
 *   <li>织巢蛛后：光界唤曜甲虫＋晨露孢囊，影界唤掘影虫＋夜钩蝠——按素材包的 {@code summons} 配置。</li>
 * </ul>
 *
 * <p>没有召唤招式的首领返回 {@code null}，调用方据此跳过整套召唤判定。
 */
public record SummonProfile(int count,
                            int maxAlive,
                            double cooldown,
                            double openingDelay,
                            double pressureTime,
                            double riftTime,
                            double riftMinDistance,
                            double riftMaxDistance,
                            double riftSpacing,
                            double playerClearance,
                            double hitPointScale,
                            double alert,
                            double[] healthTriggers,
                            double deferDistance,
                            List<EnemyKind> lightMinions,
                            List<EnemyKind> shadowMinions) {

    /** 守望者的召唤档案：数值全部来自 {@link GameConfig} 的既有常量，行为与旧版本完全一致。 */
    private static final SummonProfile WATCHER_PROFILE = new SummonProfile(
            GameConfig.WATCHER_SUMMON_COUNT,
            GameConfig.WATCHER_SUMMON_MAX_ALIVE,
            GameConfig.WATCHER_SUMMON_COOLDOWN,
            GameConfig.WATCHER_SUMMON_OPENING_DELAY,
            GameConfig.WATCHER_SUMMON_PRESSURE_TIME,
            GameConfig.WATCHER_SUMMON_RIFT_TIME,
            GameConfig.WATCHER_SUMMON_RIFT_MIN_DISTANCE,
            GameConfig.WATCHER_SUMMON_RIFT_MAX_DISTANCE,
            GameConfig.WATCHER_SUMMON_RIFT_SPACING,
            GameConfig.WATCHER_SUMMON_PLAYER_CLEARANCE,
            GameConfig.WATCHER_SUMMON_HP_SCALE,
            GameConfig.WATCHER_SUMMON_ALERT,
            new double[]{0.70, 0.35},
            160.0,
            List.of(EnemyKind.LANTERN, EnemyKind.MAGE),
            List.of(EnemyKind.WOLF, EnemyKind.MAGE));

    /**
     * 织巢蛛后的召唤档案。
     *
     * <p>节奏与守望者保持一致（6 秒开场缓冲、12 秒冷却、两级血量阶段、场上最多 4 只），
     * 因为她同样是「召唤型首领」：玩家要先认得出裂隙、再决定先打本体还是先清小怪。
     */
    private static final SummonProfile WEAVER_PROFILE = new SummonProfile(
            GameConfig.WATCHER_SUMMON_COUNT,
            GameConfig.WATCHER_SUMMON_MAX_ALIVE,
            GameConfig.WATCHER_SUMMON_COOLDOWN,
            GameConfig.WATCHER_SUMMON_OPENING_GRACE,
            GameConfig.WATCHER_SUMMON_PRESSURE_TIME,
            GameConfig.WATCHER_SUMMON_RIFT_TIME,
            GameConfig.WATCHER_SUMMON_RIFT_MIN_DISTANCE,
            GameConfig.WATCHER_SUMMON_RIFT_MAX_DISTANCE,
            GameConfig.WATCHER_SUMMON_RIFT_SPACING,
            GameConfig.WATCHER_SUMMON_PLAYER_CLEARANCE,
            GameConfig.WATCHER_SUMMON_HP_SCALE,
            GameConfig.WATCHER_SUMMON_ALERT,
            new double[]{0.70, 0.35},
            160.0,
            List.of(EnemyKind.BEETLE, EnemyKind.SPORE),
            List.of(EnemyKind.BEETLE, EnemyKind.RAYBAT));

    /** 该首领的召唤档案；不召唤时返回 {@code null}。 */
    public static SummonProfile of(EnemyKind kind) {
        return switch (kind) {
            case WATCHER -> WATCHER_PROFILE;
            case WEAVER -> WEAVER_PROFILE;
            default -> null;
        };
    }

    /**
     * 本界第 {@code index} 只召唤物的物种。
     *
     * <p>从 {@link GameConfig#SUMMON_ELITE_FROM_FLOOR} 层开始，每一波的**第一只换成精英**：
     * 首领不再只丢小怪出来，而是直接拉一只走廊里的执刑者（光界）/ 鸣钟者（影界）助战。
     * 精英照样吃召唤物的生命折扣，所以它比房间里的精英脆，但比小怪厚实得多。
     */
    public EnemyKind minion(WorldType world, int index, int floor) {
        boolean eliteFirst = GameConfig.summonIncludesElites(floor);
        if (eliteFirst && index == 0) return world == WorldType.LIGHT ? EnemyKind.EXECUTIONER : EnemyKind.BELL;
        int poolIndex = index - (eliteFirst ? 1 : 0);
        List<EnemyKind> pool = world == WorldType.LIGHT ? lightMinions : shadowMinions;
        return pool.get(Math.floorMod(poolIndex, pool.size()));
    }

    /** 第 {@code floor} 层场上召唤物上限（含未成型的裂隙）。 */
    public int aliveCap(int floor) {
        return Math.max(maxAlive, GameConfig.summonAliveCap(floor));
    }

    /**
     * 第 {@code floor} 层一波召唤的数量。
     *
     * @param desperate 首领是否已经跌破最后一个血量阶段；是则再追加一只（「血量越低越疯狂」）
     */
    public int waveSize(int floor, boolean desperate) {
        int wave = Math.max(count, GameConfig.summonWaveSize(floor));
        return desperate ? wave + GameConfig.SUMMON_WAVE_LOW_HEALTH_BONUS : wave;
    }

    /** 最后一个血量阶段的阈值：跌破它就算「背水一战」。 */
    public double lastHealthTrigger() {
        return healthTriggers[healthTriggers.length - 1];
    }

    /** 血量阶段是否已经触发完。 */
    public boolean hasStage(int stage) { return stage < healthTriggers.length; }

    /** 第 stage 个血量阶段的阈值。 */
    public double healthTrigger(int stage) { return healthTriggers[Math.min(stage, healthTriggers.length - 1)]; }
}
