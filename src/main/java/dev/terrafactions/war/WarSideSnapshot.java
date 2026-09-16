package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

public record WarSideSnapshot(UUID factionId, WarGoalSnapshot warGoal, UUID warCampId,
                              boolean goalCompleted, boolean goalFailed) {
    public WarSideSnapshot {
        factionId = Objects.requireNonNull(factionId);
        if (goalCompleted && goalFailed) {
            throw new IllegalArgumentException("A war goal cannot be both completed and failed");
        }
    }

    public WarSideSnapshot withGoal(WarGoalSnapshot goal) {
        return new WarSideSnapshot(factionId, Objects.requireNonNull(goal), warCampId, false, false);
    }

    public WarSideSnapshot withWarCamp(UUID value) {
        return new WarSideSnapshot(factionId, warGoal, value, goalCompleted, goalFailed);
    }

    public WarSideSnapshot withGoalState(WarGoalSnapshot goal, boolean completed) {
        return new WarSideSnapshot(factionId, Objects.requireNonNull(goal), warCampId, completed, goalFailed);
    }

    public WarSideSnapshot complete(String result) {
        if (warGoal == null) throw new IllegalStateException("A war goal has not been selected");
        return new WarSideSnapshot(factionId, warGoal.withResult(result), warCampId, true, false);
    }

    public WarSideSnapshot fail(String result) {
        if (warGoal == null) throw new IllegalStateException("A war goal has not been selected");
        return new WarSideSnapshot(factionId, warGoal.withResult(result), warCampId, false, true);
    }
}
