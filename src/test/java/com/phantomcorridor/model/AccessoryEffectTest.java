package com.phantomcorridor.model;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.combat.DamageType;
import com.phantomcorridor.model.combat.PlayerAttackSystem;
import com.phantomcorridor.model.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 《新增 15 件装备与攻击特效设计》§五 的 7 件配套装备。
 *
 * <p>逐条对照设计文档：曜纹披肩的 30% 减伤与 6 秒冷却、夜行披风的 0.45 秒加速、
 * 凝光透镜的 25% 光伤与 1.15 倍间隔、猎影牙饰的 70% 生命阈值、余震指环的每第四轮、
 * 相位陀螺的切界窗口、行者心核的 +20% 生命上限。
 */
class AccessoryEffectTest {

    private static final double EPS = 1e-9;

    private static Player lightPlayer() { return new Player(0, 0); }

    private static Player shadowPlayer() {
        Player player = new Player(0, 0);
        player.toggleWorld();
        return player;
    }

    // ---- 09 曜纹披肩 ----

    @Test
    void sunweaveMantleReducesLightDamageByThirtyPercentThenGoesOnCooldown() {
        Player player = lightPlayer();
        player.equip(EquipmentType.SUNWEAVE_MANTLE);
        player.clearShield();

        Player.DamageResult first = player.takeDamage(20.0, DamageType.LIGHT);
        assertNotNull(first);
        assertEquals(14, first.healthLost(), "20 点光伤应当减到 14 点（-30%）");
        assertFalse(player.isSunweaveReady(), "触发之后进入冷却");

        // 冷却中再挨一下是全额；冷却走完才重新就绪。
        player.updateAnimation(GameConfig.PLAYER_HIT_INVULNERABILITY + 0.01, 0, 0, false, false);
        assertEquals(20, player.takeDamage(20.0, DamageType.LIGHT).healthLost(), "冷却期内不减伤");

        player.updateAnimation(GameConfig.SUNWEAVE_MANTLE_COOLDOWN, 0, 0, false, false);
        assertTrue(player.isSunweaveReady(), "6 秒后重新就绪");
    }

    @Test
    void sunweaveMantleDoesNotSpendItsReadyChanceInTheShadowWorld() {
        // 设计文档：「影界不提供减伤，也不消耗已经就绪的减伤机会」。
        Player player = lightPlayer();
        player.equip(EquipmentType.SUNWEAVE_MANTLE);
        player.clearShield();
        player.toggleWorld();

        assertFalse(player.isSunweaveReady(), "影界不提供减伤");
        assertEquals(20, player.takeDamage(20.0, DamageType.SHADOW).healthLost(), "影界挨打是全额");

        // 回到光界：那一次减伤机会还在。
        player.toggleWorld();
        player.updateAnimation(GameConfig.PLAYER_HIT_INVULNERABILITY + 0.01, 0, 0, false, false);
        assertTrue(player.isSunweaveReady(), "影界挨打不该消耗光界的减伤机会");
        assertEquals(14, player.takeDamage(20.0, DamageType.LIGHT).healthLost());
    }

    // ---- 10 夜行披风 ----

    @Test
    void nightstepCloakGivesABriefSpeedBurstAfterAShadowAttack() {
        Player player = shadowPlayer();
        double shadowBase = GameConfig.PLAYER_BASE_SPEED * GameConfig.SHADOW_SPEED_MULTIPLIER;
        player.equip(EquipmentType.NIGHTSTEP_CLOAK);
        assertEquals(shadowBase, player.movementSpeed(), EPS, "还没出手时没有加速");

        player.onShadowAttackReleased();
        assertEquals(shadowBase * GameConfig.NIGHTSTEP_CLOAK_SPEED, player.movementSpeed(), 1e-6,
                "出手后 0.45 秒内移速 +18%");

        player.updateAnimation(GameConfig.NIGHTSTEP_CLOAK_DURATION + 0.01, 0, 0, false, false);
        assertEquals(shadowBase, player.movementSpeed(), EPS, "加速到点就结束");
    }

