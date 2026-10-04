package dev.terrafactions.factions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** A daily formal-war schedule measured in real-world UTC time. */
public record FactionWarWindow(int startUtcMinute, int durationMinutes) {
    public FactionWarWindow {
        startUtcMinute = Math.floorMod(startUtcMinute, 1440);
        durationMinutes = Math.max(1, Math.min(1440, durationMinutes));
    }

    public long nextStartUtcMillis(long nowUtcMillis, long minimumStartUtcMillis) {
        long candidate = Math.max(nowUtcMillis, minimumStartUtcMillis);
        LocalDate date = Instant.ofEpochMilli(candidate).atZone(ZoneOffset.UTC).toLocalDate();
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                + startUtcMinute * 60_000L;
        if (start < candidate) start += 86_400_000L;
        return start;
    }

    public long endAfterUtcMillis(long startUtcMillis) {
        return startUtcMillis + durationMinutes * 60_000L;
    }
}
