package com.phantomcorridor.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 护盾（临时生命值）的纯数值行为：先挡伤害、挡不住的溢出照常打进生命。
 */
class ShieldTest {

    @Test
    void shieldAbsorbsBeforeAnythingElse() {
        Shield shield = new Shield(30.0);

        assertEquals(0.0, shield.absorb(12.0), 1e-9, "12 点伤害应当被 30 点护盾完全吸收");
        assertEquals(18.0, shield.getCurrent(), 1e-9);
        assertTrue(shield.isActive());
    }

    @Test
    void overflowOfABigHitFlowsThrough() {
        Shield shield = new Shield(30.0);
        shield.absorb(28.0);

        // 剩余 2 点盾，挨一记 20 点重击：挡下 2 点，剩下 18 点必须漏出去。
        assertEquals(18.0, shield.absorb(20.0), 1e-9);
        assertEquals(0.0, shield.getCurrent(), 1e-9);
        assertFalse(shield.isActive());
    }

    @Test
    void emptiedShieldPassesDamageStraightThrough() {
        Shield shield = new Shield(30.0);
        shield.absorb(30.0);

        assertEquals(7.0, shield.absorb(7.0), 1e-9, "盾碎之后伤害必须全额进生命");
    }

    @Test
    void refillRestoresTheWholeBar() {
        Shield shield = new Shield(30.0);
        shield.absorb(30.0);

        shield.refill();

        assertEquals(30.0, shield.getCurrent(), 1e-9);
        assertEquals(1.0, shield.getRatio(), 1e-9);
    }

    @Test
    void changingCapacityDoesNotHandOutAFreeShield() {
        Shield shield = new Shield(30.0);
        shield.absorb(30.0);

        // 换上一件撑容量的饰品：容量变大，但已经打光的盾不会因此白回满。
        shield.setCapacity(42.0);

        assertEquals(42.0, shield.getCapacity(), 1e-9);
        assertEquals(0.0, shield.getCurrent(), 1e-9);
    }

    @Test
    void shrinkingCapacityClampsTheRemainder() {
        Shield shield = new Shield(42.0);

        shield.setCapacity(30.0);

        assertEquals(30.0, shield.getCurrent(), 1e-9, "剩余量不能超过新的容量上限");
    }

    @Test
    void zeroDamageIsANoOp() {
        Shield shield = new Shield(30.0);

        assertEquals(0.0, shield.absorb(0.0), 1e-9);
        assertEquals(0.0, shield.absorb(-5.0), 1e-9);
        assertEquals(30.0, shield.getCurrent(), 1e-9);
    }
}
