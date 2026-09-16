package dev.terrafactions.war;

public enum WarGoalType {
    DEFENSE(false),
    CONQUEST(true),
    PLUNDER(true),
    PUNITIVE(true);

    private final boolean requiresWarCamp;

    WarGoalType(boolean requiresWarCamp) {
        this.requiresWarCamp = requiresWarCamp;
    }

    public boolean requiresWarCamp() {
        return requiresWarCamp;
    }
}
