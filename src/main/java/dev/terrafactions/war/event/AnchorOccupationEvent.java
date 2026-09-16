package dev.terrafactions.war.event;

import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.WarSnapshot;

import java.util.Objects;

public abstract class AnchorOccupationEvent extends WarEvent {
    private final AnchorOccupationSnapshot occupation;

    protected AnchorOccupationEvent(WarSnapshot war, AnchorOccupationSnapshot occupation) {
        super(war);
        this.occupation = Objects.requireNonNull(occupation);
    }

    public AnchorOccupationSnapshot occupation() {
        return occupation;
    }
}
