package dev.terrafactions.territory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryRulesTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void cardinalNeighborsConnectButDiagonalsDoNot() {
        Set<TerritoryKey> territory = Set.of(key(0, 0));

        assertTrue(TerritoryRules.touches(territory, key(1, 0)));
        assertTrue(TerritoryRules.touches(territory, key(0, -1)));
        assertFalse(TerritoryRules.touches(territory, key(1, 1)));
    }

    @Test
    void claimsInAnotherDimensionDoNotConnect() {
        Set<TerritoryKey> territory = Set.of(key(0, 0));

        assertFalse(TerritoryRules.touches(territory, new TerritoryKey("minecraft:the_nether", 1, 0)));
    }

    @Test
    void removingBridgeWouldSplitTerritory() {
        Set<TerritoryKey> territory = Set.of(key(0, 0), key(1, 0), key(2, 0));

        assertFalse(TerritoryRules.remainsConnected(territory, key(1, 0)));
        assertTrue(TerritoryRules.remainsConnected(territory, key(0, 0)));
    }

    @Test
    void removingFromRingRemainsConnected() {
        Set<TerritoryKey> territory = Set.of(
                key(0, 0), key(1, 0), key(2, 0), key(2, 1),
                key(2, 2), key(1, 2), key(0, 2), key(0, 1));

        assertTrue(TerritoryRules.remainsConnected(territory, key(1, 0)));
    }

    @Test
    void centeredBulkRadiusMatchesFactionCommandSemantics() {
        assertEquals(Set.of(key(0, 0)), TerritoryRules.centeredSquare(key(0, 0), 0));
        assertEquals(9, TerritoryRules.centeredSquare(key(0, 0), 1).size());
        assertEquals(25, TerritoryRules.centeredSquare(key(0, 0), 2).size());
        assertTrue(TerritoryRules.centeredSquare(key(0, 0), 2).contains(key(-2, 2)));
    }

    @Test
    void circularProjectionUsesLargestCompleteRadiusAndExcludesAnchorChunk() {
        TerritoryRules.CircularProjection projection = TerritoryRules.largestCircularProjection(key(0, 0), 12);

        assertEquals(2, projection.radius());
        assertEquals(12, projection.claims().size());
        assertFalse(projection.claims().contains(key(0, 0)));
        assertTrue(projection.claims().containsAll(Set.of(key(-1, 0), key(1, 0), key(0, -1), key(0, 1))));
        assertTrue(TerritoryRules.isConnected(projection.claims()));
    }

    @Test
    void overlappingCirclesHaveAUnionSmallerThanTheirChargedFootprints() {
        Set<TerritoryKey> first = TerritoryRules.circularProjection(key(0, 0), 2);
        Set<TerritoryKey> second = TerritoryRules.circularProjection(key(1, 0), 2);
        Set<TerritoryKey> union = new java.util.HashSet<>(first);
        union.addAll(second);

        assertEquals(12, first.size());
        assertEquals(12, second.size());
        assertTrue(union.size() < first.size() + second.size());
    }

    @Test
    void equalBorderInfluenceMeetsAtTheNearestAnchorBoundary() {
        UUID firstFaction = new UUID(0L, 1L);
        UUID secondFaction = new UUID(0L, 2L);
        Map<TerritoryKey, TerritoryRules.BorderInfluence> winners = TerritoryRules.resolveBorderInfluence(Set.of(
                new TerritoryRules.BorderInfluence("first", firstFaction, key(0, 0), 5, 100),
                new TerritoryRules.BorderInfluence("second", secondFaction, key(6, 0), 5, 100)));

        assertEquals(firstFaction, winners.get(key(2, 0)).factionId());
        assertEquals(firstFaction, winners.get(key(3, 0)).factionId());
        assertEquals(secondFaction, winners.get(key(4, 0)).factionId());
    }

    @Test
    void strongerAnchorPushesTheContestedBorderTowardAWeakerAnchor() {
        UUID strongerFaction = new UUID(0L, 1L);
        UUID weakerFaction = new UUID(0L, 2L);
        Map<TerritoryKey, TerritoryRules.BorderInfluence> winners = TerritoryRules.resolveBorderInfluence(Set.of(
                new TerritoryRules.BorderInfluence("stronger", strongerFaction, key(0, 0), 5, 500),
                new TerritoryRules.BorderInfluence("weaker", weakerFaction, key(6, 0), 5, 100)));

        assertEquals(strongerFaction, winners.get(key(4, 0)).factionId());
        assertEquals(weakerFaction, winners.get(key(5, 0)).factionId());
    }

    @Test
    void borderInfluenceDoesNotCrossDimensionsOrItsProjectionRadius() {
        UUID overworldFaction = new UUID(0L, 1L);
        UUID netherFaction = new UUID(0L, 2L);
        TerritoryKey netherCenter = new TerritoryKey("minecraft:the_nether", 0, 0);
        Map<TerritoryKey, TerritoryRules.BorderInfluence> winners = TerritoryRules.resolveBorderInfluence(Set.of(
                new TerritoryRules.BorderInfluence("overworld", overworldFaction, key(0, 0), 2, 100),
                new TerritoryRules.BorderInfluence("nether", netherFaction, netherCenter, 2, 100)));

        assertEquals(overworldFaction, winners.get(key(1, 0)).factionId());
        assertEquals(netherFaction, winners.get(netherCenter.offset(1, 0)).factionId());
        assertFalse(winners.containsKey(key(3, 0)));
    }

    @Test
    void connectivityCheckRejectsSeparatedGroups() {
        assertTrue(TerritoryRules.isConnected(Set.of(key(0, 0), key(1, 0), key(1, 1))));
        assertFalse(TerritoryRules.isConnected(Set.of(key(0, 0), key(2, 0))));
    }

    @Test
    void redundantTerritoryPathSurvivesUntilEveryRouteIsCut() {
        Set<TerritoryKey> territory = new java.util.HashSet<>(Set.of(
                key(0, 0), key(1, 0), key(2, 0),
                key(0, 1), key(1, 1), key(2, 1)));

        territory.remove(key(1, 0));
        assertTrue(TerritoryRules.connectedComponent(territory, key(0, 0)).contains(key(2, 0)));
        territory.remove(key(1, 1));
        assertFalse(TerritoryRules.connectedComponent(territory, key(0, 0)).contains(key(2, 0)));
    }

    @Test
    void wrappedWorldEdgesAreCardinalNeighbors() {
        Set<TerritoryKey> territory = Set.of(key(9, 0), key(8, 0));

        assertTrue(TerritoryRules.touches(territory, key(0, 0), TerritoryRulesTest::wrapTen));
        assertTrue(TerritoryRules.isConnected(Set.of(key(9, 0), key(0, 0)),
                TerritoryRulesTest::wrapTen));
        assertEquals(Set.of(key(9, 0), key(0, -1), key(0, 1), key(1, 0)),
                TerritoryRules.circularProjection(key(0, 0), 1, TerritoryRulesTest::wrapTen));
    }

    @Test
    void borderPressureUsesShortestDistanceAcrossAWorldSeam() {
        UUID seamFaction = new UUID(0L, 1L);
        UUID inlandFaction = new UUID(0L, 2L);
        TerritoryKey contested = key(9, 0);
        Map<TerritoryKey, TerritoryRules.BorderInfluence> winners = TerritoryRules.resolveBorderInfluence(Set.of(
                new TerritoryRules.BorderInfluence("seam", seamFaction, key(0, 0), 3, 100,
                        Set.of(contested)),
                new TerritoryRules.BorderInfluence("inland", inlandFaction, key(7, 0), 3, 100,
                        Set.of(contested))), TerritoryRulesTest::wrappedDistanceSquared);

        assertEquals(seamFaction, winners.get(contested).factionId());
    }

    private static TerritoryKey wrapTen(TerritoryKey key) {
        return new TerritoryKey(key.dimension(), Math.floorMod(key.x(), 10), key.z());
    }

    private static long wrappedDistanceSquared(TerritoryKey first, TerritoryKey second) {
        long directX = Math.abs((long) Math.floorMod(first.x(), 10) - Math.floorMod(second.x(), 10));
        long dx = Math.min(directX, 10L - directX);
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static TerritoryKey key(int x, int z) {
        return new TerritoryKey(OVERWORLD, x, z);
    }
}
