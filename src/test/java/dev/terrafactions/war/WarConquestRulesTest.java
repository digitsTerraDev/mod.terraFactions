package dev.terrafactions.war;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarConquestRulesTest {
    @Test
    void onlyDeclaredTargetsHeldByTheCorrectSideInTheCorrectWarCount() {
        UUID warId = UUID.randomUUID();
        UUID occupier = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        WarGoalSnapshot conquest = WarGoalSnapshot.selected(WarGoalType.CONQUEST)
                .withTargets(Set.of("north", "west"));
        List<AnchorOccupationSnapshot> occupations = List.of(
                occupation("north", owner, occupier, warId),
                occupation("unlisted", owner, occupier, warId),
                occupation("west", owner, UUID.randomUUID(), warId),
                occupation("west", owner, occupier, UUID.randomUUID()));

        assertEquals(Set.of("north"),
                WarConquestRules.heldTargets(conquest, occupations, warId, occupier));
        assertFalse(WarConquestRules.isComplete(conquest, occupations, warId, occupier));

        List<AnchorOccupationSnapshot> completed = List.of(
                occupation("north", owner, occupier, warId),
                occupation("west", owner, occupier, warId));
        assertTrue(WarConquestRules.isComplete(conquest, completed, warId, occupier));
    }

    @Test
    void integrationPowerDecaysLinearlyAndNeverBecomesNegative() {
        TemporaryPowerModifierSnapshot modifier = new TemporaryPowerModifierSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 200.0D,
                100, 300, PowerModifierType.CONQUEST_INTEGRATION);

        assertEquals(200.0D, modifier.currentAmount(100));
        assertEquals(150, modifier.currentWholePower(150));
        assertEquals(100, modifier.currentWholePower(200));
        assertEquals(0.0D, modifier.currentAmount(300));
        assertEquals(0.0D, modifier.currentAmount(400));
    }

    private static AnchorOccupationSnapshot occupation(String anchorId, UUID owner, UUID occupier, UUID warId) {
        return new AnchorOccupationSnapshot(anchorId, owner, occupier, warId, 100);
    }
}
