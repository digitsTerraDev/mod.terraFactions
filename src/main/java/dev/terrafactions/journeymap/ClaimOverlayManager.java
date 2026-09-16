package dev.terrafactions.journeymap;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorNetworkRules;
import dev.terrafactions.anchor.AnchorNetworkRules.LinkType;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import dev.terrafactions.compat.toroidal.ToroidalTerritoryCompat;
import dev.terrafactions.factions.FactionSnapshot;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.factions.FactionSnapshot.CapitalSnapshot;
import dev.terrafactions.factions.FactionSnapshot.ClaimSnapshot;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.territory.TerritoryClaim;
import dev.terrafactions.territory.TerritoryKey;
import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarCampState;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarOccupationRules;
import dev.terrafactions.war.WarSideSnapshot;
import dev.terrafactions.war.WarSnapshot;
import dev.terrafactions.war.WarState;
import dev.terrafactions.war.PlunderBreachSnapshot;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.server.overlay.IServerOverlayAPI;
import journeymap.api.v2.server.overlay.OverlayShapeProps;
import journeymap.api.v2.server.overlay.OverlayPoints;
import journeymap.api.v2.server.overlay.OverlayPolygon;
import journeymap.api.v2.server.overlay.ServerPolygon;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

final class ClaimOverlayManager {
    private final IServerOverlayAPI overlayApi;
    private final Map<UUID, Set<String>> overlayIds = new HashMap<>();
    private final Map<UUID, Set<String>> warOverlayIds = new HashMap<>();
    private final Map<UUID, FactionSnapshot> knownFactions = new HashMap<>();
    private final Map<UUID, List<AnchorMapSnapshot>> knownAnchors = new HashMap<>();
    private final Map<UUID, Integer> vulnerabilityMasks = new HashMap<>();
    private final Map<UUID, Integer> pendingFullSyncs = new HashMap<>();
    private final Set<UUID> disabledPlayers = new HashSet<>();
    private boolean flashBright;
    private MinecraftServer server;
    private int knownWarSignature;

    ClaimOverlayManager(IServerOverlayAPI overlayApi) {
        this.overlayApi = overlayApi;
    }

