package dev.terrafactions.war;

import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.territory.TerritoryKey;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongBiFunction;

/** Geometry rules for the temporary War Camp -> occupied-anchor invasion network. */
public final class WarOccupationRules {
    private WarOccupationRules() {
    }

    public static boolean campReachesAnchor(WarCampSnapshot camp, AnchorMapSnapshot target,
                                             int connectionRangeChunks) {
        return campReachesAnchor(camp, target, connectionRangeChunks, WarOccupationRules::distanceSquared);
    }

    public static boolean campReachesAnchor(WarCampSnapshot camp, AnchorMapSnapshot target,
                                             int connectionRangeChunks,
                                             ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        return camp.state() == WarCampState.ACTIVE && positionReachesAnchor(
                camp.dimension(), camp.x(), camp.z(), target, connectionRangeChunks, distance);
    }

    /** Placement-time geometry for the initial War Camp -> enemy-anchor connection. */
    public static boolean positionReachesAnchor(String dimension, int blockX, int blockZ,
                                                AnchorMapSnapshot target, int connectionRangeChunks) {
        return positionReachesAnchor(dimension, blockX, blockZ, target, connectionRangeChunks,
                WarOccupationRules::distanceSquared);
    }

    public static boolean positionReachesAnchor(String dimension, int blockX, int blockZ,
                                                AnchorMapSnapshot target, int connectionRangeChunks,
                                                ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        if (!dimension.equals(target.dimension())) return false;
        long range = (long) Math.max(0, connectionRangeChunks) + Math.max(0, target.projectedRadius());
        TerritoryKey camp = new TerritoryKey(dimension, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        return within(camp, anchorKey(target), range, distance);
    }

    public static boolean anchorsConnect(AnchorMapSnapshot first, AnchorMapSnapshot second,
                                         int connectionRangeChunks) {
        return anchorsConnect(first, second, connectionRangeChunks, WarOccupationRules::distanceSquared);
    }

    public static boolean anchorsConnect(AnchorMapSnapshot first, AnchorMapSnapshot second,
                                         int connectionRangeChunks,
                                         ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        if (!first.dimension().equals(second.dimension()) || first.id().equals(second.id())) return false;
        long range = (long) Math.max(0, connectionRangeChunks)
                + Math.max(0, first.projectedRadius()) + Math.max(0, second.projectedRadius());
        return within(anchorKey(first), anchorKey(second), range, distance);
    }

    public static boolean attackEligible(WarCampSnapshot camp,
                                         Collection<AnchorMapSnapshot> occupiedAnchors,
                                         AnchorMapSnapshot target, int connectionRangeChunks) {
        return attackEligible(camp, occupiedAnchors, target, connectionRangeChunks,
                WarOccupationRules::distanceSquared);
    }

    public static boolean attackEligible(WarCampSnapshot camp,
                                         Collection<AnchorMapSnapshot> occupiedAnchors,
                                         AnchorMapSnapshot target, int connectionRangeChunks,
                                         ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        if (campReachesAnchor(camp, target, connectionRangeChunks, distance)) return true;
        return occupiedAnchors.stream().anyMatch(source ->
                anchorsConnect(source, target, connectionRangeChunks, distance));
    }

    /** Returns every occupied anchor with at least one path back to the active camp. */
    public static Set<String> connectedAnchorIds(WarCampSnapshot camp,
                                                  Collection<AnchorMapSnapshot> occupiedAnchors,
                                                  int connectionRangeChunks) {
        return connectedAnchorIds(camp, occupiedAnchors, connectionRangeChunks,
                WarOccupationRules::distanceSquared);
    }

    public static Set<String> connectedAnchorIds(WarCampSnapshot camp,
                                                  Collection<AnchorMapSnapshot> occupiedAnchors,
                                                  int connectionRangeChunks,
                                                  ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        Map<String, AnchorMapSnapshot> anchors = new HashMap<>();
        occupiedAnchors.forEach(anchor -> anchors.put(anchor.id(), anchor));
        Set<String> connected = new HashSet<>();
        ArrayDeque<AnchorMapSnapshot> pending = new ArrayDeque<>();
        for (AnchorMapSnapshot anchor : anchors.values()) {
            if (campReachesAnchor(camp, anchor, connectionRangeChunks, distance)) {
                connected.add(anchor.id());
                pending.addLast(anchor);
            }
        }
        while (!pending.isEmpty()) {
            AnchorMapSnapshot source = pending.removeFirst();
            for (AnchorMapSnapshot candidate : anchors.values()) {
                if (!connected.contains(candidate.id())
                        && anchorsConnect(source, candidate, connectionRangeChunks, distance)) {
                    connected.add(candidate.id());
                    pending.addLast(candidate);
                }
            }
        }
        return Set.copyOf(connected);
    }

    private static TerritoryKey anchorKey(AnchorMapSnapshot anchor) {
        return new TerritoryKey(anchor.dimension(), Math.floorDiv(anchor.x(), 16),
                Math.floorDiv(anchor.z(), 16));
    }

    private static long distanceSquared(TerritoryKey first, TerritoryKey second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static boolean within(TerritoryKey first, TerritoryKey second, long range,
                                  ToLongBiFunction<TerritoryKey, TerritoryKey> distance) {
        return (double) distance.applyAsLong(first, second) <= (double) range * range;
    }
}
