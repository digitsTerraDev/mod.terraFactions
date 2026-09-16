package dev.terrafactions.territory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongBiFunction;
import java.util.function.UnaryOperator;

public final class TerritoryRules {
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final UnaryOperator<TerritoryKey> IDENTITY = key -> key;

    private TerritoryRules() {
    }

    public static boolean touches(Collection<TerritoryKey> territory, TerritoryKey target) {
        return touches(territory, target, IDENTITY);
    }

    public static boolean touches(Collection<TerritoryKey> territory, TerritoryKey target,
                                  UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> keys = normalized(territory, normalizer);
        target = normalizer.apply(target);
        for (int[] direction : DIRECTIONS) {
            if (keys.contains(normalizer.apply(target.offset(direction[0], direction[1])))) {
                return true;
            }
        }
        return false;
    }

    public static boolean remainsConnected(Collection<TerritoryKey> territory, TerritoryKey removed) {
        return remainsConnected(territory, removed, IDENTITY);
    }

    public static boolean remainsConnected(Collection<TerritoryKey> territory, TerritoryKey removed,
                                           UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> remaining = normalized(territory, normalizer);
        remaining.remove(normalizer.apply(removed));
        return isConnected(remaining, normalizer);
    }

    public static boolean isConnected(Collection<TerritoryKey> territory) {
        return isConnected(territory, IDENTITY);
    }

    public static boolean isConnected(Collection<TerritoryKey> territory,
                                      UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> remaining = normalized(territory, normalizer);
        if (remaining.size() < 2) {
            return true;
        }

        TerritoryKey first = remaining.iterator().next();
        Set<TerritoryKey> visited = new HashSet<>();
        ArrayDeque<TerritoryKey> queue = new ArrayDeque<>();
        queue.add(first);
        while (!queue.isEmpty()) {
            TerritoryKey key = queue.removeFirst();
            if (!remaining.contains(key) || !visited.add(key)) {
                continue;
            }
            for (int[] direction : DIRECTIONS) {
                queue.addLast(normalizer.apply(key.offset(direction[0], direction[1])));
            }
        }
        return visited.size() == remaining.size();
    }

    public static Set<TerritoryKey> connectedComponent(Collection<TerritoryKey> territory, TerritoryKey start) {
        return connectedComponent(territory, start, IDENTITY);
    }