    void initialize() {
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawned);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        server = player.getServer();
        syncAll(player);
        rememberCurrentFactions();
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        scheduleFullSync(event);
    }

    private void onPlayerRespawned(PlayerEvent.PlayerRespawnEvent event) {
        scheduleFullSync(event);
    }

    private void scheduleFullSync(PlayerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        server = player.getServer();
        // JourneyMap clears transient server overlays while replacing its client world state.
        // Wait briefly so the replacement finishes before rebuilding every dimension's overlays.
        pendingFullSyncs.put(player.getUUID(), server.getTickCount() + 5);
    }

    private void syncAll(ServerPlayer player) {
        overlayApi.clearAll(player, TerraFactions.MOD_ID);
        if (disabledPlayers.contains(player.getUUID())) {
            return;
        }
        for (FactionSnapshot faction : currentFactions()) {
            showFaction(player, faction);
        }
        warOverlayIds.remove(player.getUUID());
        showWars(player);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        server = event.getServer();
        syncPendingPlayers(event.getServer());
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        syncChanges();
    }

    private void syncPendingPlayers(MinecraftServer minecraftServer) {
        int now = minecraftServer.getTickCount();
        var iterator = pendingFullSyncs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> pending = iterator.next();
            if (now < pending.getValue()) continue;
            iterator.remove();
            ServerPlayer player = minecraftServer.getPlayerList().getPlayer(pending.getKey());
            if (player != null) syncAll(player);
        }
    }

    private void updateFaction(FactionSnapshot faction) {
        Set<String> previousIds = new HashSet<>(overlayIds.getOrDefault(faction.id(), Set.of()));
        Set<String> currentIds = new HashSet<>();
        overlayIds.put(faction.id(), currentIds);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!disabledPlayers.contains(player.getUUID())) {
                showFaction(player, faction);
            }
        }
        previousIds.removeAll(currentIds);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (String staleId : previousIds) overlayApi.remove(player, TerraFactions.MOD_ID, staleId);
        }
    }

    private void removeFaction(UUID factionId) {
        removeFactionOverlays(factionId);
    }

    private void rememberCurrentFactions() {
        knownFactions.clear();
        for (FactionSnapshot faction : currentFactions()) {
            knownFactions.put(faction.id(), faction);
        }
        knownAnchors.clear();
        knownAnchors.putAll(currentAnchors());
        knownWarSignature = currentWarSignature();
    }

    private void syncChanges() {
        flashBright = !flashBright;
        Map<UUID, List<AnchorMapSnapshot>> anchors = currentAnchors();
        Map<UUID, FactionSnapshot> current = new HashMap<>();
        for (FactionSnapshot faction : currentFactions()) {
            current.put(faction.id(), faction);
            int mask = vulnerabilityMask(faction);
            boolean vulnerabilityChanged = vulnerabilityMasks.getOrDefault(faction.id(), -1) != mask;
            vulnerabilityMasks.put(faction.id(), mask);
            if (!faction.equals(knownFactions.get(faction.id()))
                    || !anchors.getOrDefault(faction.id(), List.of())
                    .equals(knownAnchors.getOrDefault(faction.id(), List.of()))
                    || vulnerabilityChanged || mask != 0) {
                updateFaction(faction);
            }
        }

        for (UUID removedId : new HashSet<>(knownFactions.keySet())) {
            if (!current.containsKey(removedId)) {
                removeFaction(removedId);
                vulnerabilityMasks.remove(removedId);
            }
        }

        knownFactions.clear();
        knownFactions.putAll(current);
        knownAnchors.clear();
        knownAnchors.putAll(anchors);
        syncWarChanges();
    }

    private void removeFactionOverlays(UUID factionId) {
        Set<String> ids = overlayIds.remove(factionId);
        if (ids == null || server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (String id : ids) {
                overlayApi.remove(player, TerraFactions.MOD_ID, id);
            }
        }
    }

    void setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) {
            disabledPlayers.remove(player.getUUID());
            syncAll(player);
        } else {
            disabledPlayers.add(player.getUUID());
            overlayApi.clearAll(player, TerraFactions.MOD_ID);
            warOverlayIds.remove(player.getUUID());
        }
    }

    private void syncWarChanges() {
        int signature = currentWarSignature();
        if (signature == knownWarSignature || server == null) return;
        knownWarSignature = signature;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearWarOverlays(player);
            if (!disabledPlayers.contains(player.getUUID())) showWars(player);
        }
    }

    private int currentWarSignature() {
        if (!TerraFactions.territories().wars().isReady()) return 0;
        long now = server == null ? 0L : server.overworld().getGameTime();
        List<PlunderBreachSnapshot> activeBreaches = TerraFactions.territories().wars().allPlunderBreaches()
                .stream().filter(breach -> breach.active(now)).toList();
        return Objects.hash(TerraFactions.territories().wars().allWars(),
                TerraFactions.territories().wars().allWarCamps(),
                TerraFactions.territories().wars().allOccupations(),
                activeBreaches);
    }

    private void clearWarOverlays(ServerPlayer player) {
        Set<String> ids = warOverlayIds.remove(player.getUUID());
        if (ids == null) return;
        for (String id : ids) overlayApi.remove(player, TerraFactions.MOD_ID, id);
    }

    private void showWars(ServerPlayer player) {
        FactionIdentity viewer = TerraFactions.territories().factions().factionForPlayer(player.getUUID());
        if (viewer == null || !TerraFactions.territories().wars().isReady()) return;
        Set<String> ids = warOverlayIds.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        for (WarSnapshot war : TerraFactions.territories().wars().getWarsForFaction(viewer.id())) {
            if (war.state() == WarState.ENDED) continue;
            showWarCamps(player, war, ids);
            showWarObjectives(player, war, ids);
            showWarOccupations(player, war, ids);
            showInvasionRoutes(player, war, ids);
        }
        showPlunderBreaches(player, viewer.id(), ids);
    }

    private void showPlunderBreaches(ServerPlayer player, UUID viewerFactionId, Set<String> ids) {
        long now = server == null ? 0L : server.overworld().getGameTime();
        for (PlunderBreachSnapshot breach : TerraFactions.territories().wars().allPlunderBreaches()) {
            if (!breach.active(now) || (!breach.breachingFactionId().equals(viewerFactionId)
                    && !breach.originalOwnerFactionId().equals(viewerFactionId))) continue;
            ResourceKey<Level> dimension = dimension(breach.dimension());
            if (dimension == null) continue;
            String id = "war/" + breach.warId() + "/breach/" + breach.id();
            ids.add(id);
            String attacker = TerraFactions.territories().factions().factionName(breach.breachingFactionId());
            overlayApi.show(player, TerraFactions.MOD_ID, new ServerPolygon(id, dimension,
                    List.of(chunkCircle(breach.centerChunkX(), breach.centerChunkZ(), breach.radius())),
                    targetProperties(0xFFAA00, "Plunder breach", "Breached by " + attacker)));
        }
    }

    private void showWarCamps(ServerPlayer player, WarSnapshot war, Set<String> ids) {
        for (WarCampSnapshot camp : TerraFactions.territories().wars().getWarCamps(war.id())) {
            if (camp.state() == WarCampState.DESTROYED) continue;
            ResourceKey<Level> dimension = dimension(camp.dimension());
            FactionSnapshot owner = faction(camp.ownerFactionId());
            if (dimension == null || owner == null) continue;
            String id = "war/" + war.id() + "/camp/" + camp.id();
            ids.add(id);
            int color = viewerWarColor(player, camp.ownerFactionId(), owner.color());
            overlayApi.show(player, TerraFactions.MOD_ID, new ServerPolygon(id, dimension,
                    List.of(diamond(camp.x(), camp.z(), 7)), markerProperties(color, 1010,
                    "War Camp — " + owner.name(), camp.state().name())));
        }
    }

    private void showWarObjectives(ServerPlayer player, WarSnapshot war, Set<String> ids) {
        showWarObjectives(player, war, war.attacker(), ids);
        showWarObjectives(player, war, war.defender(), ids);
    }

    private void showWarObjectives(ServerPlayer player, WarSnapshot war, WarSideSnapshot side, Set<String> ids) {
        if (side.warGoal() == null || (side.warGoal().type() != WarGoalType.CONQUEST
                && side.warGoal().type() != WarGoalType.PLUNDER)) return;
        FactionSnapshot faction = faction(side.factionId());
        if (faction == null) return;
        for (String targetId : side.warGoal().targetAnchorIds()) {
            AnchorMapSnapshot anchor = TerraFactions.territories().factions().anchor(targetId);
            ResourceKey<Level> dimension = anchor == null ? null : dimension(anchor.dimension());
            if (anchor == null || dimension == null) continue;
            String id = "war/" + war.id() + "/target/" + side.factionId() + "/"
                    + Integer.toUnsignedString(targetId.hashCode());
            ids.add(id);
            int color = viewerWarColor(player, side.factionId(), faction.color());
            overlayApi.show(player, TerraFactions.MOD_ID, new ServerPolygon(id, dimension,
                    List.of(square(anchor.x(), anchor.z(), 8)), targetProperties(color,
                    side.warGoal().type().name() + " target", faction.name())));
        }
    }

    private void showWarOccupations(ServerPlayer player, WarSnapshot war, Set<String> ids) {
        for (AnchorOccupationSnapshot occupation : TerraFactions.territories().wars().allOccupations()) {
            if (!occupation.warId().equals(war.id())) continue;
            AnchorMapSnapshot anchor = TerraFactions.territories().factions().anchor(occupation.anchorId());
            ResourceKey<Level> dimension = anchor == null ? null : dimension(anchor.dimension());
            FactionSnapshot occupier = faction(occupation.occupyingFactionId());
            if (anchor == null || dimension == null || occupier == null) continue;
            String id = "war/" + war.id() + "/occupied/"
                    + Integer.toUnsignedString(anchor.id().hashCode());
            ids.add(id);
            int color = viewerWarColor(player, occupation.occupyingFactionId(), occupier.color());
            overlayApi.show(player, TerraFactions.MOD_ID, new ServerPolygon(id, dimension,
                    List.of(diamond(anchor.x(), anchor.z(), 6)), markerProperties(color, 1012,
                    "Occupied Anchor", "Occupied by " + occupier.name())));
        }
    }

    private void showInvasionRoutes(ServerPlayer player, WarSnapshot war, Set<String> ids) {
        showInvasionRoutes(player, war, war.attackerFactionId(), ids);
        showInvasionRoutes(player, war, war.defenderFactionId(), ids);
    }

    private void showInvasionRoutes(ServerPlayer player, WarSnapshot war, UUID factionId, Set<String> ids) {
        WarSideSnapshot side = war.side(factionId);
        WarCampSnapshot camp = side == null || side.warCampId() == null ? null
                : TerraFactions.territories().wars().getWarCamp(side.warCampId());
        if (camp == null || camp.state() != WarCampState.ACTIVE) return;
        FactionSnapshot faction = faction(factionId);
        if (faction == null) return;
        List<AnchorMapSnapshot> anchors = TerraFactions.territories().wars().getOccupations(war.id(), factionId)
                .stream().map(occupation -> TerraFactions.territories().factions().anchor(occupation.anchorId()))
                .filter(Objects::nonNull).toList();
        int range = dev.terrafactions.territory.TerraFactionsConfig.ANCHOR_OCCUPATION_RANGE_CHUNKS.get();
        for (AnchorMapSnapshot anchor : anchors) {
            if (!WarOccupationRules.campReachesAnchor(camp, anchor, range,
                    (first, second) -> ToroidalTerritoryCompat.distanceSquared(
                            player.getServer(), first, second))) continue;
            showWarLine(player, war, faction, camp.dimension(), camp.x(), camp.z(), anchor.x(), anchor.z(),
                    "camp/" + Integer.toUnsignedString(anchor.id().hashCode()), ids);
        }
        for (int first = 0; first < anchors.size(); first++) {
            for (int second = first + 1; second < anchors.size(); second++) {
                AnchorMapSnapshot a = anchors.get(first);
                AnchorMapSnapshot b = anchors.get(second);
                if (!WarOccupationRules.anchorsConnect(a, b, range,
                        (firstKey, secondKey) -> ToroidalTerritoryCompat.distanceSquared(
                                player.getServer(), firstKey, secondKey))) continue;
                showWarLine(player, war, faction, a.dimension(), a.x(), a.z(), b.x(), b.z(),
                        Integer.toUnsignedString(a.id().hashCode()) + "/"
                                + Integer.toUnsignedString(b.id().hashCode()), ids);
            }
        }
    }

    private void showWarLine(ServerPlayer player, WarSnapshot war, FactionSnapshot faction,
                             String dimensionName, int firstX, int firstZ, int secondX, int secondZ,
                             String suffix, Set<String> ids) {
        ResourceKey<Level> dimension = dimension(dimensionName);
        if (dimension == null) return;
        String id = "war/" + war.id() + "/route/" + faction.id() + "/" + suffix;
        ids.add(id);
        Vec3 secondCopy = ToroidalTerritoryCompat.nearestCopy(player.getServer(), dimensionName,
                new Vec3(firstX, 0.0D, firstZ), new Vec3(secondX, 0.0D, secondZ));
        overlayApi.show(player, TerraFactions.MOD_ID, new ServerPolygon(id, dimension,
                List.of(connectionLine(firstX, firstZ, secondCopy.x, secondCopy.z)),
                routeProperties(viewerWarColor(player, faction.id(), faction.color()))));
    }

    private void showFaction(ServerPlayer player, FactionSnapshot faction) {
        Map<OverlayGroup, List<ClaimSnapshot>> claimsByGroup = new HashMap<>();
        for (ClaimSnapshot claim : faction.claims()) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(claim.dimension());
            if (dimensionId == null) {
                TerraFactions.LOGGER.warn("Ignoring claim with invalid dimension '{}': {}, {}",
                        claim.dimension(), claim.x(), claim.z());
                continue;
            }

            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            TerritoryType visualType = claim.type() == TerritoryType.CAPITAL ? TerritoryType.CORE : claim.type();
            TerritoryClaim territoryClaim = TerraFactions.territories().claimAt(
                    new TerritoryKey(claim.dimension(), claim.x(), claim.z()));
            AnchorVulnerabilityState state = territoryClaim == null ? AnchorVulnerabilityState.PROTECTED
                    : TerraFactions.territories().vulnerabilityState(territoryClaim);
            claimsByGroup.computeIfAbsent(new OverlayGroup(dimension, visualType, state),
                    ignored -> new ArrayList<>()).add(claim);
        }

        Set<String> ids = overlayIds.computeIfAbsent(faction.id(), ignored -> new HashSet<>());
        for (Map.Entry<OverlayGroup, List<ClaimSnapshot>> entry : claimsByGroup.entrySet()) {
            OverlayGroup group = entry.getKey();
            String id = overlayId(faction.id(), group.dimension(), group.type(), group.state());
            ids.add(id);
            overlayApi.show(player, TerraFactions.MOD_ID,
                    new ServerPolygon(id, group.dimension(), ClaimPolygonMerger.merge(entry.getValue(),
                            key -> ToroidalTerritoryCompat.fold(player.getServer(), key)),
                            shapeProperties(faction, group.type(), group.state(), flashBright)));
        }
        showCapital(player, faction, ids);
        showAnchors(player, faction, ids);
    }

    private void showAnchors(ServerPlayer player, FactionSnapshot faction, Set<String> ids) {
        List<AnchorMapSnapshot> anchors = currentAnchors().getOrDefault(faction.id(), List.of());
        showAnchorConnections(player, faction, anchors, ids);
        for (AnchorMapSnapshot anchor : anchors) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(anchor.dimension());
            if (dimensionId == null) continue;
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            String baseId = faction.id() + "/anchor/" + Integer.toUnsignedString(anchor.id().hashCode());
            String iconId = baseId + "/icon";
            ids.add(iconId);
            overlayApi.show(player, TerraFactions.MOD_ID,
                    new ServerPolygon(iconId, dimension, List.of(anchorIcon(anchor)),
                            anchorIconProperties(faction, anchor)));

        }
    }

    private void showAnchorConnections(ServerPlayer player, FactionSnapshot faction,
                                       List<AnchorMapSnapshot> anchors, Set<String> ids) {
        for (int firstIndex = 0; firstIndex < anchors.size(); firstIndex++) {
            AnchorMapSnapshot first = anchors.get(firstIndex);
            ResourceLocation dimensionId = ResourceLocation.tryParse(first.dimension());
            if (dimensionId == null) continue;
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            for (int secondIndex = firstIndex + 1; secondIndex < anchors.size(); secondIndex++) {
                AnchorMapSnapshot second = anchors.get(secondIndex);
                LinkType linkType = AnchorNetworkRules.linkType(first, second,
                        (a, b) -> ToroidalTerritoryCompat.distanceSquared(player.getServer(),
                                anchorKey(a), anchorKey(b)));
                if (linkType == LinkType.NONE) continue;

                String id = faction.id() + "/anchor-link/"
                        + Integer.toUnsignedString(first.id().hashCode()) + "/"
                        + Integer.toUnsignedString(second.id().hashCode());
                Vec3 secondCopy = ToroidalTerritoryCompat.nearestCopy(player.getServer(), first.dimension(),
                        new Vec3(first.x(), 0.0D, first.z()), new Vec3(second.x(), 0.0D, second.z()));
                List<OverlayPolygon> shapes = new ArrayList<>();
                shapes.add(connectionLine(first.x(), first.z(), secondCopy.x, secondCopy.z));
                if (linkType == LinkType.ARROW_TO_FIRST) {
                    shapes.add(connectionArrow(secondCopy.x, secondCopy.z, first.x(), first.z()));
                } else if (linkType == LinkType.ARROW_TO_SECOND) {
                    shapes.add(connectionArrow(first.x(), first.z(), secondCopy.x, secondCopy.z));
                }
                ids.add(id);
                overlayApi.show(player, TerraFactions.MOD_ID,
                        new ServerPolygon(id, dimension, shapes,
                                anchorConnectionProperties(faction, first, second)));
            }
        }
    }

    private static OverlayPolygon connectionLine(double firstX, double firstZ, double secondX, double secondZ) {
        double dx = secondX - firstX;
        double dz = secondZ - firstZ;
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        // Server-side JourneyMap overlays only expose closed polygons. A one-block strip is the
        // narrowest valid polygon and renders as a line once its outline is disabled.
        double offsetX = -dz / length;
        double offsetZ = dx / length;
        return polygon(List.of(
                point(firstX, firstZ),
                point(secondX, secondZ),
                point(secondX + offsetX, secondZ + offsetZ),
                point(firstX + offsetX, firstZ + offsetZ)));
    }

    private static OverlayPolygon connectionArrow(double sourceX, double sourceZ,
                                                    double targetX, double targetZ) {
        double dx = targetX - sourceX;
        double dz = targetZ - sourceZ;
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double unitX = dx / length;
        double unitZ = dz / length;
        double perpendicularX = -unitZ;
        double perpendicularZ = unitX;
        double middleX = (sourceX + targetX) / 2.0D;
        double middleZ = (sourceZ + targetZ) / 2.0D;
        double tipX = middleX + unitX * 5.0D;
        double tipZ = middleZ + unitZ * 5.0D;
        double baseX = middleX - unitX * 5.0D;
        double baseZ = middleZ - unitZ * 5.0D;
        return polygon(List.of(
                point(tipX, tipZ),
                point(baseX + perpendicularX * 4.0D, baseZ + perpendicularZ * 4.0D),
                point(baseX - perpendicularX * 4.0D, baseZ - perpendicularZ * 4.0D)));
    }

    private static TerritoryKey anchorKey(AnchorMapSnapshot anchor) {
        return new TerritoryKey(anchor.dimension(), Math.floorDiv(anchor.x(), 16),
                Math.floorDiv(anchor.z(), 16));
    }

    private static OverlayPolygon anchorIcon(AnchorMapSnapshot anchor) {
        int x = anchor.x();
        int z = anchor.z();
        int size = 5;
        return polygon(List.of(point(x, z - size), point(x + size, z), point(x, z + size), point(x - size, z)));
    }

    private static OverlayPolygon diamond(int x, int z, int size) {
        return polygon(List.of(point(x, z - size), point(x + size, z),
                point(x, z + size), point(x - size, z)));
    }

    private static OverlayPolygon square(int x, int z, int size) {
        return polygon(List.of(point(x - size, z - size), point(x + size, z - size),
                point(x + size, z + size), point(x - size, z + size)));
    }

    private static OverlayPolygon chunkCircle(int chunkX, int chunkZ, int radiusChunks) {
        double centerX = chunkX * 16.0D + 8.0D;
        double centerZ = chunkZ * 16.0D + 8.0D;
        double radius = Math.max(8.0D, radiusChunks * 16.0D + 8.0D);
        List<Long> points = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0D * index / 24.0D;
            points.add(point(centerX + Math.cos(angle) * radius,
                    centerZ + Math.sin(angle) * radius));
        }
        return polygon(points);
    }

    private static OverlayPolygon polygon(List<Long> points) {
        return new OverlayPolygon(new OverlayPoints(points), List.of());
    }

    private static long point(double x, double z) {
        return new net.minecraft.core.BlockPos((int) Math.round(x), 64, (int) Math.round(z)).asLong();
    }

    private void showCapital(ServerPlayer player, FactionSnapshot faction, Set<String> ids) {
        CapitalSnapshot capital = faction.capital();
        if (capital == null) {
            return;
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(capital.dimension());
        if (dimensionId == null) {
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        String id = faction.id() + "/capital";
        ids.add(id);
        ClaimSnapshot capitalChunk = new ClaimSnapshot(
                capital.x(), capital.z(), capital.dimension(), TerritoryType.CORE);
        overlayApi.show(player, TerraFactions.MOD_ID,
                new ServerPolygon(id, dimension, ClaimPolygonMerger.merge(List.of(capitalChunk)),
                        capitalProperties(faction)));
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps shapeProperties(FactionSnapshot faction, TerritoryType type,
                                                     AnchorVulnerabilityState state, boolean flashBright) {
        String description = faction.description();
        String title = description == null || description.isBlank()
                ? faction.name()
                : faction.name() + " - " + description;

        float normalFill = type != TerritoryType.BORDER ? 0.38f : 0.18f;
        float normalStrokeWidth = type != TerritoryType.BORDER ? 2.0f : 1.0f;
        float normalStrokeOpacity = type != TerritoryType.BORDER ? 0.95f : 0.60f;
        boolean vulnerable = state == AnchorVulnerabilityState.VULNERABLE;
        boolean isolated = state == AnchorVulnerabilityState.GRACE_PERIOD;
        return new OverlayShapeProps(
                isolated ? 0xFFAA00 : faction.color(),
                vulnerable ? (flashBright ? normalFill : normalFill * 0.30f)
                        : isolated ? normalFill * 0.65f : normalFill,
                vulnerable ? 0xFF3030 : isolated ? 0xFFAA00 : faction.color(),
                vulnerable ? normalStrokeWidth + 1.5f : isolated ? normalStrokeWidth + 0.5f : normalStrokeWidth,
                vulnerable ? (flashBright ? 1.0f : 0.25f) : isolated ? 0.85f : normalStrokeOpacity,
                1000,
                UIState.FULLSCREEN_ZOOM_MIN,
                UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class),
                EnumSet.allOf(Context.MapType.class),
                null,
                null);
    }

    private static int vulnerabilityMask(FactionSnapshot faction) {
        int mask = 0;
        for (ClaimSnapshot claim : faction.claims()) {
            TerritoryType visualType = claim.type() == TerritoryType.CAPITAL ? TerritoryType.CORE : claim.type();
            TerritoryClaim territoryClaim = TerraFactions.territories().claimAt(
                    new TerritoryKey(claim.dimension(), claim.x(), claim.z()));
            if (territoryClaim != null && TerraFactions.territories().isVulnerable(territoryClaim)) {
                mask |= visualType == TerritoryType.BORDER ? 1 : 2;
            }
        }
        return mask;
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps capitalProperties(FactionSnapshot faction) {
        String description = faction.description();
        String title = description == null || description.isBlank()
                ? faction.name()
                : faction.name() + " - " + description;
        return new OverlayShapeProps(
                faction.color(),
                0.0f,
                faction.color(),
                0.0f,
                0.0f,
                1001,
                UIState.FULLSCREEN_ZOOM_MIN,
                UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class),
                EnumSet.allOf(Context.MapType.class),
                faction.name(),
                title);
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps anchorIconProperties(FactionSnapshot faction, AnchorMapSnapshot anchor) {
        int color = anchor.vulnerabilityState() == AnchorVulnerabilityState.VULNERABLE ? 0xFF3030
                : anchor.vulnerabilityState() == AnchorVulnerabilityState.GRACE_PERIOD ? 0xFFAA00
                : faction.color();
        return new OverlayShapeProps(color, 0.90f, 0xFFFFFF, 1.5f, 0.95f, 1003,
                UIState.FULLSCREEN_ZOOM_MIN, UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class), EnumSet.allOf(Context.MapType.class), "◆",
                anchor.tier().displayName() + " Faction Anchor — " + faction.name()
                        + " — " + anchor.connectionState().name() + " / " + anchor.vulnerabilityState().name()
                        + " — " + anchor.allocatedPower() + " allocated, "
                        + formatPower(anchor.usablePowerTenths()) + " usable");
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps anchorConnectionProperties(FactionSnapshot faction,
                                                                 AnchorMapSnapshot first,
                                                                 AnchorMapSnapshot second) {
        AnchorVulnerabilityState state = first.vulnerabilityState() == AnchorVulnerabilityState.VULNERABLE
                || second.vulnerabilityState() == AnchorVulnerabilityState.VULNERABLE
                ? AnchorVulnerabilityState.VULNERABLE
                : first.vulnerabilityState() == AnchorVulnerabilityState.GRACE_PERIOD
                || second.vulnerabilityState() == AnchorVulnerabilityState.GRACE_PERIOD
                ? AnchorVulnerabilityState.GRACE_PERIOD : AnchorVulnerabilityState.PROTECTED;
        int color = state == AnchorVulnerabilityState.VULNERABLE ? 0xFF3030
                : state == AnchorVulnerabilityState.GRACE_PERIOD ? 0xFFAA00 : faction.color();
        return new OverlayShapeProps(color, 0.68f, color, 0.0f, 0.0f, 1002,
                UIState.FULLSCREEN_ZOOM_MIN, UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class), EnumSet.allOf(Context.MapType.class),
                null, null);
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps markerProperties(int color, int zIndex, String title, String description) {
        return new OverlayShapeProps(color, 0.88f, 0xFFFFFF, 1.5f, 0.95f, zIndex,
                UIState.FULLSCREEN_ZOOM_MIN, UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class), EnumSet.allOf(Context.MapType.class), title, description);
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps targetProperties(int color, String title, String description) {
        return new OverlayShapeProps(color, 0.12f, color, 2.5f, 0.95f, 1011,
                UIState.FULLSCREEN_ZOOM_MIN, UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class), EnumSet.allOf(Context.MapType.class), title, description);
    }

    @SuppressWarnings("deprecation")
    private static OverlayShapeProps routeProperties(int color) {
        return new OverlayShapeProps(color, 0.48f, color, 0.0f, 0.0f, 1009,
                UIState.FULLSCREEN_ZOOM_MIN, UIState.ZOOM_IN_MAX,
                EnumSet.allOf(Context.UI.class), EnumSet.allOf(Context.MapType.class), null, null);
    }

    private static String formatPower(int tenths) {
        return tenths % 10 == 0 ? Integer.toString(tenths / 10)
                : (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    private List<FactionSnapshot> currentFactions() {
        return TerraFactions.territories().factions().allFactions();
    }

    private Map<UUID, List<AnchorMapSnapshot>> currentAnchors() {
        Map<UUID, List<AnchorMapSnapshot>> result = new HashMap<>();
        for (AnchorMapSnapshot anchor : TerraFactions.territories().factions().allAnchors()) {
            result.computeIfAbsent(anchor.factionId(), ignored -> new ArrayList<>()).add(anchor);
        }
        result.values().forEach(values -> values.sort(java.util.Comparator.comparing(AnchorMapSnapshot::id)));
        return result;
    }

    private FactionSnapshot faction(UUID factionId) {
        return TerraFactions.territories().factions().snapshot(factionId);
    }

    private static int viewerWarColor(ServerPlayer player, UUID factionId, int fallback) {
        FactionIdentity viewer = TerraFactions.territories().factions().factionForPlayer(player.getUUID());
        if (viewer == null) return fallback;
        return viewer.id().equals(factionId) ? 0x55FF55 : 0xFF5555;
    }

    private static ResourceKey<Level> dimension(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    private static String overlayId(UUID factionId, ResourceKey<Level> dimension, TerritoryType type,
                                    AnchorVulnerabilityState state) {
        return factionId + "/" + dimension.location() + "/" + type.name().toLowerCase()
                + "/" + state.name().toLowerCase();
    }

    private record OverlayGroup(ResourceKey<Level> dimension, TerritoryType type,
                                AnchorVulnerabilityState state) {
    }
}
