package dev.terrafactions.anchor;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnchorNetworkRulesTest {
    @Test
    void overlappingAnchorsAreChargedForBothCompleteFootprints() {
        long northern = AnchorNetworkRules.requiredPowerTenths(AnchorTier.BASIC, 12);
        long western = AnchorNetworkRules.requiredPowerTenths(AnchorTier.BASIC, 12);

        assertEquals(120, northern);
        assertEquals(240, northern + western);
    }

    @Test
    void anchorLinksAreDirectedOnlyForOneWayRadiusContainment() {
        UUID faction = UUID.randomUUID();
        AnchorMapSnapshot large = anchor("large", faction, 0, 0, 5);
        AnchorMapSnapshot small = anchor("small", faction, 4, 0, 2);
        AnchorMapSnapshot mutual = anchor("mutual", faction, 3, 0, 4);
        AnchorMapSnapshot distant = anchor("distant", faction, 20, 0, 2);

        assertEquals(AnchorNetworkRules.LinkType.ARROW_TO_SECOND,
                AnchorNetworkRules.linkType(large, small));
        assertEquals(AnchorNetworkRules.LinkType.MUTUAL,
                AnchorNetworkRules.linkType(large, mutual));
        assertEquals(AnchorNetworkRules.LinkType.NONE,
                AnchorNetworkRules.linkType(large, distant));
    }

    @Test
    void anchorConnectionMustChainBackToACapitalAnchor() {
        UUID faction = UUID.randomUUID();
        AnchorMapSnapshot capital = anchor("capital", faction, 0, 0, 5);
        AnchorMapSnapshot middle = anchor("middle", faction, 4, 0, 2);
        AnchorMapSnapshot frontier = anchor("frontier", faction, 6, 0, 2);
        AnchorMapSnapshot colony = anchor("colony", faction, 20, 0, 2);
        List<AnchorMapSnapshot> anchors = List.of(capital, middle, frontier, colony);

        assertEquals(Set.of("capital", "middle", "frontier"), AnchorNetworkRules.connectedToCapital(
                anchors, Set.of("capital"), Set.of("capital", "middle", "frontier", "colony")));
        assertEquals(Set.of("capital", "middle"), AnchorNetworkRules.connectedToCapital(
                anchors, Set.of("capital"), Set.of("capital", "middle", "colony")));
    }

    @Test
    void anchorSupplyNetworkCrossesAWorldSeam() {
        UUID faction = UUID.randomUUID();
        AnchorMapSnapshot capital = anchor("capital", faction, 0, 0, 1);
        AnchorMapSnapshot oppositeEdge = anchor("edge", faction, 9, 0, 1);

        assertEquals(AnchorNetworkRules.LinkType.MUTUAL,
                AnchorNetworkRules.linkType(capital, oppositeEdge,
                        AnchorNetworkRulesTest::wrappedDistanceSquared));
        assertEquals(Set.of("capital", "edge"), AnchorNetworkRules.connectedToCapital(
                List.of(capital, oppositeEdge), Set.of("capital"), Set.of("capital", "edge"),
                AnchorNetworkRulesTest::wrappedDistanceSquared));
    }

    private static long wrappedDistanceSquared(AnchorMapSnapshot first, AnchorMapSnapshot second) {
        int firstX = Math.floorDiv(first.x(), 16);
        int secondX = Math.floorDiv(second.x(), 16);
        long directX = Math.abs((long) Math.floorMod(firstX, 10) - Math.floorMod(secondX, 10));
        long dx = Math.min(directX, 10L - directX);
        long dz = (long) Math.floorDiv(first.z(), 16) - Math.floorDiv(second.z(), 16);
        return dx * dx + dz * dz;
    }

    private static AnchorMapSnapshot anchor(String id, UUID faction, int chunkX, int chunkZ, int radius) {
        return new AnchorMapSnapshot(id, faction, "minecraft:overworld", chunkX * 16, 64, chunkZ * 16,
                AnchorTier.BASIC, 0, 0, 0, radius, 0, AnchorPowerState.FULL,
                AnchorConnectionState.CONNECTED, AnchorVulnerabilityState.PROTECTED, 0);
    }

    @Test
    void powerAndConnectionRemainSeparate() {
        assertEquals(AnchorPowerState.FULL, AnchorNetworkRules.powerState(100, 100));
        assertEquals(AnchorPowerState.UNDERPOWERED, AnchorNetworkRules.powerState(100, 40));
        assertEquals(AnchorPowerState.UNPOWERED, AnchorNetworkRules.powerState(100, 0));
        assertEquals(AnchorVulnerabilityState.PROTECTED, AnchorNetworkRules.vulnerabilityState(
                AnchorPowerState.FULL, true, true, 0, 100, 200));
        assertEquals(AnchorVulnerabilityState.GRACE_PERIOD, AnchorNetworkRules.vulnerabilityState(
                AnchorPowerState.FULL, false, true, 100, 250, 200));
        assertEquals(AnchorVulnerabilityState.VULNERABLE, AnchorNetworkRules.vulnerabilityState(
                AnchorPowerState.FULL, false, true, 100, 300, 200));
        assertEquals(AnchorVulnerabilityState.VULNERABLE, AnchorNetworkRules.vulnerabilityState(
                AnchorPowerState.UNDERPOWERED, true, true, 0, 10, 200));
    }
}
