package dev.terrafactions.war;

public final class WarRules {
    public record CampDestructionOutcome(WarSnapshot war, java.util.UUID completedDefenseFactionId) {
    }

    private WarRules() {
    }

    public static WarSnapshot withDefaultDefenderGoal(WarSnapshot war) {
        return war.defender().warGoal() == null
                ? war.withDefender(war.defender().withGoal(WarGoalSnapshot.selected(WarGoalType.DEFENSE)))
                : war;
    }

    public static boolean canEstablishWarCamp(WarSnapshot war, java.util.UUID factionId) {
        WarSideSnapshot side = war.side(factionId);
        return war.state() == WarState.ACTIVE && side != null && side.warGoal() != null
                && side.warGoal().requiresWarCamp() && !side.goalCompleted() && !side.goalFailed()
                && side.warCampId() == null;
    }

    public static boolean hasViableOffensiveGoal(WarSnapshot war) {
        return viableOffensive(war.attacker()) || viableOffensive(war.defender());
    }

    public static boolean campPlacementDeadlineExpired(WarSnapshot war, java.util.UUID factionId,
                                                         long now, long deadlineTicks) {
        WarSideSnapshot side = war.side(factionId);
        return war.state() == WarState.ACTIVE && side != null && viableOffensive(side)
                && side.warCampId() == null && elapsed(war.startedAt(), now) >= Math.max(0L, deadlineTicks);
    }

    public static boolean maximumDurationExpired(WarSnapshot war, long now, long maximumDurationTicks) {
        return war.state() == WarState.ACTIVE
                && elapsed(war.startedAt(), now) >= Math.max(0L, maximumDurationTicks);
    }

    public static CampDestructionOutcome resolveCampDestruction(WarSnapshot war,
                                                                 java.util.UUID campOwnerFactionId) {
        WarSideSnapshot owner = war.side(campOwnerFactionId);
        if (owner == null || owner.warGoal() == null || !owner.warGoal().requiresWarCamp()) {
            throw new IllegalArgumentException("The War Camp owner is not an offensive side of this war");
        }
        WarSnapshot updated = war;
        if (!owner.goalFailed()) {
            WarSideSnapshot failed = owner.fail("War Camp destroyed");
            updated = war.attackerFactionId().equals(owner.factionId())
                    ? war.withAttacker(failed) : war.withDefender(failed);
        }
        WarSideSnapshot opponent = updated.attackerFactionId().equals(owner.factionId())
                ? updated.defender() : updated.attacker();
        java.util.UUID completedDefense = null;
        if (opponent.warGoal() != null && opponent.warGoal().type() == WarGoalType.DEFENSE
                && !opponent.goalCompleted() && !opponent.goalFailed()) {
            WarSideSnapshot completed = opponent.complete("Destroyed the opposing War Camp");
            updated = updated.attackerFactionId().equals(opponent.factionId())
                    ? updated.withAttacker(completed) : updated.withDefender(completed);
            completedDefense = opponent.factionId();
        }
        return new CampDestructionOutcome(updated, completedDefense);
    }

    private static boolean viableOffensive(WarSideSnapshot side) {
        return side.warGoal() != null && side.warGoal().requiresWarCamp()
                && !side.goalCompleted() && !side.goalFailed();
    }

    private static long elapsed(long startedAt, long now) {
        if (now <= startedAt) return 0L;
        long elapsed = now - startedAt;
        return elapsed < 0L ? Long.MAX_VALUE : elapsed;
    }
}
