package dev.terrafactions.factions;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionWarWindowTest {
    @Test
    void waitsForWarningAndThenUsesTheDefendersNextUtcDailyWindow() {
        FactionWarWindow window = new FactionWarWindow(12 * 60, 180);
        long elevenUtc = Instant.parse("2026-09-16T11:00:00Z").toEpochMilli();
        long thirteenUtc = Instant.parse("2026-09-16T13:00:00Z").toEpochMilli();
        long noon = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli();
        long nextNoon = Instant.parse("2026-09-17T12:00:00Z").toEpochMilli();

        assertEquals(noon, window.nextStartUtcMillis(elevenUtc, elevenUtc));
        assertEquals(nextNoon, window.nextStartUtcMillis(elevenUtc, thirteenUtc));
        assertEquals(Instant.parse("2026-09-16T15:00:00Z").toEpochMilli(),
                window.endAfterUtcMillis(noon));
    }
}
