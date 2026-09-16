package dev.terrafactions.war.event;

import dev.terrafactions.war.PlunderBreachSnapshot;
import dev.terrafactions.war.WarSnapshot;

import java.util.Objects;

public abstract class PlunderBreachEvent extends WarEvent {
    private final PlunderBreachSnapshot breach;

    protected PlunderBreachEvent(WarSnapshot war, PlunderBreachSnapshot breach) {
        super(war);
        this.breach = Objects.requireNonNull(breach);
    }

    public PlunderBreachSnapshot breach() {
        return breach;
    }
}
