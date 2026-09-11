package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.combat.DamageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    @Test
    void diagonalMovementIsNormalized() {
        Player player = new Player(100, 100);
        player.move(1, 1, 1.0, 0, 0, 1000, 1000);

        double travelled = Math.hypot(player.getX() - 100, player.getY() - 100);
        assertEquals(GameConfig.PLAYER_BASE_SPEED, travelled, 0.0001);
    }

    @Test
    void movementIsClampedToRoomBounds() {
        Player player = new Player(100, 100);
        player.move(-1, 0, 10.0, 50, 50, 200, 200);
        assertEquals(50, player.getX());
    }

    @Test
    void playerStartsWithAHundredPointHealthBarAndAFullShield() {
        Player player = new Player(0, 0);

        assertEquals(100, player.maxHp(), "生命刻度应当是 100 点");
        assertEquals(100, player.getHp());
        assertEquals(GameConfig.PLAYER_SHIELD_CAPACITY, player.getMaxShield(), 1e-9);
        assertEquals(player.getMaxShield(), player.getShield(), 1e-9, "开局应当带一整条护盾");
        assertTrue(player.hasShield());
    }

    @Test
    void shieldEatsTheHitAndHealthStaysUntouched() {
        Player player = new Player(0, 0);

        Player.DamageResult result = player.takeDamage(12.0, DamageType.SHADOW);

        assertNotNull(result);
        assertEquals(12.0, result.absorbedByShield(), 1e-9);
        assertEquals(0, result.healthLost(), "护盾够挡时不该掉血");
        assertEquals(100, player.getHp());
        assertEquals(18.0, player.getShield(), 1e-9);
    }

    @Test
    void damageThatBreaksTheShieldStillReachesHealth() {
        Player player = new Player(0, 0);
        player.clearShield();
        player.restoreShield(3.0);   // 只剩 3 点盾

        Player.DamageResult result = player.takeDamage(10.0, DamageType.PHYSICAL);

        assertNotNull(result);
        assertEquals(3.0, result.absorbedByShield(), 1e-9, "剩下多少盾就挡多少");
        assertEquals(7, result.healthLost(), "挡不住的 7 点必须打进生命");
        assertEquals(93, player.getHp());
        assertEquals(0.0, player.getShield(), 1e-9);
    }

    @Test
    void absurdlyBigHitIsNotFullyAbsorbedByAFullShield() {
        // 回归防线：护盾是“一次性血量”，不是免伤。首领的重招打穿 30 点盾之后，
        // 多出来的部分一定要继续扣血，否则装上护盾就等于无敌。
        Player player = new Player(0, 0);
        player.refillShield();

        Player.DamageResult result = player.takeDamage(100.0, DamageType.SHADOW);

        assertNotNull(result);
        assertEquals(GameConfig.PLAYER_SHIELD_CAPACITY, result.absorbedByShield(), 1e-9);
        assertEquals(70, result.healthLost());
        assertEquals(30, player.getHp());
    }

    @Test
    void fractionalDamageRoundsUpSoEveryHitCostsAtLeastOnePoint() {
        Player player = new Player(0, 0);
        player.clearShield();

        Player.DamageResult result = player.takeDamage(0.4, DamageType.PHYSICAL);

        assertNotNull(result);
        assertFalse(result.absorbedByShield() > 0.0);
        assertEquals(1, result.healthLost(), "不足 1 点的伤害也要至少扣 1 点，不能白吃");
        assertEquals(99, player.getHp());
    }

    @Test
    void invulnerabilityWindowBlocksRepeatedHitsAndProtectsTheShield() {
        Player player = new Player(0, 0);
        player.clearShield();

        assertNotNull(player.takeDamage(9.0, DamageType.LIGHT));
        assertNull(player.takeDamage(9.0, DamageType.LIGHT), "无敌帧内的第二次命中必须被忽略");
        assertEquals(91, player.getHp(), "同一帧的重叠弹幕不能重复扣血");
    }

    @Test
    void shieldIsNotHealedByHealthPotions() {
        Player player = new Player(0, 0);
        player.takeDamage(40.0, DamageType.SHADOW);   // 30 点盾全碎，另扣 10 点血
        assertEquals(0.0, player.getShield(), 1e-9);
        assertEquals(90, player.getHp());

        player.restoreHealthByPickups(2);   // 两瓶药剂 = 20 点血

        assertEquals(100, player.getHp());
        assertEquals(0.0, player.getShield(), 1e-9, "药剂只回生命，不会白送护盾");
    }

    @Test
    void healingWithTwoPotionsRestoresTwentyPointsOnTheHundredScale() {
        Player player = new Player(0, 0);
        player.clearShield();
        player.takeDamage(50.0, DamageType.SHADOW);

        player.restoreHealthByPickups(2);

        assertEquals(70, player.getHp(), "一瓶药剂 = 10 点生命，两瓶就是 20 点");
    }
    @Test
    void healingNeverGoesOverTheMaximum() {
        Player player = new Player(0, 0);
        player.restoreHealthByPickups(99);
        assertEquals(100, player.getHp());
    }

    @Test
    void refillShieldRestoresTheWholeBarForTheNextFloor() {
        Player player = new Player(0, 0);
        player.takeDamage(20.0, DamageType.LIGHT);
        assertTrue(player.getShield() < player.getMaxShield());

        player.refillShield();

        assertEquals(player.getMaxShield(), player.getShield(), 1e-9);
    }

    @Test
    void shieldEquipmentEnlargesTheCapacityWithoutRefillingIt() {
        Player player = new Player(0, 0);
        player.clearShield();

        player.equip(EquipmentType.PHASE_VESSEL);

        assertEquals(GameConfig.PLAYER_SHIELD_CAPACITY + EquipmentType.PHASE_VESSEL.shieldCapacityBonus(),
                player.getMaxShield(), 1e-9);
        assertEquals(0.0, player.getShield(), 1e-9, "换装备不该白送一整条护盾");
    }

    @Test
    void shieldBarRatioIsExpressedOnTheHealthScale() {
        // 护盾条是叠在血条上的，必须换算到生命刻度：30 点盾 / 100 点血 = 血条长度的 30%，
        // 而不是护盾自己容量的 100%（否则满盾时护盾条会铺满整条血条，看不出还剩多少）。
        Player player = new Player(0, 0);
        assertEquals(0.3, player.getShieldBarRatio(), 1e-9);

        player.takeDamage(15.0, DamageType.SHADOW);

        assertEquals(0.15, player.getShieldBarRatio(), 1e-9);
    }

    @Test
    void deadPlayerTakesNoFurtherDamage() {
        Player player = new Player(0, 0);
        player.clearShield();

        assertNotNull(player.takeDamage(100.0, DamageType.SHADOW));
        assertEquals(0, player.getHp());
        assertNull(player.takeDamage(5.0, DamageType.SHADOW));
        assertEquals(0, player.getHp());
    }
}
