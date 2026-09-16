package dev.terrafactions.war.event;

import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.AnchorReleaseReason;
import dev.terrafactions.war.WarSnapshot;

import java.util.Objects;

public final class AnchorReleasedEvent extends AnchorOccupationEvent {
    private final AnchorReleaseReason reason;

    public AnchorReleasedEvent(WarSnapshot war, AnchorOccupationSnapshot occupation,
                               AnchorReleaseReason reason) {
        super(war, occupation);
        this.reason = Objects.requireNonNull(reason);
    }

    public AnchorReleaseReason reason() {
        return reason;
    }
}
