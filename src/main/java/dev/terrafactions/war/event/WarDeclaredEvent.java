package dev.terrafactions.war.event;

import dev.terrafactions.war.WarSnapshot;

public final class WarDeclaredEvent extends WarEvent {
    public WarDeclaredEvent(WarSnapshot war) {
        super(war);
    }
}
