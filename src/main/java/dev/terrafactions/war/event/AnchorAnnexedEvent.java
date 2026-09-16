package dev.terrafactions.war.event;

import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.WarSnapshot;

public final class AnchorAnnexedEvent extends AnchorOccupationEvent {
    public AnchorAnnexedEvent(WarSnapshot war, AnchorOccupationSnapshot occupation) {
        super(war, occupation);
    }
}
