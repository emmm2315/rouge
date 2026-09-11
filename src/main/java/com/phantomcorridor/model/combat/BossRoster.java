package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.entity.EnemyKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 每层首领的名册。
 *
 * <p>规则（本次扩展的硬要求）：**一局五层，每层的首领都不相同**，且名字由本局种子决定，
 * 所以同一种子下可复现、不同种子下排布不同：
 *
 * <ul>
 *   <li>第 1 层固定为裂隙守望者（旧包 Boss，剧情上的「门房」）；</li>
 *   <li>第 2～5 层从 v2 扩展包的五个首领里按种子抽出<b>互不重复</b>的四个：
 *       棱镜炮蟹、双镰螳爵、织巢蛛后、根冠古树、镜砂术士。</li>
 * </ul>
 *
 * <p>五个新首领只会同时上场四个，剩下一只按种子轮换缺席——这样五种新招式组合都能被玩到，
 * 而单局内又不会出现「第 3 层和第 5 层打同一只」的重复。
 */
public final class BossRoster {

    /** 第 1 层的固定首领：旧包的守望者。 */
    public static final EnemyKind FIRST_FLOOR_BOSS = EnemyKind.WATCHER;

    /** v2 扩展包的五个首领（第 2 层起的备选池）。 */
    public static final List<EnemyKind> EXPANSION_BOSSES = List.of(
            EnemyKind.PRISM_CRAB,
            EnemyKind.MANTIS,
            EnemyKind.WEAVER,
            EnemyKind.ROOTKING,
            EnemyKind.HOURGLASS);

    /** 旧包首领（备用池：调试、测试与后续「混合轮换」模式用）。 */
    public static final List<EnemyKind> LEGACY_BOSSES = List.of(EnemyKind.WATCHER);

    private BossRoster() { }

    /**
     * 第 {@code floor} 层的首领。
     *
     * <p>关键点：**整局共用一份打乱后的名册**。调用方传进来的是「本层」的关卡种子
     * （{@code 本局种子 + (层数-1) × FLOOR_SEED_STEP}），先把它还原成本局种子再打乱，
     * 各层才会落在同一个排列的不同位置上。若直接拿每层不同的种子去洗牌，
     * 五层会各自独立地抽到同一个首领——「五层五个 Boss」当场失效。
     *
     * @param floor       层数（从 1 开始）
     * @param dungeonSeed 本层的关卡种子
     */
    public static EnemyKind forFloor(int floor, long dungeonSeed) {
        int index = Math.max(1, floor);
        if (index == 1) return FIRST_FLOOR_BOSS;
        long runSeed = dungeonSeed - (long) (index - 1) * GameConfig.FLOOR_SEED_STEP;
        List<EnemyKind> pool = shuffledExpansionBosses(runSeed);
        int slot = index - 2;
        // 超过五层（调试用长局）时循环取用；主线 TOTAL_FLOORS = 5 永远落在互不重复的区间里。
        return pool.get(Math.floorMod(slot, pool.size()));
    }

    /**
     * 一整局各层的首领列表。
     *
     * <p>主线长度（{@link GameConfig#TOTAL_FLOORS} = 5）下，返回的五个元素互不相同：
     * 这是「五层五个 Boss」的验收点，测试直接对它下断言。
     */
    public static List<EnemyKind> forRun(long runSeed, int floors) {
        List<EnemyKind> result = new ArrayList<>();
        for (int floor = 1; floor <= Math.max(1, floors); floor++) {
            long floorSeed = runSeed + (floor - 1) * GameConfig.FLOOR_SEED_STEP;
            result.add(forFloor(floor, floorSeed));
        }
        return result;
    }

    /**
     * 按种子打乱后的扩展首领池。
     *
     * <p>每次都新建 {@link Random}，所以同一个种子在任意调用顺序下都得到同一个排列——
     * 首领不能因为「玩家这次是从北门进的房间」而换一只。
     */
    private static List<EnemyKind> shuffledExpansionBosses(long dungeonSeed) {
        List<EnemyKind> pool = new ArrayList<>(EXPANSION_BOSSES);
        Collections.shuffle(pool, new Random(dungeonSeed * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL));
        return pool;
    }
}
