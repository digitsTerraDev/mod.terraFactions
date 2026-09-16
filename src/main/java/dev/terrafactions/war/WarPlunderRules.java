package dev.terrafactions.war;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure progress rules for target-based Plunder goals. */
public final class WarPlunderRules {
    private WarPlunderRules() {
    }

    public static Set<String> breachedTargets(WarGoalSnapshot goal,
                                               Collection<PlunderBreachSnapshot> breaches,
                                               UUID warId, UUID breachingFactionId) {
        if (goal == null || goal.type() != WarGoalType.PLUNDER) return Set.of();
        return breaches.stream()
                .filter(breach -> breach.warId().equals(warId))
                .filter(breach -> breach.breachingFactionId().equals(breachingFactionId))
                .map(PlunderBreachSnapshot::anchorId)
                .filter(goal.targetAnchorIds()::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isComplete(WarGoalSnapshot goal,
                                     Collection<PlunderBreachSnapshot> breaches,
                                     UUID warId, UUID breachingFactionId) {
        return goal != null && goal.type() == WarGoalType.PLUNDER
                && !goal.targetAnchorIds().isEmpty()
                && breachedTargets(goal, breaches, warId, breachingFactionId)
                .containsAll(goal.targetAnchorIds());
    }
}
