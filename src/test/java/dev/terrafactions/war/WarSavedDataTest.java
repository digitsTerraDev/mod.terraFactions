package dev.terrafactions.war;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarSavedDataTest {
    @Test
    void warGoalsBelongToIndependentSides() {
        UUID warId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarGoalSnapshot conquest = new WarGoalSnapshot(
                WarGoalType.CONQUEST, Set.of("overworld/123", "overworld/456"), 2, 1, "pending");
        WarGoalSnapshot defense = WarGoalSnapshot.selected(WarGoalType.DEFENSE);
        WarSnapshot expected = new WarSnapshot(warId, attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, conquest, UUID.randomUUID(), false, false),
                new WarSideSnapshot(defenderId, defense, null, true, false),
                100, 200, 200, 0);
        assertEquals(WarGoalType.CONQUEST, expected.attacker().warGoal().type());
        assertEquals(Set.of("overworld/123", "overworld/456"),
                expected.attacker().warGoal().targetAnchorIds());
        assertEquals(WarGoalType.DEFENSE, expected.defender().warGoal().type());
        assertTrue(expected.defender().goalCompleted());
        assertTrue(expected.attacker().warGoal().requiresWarCamp());
        assertFalse(expected.defender().warGoal().requiresWarCamp());
    }

    @Test
    void defenderGoalMayRemainUnselectedDuringPreparation() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot war = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.PREPARING,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.PLUNDER), null, false, false),
                new WarSideSnapshot(defenderId, null, null, false, false), 10, 20, 0, 0);

        assertNull(war.defender().warGoal());
        WarSnapshot selected = war.withDefender(
                war.defender().withGoal(WarGoalSnapshot.selected(WarGoalType.PUNITIVE)));
        assertEquals(WarGoalType.PUNITIVE, selected.defender().warGoal().type());
        assertEquals(WarGoalType.PLUNDER, selected.attacker().warGoal().type());
    }

    @Test
    void unselectedDefenderGoalDefaultsToDefenseWhenWarStarts() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot preparing = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.PREPARING,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST), null, false, false),
                new WarSideSnapshot(defenderId, null, null, false, false), 10, 20, 0, 0);

        WarSnapshot ready = WarRules.withDefaultDefenderGoal(preparing);

        assertEquals(WarGoalType.DEFENSE, ready.defender().warGoal().type());
        assertEquals(WarGoalType.CONQUEST, ready.attacker().warGoal().type());
    }

    @Test
    void onlyActiveUncommittedOffensiveSidesCanEstablishWarCamps() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST), null, false, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.DEFENSE), null, false, false),
                10, 20, 20, 0);

        assertTrue(WarRules.canEstablishWarCamp(active, attackerId));
        assertFalse(WarRules.canEstablishWarCamp(active, defenderId));
        WarSnapshot committed = active.withAttacker(active.attacker().withWarCamp(UUID.randomUUID()));
        assertFalse(WarRules.canEstablishWarCamp(committed, attackerId));
    }

    @Test
    void destroyedCampStateAndSabotageProgressRemainExplicit() {
        WarCampSnapshot camp = new WarCampSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", 10, 64, 20, WarCampState.ESTABLISHING, 100, 200, 0);

        assertEquals(WarCampState.ACTIVE, camp.withState(WarCampState.ACTIVE).state());
        assertEquals(7, camp.withDestructionProgress(7).destructionProgress());
        assertEquals(WarCampState.DESTROYED, camp.withState(WarCampState.DESTROYED).state());
    }

    @Test
    void destroyingOffensiveCampFailsOffenseAndCompletesOpposingDefense() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST),
                        UUID.randomUUID(), false, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.DEFENSE),
                        null, false, false), 10, 20, 20, 0);

        WarRules.CampDestructionOutcome outcome = WarRules.resolveCampDestruction(active, attackerId);

        assertTrue(outcome.war().attacker().goalFailed());
        assertTrue(outcome.war().defender().goalCompleted());
        assertEquals(defenderId, outcome.completedDefenseFactionId());
        assertFalse(WarRules.hasViableOffensiveGoal(outcome.war()));
    }

    @Test
    void destroyingOneCampDoesNotEndTheOtherSidesIndependentOffensive() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.PLUNDER),
                        UUID.randomUUID(), false, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.PUNITIVE),
                        UUID.randomUUID(), false, false), 10, 20, 20, 0);

        WarRules.CampDestructionOutcome outcome = WarRules.resolveCampDestruction(active, attackerId);

        assertTrue(outcome.war().attacker().goalFailed());
        assertFalse(outcome.war().defender().goalFailed());
        assertFalse(outcome.war().defender().goalCompleted());
        assertNull(outcome.completedDefenseFactionId());
        assertTrue(WarRules.hasViableOffensiveGoal(outcome.war()));
    }

    @Test
    void destroyingCampRevokesAProvisionallyCompletedOffensive() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST),
                        UUID.randomUUID(), true, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.PUNITIVE),
                        UUID.randomUUID(), false, false), 10, 20, 20, 0);

        WarRules.CampDestructionOutcome outcome = WarRules.resolveCampDestruction(active, attackerId);

        assertTrue(outcome.war().attacker().goalFailed());
        assertFalse(outcome.war().attacker().goalCompleted());
        assertTrue(WarRules.hasViableOffensiveGoal(outcome.war()));
    }

    @Test
    void missingOffensiveCampForfeitsAtTheDeadline() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST), null, false, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.DEFENSE), null, false, false),
                10, 20, 100, 0);

        assertFalse(WarRules.campPlacementDeadlineExpired(active, attackerId, 199, 100));
        assertTrue(WarRules.campPlacementDeadlineExpired(active, attackerId, 200, 100));
        assertFalse(WarRules.campPlacementDeadlineExpired(active, defenderId, 200, 100));
        assertFalse(WarRules.campPlacementDeadlineExpired(
                active.withAttacker(active.attacker().withWarCamp(UUID.randomUUID())), attackerId, 200, 100));
    }

    @Test
    void activeWarExpiresAtItsMaximumDuration() {
        UUID attackerId = UUID.randomUUID();
        UUID defenderId = UUID.randomUUID();
        WarSnapshot active = new WarSnapshot(UUID.randomUUID(), attackerId, defenderId, WarState.ACTIVE,
                new WarSideSnapshot(attackerId, WarGoalSnapshot.selected(WarGoalType.CONQUEST), null, false, false),
                new WarSideSnapshot(defenderId, WarGoalSnapshot.selected(WarGoalType.DEFENSE), null, false, false),
                10, 20, 100, 0);

        assertFalse(WarRules.maximumDurationExpired(active, 1099, 1000));
        assertTrue(WarRules.maximumDurationExpired(active, 1100, 1000));
        assertFalse(WarRules.maximumDurationExpired(active.withState(WarState.ENDED, 100, 1100), 1200, 1000));
    }

}
