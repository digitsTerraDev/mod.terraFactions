package dev.terrafactions.journeymap;

import dev.terrafactions.factions.FactionSnapshot.ClaimSnapshot;
import dev.terrafactions.territory.TerritoryKey;
import journeymap.api.v2.server.overlay.OverlayPoints;
import journeymap.api.v2.server.overlay.OverlayPolygon;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

final class ClaimPolygonMerger {
    private static final int CHUNK_SIZE = 16;
    private static final int POLYGON_Y = 64;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private ClaimPolygonMerger() {
    }

    static List<OverlayPolygon> merge(Collection<ClaimSnapshot> claims) {
        return merge(claims, key -> key);
    }

    static List<OverlayPolygon> merge(Collection<ClaimSnapshot> claims,
                                      UnaryOperator<TerritoryKey> normalizer) {
        Set<Cell> cells = unwrap(claims, normalizer);

        return mergeCells(cells);
    }

    private static Set<Cell> unwrap(Collection<ClaimSnapshot> claims,
                                    UnaryOperator<TerritoryKey> normalizer) {
        Set<TerritoryKey> canonical = new HashSet<>();
        for (ClaimSnapshot claim : claims) {
            canonical.add(normalizer.apply(new TerritoryKey(claim.dimension(), claim.x(), claim.z())));
        }

        Map<TerritoryKey, Cell> unwrapped = new HashMap<>();
        List<TerritoryKey> roots = canonical.stream()
                .sorted(Comparator.comparing(TerritoryKey::dimension)
                        .thenComparingInt(TerritoryKey::x).thenComparingInt(TerritoryKey::z))
                .toList();
        for (TerritoryKey root : roots) {
            if (unwrapped.containsKey(root)) continue;
            unwrapped.put(root, new Cell(root.x(), root.z()));
            ArrayDeque<TerritoryKey> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                TerritoryKey current = pending.removeFirst();
                Cell position = unwrapped.get(current);
                for (int[] direction : DIRECTIONS) {
                    TerritoryKey neighbor = normalizer.apply(current.offset(direction[0], direction[1]));
                    if (!canonical.contains(neighbor) || unwrapped.containsKey(neighbor)) continue;
                    unwrapped.put(neighbor,
                            new Cell(position.x() + direction[0], position.z() + direction[1]));
                    pending.addLast(neighbor);
                }
            }
        }
        return new HashSet<>(unwrapped.values());
    }

    private static List<OverlayPolygon> mergeCells(Set<Cell> cells) {

        Set<Edge> remaining = new LinkedHashSet<>();
        for (Cell cell : cells) {
            int x = cell.x();
            int z = cell.z();
            if (!cells.contains(new Cell(x, z - 1))) {
                remaining.add(new Edge(new Point(x, z), new Point(x + 1, z)));
            }
            if (!cells.contains(new Cell(x + 1, z))) {
                remaining.add(new Edge(new Point(x + 1, z), new Point(x + 1, z + 1)));
            }
            if (!cells.contains(new Cell(x, z + 1))) {
                remaining.add(new Edge(new Point(x + 1, z + 1), new Point(x, z + 1)));
            }
            if (!cells.contains(new Cell(x - 1, z))) {
                remaining.add(new Edge(new Point(x, z + 1), new Point(x, z)));
            }
        }

        Map<Point, List<Edge>> outgoing = new HashMap<>();
        for (Edge edge : remaining) {
            outgoing.computeIfAbsent(edge.start(), ignored -> new ArrayList<>()).add(edge);
        }

        List<List<Point>> outerRings = new ArrayList<>();
        List<List<Point>> holeRings = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Edge first = remaining.iterator().next();
            Edge edge = first;
            List<Point> ring = new ArrayList<>();
            ring.add(first.start());

            do {
                remaining.remove(edge);
                if (!edge.end().equals(first.start())) {
                    ring.add(edge.end());
                }
                edge = edge.end().equals(first.start())
                        ? null
                        : nextEdge(edge, outgoing.getOrDefault(edge.end(), List.of()), remaining);
            } while (edge != null);

            ring = removeCollinearPoints(ring);
            if (signedArea(ring) > 0) {
                outerRings.add(ring);
            } else {
                holeRings.add(ring);
            }
        }

        List<OverlayPolygon> polygons = new ArrayList<>(outerRings.size());
        for (List<Point> outer : outerRings) {
            List<OverlayPoints> holes = holeRings.stream()
                    .filter(hole -> contains(outer, hole.getFirst()))
                    .map(ClaimPolygonMerger::toOverlayPoints)
                    .toList();
            polygons.add(new OverlayPolygon(toOverlayPoints(outer), holes));
        }
        return polygons;
    }

    private static Edge nextEdge(Edge previous, List<Edge> candidates, Set<Edge> remaining) {
        Edge selected = null;
        int selectedRank = Integer.MAX_VALUE;
        int previousDirection = direction(previous);
        for (Edge candidate : candidates) {
            if (!remaining.contains(candidate)) {
                continue;
            }
            int turn = Math.floorMod(direction(candidate) - previousDirection, 4);
            int rank = switch (turn) {
                case 1 -> 0; // right
                case 0 -> 1; // straight
                case 3 -> 2; // left
                default -> 3; // back
            };
            if (rank < selectedRank) {
                selected = candidate;
                selectedRank = rank;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("Claim boundary did not form a closed ring");
        }
        return selected;
    }

    private static int direction(Edge edge) {
        int dx = edge.end().x() - edge.start().x();
        int dz = edge.end().z() - edge.start().z();
        if (dx > 0) return 0;
        if (dz > 0) return 1;
        if (dx < 0) return 2;
        return 3;
    }

    private static List<Point> removeCollinearPoints(List<Point> ring) {
        List<Point> simplified = new ArrayList<>();
        for (int index = 0; index < ring.size(); index++) {
            Point previous = ring.get(Math.floorMod(index - 1, ring.size()));
            Point current = ring.get(index);
            Point next = ring.get((index + 1) % ring.size());
            if ((previous.x() == current.x() && current.x() == next.x())
                    || (previous.z() == current.z() && current.z() == next.z())) {
                continue;
            }
            simplified.add(current);
        }
        return simplified;
    }

    private static long signedArea(List<Point> ring) {
        long twiceArea = 0;
        for (int index = 0; index < ring.size(); index++) {
            Point current = ring.get(index);
            Point next = ring.get((index + 1) % ring.size());
            twiceArea += (long) current.x() * next.z() - (long) next.x() * current.z();
        }
        return twiceArea;
    }

    private static boolean contains(List<Point> ring, Point point) {
        boolean inside = false;
        for (int current = 0, previous = ring.size() - 1; current < ring.size(); previous = current++) {
            Point a = ring.get(current);
            Point b = ring.get(previous);
            if ((a.z() > point.z()) != (b.z() > point.z())
                    && point.x() < (double) (b.x() - a.x()) * (point.z() - a.z()) / (b.z() - a.z()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static OverlayPoints toOverlayPoints(List<Point> ring) {
        return new OverlayPoints(ring.stream()
                .map(point -> packBlockPos(point.x() * CHUNK_SIZE, POLYGON_Y, point.z() * CHUNK_SIZE))
                .toList());
    }

    private static long packBlockPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (long) y & 0xFFFL;
    }

    private record Cell(int x, int z) {
    }

    private record Point(int x, int z) {
    }

    private record Edge(Point start, Point end) {
    }
}
