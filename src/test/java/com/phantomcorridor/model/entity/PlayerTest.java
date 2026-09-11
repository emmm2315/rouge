package com.phantomcorridor.model.entity;

import com.phantomcorridor.config.GameConfig;
import com.phantomcorridor.model.EquipmentType;
import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.combat.DamageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    @Test
    void equipmentAllowsDuplicatesAndNeverEvictsAnOlderSlotAutomatically() {
        Player player = new Player(0, 0);
        player.equip(EquipmentType.DAWN_WAND);
        player.equip(EquipmentType.DAWN_WAND);
        player.equip(EquipmentType.DAWN_SEAL);
        player.equip(EquipmentType.SHADOW_FANG);

        assertEquals(3, player.getEquipment().size());
        assertEquals(2, player.getEquipment().stream().filter(item -> item == EquipmentType.DAWN_WAND).count());
        assertEquals(EquipmentType.DAWN_WAND, player.getEquipment().get(0));
        assertEquals(EquipmentType.DAWN_WAND, player.getEquipment().get(1));
    }

    @Test
    void replacementAndDiscardReturnTheExactEquipment() {
        Player player = new Player(0, 0);
        player.equip(EquipmentType.DAWN_WAND);
        player.equip(EquipmentType.SHADOW_FANG);
        player.equip(EquipmentType.DAWN_SEAL);

        assertEquals(EquipmentType.SHADOW_FANG, player.replaceEquipment(1, EquipmentType.CRESCENT_REAPER));
        assertEquals(EquipmentType.CRESCENT_REAPER, player.getEquipment().get(1));
        assertEquals(EquipmentType.DAWN_SEAL, player.removeEquipment(2));
        assertEquals(2, player.getEquipment().size());
    }

    @Test
    void duplicateEquipmentStacksItsStatBonus() {
        Player player = new Player(0, 0);
        player.equip(EquipmentType.WAYFARER_HEART);
        player.equip(EquipmentType.WAYFARER_HEART);

        assertEquals(2, player.equipmentCount(EquipmentType.WAYFARER_HEART));
        assertEquals(140, player.maxHp());
    }

    @Test
    void movementSpeedIsBaseSpeedInTheLightWorld() {
        Player player = new Player(0, 0);

        assertEquals(GameConfig.PLAYER_BASE_SPEED, player.movementSpeed(), 0.0001);
    }

    @Test
    void duskCloakStacksItsShadowSpeedBonusPerCopy() {
        Player player = new Player(0, 0);
        player.toggleWorld();
        double shadowBase = GameConfig.PLAYER_BASE_SPEED * GameConfig.SHADOW_SPEED_MULTIPLIER;
        assertEquals(shadowBase, player.movementSpeed(), 0.0001, "影界本身更快，但没斗篷时不该再多");

        player.equip(EquipmentType.DUSK_CLOAK);
        assertEquals(shadowBase * 1.10, player.movementSpeed(), 0.0001);

        player.equip(EquipmentType.DUSK_CLOAK);
        assertEquals(shadowBase * 1.20, player.movementSpeed(), 0.0001, "同名斗篷的加成必须叠加");

        player.toggleWorld();
        assertEquals(GameConfig.PLAYER_BASE_SPEED, player.movementSpeed(), 0.0001, "斗篷只改影界移速");
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
    void aFreshShieldIsFullEvenWhenItsCapacityIsNotTheBaseValue() {
        // HUD 的护盾胶囊按「当前盾 / 护盾上限」填充，所以新一局必须画满：
        // 旧版把填充算成「当前盾 / 生命上限」，只要生命上限不是 100（行者心核 +20%）
        // 或者护盾上限不是 30，开局就会显示成一条没填满的盾。
        Player player = new Player(0, 0);
        assertEquals(1.0, player.getShieldRatio(), 1e-9, "开局护盾必须是满的");

        player.equip(EquipmentType.WAYFARER_HEART);
        assertEquals(1.0, player.getShieldRatio(), 1e-9, "生命上限被心核抬高不该影响护盾自己的比例");

        // 相位容器抬的是**上限**，剩余量保持不变（否则“换一件饰品”就白送一整条护盾，见 Shield.setCapacity）。
        // 所以比例会掉下来，但护盾点数本身没有变化——胶囊因此同时读得出“还剩多少”和“上限被抬高了”。
        double shieldBefore = player.getShield();
        player.equip(EquipmentType.PHASE_VESSEL);
        assertEquals(GameConfig.PLAYER_SHIELD_CAPACITY + EquipmentType.PHASE_VESSEL.shieldCapacityBonus(),
                player.getMaxShield(), 1e-9);
        assertEquals(shieldBefore, player.getShield(), 1e-9, "换装备不能白送护盾点数");
        assertTrue(player.getShieldRatio() < 1.0, "上限抬高后按新上限算就不满了");

        player.takeDamage(6.0, DamageType.PHYSICAL);
        assertTrue(player.getShieldRatio() < 1.0, "挨打之后不再是满盾");
    }

    @Test
    void damageBonusIsSplitBetweenEquipmentAndItems() {
        // 设计文档的口径：伤害 = 基础伤害 × 段系数 ×（1 + 同类加成之和）。
        // 面板要把「装备给的那一份」单独显示出来，所以装备与道具的加成要能分别读到。
        Player player = new Player(0, 0);
        assertEquals(Player.BASE_ATTACK_DAMAGE, player.getCurrentBaseDamage(), 1e-9,
                "基础伤害就是角色面板上的基础攻击力");
        assertEquals(0.0, player.getDamageBonus(), 1e-9);
        assertEquals(0.0, player.getEquipmentDamageBonus(), 1e-9);
        assertEquals(1.0, player.damageMultiplier(WorldType.LIGHT), 1e-9);

        player.equip(EquipmentType.DAWN_WAND);
        assertEquals(0.20, player.getEquipmentDamageBonus(), 1e-9, "晨曦法杖光界 +20%");
        assertEquals(1.20, player.damageMultiplier(WorldType.LIGHT), 1e-9);
        // HUD 写的是「这一下会打出多少点」：基础 10 × 1.20 = 12。
        assertEquals("12", player.getDamageText());

        player.equip(EquipmentType.DAWN_SEAL);
        assertEquals(0.30, player.getEquipmentDamageBonus(), 1e-9, "圣印的 +10% 与法杖相加");

        // 光界装备在影界不提供加成，也不该留下任何残余。
        player.toggleWorld();
        assertEquals(0.0, player.getEquipmentDamageBonus(), 1e-9, "光界装备在影界不提供加成");
        assertEquals(1.0, player.damageMultiplier(WorldType.SHADOW), 1e-9);
    }

    @Test
    void focusLensAddsTwentyFivePercentLightDamageAndSlowsOnlyLightAttacks() {
        Player player = new Player(0, 0);
        player.equip(EquipmentType.FOCUS_LENS);

        assertEquals(0.25, player.getEquipmentDamageBonus(), 1e-9);
        assertEquals(1.15, player.getAttackCooldownMultiplier(WorldType.LIGHT), 1e-9,
                "凝光透镜的光界攻击间隔 ×1.15");
        assertEquals(1.0, player.getAttackCooldownMultiplier(WorldType.SHADOW), 1e-9,
                "影界既没有伤害加成，就不该白背一个间隔惩罚");
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
