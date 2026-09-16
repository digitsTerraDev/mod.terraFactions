package dev.terrafactions.war.event;

import dev.terrafactions.war.PlunderBreachSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class PlunderBreachEndedEvent extends PlunderBreachEvent {
    public PlunderBreachEndedEvent(WarSnapshot war, PlunderBreachSnapshot breach) {
        super(war, breach);
    }
}
