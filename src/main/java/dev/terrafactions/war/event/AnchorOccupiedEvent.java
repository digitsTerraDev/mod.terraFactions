package dev.terrafactions.war.event;

import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class AnchorOccupiedEvent extends AnchorOccupationEvent {
    public AnchorOccupiedEvent(WarSnapshot war, AnchorOccupationSnapshot occupation) {
        super(war, occupation);
    }
}