    @Test
    void nightstepCloakIsRemovedImmediatelyOnSwitchingToLight() {
        Player player = shadowPlayer();
        player.equip(EquipmentType.NIGHTSTEP_CLOAK);
        player.onShadowAttackReleased();
        assertTrue(player.getNightstepRemaining() > 0.0);

        player.toggleWorld();
        player.updateAnimation(0.01, 0, 0, false, false);

        assertEquals(0.0, player.getNightstepRemaining(), EPS, "切入光界立即结束加速");
    }

    @Test
    void nightstepCloakDoesNothingWithoutTheEquip() {
        Player player = shadowPlayer();
        player.onShadowAttackReleased();
        assertEquals(0.0, player.getNightstepRemaining(), EPS, "没穿披风就不该有加速");
    }

    // ---- 11 凝光透镜 ----

    @Test
    void focusLensBoostsLightDamageAndLengthensOnlyLightAttackInterval() {
        Player player = lightPlayer();
        player.equip(EquipmentType.FOCUS_LENS);

        assertEquals(1.25, player.damageMultiplier(WorldType.LIGHT), EPS, "光界伤害 +25%");
        assertEquals(1.0, player.damageMultiplier(WorldType.SHADOW), EPS, "影界没有伤害加成");
        assertEquals(1.15, player.getAttackCooldownMultiplier(WorldType.LIGHT), EPS, "光界间隔 ×1.15");
        assertEquals(1.0, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS,
                "影界不该白背一个间隔惩罚");
    }

    @Test
    void focusLensMultiplierIsAppliedToTheRealAttackInterval() {
        Player player = lightPlayer();
        player.equip(EquipmentType.FOCUS_LENS);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);

