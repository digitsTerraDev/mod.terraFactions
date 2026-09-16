package dev.terrafactions.war.event;

import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarSnapshot;

import java.util.Objects;

public abstract class WarCampEvent extends WarEvent {
    private final WarCampSnapshot warCamp;

    protected WarCampEvent(WarSnapshot war, WarCampSnapshot warCamp) {
        super(war);
        this.warCamp = Objects.requireNonNull(warCamp);
    }

    public WarCampSnapshot warCamp() {
        return warCamp;
    }
}
