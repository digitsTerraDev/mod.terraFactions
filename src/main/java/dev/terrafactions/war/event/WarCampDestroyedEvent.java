package dev.terrafactions.war.event;

import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class WarCampDestroyedEvent extends WarCampEvent {
    public WarCampDestroyedEvent(WarSnapshot war, WarCampSnapshot warCamp) {
        super(war, warCamp);
    }
}