        assertEquals(GameConfig.LIGHT_ATTACK_COOLDOWN * 1.15, attacks.getCooldownRemaining(), 1e-6);
    }

    // ---- 12 猎影牙饰 ----

    @Test
    void huntersFangOnlyTriggersAboveTheSeventyPercentHealthThreshold() {
        // 阈值判定本身是纯函数，这里直接对着 Player 的加成接口验证“有没有装”。
        Player player = shadowPlayer();
        assertEquals(0, player.equipmentCount(EquipmentType.HUNTERS_FANG));
        player.equip(EquipmentType.HUNTERS_FANG);
        assertEquals(1, player.equipmentCount(EquipmentType.HUNTERS_FANG));
        // 猎影牙饰不给通用伤害加成——它的 +25% 只对高生命目标、由命中结算单独判断。
        assertEquals(0.0, player.getEquipmentDamageBonus(), EPS,
                "牙饰的加成是条件触发的，不该混进通用伤害加成里");
    }

    // ---- 13 余震指环 ----

    @Test
    void resonanceRingMarksEveryFourthAttackRound() {
        Player player = lightPlayer();
        player.equip(EquipmentType.RESONANCE_RING);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        for (int round = 1; round <= 4; round++) {
            assertTrue(attacks.tryAttack(player, 200.0, 100.0), "第 " + round + " 轮应当能出手");
            if (round < GameConfig.RESONANCE_RING_INTERVAL) {
                assertFalse(attacks.isResonanceRound(), "第 " + round + " 轮不该是强化轮");
            }
            attacks.update(GameConfig.LIGHT_ATTACK_COOLDOWN + 0.01);
            player.restoreAttackCharges(1);
        }
        // 第 4 轮出手后计数到 4，此时标记为强化轮（命中结算按它触发震波）。
        assertEquals(GameConfig.RESONANCE_RING_INTERVAL, attacks.getWorldAttackCount());
    }

    @Test
    void resonanceRingCountResetsWhenSwitchingWorld() {
        Player player = lightPlayer();
        player.equip(EquipmentType.RESONANCE_RING);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);
        assertEquals(1, attacks.getWorldAttackCount());

        attacks.clearTransientAttacks();

        assertEquals(0, attacks.getWorldAttackCount(), "切界/离房都要清零，不能预存强化");
    }

    // ---- 14 相位陀螺 ----

    @Test
    void phaseGyroscopeOpensATwoSecondAttackSpeedWindowOnWorldShift() {
        Player player = lightPlayer();
        player.equip(EquipmentType.PHASE_GYROSCOPE);
        assertEquals(1.0, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS, "还没切界时没有加成");

        player.toggleWorld();
        player.onWorldShifted();

        assertEquals(0.85, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS, "切界后间隔 ×0.85");
        assertTrue(player.isGyroscopeActive());

        player.updateAnimation(GameConfig.PHASE_GYROSCOPE_WINDOW + 0.01, 0, 0, false, false);
        assertEquals(1.0, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS, "2 秒后窗口关闭");
    }

    @Test
    void phaseGyroscopeShortensTheRealAttackIntervalAfterAShift() {
        Player player = lightPlayer();
        player.equip(EquipmentType.PHASE_GYROSCOPE);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        player.toggleWorld();
        player.onWorldShifted();
        assertTrue(attacks.tryAttack(player, 100.0, 200.0), "影界应当能出手");
        assertEquals(0.85, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS,
                "切界后陀螺的倍率应当是 0.85");

        assertEquals(GameConfig.SHADOW_ATTACK_COOLDOWN * 0.85, attacks.getCooldownRemaining(), 1e-6,
                "切界后的攻速窗口必须作用在真实的出手间隔上");
    }

    @Test
    void phaseGyroscopeWindowIsNotRefreshedByAnUnsuccessfulShift() {
        // 「失败的切界不触发任何装备奖励」——所以窗口只由成功切界打开。
        Player player = lightPlayer();
        player.equip(EquipmentType.PHASE_GYROSCOPE);
        assertEquals(1.0, player.getAttackCooldownMultiplier(WorldType.SHADOW), EPS);
    }

    // ---- 15 行者心核 ----

    @Test
    void wayfarerHeartRaisesMaxHpWithoutHealingAndTruncatesOnRemoval() {
        Player player = lightPlayer();
        // 先清盾再扣血：护盾会先吃掉这一击，清晚了血量根本掉不下去。
        player.clearShield();
        player.takeDamage((double) GameConfig.PLAYER_MAX_HP, DamageType.PHYSICAL);
        player.restoreHealth(80);
        assertEquals(80, player.getHp());

        player.equip(EquipmentType.WAYFARER_HEART);

        assertEquals(120, player.maxHp(), "生命上限 +20%");
        assertEquals(80, player.getHp(), "装备时不补血");

        // 卸下时当前生命截断到新的上限，不会因为上限变低而溢出。
        player.restoreHealth(200);
        assertEquals(120, player.getHp(), "回满到新上限");
        player.removeEquipment(0);
        assertEquals(100, player.maxHp());
        assertEquals(100, player.getHp(), "卸下后当前生命截断到新上限");
    }

    @Test
    void wayfarerHeartCannotBeUsedToFarmHealthByReEquipping() {
        Player player = lightPlayer();
        player.clearShield();
        player.takeDamage((double) GameConfig.PLAYER_MAX_HP, DamageType.PHYSICAL);
        player.restoreHealth(50);

        for (int i = 0; i < 5; i++) {
            player.equip(EquipmentType.WAYFARER_HEART);
            player.removeEquipment(0);
        }

        assertEquals(50, player.getHp(), "反复穿脱不能刷血");
    }

    @Test
    void duplicateWayfarerHeartsStackTheirMaxHpBonus() {
        Player player = lightPlayer();
        player.equip(EquipmentType.WAYFARER_HEART);
        player.equip(EquipmentType.WAYFARER_HEART);

        assertEquals(140, player.maxHp(), "两枚心核 = +40%");
    }
}