    public static Set<TerritoryKey> connectedComponent(Collection<TerritoryKey> territory, TerritoryKey start,
                                                       UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> remaining = normalized(territory, normalizer);
        start = normalizer.apply(start);
        if (!remaining.contains(start)) return Set.of();
        Set<TerritoryKey> visited = new HashSet<>();
        ArrayDeque<TerritoryKey> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            TerritoryKey key = queue.removeFirst();
            if (!remaining.contains(key) || !visited.add(key)) continue;
            for (int[] direction : DIRECTIONS) {
                queue.addLast(normalizer.apply(key.offset(direction[0], direction[1])));
            }
        }
        return visited;
    }

    /** A centered square extending the requested chunk radius in every cardinal direction. */
    public static Set<TerritoryKey> centeredSquare(TerritoryKey center, int radius) {
        return centeredSquare(center, radius, IDENTITY);
    }

    public static Set<TerritoryKey> centeredSquare(TerritoryKey center, int radius,
                                                   UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> result = new LinkedHashSet<>();
        center = normalizer.apply(center);
        radius = Math.max(0, radius);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                result.add(normalizer.apply(center.offset(x, z)));
            }
        }
        return result;
    }

    /** Finds the largest complete discrete circle whose claim count fits the supplied budget. */
    public static CircularProjection largestCircularProjection(TerritoryKey center, int maxClaims) {
        if (maxClaims <= 0) return new CircularProjection(0, Set.of());
        int radius = Math.max(0, (int) Math.floor(Math.sqrt(maxClaims / Math.PI)));
        Set<TerritoryKey> claims = circularProjection(center, radius);
        while (claims.size() > maxClaims && radius > 0) {
            claims = circularProjection(center, --radius);
        }
        while (true) {
            Set<TerritoryKey> next = circularProjection(center, radius + 1);
            if (next.size() > maxClaims) return new CircularProjection(radius, claims);
            radius++;
            claims = next;
        }
    }

    public static CircularProjection largestCircularProjection(TerritoryKey center, int maxClaims,
                                                                UnaryOperator<TerritoryKey> normalizer) {
        CircularProjection projection = largestCircularProjection(center, maxClaims);
        Set<TerritoryKey> claims = normalized(projection.claims(), normalizer);
        claims.remove(normalizer.apply(center));
        return new CircularProjection(projection.radius(), claims);
    }

    public static Set<TerritoryKey> circularProjection(TerritoryKey center, int radius) {
        return circularProjection(center, radius, IDENTITY);
    }

    public static Set<TerritoryKey> circularProjection(TerritoryKey center, int radius,
                                                       UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> result = new LinkedHashSet<>();
        center = normalizer.apply(center);
        long radiusSquared = (long) radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((dx != 0 || dz != 0) && (long) dx * dx + (long) dz * dz <= radiusSquared) {
                    result.add(normalizer.apply(center.offset(dx, dz)));
                }
            }
        }
        return result;
    }

    /**
     * Resolves overlapping anchor projections using effective power divided by squared chunk distance.
     * Every returned chunk is owned by exactly one strongest anchor; exact ties are deterministic.
     */
    public static Map<TerritoryKey, BorderInfluence> resolveBorderInfluence(
            Collection<BorderInfluence> influences) {
        return resolveBorderInfluence(influences, TerritoryRules::distanceSquared);
    }

    public static Map<TerritoryKey, BorderInfluence> resolveBorderInfluence(
            Collection<BorderInfluence> influences,
            ToLongBiFunction<TerritoryKey, TerritoryKey> distanceSquared) {
        Map<TerritoryKey, BorderInfluence> winners = new HashMap<>();
        for (BorderInfluence influence : influences) {
            if (influence.radius() <= 0 || influence.strength() <= 0L) continue;
            for (TerritoryKey key : influence.footprint()) {
                BorderInfluence current = winners.get(key);
                if (current == null || strongerAt(influence, current, key, distanceSquared)) {
                    winners.put(key, influence);
                }
            }
        }
        return Map.copyOf(winners);
    }

    private static boolean strongerAt(BorderInfluence candidate, BorderInfluence current, TerritoryKey key,
                                      ToLongBiFunction<TerritoryKey, TerritoryKey> distanceSquared) {
        long candidateDistance = distanceSquared.applyAsLong(candidate.center(), key);
        long currentDistance = distanceSquared.applyAsLong(current.center(), key);
        long candidatePressure = saturatedMultiply(candidate.strength(), currentDistance);
        long currentPressure = saturatedMultiply(current.strength(), candidateDistance);
        int pressureComparison = Long.compare(candidatePressure, currentPressure);
        if (pressureComparison != 0) return pressureComparison > 0;
        int distanceComparison = Long.compare(candidateDistance, currentDistance);
        if (distanceComparison != 0) return distanceComparison < 0;
        int strengthComparison = Long.compare(candidate.strength(), current.strength());
        if (strengthComparison != 0) return strengthComparison > 0;
        int factionComparison = candidate.factionId().compareTo(current.factionId());
        return factionComparison < 0
                || factionComparison == 0 && candidate.anchorId().compareTo(current.anchorId()) < 0;
    }

    private static long distanceSquared(TerritoryKey first, TerritoryKey second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return saturatedAdd(saturatedMultiply(Math.abs(dx), Math.abs(dx)),
                saturatedMultiply(Math.abs(dz), Math.abs(dz)));
    }

    private static long saturatedMultiply(long first, long second) {
        if (first <= 0L || second <= 0L) return 0L;
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static Set<TerritoryKey> normalized(Collection<TerritoryKey> territory,
                                                UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> result = new HashSet<>();
        territory.forEach(key -> result.add(normalizer.apply(key)));
        return result;
    }

    public record BorderInfluence(String anchorId, UUID factionId, TerritoryKey center,
                                  int radius, long strength, Set<TerritoryKey> footprint) {
        public BorderInfluence(String anchorId, UUID factionId, TerritoryKey center,
                               int radius, long strength) {
            this(anchorId, factionId, center, radius, strength, circularProjection(center, radius));
        }

        public BorderInfluence {
            Objects.requireNonNull(anchorId);
            Objects.requireNonNull(factionId);
            Objects.requireNonNull(center);
            Objects.requireNonNull(footprint);
            radius = Math.max(0, radius);
            strength = Math.max(0L, strength);
            footprint = Set.copyOf(footprint);
        }
    }

    public record CircularProjection(int radius, Set<TerritoryKey> claims) {
        public CircularProjection {
            claims = Set.copyOf(claims);
        }
    }
}
