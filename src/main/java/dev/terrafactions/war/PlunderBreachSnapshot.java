package dev.terrafactions.war;

import dev.terrafactions.territory.TerritoryKey;

import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongBiFunction;

/** Timed, faction-specific protection breach over one target anchor's projected region. */
public record PlunderBreachSnapshot(UUID id, UUID warId, String anchorId,
                                    UUID originalOwnerFactionId, UUID breachingFactionId,
                                    String dimension, int centerChunkX, int centerChunkZ,
                                    int radius, long startTime, long expirationTime) {
    public PlunderBreachSnapshot {
        id = Objects.requireNonNull(id);
        warId = Objects.requireNonNull(warId);
        anchorId = Objects.requireNonNull(anchorId);
        originalOwnerFactionId = Objects.requireNonNull(originalOwnerFactionId);
        breachingFactionId = Objects.requireNonNull(breachingFactionId);
        dimension = Objects.requireNonNull(dimension);
        radius = Math.max(0, radius);
        if (originalOwnerFactionId.equals(breachingFactionId)) {
            throw new IllegalArgumentException("A faction cannot plunder itself");
        }
        if (expirationTime < startTime) expirationTime = startTime;
    }

    public boolean active(long now) {
        return now < expirationTime;
    }

    public boolean contains(TerritoryKey key) {
        return contains(key, PlunderBreachSnapshot::distanceSquared);
    }

    public boolean contains(TerritoryKey key,
                            ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        if (!dimension.equals(key.dimension())) return false;
        TerritoryKey center = new TerritoryKey(dimension, centerChunkX, centerChunkZ);
        return distance.applyAsLong(center, key) <= (long) radius * radius;
    }

    private static long distanceSquared(TerritoryKey first, TerritoryKey second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }
}
