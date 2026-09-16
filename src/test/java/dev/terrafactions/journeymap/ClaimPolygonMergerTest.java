package dev.terrafactions.journeymap;

import dev.terrafactions.factions.FactionSnapshot.ClaimSnapshot;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.territory.TerritoryKey;
import journeymap.api.v2.server.overlay.OverlayPolygon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPolygonMergerTest {
    @Test
    void mergesAdjacentChunksIntoOneRectangle() {
        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(List.of(claim(0, 0), claim(1, 0)));

        assertEquals(1, polygons.size());
        assertEquals(4, polygons.getFirst().outer().points().size());
        assertEquals(0, polygons.getFirst().holes().size());
    }

    @Test
    void preservesCornersOfAnLShape() {
        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(List.of(claim(0, 0), claim(1, 0), claim(0, 1)));

        assertEquals(1, polygons.size());
        assertEquals(6, polygons.getFirst().outer().points().size());
    }

    @Test
    void keepsDisconnectedRegionsSeparate() {
        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(List.of(claim(0, 0), claim(2, 0)));

        assertEquals(2, polygons.size());
    }

    @Test
    void representsEnclosedUnclaimedChunksAsHoles() {
        List<ClaimSnapshot> claims = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x != 1 || z != 1) {
                    claims.add(claim(x, z));
                }
            }
        }

        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(claims);
        assertEquals(1, polygons.size());
        assertEquals(4, polygons.getFirst().outer().points().size());
        assertEquals(1, polygons.getFirst().holes().size());
        assertEquals(4, polygons.getFirst().holes().getFirst().points().size());
    }

    @Test
    void doesNotJoinRegionsThatOnlyTouchAtACorner() {
        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(List.of(claim(0, 0), claim(1, 1)));

        assertEquals(2, polygons.size());
    }

    @Test
    void unwrapsClaimsAcrossTheWorldEdgeIntoOneShortPolygon() {
        List<OverlayPolygon> polygons = ClaimPolygonMerger.merge(
                List.of(claim(0, 0), claim(9, 0)), ClaimPolygonMergerTest::wrapTen);

        assertEquals(1, polygons.size());
        List<Integer> pointXs = polygons.getFirst().outer().points().stream()
                .map(packed -> (int) (packed >> 38)).toList();
        assertEquals(4, pointXs.size());
        assertTrue(pointXs.contains(-16));
        assertTrue(pointXs.contains(16));
    }

    private static TerritoryKey wrapTen(TerritoryKey key) {
        return new TerritoryKey(key.dimension(), Math.floorMod(key.x(), 10), key.z());
    }

    private static ClaimSnapshot claim(int x, int z) {
        return new ClaimSnapshot(x, z, "minecraft:overworld", TerritoryType.CORE);
    }
}
