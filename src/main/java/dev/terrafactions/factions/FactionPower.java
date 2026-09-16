package dev.terrafactions.factions;

import java.util.Map;
import java.util.UUID;

public record FactionPower(int current, int maximum, int claimUsage, int deathLoss, int specialPower,
                           int temporaryPower, int suppressedPower, double suppressionPercent,
                           Map<UUID, Integer> deathLossByPlayer) {
    public FactionPower {
        deathLossByPlayer = Map.copyOf(deathLossByPlayer);
    }
}
