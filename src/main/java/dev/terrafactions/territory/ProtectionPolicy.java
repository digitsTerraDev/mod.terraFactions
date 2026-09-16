package dev.terrafactions.territory;

/** Server authority over whether a faction may configure one protection. */
public enum ProtectionPolicy {
    FACTION_CONTROLLED,
    FORCED_ON,
    FORCED_OFF;

    public boolean resolve(boolean factionChoice) {
        return switch (this) {
            case FACTION_CONTROLLED -> factionChoice;
            case FORCED_ON -> true;
            case FORCED_OFF -> false;
        };
    }

    public boolean configurable() {
        return this == FACTION_CONTROLLED;
    }
}
