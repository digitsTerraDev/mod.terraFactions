package dev.terrafactions.territory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionActionTest {
    @Test
    void defaultsPreserveExistingCoreAndBorderBehavior() {
        int core = ProtectionAction.defaultMask(TerritoryType.CORE);
        int border = ProtectionAction.defaultMask(TerritoryType.BORDER);

        for (ProtectionAction action : ProtectionAction.values()) {
            if (action != ProtectionAction.PVP) assertTrue(action.enabledIn(core));
        }
        assertFalse(ProtectionAction.PVP.enabledIn(core));
        assertTrue(ProtectionAction.BLOCK_BREAKING.enabledIn(border));
        assertTrue(ProtectionAction.BLOCK_PLACEMENT.enabledIn(border));
        assertTrue(ProtectionAction.LIQUID_PLACEMENT.enabledIn(border));
        assertTrue(ProtectionAction.EXPLOSIONS.enabledIn(border));
        assertFalse(ProtectionAction.BLOCK_INTERACTIONS.enabledIn(border));
        assertFalse(ProtectionAction.ENTITY_INTERACTIONS.enabledIn(border));
        assertFalse(ProtectionAction.PVP.enabledIn(border));
    }

    @Test
    void serverPoliciesOverrideFactionChoice() {
        assertTrue(ProtectionPolicy.FORCED_ON.resolve(false));
        assertFalse(ProtectionPolicy.FORCED_OFF.resolve(true));
        assertTrue(ProtectionPolicy.FACTION_CONTROLLED.resolve(true));
        assertFalse(ProtectionPolicy.FACTION_CONTROLLED.resolve(false));
    }
}
