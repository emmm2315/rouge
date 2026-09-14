package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.AppConfig;
import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.WorldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 形态右键机制：光形态蓄力（1 → 5 倍线性增长、移速惩罚、固定能量消耗）与
 * 影形态格挡（1.5 秒无敌窗口、挡下伤害强化下一次暗影普攻）。
 *
 * <p>两条规则都挂在 {@link Player#updateAnimation} 的计时上，所以暂停时它们会一起冻结——
 * 测试用 {@code advance} 手动推进同样的帧步长。
 */
class PlayerStanceTest {

    private static final double FRAME = AppConfig.FIXED_DT;

    // ---- 光形态蓄力 ----

    @Test
    void chargeDamageGrowsLinearlyFromOneToFiveTimes() {
        Player player = new Player(100, 100);
        assertTrue(player.beginCharge());

        assertEquals(1.0, player.chargeDamageMultiplier(), 0.05, "刚开始蓄力时约等于普攻");

        advance(player, GameConfig.LIGHT_CHARGE_MAX_TIME / 2);
        assertEquals(3.0, player.chargeDamageMultiplier(), 0.1, "蓄到一半应当是 3 倍");

        advance(player, GameConfig.LIGHT_CHARGE_MAX_TIME);
        assertEquals(GameConfig.LIGHT_CHARGE_MAX_MULTIPLIER, player.chargeDamageMultiplier(), 1e-9,
                "蓄满封顶在 5 倍，不会无限增长");
    }

    @Test
    void chargingHalvesMovementSpeed() {
        Player player = new Player(100, 100);
        double walking = player.movementSpeed();

        assertTrue(player.beginCharge());

        assertEquals(walking * GameConfig.LIGHT_CHARGE_MOVE_MULTIPLIER, player.movementSpeed(), 1e-9,
                "蓄力必须付出机动性代价");
    }

    @Test
    void chargeCannotStartWithoutItsEnergyCost() {
        Player player = new Player(100, 100);
        for (int i = 0; i < GameConfig.ATTACK_CHARGE_MAX; i++) player.consumeSkillEnergy(1);
        assertTrue(player.getSkillEnergy() < GameConfig.LIGHT_CHARGE_ENERGY_COST);

        assertFalse(player.beginCharge(), "蓝量不够时连蓄力都不该开始");
        assertFalse(player.isCharging());
    }

    @Test
    void aVeryShortTapIsTreatedAsAMisTap() {
        Player player = new Player(100, 100);
        assertTrue(player.beginCharge());
        advance(player, GameConfig.LIGHT_CHARGE_MIN_TIME / 2);

        assertFalse(player.isChargeFireable(), "过短的按下视为误触，不发射");

        advance(player, GameConfig.LIGHT_CHARGE_MIN_TIME);
        assertTrue(player.isChargeFireable(), "超过最短蓄力时间后可以发射");
    }

    @Test
    void consumingTheChargeSpendsAFixedAmountOfEnergy() {
        Player player = new Player(100, 100);
        int before = player.getSkillEnergy();
        assertTrue(player.beginCharge());
        advance(player, GameConfig.LIGHT_CHARGE_MAX_TIME);

        double multiplier = player.consumeCharge();

        assertEquals(GameConfig.LIGHT_CHARGE_MAX_MULTIPLIER, multiplier, 1e-9);
        assertEquals(before - GameConfig.LIGHT_CHARGE_ENERGY_COST, player.getSkillEnergy(),
                "蓄力发射固定扣 2 点，与蓄了多久无关");
        assertFalse(player.isCharging());
    }

    @Test
    void chargeOnlyExistsInTheLightWorld() {
        Player player = new Player(100, 100);
        player.toggleWorld();

        assertEquals(WorldType.SHADOW, player.getCurrentWorld());
        assertFalse(player.beginCharge(), "影形态没有蓄力，右键的语义是格挡");
    }

    // ---- 影形态格挡 ----

    @Test
    void blockWindowAbsorbsDamageAndEmpowersTheNextShadowAttack() {
        Player player = shadowPlayer();
        int before = player.getSkillEnergy();
        assertTrue(player.beginBlock());
        assertEquals(before - GameConfig.SHADOW_BLOCK_ENERGY_COST, player.getSkillEnergy(), "开格挡需要五格");

        Player.DamageResult result = player.takeDamage(40.0);

        assertNotNull(result, "格挡窗口内这一下必须被吃掉");
        assertEquals(0, result.healthLost(), "格挡期间不该掉血");
        assertEquals(GameConfig.PLAYER_MAX_HP, player.getHp());
        assertTrue(player.hasBlockEmpower(), "挡下伤害要攒下一次强化暗影普攻");
        assertEquals(before, player.getSkillEnergy(), "格挡成功要把消耗的能量退回来");

        assertTrue(player.consumeBlockEmpower());
        assertFalse(player.hasBlockEmpower(), "强化只作用于紧接着的那一次普攻");
    }

    @Test
    void blockWindowExpiresAfterItsDuration() {
        Player player = shadowPlayer();
        assertTrue(player.beginBlock());
        assertTrue(player.isBlocking());

        // 多推几帧：窗口是按浮点秒数倒数的，正好卡在最后一帧上会差一个 1e-16。
        advance(player, GameConfig.SHADOW_BLOCK_DURATION + 0.1);

        assertFalse(player.isBlocking(), "窗口走完就恢复常态");
        assertNotNull(player.takeDamage(40.0), "窗口结束后照常受击");
    }

    @Test
    void blockOnlyRefundsAndEmpowersOncePerWindow() {
        Player player = shadowPlayer();
        assertTrue(player.beginBlock());
        int afterCost = player.getSkillEnergy();

        assertNotNull(player.takeDamage(40.0));
        assertEquals(afterCost + GameConfig.SHADOW_BLOCK_ENERGY_REFUND, player.getSkillEnergy());

        // 窗口内的第二下（例如同一帧的另一枚弹体）只免伤，不重复返能量、也不再多攒一次强化。
        assertNotNull(player.takeDamage(40.0));
        assertEquals(afterCost + GameConfig.SHADOW_BLOCK_ENERGY_REFUND, player.getSkillEnergy(),
                "一次格挡只能返还一次能量");
        assertTrue(player.hasBlockEmpower());
    }

    @Test
    void blockCannotStartWithoutFiveEnergy() {
        Player player = shadowPlayer();
        player.consumeSkillEnergy(GameConfig.ATTACK_CHARGE_MAX);
        player.restoreAttackCharges(4);
        assertEquals(4, player.getSkillEnergy());

        assertFalse(player.beginBlock());
        assertFalse(player.isBlocking());
    }

    @Test
    void blockOnlyExistsInTheShadowWorld() {
        Player player = new Player(100, 100);
        assertEquals(WorldType.LIGHT, player.getCurrentWorld());

        assertFalse(player.beginBlock(), "光形态没有格挡，右键的语义是蓄力");
    }

    private static Player shadowPlayer() {
        Player player = new Player(100, 100);
        player.toggleWorld();
        return player;
    }

    /** 推进 {@code seconds} 秒：蓄力与格挡的计时都挂在 {@code updateAnimation} 上。 */
    private static void advance(Player player, double seconds) {
        int frames = (int) Math.ceil(seconds / FRAME);
        for (int frame = 0; frame < frames; frame++) {
            player.updateAnimation(FRAME, 0, 0, false, false);
        }
    }
}
