package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

/** Persistent interaction progress for occupying or liberating one anchor. */
public record AnchorSiegeSnapshot(String anchorId, UUID warId, UUID attackingFactionId,
                                  int progress, long startedAt) {
    public AnchorSiegeSnapshot {
        anchorId = Objects.requireNonNull(anchorId);
        warId = Objects.requireNonNull(warId);
        attackingFactionId = Objects.requireNonNull(attackingFactionId);
        progress = Math.max(0, progress);
    }

    public AnchorSiegeSnapshot withProgress(int value) {
        return new AnchorSiegeSnapshot(anchorId, warId, attackingFactionId, value, startedAt);
    }
}
