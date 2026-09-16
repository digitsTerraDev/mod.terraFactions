package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

/** Temporary wartime control of an anchor without changing its permanent faction owner. */
public record AnchorOccupationSnapshot(String anchorId, UUID originalOwnerFactionId,
                                       UUID occupyingFactionId, UUID warId,
                                       long occupationStartTime) {
    public AnchorOccupationSnapshot {
        anchorId = Objects.requireNonNull(anchorId);
        originalOwnerFactionId = Objects.requireNonNull(originalOwnerFactionId);
        occupyingFactionId = Objects.requireNonNull(occupyingFactionId);
        warId = Objects.requireNonNull(warId);
        if (originalOwnerFactionId.equals(occupyingFactionId)) {
            throw new IllegalArgumentException("An anchor cannot be occupied by its permanent owner");
        }
    }
}
