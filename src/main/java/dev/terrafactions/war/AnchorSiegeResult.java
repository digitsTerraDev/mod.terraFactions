package dev.terrafactions.war;

/** Result of one server-authoritative anchor siege interaction. */
public record AnchorSiegeResult(int progress, int requiredProgress, boolean completed,
                                boolean liberated, boolean annexed,
                                AnchorOccupationSnapshot occupation) {
}
