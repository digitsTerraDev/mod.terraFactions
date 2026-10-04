package dev.terrafactions.war;

import java.util.Objects;
import java.util.UUID;

public record WarSnapshot(UUID id, UUID attackerFactionId, UUID defenderFactionId, WarState state,
                          WarSideSnapshot attacker, WarSideSnapshot defender, long declaredAt,
                          long preparationEndsAt, long startedAt, long endedAt,
                          long activeEndsAt, long lastPressureAt) {
    public WarSnapshot(UUID id, UUID attackerFactionId, UUID defenderFactionId, WarState state,
                       WarSideSnapshot attacker, WarSideSnapshot defender, long declaredAt,
                       long preparationEndsAt, long startedAt, long endedAt) {
        this(id, attackerFactionId, defenderFactionId, state, attacker, defender, declaredAt,
                preparationEndsAt, startedAt, endedAt, 0L, 0L);
    }
    public WarSnapshot {
        id = Objects.requireNonNull(id);
        attackerFactionId = Objects.requireNonNull(attackerFactionId);
        defenderFactionId = Objects.requireNonNull(defenderFactionId);
        state = Objects.requireNonNull(state);
        attacker = Objects.requireNonNull(attacker);
        defender = Objects.requireNonNull(defender);
        if (attackerFactionId.equals(defenderFactionId)) {
            throw new IllegalArgumentException("A faction cannot be at war with itself");
        }
        if (!attacker.factionId().equals(attackerFactionId)
                || !defender.factionId().equals(defenderFactionId)) {
            throw new IllegalArgumentException("War side faction IDs do not match the war");
        }
    }

    public WarSideSnapshot side(UUID factionId) {
        if (attackerFactionId.equals(factionId)) return attacker;
        if (defenderFactionId.equals(factionId)) return defender;
        return null;
    }

    public WarSnapshot withDefender(WarSideSnapshot value) {
        return new WarSnapshot(id, attackerFactionId, defenderFactionId, state, attacker, value,
                declaredAt, preparationEndsAt, startedAt, endedAt, activeEndsAt, lastPressureAt);
    }

    public WarSnapshot withAttacker(WarSideSnapshot value) {
        return new WarSnapshot(id, attackerFactionId, defenderFactionId, state, value, defender,
                declaredAt, preparationEndsAt, startedAt, endedAt, activeEndsAt, lastPressureAt);
    }

    public WarSnapshot withState(WarState value, long started, long ended) {
        return new WarSnapshot(id, attackerFactionId, defenderFactionId, value, attacker, defender,
                declaredAt, preparationEndsAt, started, ended, activeEndsAt,
                value == WarState.ACTIVE ? started : lastPressureAt);
    }

    public WarSnapshot withSchedule(long activeEnd) {
        return new WarSnapshot(id, attackerFactionId, defenderFactionId, state, attacker, defender,
                declaredAt, preparationEndsAt, startedAt, endedAt, activeEnd, lastPressureAt);
    }

    public WarSnapshot withLastPressureAt(long value) {
        return new WarSnapshot(id, attackerFactionId, defenderFactionId, state, attacker, defender,
                declaredAt, preparationEndsAt, startedAt, endedAt, activeEndsAt, value);
    }
}
