package dev.terrafactions.war.event;

import dev.terrafactions.war.WarSnapshot;

public final class WarEndedEvent extends WarEvent {
    public WarEndedEvent(WarSnapshot war) {
        super(war);
    }
}
