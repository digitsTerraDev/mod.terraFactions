package dev.terrafactions.war.event;

import dev.terrafactions.war.PlunderBreachSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class PlunderBreachStartedEvent extends PlunderBreachEvent {
    public PlunderBreachStartedEvent(WarSnapshot war, PlunderBreachSnapshot breach) {
        super(war, breach);
    }
}
