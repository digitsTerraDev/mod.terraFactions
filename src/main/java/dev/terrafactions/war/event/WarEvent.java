package dev.terrafactions.war.event;

import dev.terrafactions.war.WarSnapshot;
import net.neoforged.bus.api.Event;

import java.util.Objects;

public abstract class WarEvent extends Event {
    private final WarSnapshot war;

    protected WarEvent(WarSnapshot war) {
        this.war = Objects.requireNonNull(war);
    }

    public WarSnapshot war() {
        return war;
    }
}
