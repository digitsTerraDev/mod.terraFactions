package dev.terrafactions.war.event;

import dev.terrafactions.war.WarSnapshot;

public final class WarStartedEvent extends WarEvent {
    public WarStartedEvent(WarSnapshot war) {
        super(war);
    }
}
