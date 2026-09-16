package dev.terrafactions.war;

import java.util.Objects;
import java.util.Set;

/** Persistent, goal-specific state. Later phases can populate targets, progress, and results. */
public record WarGoalSnapshot(WarGoalType type, Set<String> targetAnchorIds,
                              int requiredObjectiveValue, int progress, String result) {
    public WarGoalSnapshot {
        type = Objects.requireNonNull(type);
        targetAnchorIds = Set.copyOf(targetAnchorIds);
        requiredObjectiveValue = Math.max(0, requiredObjectiveValue);
        progress = Math.max(0, progress);
        result = result == null ? "" : result;
    }

    public static WarGoalSnapshot selected(WarGoalType type) {
        return new WarGoalSnapshot(type, Set.of(), 0, 0, "");
    }

    public boolean requiresWarCamp() {
        return type.requiresWarCamp();
    }

    public WarGoalSnapshot withProgress(int value) {
        return new WarGoalSnapshot(type, targetAnchorIds, requiredObjectiveValue, value, result);
    }

    public WarGoalSnapshot withTargets(Set<String> values) {
        return new WarGoalSnapshot(type, values, values.size(), progress, result);
    }

    public WarGoalSnapshot withRequiredObjectiveValue(int value) {
        return new WarGoalSnapshot(type, targetAnchorIds, value, progress, result);
    }

    public WarGoalSnapshot withResult(String value) {
        return new WarGoalSnapshot(type, targetAnchorIds, requiredObjectiveValue, progress, value);
    }
}
