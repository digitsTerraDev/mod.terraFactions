package dev.terrafactions.anchor;

public enum AnchorTier {
    BASIC("Basic", 10),
    ADVANCED("Advanced", 5),
    MASTER("Master", 2),
    ULTIMATE("Ultimate", 1);

    private final String displayName;
    private final int powerTenthsPerClaim;

    AnchorTier(String displayName, int powerTenthsPerClaim) {
        this.displayName = displayName;
        this.powerTenthsPerClaim = powerTenthsPerClaim;
    }

    public String displayName() {
        return displayName;
    }

    public int powerTenthsPerClaim() {
        return powerTenthsPerClaim;
    }
}
