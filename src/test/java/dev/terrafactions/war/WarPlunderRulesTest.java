package dev.terrafactions.war;

import dev.terrafactions.territory.TerritoryKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarPlunderRulesTest {
    @Test
    void onlyDeclaredTargetsBreachedByTheCorrectSideInTheCorrectWarCount() {
        UUID warId = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        WarGoalSnapshot goal = WarGoalSnapshot.selected(WarGoalType.PLUNDER)
                .withTargets(Set.of("mine", "factory"));
        List<PlunderBreachSnapshot> breaches = List.of(
                breach("mine", owner, attacker, warId, 100, 200),
                breach("unlisted", owner, attacker, warId, 100, 200),
                breach("factory", owner, UUID.randomUUID(), warId, 100, 200),
                breach("factory", owner, attacker, UUID.randomUUID(), 100, 200));

        assertEquals(Set.of("mine"), WarPlunderRules.breachedTargets(goal, breaches, warId, attacker));
        assertFalse(WarPlunderRules.isComplete(goal, breaches, warId, attacker));

        List<PlunderBreachSnapshot> completed = List.of(
                breach("mine", owner, attacker, warId, 100, 101),
                breach("factory", owner, attacker, warId, 100, 200));
        assertTrue(WarPlunderRules.isComplete(goal, completed, warId, attacker));
    }

    @Test
    void breachUsesAChunkCircleAndAnExclusiveExpiration() {
        PlunderBreachSnapshot breach = breach("mine", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 100, 200);

        assertTrue(breach.active(199));
        assertFalse(breach.active(200));
        assertTrue(breach.contains(new TerritoryKey("minecraft:overworld", 13, 20)));
        assertFalse(breach.contains(new TerritoryKey("minecraft:overworld", 13, 21)));
        assertFalse(breach.contains(new TerritoryKey("minecraft:the_nether", 10, 20)));
    }

    @Test
    void breachCircleContinuesAcrossAWorldSeam() {
        PlunderBreachSnapshot breach = new PlunderBreachSnapshot(UUID.randomUUID(), UUID.randomUUID(), "edge",
                UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0, 0, 1, 100, 200);

        assertTrue(breach.contains(new TerritoryKey("minecraft:overworld", 9, 0),
                WarPlunderRulesTest::wrappedDistanceSquared));
    }

    private static long wrappedDistanceSquared(TerritoryKey first, TerritoryKey second) {
        long directX = Math.abs((long) Math.floorMod(first.x(), 10) - Math.floorMod(second.x(), 10));
        long dx = Math.min(directX, 10L - directX);
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static PlunderBreachSnapshot breach(String anchorId, UUID owner, UUID attacker,
                                                  UUID warId, long start, long end) {
        return new PlunderBreachSnapshot(UUID.randomUUID(), warId, anchorId, owner, attacker,
                "minecraft:overworld", 10, 20, 3, start, end);
    }
}
