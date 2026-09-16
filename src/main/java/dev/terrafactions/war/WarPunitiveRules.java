package dev.terrafactions.war;

import dev.terrafactions.anchor.AnchorMapSnapshot;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure progress rules for Power-based Punitive objectives. */
public final class WarPunitiveRules {
    private WarPunitiveRules() {
    }

    public static int occupiedAnchorPower(Collection<AnchorOccupationSnapshot> occupations,
                                          Collection<AnchorMapSnapshot> anchors,
                                          UUID warId, UUID occupyingFactionId) {
        Map<String, AnchorMapSnapshot> anchorsById = anchors.stream()
                .collect(Collectors.toMap(AnchorMapSnapshot::id, Function.identity(), (first, ignored) -> first));
        long total = occupations.stream()
                .filter(occupation -> occupation.warId().equals(warId))
                .filter(occupation -> occupation.occupyingFactionId().equals(occupyingFactionId))
                .map(AnchorOccupationSnapshot::anchorId)
                .distinct()
                .map(anchorsById::get)
                .filter(java.util.Objects::nonNull)
                .mapToLong(anchor -> Math.max(0, anchor.allocatedPower()))
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static boolean isComplete(WarGoalSnapshot goal, int occupiedPower) {
        return goal != null && goal.type() == WarGoalType.PUNITIVE
                && goal.requiredObjectiveValue() > 0
                && occupiedPower >= goal.requiredObjectiveValue();
    }

    public static double strongestSuppression(Collection<TemporaryPowerModifierSnapshot> modifiers,
                                               UUID factionId, long now) {
        return modifiers.stream()
                .filter(modifier -> modifier.factionId().equals(factionId))
                .filter(modifier -> modifier.type() == PowerModifierType.PUNITIVE_SUPPRESSION)
                .mapToDouble(modifier -> modifier.currentAmount(now))
                .max().orElse(0.0D);
    }
}
