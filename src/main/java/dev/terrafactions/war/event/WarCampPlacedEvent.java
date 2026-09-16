package dev.terrafactions.war.event;

import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class WarCampPlacedEvent extends WarCampEvent {
    public WarCampPlacedEvent(WarSnapshot war, WarCampSnapshot warCamp) {
        super(war, warCamp);
    }
}
