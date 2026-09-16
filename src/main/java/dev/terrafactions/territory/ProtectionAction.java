package dev.terrafactions.territory;

/** A faction-controlled category of territory protection. */
public enum ProtectionAction {
    BLOCK_BREAKING(true, true, "blockBreaking"),
    BLOCK_PLACEMENT(true, true, "blockPlacement"),
    BLOCK_INTERACTIONS(true, false, "blockInteractions"),
    ENTITY_INTERACTIONS(true, false, "entityInteractions"),
    LIQUID_PLACEMENT(true, true, "liquidPlacement"),
    EXPLOSIONS(true, true, "explosions");

    private final boolean coreDefault;
    private final boolean borderDefault;
    private final String configKey;

    ProtectionAction(boolean coreDefault, boolean borderDefault, String configKey) {
        this.coreDefault = coreDefault;
        this.borderDefault = borderDefault;
        this.configKey = configKey;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public String configKey() {
        return configKey;
    }

    public boolean enabledIn(int mask) {
        return (mask & bit()) != 0;
    }

    public static int defaultMask(TerritoryType type) {
        boolean core = type != TerritoryType.BORDER;
        int mask = 0;
        for (ProtectionAction action : values()) {
            if (core ? action.coreDefault : action.borderDefault) mask |= action.bit();
        }
        return mask;
    }
}
