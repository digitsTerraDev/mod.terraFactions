package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

public record WarCampSnapshot(UUID id, UUID warId, UUID ownerFactionId, String dimension,
                              int x, int y, int z, WarCampState state, long placementTime,
                              long activationTime, int destructionProgress) {
    public WarCampSnapshot {
        id = Objects.requireNonNull(id);
        warId = Objects.requireNonNull(warId);
        ownerFactionId = Objects.requireNonNull(ownerFactionId);
        dimension = Objects.requireNonNull(dimension);
        state = Objects.requireNonNull(state);
        destructionProgress = Math.max(0, destructionProgress);
    }

    public WarCampSnapshot withState(WarCampState value) {
        return new WarCampSnapshot(id, warId, ownerFactionId, dimension, x, y, z, value,
                placementTime, activationTime, destructionProgress);
    }

    public WarCampSnapshot withDestructionProgress(int value) {
        return new WarCampSnapshot(id, warId, ownerFactionId, dimension, x, y, z, state,
                placementTime, activationTime, value);
    }
}
