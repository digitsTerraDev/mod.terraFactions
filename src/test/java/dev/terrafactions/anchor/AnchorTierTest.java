package dev.terrafactions.anchor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnchorTierTest {
    @Test
    void tiersUseExactFractionalPowerCosts() {
        assertEquals(10, AnchorTier.BASIC.powerTenthsPerClaim());
        assertEquals(5, AnchorTier.ADVANCED.powerTenthsPerClaim());
        assertEquals(2, AnchorTier.MASTER.powerTenthsPerClaim());
        assertEquals(1, AnchorTier.ULTIMATE.powerTenthsPerClaim());
    }
}
