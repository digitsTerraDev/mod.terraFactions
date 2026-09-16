package dev.terrafactions.war;

import dev.terrafactions.anchor.AnchorConnectionState;
import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorPowerState;
import dev.terrafactions.anchor.AnchorTier;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarPunitiveRulesTest {
    @Test
    void progressCountsUniqueOccupiedAnchorAllocationsForTheCorrectWarAndFaction() {
        UUID warId = UUID.randomUUID();
        UUID occupier = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        List<AnchorMapSnapshot> anchors = List.of(anchor("a", owner, 100), anchor("b", owner, 80));
        List<AnchorOccupationSnapshot> occupations = List.of(
                occupation("a", owner, occupier, warId),
                occupation("a", owner, occupier, warId),
                occupation("b", owner, occupier, warId),
                occupation("b", owner, UUID.randomUUID(), warId),
                occupation("b", owner, occupier, UUID.randomUUID()));

        int progress = WarPunitiveRules.occupiedAnchorPower(occupations, anchors, warId, occupier);
        WarGoalSnapshot goal = WarGoalSnapshot.selected(WarGoalType.PUNITIVE)
                .withRequiredObjectiveValue(180);

        assertEquals(180, progress);
        assertTrue(WarPunitiveRules.isComplete(goal, progress));
        assertFalse(WarPunitiveRules.isComplete(goal.withRequiredObjectiveValue(181), progress));
    }

    @Test
    void onlyStrongestActivePunitiveSuppressionApplies() {
        UUID factionId = UUID.randomUUID();
        List<TemporaryPowerModifierSnapshot> modifiers = List.of(
                modifier(factionId, 0.10D, 100, 300, PowerModifierType.PUNITIVE_SUPPRESSION),
                modifier(factionId, 0.25D, 150, 400, PowerModifierType.PUNITIVE_SUPPRESSION),
                modifier(factionId, 50.0D, 100, 400, PowerModifierType.CONQUEST_INTEGRATION),
                modifier(UUID.randomUUID(), 0.90D, 100, 400, PowerModifierType.PUNITIVE_SUPPRESSION));

        assertEquals(0.25D, WarPunitiveRules.strongestSuppression(modifiers, factionId, 200));
        assertEquals(0.0D, WarPunitiveRules.strongestSuppression(modifiers, factionId, 400));
    }

    @Test
    void punitiveSuppressionStaysConstantUntilItsExpiration() {
        TemporaryPowerModifierSnapshot suppression = modifier(UUID.randomUUID(), 0.20D,
                100, 300, PowerModifierType.PUNITIVE_SUPPRESSION);

        assertEquals(0.20D, suppression.currentAmount(100));
        assertEquals(0.20D, suppression.currentAmount(299));
        assertEquals(0.0D, suppression.currentAmount(300));
    }

    private static TemporaryPowerModifierSnapshot modifier(UUID factionId, double amount,
                                                            long start, long end, PowerModifierType type) {
        return new TemporaryPowerModifierSnapshot(UUID.randomUUID(), factionId, UUID.randomUUID(),
                amount, start, end, type);
    }

    private static AnchorOccupationSnapshot occupation(String anchorId, UUID owner,
                                                        UUID occupier, UUID warId) {
        return new AnchorOccupationSnapshot(anchorId, owner, occupier, warId, 100);
    }

    private static AnchorMapSnapshot anchor(String id, UUID owner, int allocatedPower) {
        return new AnchorMapSnapshot(id, owner, "minecraft:overworld", 0, 64, 0,
                AnchorTier.BASIC, allocatedPower, allocatedPower * 10, 0, 1, 5,
                AnchorPowerState.FULL, AnchorConnectionState.CONNECTED,
                AnchorVulnerabilityState.PROTECTED, 0);
    }
}
