package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

/** A deterministic temporary power effect; current value is derived from persisted timestamps. */
public record TemporaryPowerModifierSnapshot(UUID id, UUID factionId, UUID sourceWarId,
                                             double initialAmount, long startTime,
                                             long expirationTime, PowerModifierType type) {
    public TemporaryPowerModifierSnapshot {
        id = Objects.requireNonNull(id);
        factionId = Objects.requireNonNull(factionId);
        sourceWarId = Objects.requireNonNull(sourceWarId);
        type = Objects.requireNonNull(type);
        initialAmount = Math.max(0.0D, initialAmount);
        if (expirationTime < startTime) expirationTime = startTime;
    }

    public double currentAmount(long now) {
        if (initialAmount <= 0.0D || now >= expirationTime) return 0.0D;
        if (type == PowerModifierType.PUNITIVE_SUPPRESSION) return Math.min(1.0D, initialAmount);
        if (now <= startTime || expirationTime == startTime) return initialAmount;
        double remaining = (double) (expirationTime - now) / (expirationTime - startTime);
        return initialAmount * Math.max(0.0D, Math.min(1.0D, remaining));
    }

    public int currentWholePower(long now) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(currentAmount(now)));
    }
}
