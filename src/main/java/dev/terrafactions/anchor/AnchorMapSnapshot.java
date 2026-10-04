package dev.terrafactions.anchor;

import java.util.UUID;

public record AnchorMapSnapshot(String id, UUID factionId, String dimension, int x, int y, int z,
                                AnchorTier tier, int allocatedPower, int usablePowerTenths, int priority,
                                int projectedRadius, int projectedClaims,
                                AnchorPowerState powerState, AnchorConnectionState connectionState,
                                AnchorVulnerabilityState vulnerabilityState, long isolationStartTick,
                                boolean skyExposed, boolean capital, int siegeDamage) {
    public AnchorMapSnapshot(String id, UUID factionId, String dimension, int x, int y, int z,
                             AnchorTier tier, int allocatedPower, int usablePowerTenths, int priority,
                             int projectedRadius, int projectedClaims, AnchorPowerState powerState,
                             AnchorConnectionState connectionState,
                             AnchorVulnerabilityState vulnerabilityState, long isolationStartTick) {
        this(id, factionId, dimension, x, y, z, tier, allocatedPower, usablePowerTenths, priority,
                projectedRadius, projectedClaims, powerState, connectionState, vulnerabilityState,
                isolationStartTick, true, false, 0);
    }

    public AnchorMapSnapshot withOperationalState(int usablePower, AnchorPowerState newPowerState,
                                                   AnchorVulnerabilityState newVulnerability,
                                                   boolean exposed, boolean isCapital) {
        return new AnchorMapSnapshot(id, factionId, dimension, x, y, z, tier, allocatedPower,
                usablePower, priority, projectedRadius, projectedClaims, newPowerState,
                connectionState, newVulnerability, isolationStartTick, exposed, isCapital, siegeDamage);
    }

    public AnchorMapSnapshot withSiegeDamage(int damage, AnchorVulnerabilityState state) {
        return new AnchorMapSnapshot(id, factionId, dimension, x, y, z, tier, allocatedPower,
                usablePowerTenths, priority, projectedRadius, projectedClaims, powerState,
                connectionState, state, isolationStartTick, skyExposed, capital, Math.max(0, damage));
    }
}
