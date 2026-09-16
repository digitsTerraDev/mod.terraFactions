package dev.terrafactions.war;

import dev.terrafactions.anchor.AnchorConnectionState;
import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorPowerState;
import dev.terrafactions.anchor.AnchorTier;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import dev.terrafactions.territory.TerritoryKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarOccupationRulesTest {
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void activeCampReachesTheEdgeOfAnAnchorProjectionPlusConfiguredGap() {
        WarCampSnapshot camp = campAtChunk(0, 0, WarCampState.ACTIVE);
        AnchorMapSnapshot edge = anchor("edge", 5, 0, 2);
        AnchorMapSnapshot beyond = anchor("beyond", 6, 0, 2);

        assertTrue(WarOccupationRules.campReachesAnchor(camp, edge, 3));
        assertFalse(WarOccupationRules.campReachesAnchor(camp, beyond, 3));
        assertFalse(WarOccupationRules.campReachesAnchor(
                campAtChunk(0, 0, WarCampState.ESTABLISHING), edge, 3));
    }

    @Test
    void placementPositionMustReachAnEnemyAnchorConnection() {
        AnchorMapSnapshot target = anchor("target", 5, 0, 2);

        assertTrue(WarOccupationRules.positionReachesAnchor(
                "minecraft:overworld", 8, 8, target, 3));
        assertFalse(WarOccupationRules.positionReachesAnchor(
                "minecraft:overworld", -8, 8, target, 3));
        assertFalse(WarOccupationRules.positionReachesAnchor(
                "minecraft:the_nether", 8, 8, target, 3));
    }

    @Test
    void occupiedAnchorsExtendTheFrontlineByOverlappingProjectedCircles() {
        AnchorMapSnapshot source = anchor("source", 5, 0, 2);
        AnchorMapSnapshot target = anchor("target", 12, 0, 2);

        assertTrue(WarOccupationRules.anchorsConnect(source, target, 3));
        assertTrue(WarOccupationRules.attackEligible(
                campAtChunk(0, 0, WarCampState.ACTIVE), List.of(source), target, 3));
    }

    @Test
    void reachabilityUsesAllAvailablePathsAndDropsOnlyDisconnectedBranches() {
        WarCampSnapshot camp = campAtChunk(0, 0, WarCampState.ACTIVE);
        AnchorMapSnapshot first = anchor("first", 4, 0, 1);
        AnchorMapSnapshot upperRoute = anchor("upper", 9, 2, 2);
        AnchorMapSnapshot lowerRoute = anchor("lower", 9, -2, 2);
        AnchorMapSnapshot frontier = anchor("frontier", 14, 0, 1);
        AnchorMapSnapshot isolated = anchor("isolated", 30, 0, 1);

        Set<String> connected = WarOccupationRules.connectedAnchorIds(camp,
                List.of(first, upperRoute, lowerRoute, frontier, isolated), 3);
        assertEquals(Set.of("first", "upper", "lower", "frontier"), connected);

        Set<String> alternatePath = WarOccupationRules.connectedAnchorIds(camp,
                List.of(first, lowerRoute, frontier), 3);
        assertTrue(alternatePath.contains("frontier"));

        Set<String> cutOff = WarOccupationRules.connectedAnchorIds(camp,
                List.of(first, frontier), 3);
        assertFalse(cutOff.contains("frontier"));
    }

    @Test
    void warCampAndInvasionLinksCrossAWorldSeam() {
        WarCampSnapshot camp = campAtChunk(0, 0, WarCampState.ACTIVE);
        AnchorMapSnapshot edge = anchor("edge", 9, 0, 1);
        AnchorMapSnapshot next = anchor("next", 8, 0, 0);

        assertTrue(WarOccupationRules.campReachesAnchor(camp, edge, 0,
                WarOccupationRulesTest::wrappedDistanceSquared));
        assertTrue(WarOccupationRules.attackEligible(camp, List.of(edge), next, 0,
                WarOccupationRulesTest::wrappedDistanceSquared));
        assertEquals(Set.of("edge", "next"), WarOccupationRules.connectedAnchorIds(
                camp, List.of(edge, next), 0, WarOccupationRulesTest::wrappedDistanceSquared));
    }

    private static long wrappedDistanceSquared(TerritoryKey first, TerritoryKey second) {
        long directX = Math.abs((long) Math.floorMod(first.x(), 10) - Math.floorMod(second.x(), 10));
        long dx = Math.min(directX, 10L - directX);
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static WarCampSnapshot campAtChunk(int chunkX, int chunkZ, WarCampState state) {
        return new WarCampSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", chunkX * 16 + 8, 64, chunkZ * 16 + 8,
                state, 10, 20, 0);
    }

    private static AnchorMapSnapshot anchor(String id, int chunkX, int chunkZ, int radius) {
        return new AnchorMapSnapshot(id, OWNER, "minecraft:overworld", chunkX * 16 + 8, 64,
                chunkZ * 16 + 8, AnchorTier.BASIC, 0, 0, 0, radius, 0,
                AnchorPowerState.FULL, AnchorConnectionState.CONNECTED,
                AnchorVulnerabilityState.PROTECTED, 0);
    }
}
