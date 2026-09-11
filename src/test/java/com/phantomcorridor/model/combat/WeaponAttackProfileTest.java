package com.phantomcorridor.model.combat;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 《新增 15 件装备与攻击特效设计》§四 的 8 件武器：每一件都必须**替换攻击方案**，
 * 而不是换一个叠加属性。
 *
 * <p>这一组用例逐条对照设计文档里的数值：弹体数量与张角、每发系数、间隔倍率与绝对下限、
 * 穿透次数、弹射段数、爆裂半径、往返、前摇。系数与间隔一旦被改回“攻击力 +1”，
 * 这些断言就会立刻失败。
 */
class WeaponAttackProfileTest {

    private static final double EPS = 1e-9;

    private static Player lightPlayer(EquipmentType... weapons) {
        Player player = new Player(100, 100);
        for (EquipmentType weapon : weapons) player.equip(weapon);
        return player;
    }

    private static Player shadowPlayer(EquipmentType... weapons) {
        Player player = lightPlayer(weapons);
        player.toggleWorld();
        return player;
    }

    // ---- 01 棱光三叉杖 ----

    @Test
    void prismFanWandFiresThreePelletsAtFixedSpreadWithWeakerDamageEach() {
        Player player = lightPlayer(EquipmentType.PRISM_FAN_WAND);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 200.0, 100.0));
        assertEquals(3, attacks.getProjectiles().size(), "三向散射应当产生三发弹体");

        // 三发分别指向 -14° / 0° / +14°。
        Set<Double> angles = new HashSet<>();
        for (Projectile projectile : attacks.getProjectiles()) {
            angles.add(Math.round(Math.toDegrees(Math.atan2(projectile.getVelocityY(), projectile.getVelocityX())) * 100.0) / 100.0);
        }
        assertEquals(Set.of(-14.0, 0.0, 14.0), angles, "散射角度必须是 -14° / 0° / +14°");

        for (Projectile projectile : attacks.getProjectiles()) {
            assertEquals(0.45, projectile.getDamageCoefficient(), EPS, "每发系数是 0.45");
        }
        // 间隔 1.10 × 0.45 = 0.495。
        assertEquals(GameConfig.LIGHT_ATTACK_COOLDOWN * 1.10, attacks.getCooldownRemaining(), 1e-6);
    }

    // ---- 02 贯日长杖 ----

    @Test
    void sunlancePiercesThreeEnemiesWithDecreasingDamage() {
        Player player = lightPlayer(EquipmentType.SUNLANCE);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);

        Projectile lance = attacks.getProjectiles().getFirst();
        assertEquals(Projectile.Behaviour.PIERCE, lance.getBehaviour());
        assertEquals(0.60, lance.getRadius() / GameConfig.LIGHT_PROJECTILE_RADIUS, 1e-6, "光矛更细");

        // 逐次命中换成 1.00 / 0.75 / 0.50。
        assertEquals(1.00, lance.getDamageCoefficient(), EPS);
        assertTrue(lance.registerHit("a"));
        assertEquals(0.75, lance.getDamageCoefficient(), EPS, "第二段降到 0.75");
        assertTrue(lance.registerHit("b"));
        assertEquals(0.50, lance.getDamageCoefficient(), EPS, "第三段降到 0.50");
        assertFalse(lance.registerHit("c"), "最多三个敌人，之后必须消失");
    }

    @Test
    void sunlanceKeepsTheOriginalMaximumFlightDistance() {
        Player player = lightPlayer(EquipmentType.SUNLANCE);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);
        Projectile lance = attacks.getProjectiles().getFirst();

        double speed = Math.hypot(lance.getVelocityX(), lance.getVelocityY());
        double distance = speed * lance.getRemainingLifetime();
        double baseDistance = GameConfig.LIGHT_PROJECTILE_SPEED * GameConfig.LIGHT_PROJECTILE_LIFETIME;
        assertEquals(baseDistance, distance, 1e-6, "速度 ×1.35 与寿命 ÷1.35 应当抵消，飞行距离不变");
    }

    // ---- 03 折镜法球 ----

    @Test
    void mirrorOrbBouncesTwiceThroughThreeSeparateDamageSegments() {
        Player player = lightPlayer(EquipmentType.MIRROR_ORB);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);

        Projectile orb = attacks.getProjectiles().getFirst();
        assertEquals(Projectile.Behaviour.BOUNCE, orb.getBehaviour());
        assertEquals(0.85, orb.getDamageCoefficient(), EPS, "首发 0.85");

        assertTrue(orb.registerHit("a"));
        assertEquals(0.55, orb.getDamageCoefficient(), EPS, "第一段弹射 0.55");
        assertEquals(1, orb.getHitIndex());
        assertTrue(orb.registerHit("b"));
        assertEquals(0.35, orb.getDamageCoefficient(), EPS, "第二段弹射 0.35");
        assertFalse(orb.registerHit("c"), "一条链最多伤害三个敌人");
    }

    // ---- 04 炽核权杖 ----

    @Test
    void solarBurstStaffFiresASlowCoreThatOnlyDealsItsDamageViaExplosion() {
        Player player = lightPlayer(EquipmentType.SOLAR_BURST_STAFF);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);

        Projectile core = attacks.getProjectiles().getFirst();
        assertEquals(Projectile.Behaviour.BURST, core.getBehaviour());
        assertEquals(0.0, core.getDamageCoefficient(), EPS, "光核本身没有接触伤害");
        assertEquals(GameConfig.SOLAR_BURST_RADIUS, core.getEffectRadius(), EPS);

        double speed = Math.hypot(core.getVelocityX(), core.getVelocityY());
        assertEquals(GameConfig.LIGHT_PROJECTILE_SPEED * 0.70, speed, 1e-6, "慢速光核：速度 ×0.70");
    }

    // ---- 05 环月镰 ----

    @Test
    void crescentReaperTurnsTheMeleeArcIntoAFullCircle() {
        Player player = shadowPlayer(EquipmentType.CRESCENT_REAPER);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 100.0, 200.0));
        assertTrue(attacks.isMeleeVisible());
        assertTrue(attacks.getProjectiles().isEmpty(), "环斩不应该产生弹体");
        assertEquals(360.0, attacks.getMeleeArcDegrees(), EPS, "环月镰是整圈环斩");
        assertEquals(GameConfig.SHADOW_MELEE_RANGE * 0.80, attacks.getMeleeRange(), 1e-6, "半径 0.80");
        assertEquals(0.80, attacks.getCurrentProfile().coefficient(0), EPS, "伤害 0.80");
    }

    // ---- 06 归影双刃 ----

    @Test
    void returningFangFliesOutAndComesBackHittingEachWayOnce() {
        Player player = shadowPlayer(EquipmentType.RETURNING_FANG);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 100.0, 200.0));
        assertFalse(attacks.isMeleeVisible(), "归影双刃不是近战扇形");
        assertEquals(1, attacks.getProjectiles().size());
        Projectile fang = attacks.getProjectiles().getFirst();
        assertEquals(Projectile.Behaviour.RETURN, fang.getBehaviour());
        assertEquals(GameConfig.RETURNING_FANG_COEFFICIENT, fang.getDamageCoefficient(), EPS);
        assertFalse(fang.isReturning());

        // 出程飞满 0.22 秒后掉头；出程、返程各能命中一次（同一敌人两次）。
        attacks.update(GameConfig.RETURNING_FANG_FLIGHT_TIME + 0.001);
        assertTrue(fang.isReturning(), "飞满单程时间应当掉头");

        assertTrue(fang.registerHit("boss"), "出程命中后继续飞（要回来）");
        assertFalse(fang.hasHit("other"));
        assertTrue(fang.registerHit("other"), "返程还能再命中一次");
    }

    @Test
    void returningFangReachIsOnePointSixTimesTheMeleeRange() {
        Player player = shadowPlayer(EquipmentType.RETURNING_FANG);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 100.0, 200.0);
        Projectile fang = attacks.getProjectiles().getFirst();

        double speed = Math.hypot(fang.getVelocityX(), fang.getVelocityY());
        double singleTrip = speed * GameConfig.RETURNING_FANG_FLIGHT_TIME;
        assertEquals(GameConfig.SHADOW_MELEE_RANGE * 1.60, singleTrip, 1e-6, "最远距离 1.60 倍影斩距离");
    }

    // ---- 07 夜坠重剑 ----

    @Test
    void nightfallGreatswordHasAWindupNarrowArcAndLongerCooldown() {
        Player player = shadowPlayer(EquipmentType.NIGHTFALL_GREATSWORD);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 100.0, 200.0));
        assertFalse(attacks.isMeleeVisible(), "前摇期间这一刀还没落下");
        assertEquals(1, attacks.getPendingCount(), "重剑的前摇由延迟攻击承担");
        // 间隔在出手那一刻就定好了，所以要在推进时间之前读。
        assertEquals(GameConfig.SHADOW_ATTACK_COOLDOWN * 1.65, attacks.getCooldownRemaining(), 1e-6,
                "间隔 = max(1.65 × 基础, 0.30 秒)");

        // 前摇走完才真正开斩，并且用的是窄扇形与长距离。
        attacks.update(0.18 + 0.001);
        assertTrue(attacks.isMeleeVisible(), "0.18 秒前摇走完才落刀");
        assertEquals(0, attacks.getPendingCount());
        assertEquals(40.0, attacks.getMeleeArcDegrees(), EPS, "40° 窄劈");
        assertEquals(GameConfig.SHADOW_MELEE_RANGE * 1.35, attacks.getMeleeRange(), 1e-6, "距离 1.35");
        assertEquals(1.60, attacks.getMeleeCoefficient(), EPS, "伤害 1.60");
    }

    // ---- 08 蚀界仪 ----

    @Test
    void eclipseRelayFiresTwoPelletsTenHundredthsApartInTheLightWorld() {
        Player player = lightPlayer(EquipmentType.ECLIPSE_RELAY);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 200.0, 100.0));
        assertEquals(1, attacks.getProjectiles().size(), "第一颗立即发射");
        assertEquals(1, attacks.getPendingCount(), "第二颗要等 0.10 秒");
        assertEquals(0.55, attacks.getProjectiles().getFirst().getDamageCoefficient(), EPS);

        attacks.update(0.10 + 0.001);
        assertEquals(2, attacks.getProjectiles().size(), "两颗弹构成一轮攻击");
        for (Projectile projectile : attacks.getProjectiles()) {
            assertEquals(0.55, projectile.getDamageCoefficient(), EPS);
        }
    }

    @Test
    void eclipseRelayReslashesInTheShadowWorldAfterAPause() {
        Player player = shadowPlayer(EquipmentType.ECLIPSE_RELAY);
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 100.0, 200.0));
        assertTrue(attacks.isMeleeVisible(), "第一段立即斩出");
        assertEquals(0.75, attacks.getMeleeCoefficient(), EPS, "主斩 0.75");
        assertEquals(1, attacks.getPendingCount(), "复斩要等 0.22 秒");

        attacks.update(0.22 + 0.001);
        assertEquals(0, attacks.getPendingCount());
        assertEquals(0.45, attacks.getMeleeCoefficient(), EPS, "复斩是 0.45，不能读成主斩的系数");
    }

    // ---- 通用规则 ----

    @Test
    void weaponsReplaceTheAttackPlanInsteadOfStackingDamageBonuses() {
        // 设计文档 §三：「特效武器不再附带原武器的加成」「不能同时得到三叉杖与贯日长杖的能力」。
        Player player = lightPlayer(EquipmentType.PRISM_FAN_WAND, EquipmentType.SUNLANCE);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);

        // 只按优先级启用一件：长杖优先，所以是 1 发穿透光矛，而不是 3 发散射。
        assertEquals(1, attacks.getProjectiles().size());
        assertEquals(Projectile.Behaviour.PIERCE, attacks.getProjectiles().getFirst().getBehaviour());
        assertEquals(0.0, player.getEquipmentDamageBonus(), EPS,
                "改变攻击方式的武器不再额外贡献百分比伤害加成");
    }

    @Test
    void oneSidedWeaponsFallBackToTheBaseAttackInTheOtherWorld() {
        // 「每件单属性装备进入另一界后恢复该界基础攻击」。
        Player player = lightPlayer(EquipmentType.PRISM_FAN_WAND);
        player.toggleWorld();
        PlayerAttackSystem attacks = new PlayerAttackSystem();

        assertTrue(attacks.tryAttack(player, 100.0, 200.0));
        assertTrue(attacks.getProjectiles().isEmpty(), "影界不该出现光弹");
        assertEquals(GameConfig.SHADOW_MELEE_ARC_DEGREES, attacks.getMeleeArcDegrees(), EPS,
                "回到影界的基础扇形");
        assertEquals(1.0, attacks.getCurrentProfile().coefficient(0), EPS, "基础影斩伤害 1.00");
    }

    @Test
    void switchingWorldClearsPendingDelayedAttacks() {
        // 「切界清理未发射的第二弹和未发生的复斩，不能把旧界攻击带进新界」。
        Player player = lightPlayer(EquipmentType.ECLIPSE_RELAY);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        attacks.tryAttack(player, 200.0, 100.0);
        assertEquals(1, attacks.getPendingCount());

        attacks.clearTransientAttacks();

        assertEquals(0, attacks.getPendingCount(), "切界必须清掉还没发生的第二颗");
        assertTrue(attacks.getProjectiles().isEmpty());
        assertEquals(0, attacks.getWorldAttackCount(), "余震指环的计数也要清零");
    }

    @Test
    void attackChargeIsConsumedOncePerRoundNotPerPellet() {
        // 设计文档：「每次攻击」指成功启动的一轮攻击，不是每颗弹丸。
        Player player = lightPlayer(EquipmentType.PRISM_FAN_WAND);
        PlayerAttackSystem attacks = new PlayerAttackSystem();
        int before = player.getAttackCharges();

        attacks.tryAttack(player, 200.0, 100.0);

        assertEquals(before - 1, player.getAttackCharges(), "三发散射只算一轮攻击");
    }
}
