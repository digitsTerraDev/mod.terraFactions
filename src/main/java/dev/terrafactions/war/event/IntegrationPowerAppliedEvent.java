package dev.terrafactions.war.event;

import dev.terrafactions.war.TemporaryPowerModifierSnapshot;
import dev.terrafactions.war.WarSnapshot;

import java.util.Objects;

public final class IntegrationPowerAppliedEvent extends WarEvent {
    private final TemporaryPowerModifierSnapshot modifier;

    public IntegrationPowerAppliedEvent(WarSnapshot war, TemporaryPowerModifierSnapshot modifier) {
        super(war);
        this.modifier = Objects.requireNonNull(modifier);
    }

    public TemporaryPowerModifierSnapshot modifier() {
        return modifier;
    }
}
