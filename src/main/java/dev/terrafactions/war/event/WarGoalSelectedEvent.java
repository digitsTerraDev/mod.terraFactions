package dev.terrafactions.war.event;

import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarSnapshot;

import java.util.UUID;

public final class WarGoalSelectedEvent extends WarEvent {
    private final UUID factionId;
    private final WarGoalType goalType;

    public WarGoalSelectedEvent(WarSnapshot war, UUID factionId, WarGoalType goalType) {
        super(war);
        this.factionId = factionId;
        this.goalType = goalType;
    }

    public UUID factionId() {
        return factionId;
    }

    public WarGoalType goalType() {
        return goalType;
    }
}
