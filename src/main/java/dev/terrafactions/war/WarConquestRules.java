package dev.terrafactions.war;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class WarConquestRules {
    private WarConquestRules() {
    }

    public static Set<String> heldTargets(WarGoalSnapshot goal,
                                          Collection<AnchorOccupationSnapshot> occupations,
                                          UUID warId, UUID occupyingFactionId) {
        if (goal.type() != WarGoalType.CONQUEST) return Set.of();
        return occupations.stream()
                .filter(occupation -> occupation.warId().equals(warId)
                        && occupation.occupyingFactionId().equals(occupyingFactionId)
                        && goal.targetAnchorIds().contains(occupation.anchorId()))
                .map(AnchorOccupationSnapshot::anchorId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isComplete(WarGoalSnapshot goal,
                                     Collection<AnchorOccupationSnapshot> occupations,
                                     UUID warId, UUID occupyingFactionId) {
        return !goal.targetAnchorIds().isEmpty()
                && heldTargets(goal, occupations, warId, occupyingFactionId).size()
                == goal.targetAnchorIds().size();
    }
}
