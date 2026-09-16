package dev.terrafactions.war.event;

import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class WarCampActivatedEvent extends WarCampEvent {
    public WarCampActivatedEvent(WarSnapshot war, WarCampSnapshot warCamp) {
        super(war, warCamp);
    }
}
