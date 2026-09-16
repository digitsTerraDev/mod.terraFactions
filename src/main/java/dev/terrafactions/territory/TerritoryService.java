package dev.terrafactions.territory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.terrafactions.TerraFactions;
import dev.terrafactions.anchor.FactionAnchorBlockEntity;
import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorPowerState;
import dev.terrafactions.anchor.AnchorConnectionState;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import dev.terrafactions.anchor.AnchorNetworkRules;
import dev.terrafactions.compat.toroidal.ToroidalTerritoryCompat;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.factions.FactionCommandService;
import dev.terrafactions.factions.FactionPower;
import dev.terrafactions.factions.FactionDisplay;
import dev.terrafactions.factions.FactionRelation;
import dev.terrafactions.factions.FactionRank;
import dev.terrafactions.factions.NativeFactionService;
import dev.terrafactions.factions.FactionSnapshot;
import dev.terrafactions.factions.FactionDisplayService;
import dev.terrafactions.journeymap.TerraFactionsJourneyMapPlugin;
import dev.terrafactions.network.TerritoryRadarPayload;
import dev.terrafactions.network.FactionUiPayload;
import dev.terrafactions.network.FactionActionPayload;
import dev.terrafactions.network.JourneyMapClaimPayload;
import dev.terrafactions.network.AnchorPowerPayload;
import dev.terrafactions.network.AnchorStatePayload;
import dev.terrafactions.network.AnchorStateRequestPayload;
import dev.terrafactions.war.WarManager;
import dev.terrafactions.war.AnchorOccupationSnapshot;
import dev.terrafactions.war.AnchorSiegeSnapshot;
import dev.terrafactions.war.AnchorSiegeResult;
import dev.terrafactions.war.WarCampSnapshot;
import dev.terrafactions.war.WarSideSnapshot;
import dev.terrafactions.war.WarSnapshot;
import dev.terrafactions.war.WarState;
import dev.terrafactions.war.WarGoalSnapshot;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.event.AnchorAnnexedEvent;
import dev.terrafactions.war.event.PowerSuppressionAppliedEvent;
import dev.terrafactions.war.event.WarDeclaredEvent;
import dev.terrafactions.war.event.WarStartedEvent;
import dev.terrafactions.war.event.WarEndedEvent;
import dev.terrafactions.war.event.WarGoalCompletedEvent;
import dev.terrafactions.war.event.WarCampDestroyedEvent;
import dev.terrafactions.war.event.AnchorOccupiedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TerritoryService {
    private static final SimpleCommandExceptionType NOT_IN_FACTION =
            new SimpleCommandExceptionType(Component.literal("You must belong to a faction to manage territory."));
    private static final SimpleCommandExceptionType GUEST_CANNOT_MANAGE =
            new SimpleCommandExceptionType(Component.literal("Faction guests cannot manage territory."));

    private final NativeFactionService factions = new NativeFactionService();
    private final WarManager wars = new WarManager(factions);
    private final FactionDisplayService displays = new FactionDisplayService(this, factions);
    private final FactionCommandService factionCommands = new FactionCommandService(
            factions, wars, displays::refreshNow, this::isVulnerable);
    private final Map<UUID, TerritoryRadarPayload> radarStates = new HashMap<>();
    private final Map<UUID, FactionUiPayload> factionUiStates = new HashMap<>();
    private final Set<UUID> dirtyNetworks = new HashSet<>();
    private final Map<UUID, Long> knownPowerBudgets = new HashMap<>();
    private final Map<UUID, Long> corePowerUsageCache = new HashMap<>();
    private final Map<CapturePresence, ServerBossEvent> captureBossBars = new HashMap<>();
    private final Map<CapturePresence, Long> captureBossBarExpiry = new HashMap<>();
    private final Set<UUID> hiddenHudPlayers = new HashSet<>();
    private MinecraftServer server;

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onAnchorAnnexed);
        NeoForge.EVENT_BUS.addListener(this::onPowerSuppressionApplied);
        NeoForge.EVENT_BUS.addListener(this::onWarDeclared);
        NeoForge.EVENT_BUS.addListener(this::onWarStarted);
        NeoForge.EVENT_BUS.addListener(this::onWarEnded);
        NeoForge.EVENT_BUS.addListener(this::onWarGoalCompleted);
        NeoForge.EVENT_BUS.addListener(this::onWarCampDestroyed);
        NeoForge.EVENT_BUS.addListener(this::onAnchorOccupied);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onRegisterCommands);
        new TerritoryProtection(this, factions).register();
        displays.register();
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        factions.initialize(server);
        wars.initialize(server);
        reconcileCapitals();
        factions.allFactions().forEach(faction -> dirtyNetworks.add(faction.id()));
    }

    private void onServerStopped(ServerStoppedEvent event) {
        radarStates.clear();
        factionUiStates.clear();
        dirtyNetworks.clear();
        knownPowerBudgets.clear();
        corePowerUsageCache.clear();
        captureBossBars.values().forEach(ServerBossEvent::removeAllPlayers);
        captureBossBars.clear();
        captureBossBarExpiry.clear();
        hiddenHudPlayers.clear();
        wars.stop();
        factions.stop();
        server = null;
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (!factions.isReady()) {
            return;
        }
        int tick = event.getServer().getTickCount();
        wars.tick(event.getServer().overworld().getGameTime());
        if (tick % TerraFactionsConfig.POWER_REGEN_INTERVAL_TICKS.get() == 0) {
            factions.regeneratePower();
            factions.allFactions().forEach(faction -> dirtyNetworks.add(faction.id()));
        }
        if (tick % TerraFactionsConfig.ANCHOR_RECALCULATION_INTERVAL_TICKS.get() == 0) {
            factions.allFactions().forEach(faction -> dirtyNetworks.add(faction.id()));
        }
        processDirtyNetworks(event.getServer());
        if (tick % 20 == 0) {
            progressPresenceCaptures(event.getServer());
            hiddenHudPlayers.removeIf(playerId ->
                    event.getServer().getPlayerList().getPlayer(playerId) == null);
            radarStates.keySet().removeIf(playerId ->
                    event.getServer().getPlayerList().getPlayer(playerId) == null);
            factionUiStates.keySet().removeIf(playerId ->
                    event.getServer().getPlayerList().getPlayer(playerId) == null);
            expireUnanchoredFactions(event.getServer().overworld().getGameTime());
            reconcileCapitals();
            detectPowerAndIsolationChanges(event.getServer());
        }
    }

    private void expireUnanchoredFactions(long now) {
        for (FactionSnapshot faction : factions.allFactions()) {
            long createdAt = factions.createdAt(faction.id());
            if (createdAt < 0L || factions.hasAnchor(faction.id())
                    || now < createdAt + TerraFactionsConfig.FACTION_ANCHOR_PLACEMENT_DEADLINE_TICKS.get()) continue;
            notifyFaction(faction.id(), Component.literal("Your faction was disbanded because it did not place an anchor in time."));
            wars.cancelWarsForFaction(faction.id(), now, "Faction failed to establish an anchor");
            factions.disband(faction.id());
            dirtyNetworks.remove(faction.id());
            displays.refreshNow();
        }
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            onFactionMovement(player);
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (!factions.isReady() || !(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        if (!(victim.getKillCredit() instanceof ServerPlayer killer)
                || killer.getUUID().equals(victim.getUUID())) {
            return;
        }
        FactionIdentity identity = factions.factionForPlayer(victim.getUUID());
        if (identity != null) {
            factions.recordDeath(identity.id(), victim.getUUID(), TerraFactionsConfig.DEATH_POWER_PENALTY.get());
            dirtyNetworks.add(identity.id());
        }
    }

    private void onAnchorAnnexed(AnchorAnnexedEvent event) {
        dirtyNetworks.add(event.occupation().originalOwnerFactionId());
        dirtyNetworks.add(event.occupation().occupyingFactionId());
    }

    private void onPowerSuppressionApplied(PowerSuppressionAppliedEvent event) {
        dirtyNetworks.add(event.modifier().factionId());
    }

    private void onWarDeclared(WarDeclaredEvent event) {
        notifyWar(event.war(), Component.literal("WAR DECLARED: "
                + factions.factionName(event.war().attackerFactionId()) + " vs "
                + factions.factionName(event.war().defenderFactionId())));
    }

    private void onWarStarted(WarStartedEvent event) {
        long campSeconds = TerraFactionsConfig.WAR_CAMP_PLACEMENT_DEADLINE_TICKS.get() / 20L;
        notifyWar(event.war(), Component.literal("War is now ACTIVE: "
                + factions.factionName(event.war().attackerFactionId()) + " vs "
                + factions.factionName(event.war().defenderFactionId())
                + ". Offensive War Camps must be placed within " + campSeconds + " seconds."));
    }

    private void onWarEnded(WarEndedEvent event) {
        notifyWar(event.war(), Component.literal("War ended. Goals — "
                + factions.factionName(event.war().attackerFactionId()) + ": "
                + sideResult(event.war().attacker()) + ", "
                + factions.factionName(event.war().defenderFactionId()) + ": "
                + sideResult(event.war().defender())));
    }

    private void onWarGoalCompleted(WarGoalCompletedEvent event) {
        notifyWar(event.war(), Component.literal(factions.factionName(event.factionId())
                + " completed its " + event.goalType().name() + " war goal."));
    }

    private void onWarCampDestroyed(WarCampDestroyedEvent event) {
        notifyWar(event.war(), Component.literal(factions.factionName(event.warCamp().ownerFactionId())
                + " lost its War Camp."));
    }

    private void onAnchorOccupied(AnchorOccupiedEvent event) {
        notifyWar(event.war(), Component.literal(factions.factionName(event.occupation().occupyingFactionId())
                + " occupied an enemy anchor."));
    }

    private void notifyWar(WarSnapshot war, Component message) {
        notifyFaction(war.attackerFactionId(), message);
        notifyFaction(war.defenderFactionId(), message);
    }

    private void notifyFaction(UUID factionId, Component message) {
        if (server == null) return;
        for (UUID memberId : factions.members(factionId)) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) member.sendSystemMessage(message);
        }
    }

    private static String sideResult(WarSideSnapshot side) {
        String state = side.goalCompleted() ? "SUCCESS" : side.goalFailed() ? "FAILED" : "INCOMPLETE";
        return side.warGoal() == null || side.warGoal().result().isBlank()
                ? state : state + " (" + side.warGoal().result() + ")";
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        factionCommands.register(event.getDispatcher(), "terrafactions");
        factionCommands.register(event.getDispatcher(), "tf");
        factionCommands.register(event.getDispatcher(), "factions");
        registerCommands(event.getDispatcher(), "terrafactions");
        registerCommands(event.getDispatcher(), "tf");

        // Brigadier merges this territory subtree into the native faction root.
        event.getDispatcher().register(Commands.literal("factions")
                .then(Commands.literal("claim")
                        .executes(context -> claim(context.getSource(), TerritoryType.CORE))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 7))
                                .executes(context -> bulkClaim(context.getSource(), TerritoryType.CORE,
                                        IntegerArgumentType.getInteger(context, "radius"))))
                        .then(Commands.literal("add")
                                .executes(context -> claim(context.getSource(), TerritoryType.CORE))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 7))
                                        .executes(context -> bulkClaim(context.getSource(), TerritoryType.CORE,
                                                IntegerArgumentType.getInteger(context, "radius")))))
                        .then(Commands.literal("remove")
                                .executes(context -> unclaim(context.getSource()))
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 7))
                                        .executes(context -> unsupportedBulk(context.getSource())))
                                .then(Commands.literal("all").executes(context -> unclaimAll(context.getSource()))))
                        .then(Commands.literal("auto").executes(context -> unsupportedAutoClaim(context.getSource())))
                        .then(Commands.literal("core")
                                .executes(context -> claim(context.getSource(), TerritoryType.CORE))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 7))
                                        .executes(context -> bulkClaim(context.getSource(), TerritoryType.CORE,
                                                IntegerArgumentType.getInteger(context, "radius"))))))
                .then(Commands.literal("unclaim").executes(context -> unclaim(context.getSource())))
                .then(Commands.literal("liberate").executes(context -> liberate(context.getSource())))
                .then(Commands.literal("convert")
                        .then(Commands.literal("core").executes(context -> claim(context.getSource(), TerritoryType.CORE))))
                .then(Commands.literal("capital")
                        .then(Commands.literal("set").executes(context -> setCapital(context.getSource()))))
                .then(buildOverlayCommands())
                .then(buildTerritoryCommands("territory")));
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(Commands.literal(root).then(buildTerritoryCommands("claim"))
                .then(Commands.literal("unclaim").executes(context -> unclaim(context.getSource())))
                .then(Commands.literal("liberate").executes(context -> liberate(context.getSource())))
                .then(Commands.literal("convert")
                        .then(Commands.literal("core").executes(context -> claim(context.getSource(), TerritoryType.CORE))))
                .then(Commands.literal("capital")
                        .then(Commands.literal("set").executes(context -> setCapital(context.getSource()))))
                .then(buildOverlayCommands()));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildTerritoryCommands(String name) {
        return Commands.literal(name)
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 7))
                        .executes(context -> bulkClaim(context.getSource(), TerritoryType.CORE,
                                IntegerArgumentType.getInteger(context, "radius"))))
                .then(Commands.literal("core")
                        .executes(context -> claim(context.getSource(), TerritoryType.CORE))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 7))
                                .executes(context -> bulkClaim(context.getSource(), TerritoryType.CORE,
                                        IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("unclaim").executes(context -> unclaim(context.getSource())))
                .then(Commands.literal("liberate").executes(context -> liberate(context.getSource())))
                .then(Commands.literal("convert")
                        .then(Commands.literal("core").executes(context -> claim(context.getSource(), TerritoryType.CORE))))
                .then(Commands.literal("capital")
                        .then(Commands.literal("set").executes(context -> setCapital(context.getSource()))))
                .then(buildOverlayCommands());
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildOverlayCommands() {
        return Commands.literal("overlay")
                .then(Commands.literal("on").executes(context -> setOverlay(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setOverlay(context.getSource(), false)));
    }

    public NativeFactionService factions() {
        return factions;
    }

    public WarManager wars() {
        return wars;
    }

    public boolean placeAnchor(ServerPlayer player, FactionAnchorBlockEntity anchor) {
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        if (identity == null || !identity.rank().canBuild()) {
            player.sendSystemMessage(Component.literal("You must be a building member of a faction to place an anchor."));
            return false;
        }
        TerritoryKey key = anchorKey(anchor);
        TerritoryClaim claim = claimAt(key);
        if (claim == null || !claim.factionId().equals(identity.id())) {
            player.sendSystemMessage(Component.literal("Faction anchors must be placed inside your faction's territory."));
            return false;
        }
        TerritoryKey capital = factions.capital(identity.id());
        if (!factions.hasAnchor(identity.id())
                && (capital == null || !key.equals(canonicalKey(capital)))) {
            player.sendSystemMessage(Component.literal("Your faction's first anchor must be placed in its capital chunk."));
            return false;
        }
        anchor.assign(identity.id());
        configureAnchorProjection(anchor, 0);
        return true;
    }

    public void openAnchor(ServerPlayer player, FactionAnchorBlockEntity anchor) {
        loadAnchor(anchor);
        AnchorOccupationSnapshot occupation = wars.getOccupation(anchorId(anchor));
        if (occupation != null) {
            player.sendSystemMessage(Component.literal("This anchor is occupied by "
                    + factions.factionName(occupation.occupyingFactionId()) + " during war "
                    + occupation.warId().toString().substring(0, 8) + "."));
            return;
        }
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        if (identity != null && anchor.factionId() != null && !anchor.factionId().equals(identity.id())) {
            showEnemyAnchorCaptureStatus(player, anchor, identity);
            return;
        }
        if (!canConfigureAnchor(player, anchor)) return;
        loadAnchor(anchor);
        PacketDistributor.sendToPlayer(player, anchorState(anchor));
    }

    private void progressPresenceCaptures(MinecraftServer minecraftServer) {
        long now = minecraftServer.overworld().getGameTime();
        Map<TerritoryKey, List<AnchorMapSnapshot>> anchorsByChunk = new HashMap<>();
        for (AnchorMapSnapshot anchor : factions.allAnchors()) {
            anchorsByChunk.computeIfAbsent(anchorKey(anchor), ignored -> new java.util.ArrayList<>()).add(anchor);
        }
        Map<CapturePresence, List<ServerPlayer>> participants = new HashMap<>();
        for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
            FactionIdentity identity = factions.factionForPlayer(player.getUUID());
            if (identity == null) continue;
            for (AnchorMapSnapshot anchor : anchorsByChunk.getOrDefault(currentChunk(player), List.of())) {
                AnchorOccupationSnapshot occupation = wars.getOccupation(anchor.id());
                boolean attacking = !anchor.factionId().equals(identity.id());
                boolean liberating = occupation != null
                        && occupation.originalOwnerFactionId().equals(identity.id());
                if (!attacking && !liberating) continue;
                participants.computeIfAbsent(new CapturePresence(anchor.id(), identity.id()),
                        ignored -> new java.util.ArrayList<>()).add(player);
            }
        }
        Set<CapturePresence> activeBars = new HashSet<>();
        for (Map.Entry<CapturePresence, List<ServerPlayer>> entry : participants.entrySet()) {
            List<ServerPlayer> present = entry.getValue();
            try {
                AnchorSiegeResult result = wars.damageAnchorSiege(
                        present.getFirst().getUUID(), entry.getKey().anchorId(), 1);
                ServerBossEvent bossBar = captureBossBars.computeIfAbsent(entry.getKey(), ignored ->
                        new ServerBossEvent(Component.literal("Capturing Anchor"),
                                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10));
                syncCaptureBossBarPlayers(bossBar, present);
                bossBar.setProgress(Math.max(0.0F, Math.min(1.0F,
                        (float) result.progress() / Math.max(1, result.requiredProgress()))));
                activeBars.add(entry.getKey());
                if (result.completed()) {
                    Component completed = captureCompleted(result);
                    bossBar.setName(completed);
                    bossBar.setColor(BossEvent.BossBarColor.GREEN);
                    captureBossBarExpiry.put(entry.getKey(), now + 40L);
                    for (ServerPlayer player : present) player.sendSystemMessage(completed);
                } else {
                    bossBar.setName(Component.literal("Capturing Anchor - " + result.progress()
                            + "/" + result.requiredProgress()));
                    bossBar.setColor(BossEvent.BossBarColor.RED);
                    captureBossBarExpiry.remove(entry.getKey());
                }
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Merely standing in a non-eligible anchor chunk should not produce repeated chat spam.
            }
        }
        for (CapturePresence key : new HashSet<>(captureBossBars.keySet())) {
            if (activeBars.contains(key)) continue;
            Long expiry = captureBossBarExpiry.get(key);
            if (expiry != null && now < expiry) continue;
            captureBossBars.remove(key).removeAllPlayers();
            captureBossBarExpiry.remove(key);
        }
    }

    private void syncCaptureBossBarPlayers(ServerBossEvent bossBar, List<ServerPlayer> present) {
        Set<ServerPlayer> desired = present.stream()
                .filter(player -> !hiddenHudPlayers.contains(player.getUUID()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (ServerPlayer current : List.copyOf(bossBar.getPlayers())) {
            if (!desired.contains(current)) bossBar.removePlayer(current);
        }
        for (ServerPlayer player : desired) {
            if (!bossBar.getPlayers().contains(player)) bossBar.addPlayer(player);
        }
    }

    public void setHudVisible(ServerPlayer player, boolean visible) {
        if (visible) hiddenHudPlayers.remove(player.getUUID());
        else {
            hiddenHudPlayers.add(player.getUUID());
            captureBossBars.values().forEach(bossBar -> bossBar.removePlayer(player));
        }
    }

    private void showEnemyAnchorCaptureStatus(ServerPlayer player, FactionAnchorBlockEntity anchor,
                                              FactionIdentity identity) {
        WarSnapshot war = wars.getWarBetweenFactions(identity.id(), anchor.factionId());
        if (war == null || war.state() != WarState.ACTIVE) {
            player.displayClientMessage(Component.literal("Enemy anchor - no active war permits its capture."), true);
            return;
        }
        String id = anchorId(anchor);
        AnchorSiegeSnapshot siege = wars.getSiege(id);
        if (siege != null && siege.attackingFactionId().equals(identity.id())) {
            player.displayClientMessage(Component.literal(
                    "CAPTURE ACTIVE - remain in this chunk; progress is shown in the boss bar."), true);
            return;
        }
        try {
            if (wars.isAnchorAttackEligible(war.id(), identity.id(), id)) {
                player.displayClientMessage(Component.literal(
                        "CAPTURE READY - remain anywhere in this chunk to progress."), true);
            } else {
                player.displayClientMessage(Component.literal(
                        "NOT CONNECTED - extend the invasion frontline to this anchor first."), true);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            player.displayClientMessage(Component.literal("CANNOT CAPTURE - " + exception.getMessage()), true);
        }
    }

    private static Component captureCompleted(AnchorSiegeResult result) {
        return Component.literal(result.liberated()
                ? "Faction anchor liberated."
                : result.annexed() ? "Conquest complete. Faction anchor permanently annexed."
                : "Faction anchor occupied. The permanent owner has not changed.");
    }

    private record CapturePresence(String anchorId, UUID factionId) {
    }

    boolean isOccupiedAnchor(net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FactionAnchorBlockEntity anchor
                && wars.isReady() && wars.getOccupation(anchorId(anchor)) != null;
    }

    boolean isLastCapitalAnchor(net.minecraft.world.level.LevelAccessor level, BlockPos pos, UUID playerId) {
        if (!(level instanceof net.minecraft.world.level.Level actualLevel)
                || !(level.getBlockEntity(pos) instanceof FactionAnchorBlockEntity anchor)
                || anchor.factionId() == null) return false;
        FactionIdentity identity = factions.factionForPlayer(playerId);
        UUID owner = anchor.factionId();
        if (identity == null || !owner.equals(identity.id())) return false;
        TerritoryKey storedCapital = factions.capital(owner);
        TerritoryKey capital = storedCapital == null ? null : canonicalKey(storedCapital);
        TerritoryKey target = canonicalKey(new TerritoryKey(actualLevel.dimension().location().toString(),
                pos.getX() >> 4, pos.getZ() >> 4));
        if (!target.equals(capital)) return false;
        return factions.allAnchors().stream()
                .filter(existing -> existing.factionId().equals(owner))
                .filter(existing -> anchorKey(existing).equals(capital))
                .count() <= 1L;
    }

    public void loadAnchor(FactionAnchorBlockEntity anchor) {
        if (!factions.isReady() || anchor.factionId() == null) return;
        AnchorMapSnapshot persisted = factions.anchor(anchorId(anchor));
        if (persisted == null) {
            configureAnchorProjection(anchor, anchor.allocatedPower());
        } else if (!persisted.factionId().equals(anchor.factionId())) {
            anchor.assign(persisted.factionId());
        }
    }

    public void setAnchorPower(ServerPlayer player, AnchorPowerPayload payload) {
        if (!(player.level().getBlockEntity(payload.pos()) instanceof FactionAnchorBlockEntity anchor)) {
            return;
        }
        loadAnchor(anchor);
        if (!canConfigureAnchor(player, anchor)) return;
        int maximum = maximumAnchorPower(anchor.factionId());
        if (payload.power() < 0 || payload.power() > maximum) {
            player.sendSystemMessage(Component.literal("Dedicated power must be between 0 and " + maximum + "."));
        } else {
            configureAnchorProjection(anchor, payload.power());
            processDirtyNetworks(server);
            wars.reconcileInvasionNetworks();
        }
        PacketDistributor.sendToPlayer(player, anchorState(anchor));
    }

    public void requestAnchorState(ServerPlayer player, AnchorStateRequestPayload payload) {
        if (player.level().getBlockEntity(payload.pos()) instanceof FactionAnchorBlockEntity anchor) {
            loadAnchor(anchor);
            if (!canConfigureAnchor(player, anchor)) return;
            PacketDistributor.sendToPlayer(player, anchorState(anchor));
        }
    }

    public void removeAnchor(FactionAnchorBlockEntity anchor) {
        UUID owner = anchor.factionId();
        if (owner == null || !factions.isReady()) return;
        loadAnchor(anchor);
        owner = anchor.factionId();
        String id = anchorId(anchor);
        anchor.updateProjection(anchor.allocatedPower());
        wars.handleAnchorRemoved(id);
        factions.removeAnchor(id);
        dirtyNetworks.add(owner);
        ensureCapital(owner);
    }

    private boolean canConfigureAnchor(ServerPlayer player, FactionAnchorBlockEntity anchor) {
        if (anchor.getLevel() != player.level()
                || ToroidalTerritoryCompat.blockDistanceSquared(server,
                player.level().dimension().location().toString(), player.position(),
                net.minecraft.world.phys.Vec3.atCenterOf(anchor.getBlockPos())) > 64.0D) {
            return false;
        }
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        AnchorOccupationSnapshot occupation = wars.getOccupation(anchorId(anchor));
        if (occupation != null) {
            player.sendSystemMessage(Component.literal("This anchor is occupied and cannot be configured."));
            return false;
        }
        if (identity == null || anchor.factionId() == null || !anchor.factionId().equals(identity.id())
                || !identity.rank().canBuild()) {
            player.sendSystemMessage(Component.literal("You cannot configure this faction anchor."));
            return false;
        }
        return true;
    }

    private boolean configureAnchorProjection(FactionAnchorBlockEntity anchor, int allocatedPower) {
        UUID owner = anchor.factionId();
        if (owner == null) return false;
        String id = anchorId(anchor);
        int costTenths = anchor.tier().powerTenthsPerClaim();
        TerritoryKey anchorChunk = anchorKey(anchor);
        int maxClaims = (int) Math.min(Integer.MAX_VALUE, allocatedPower * 10L / costTenths);
        TerritoryRules.CircularProjection projection = TerritoryRules.largestCircularProjection(
                anchorChunk, maxClaims, this::canonicalKey);
        Set<TerritoryKey> desired = projection.claims();
        anchor.updateProjection(allocatedPower);
        AnchorMapSnapshot old = factions.anchor(id);
        long now = server == null ? 0L : server.overworld().getGameTime();
        factions.putAnchor(new AnchorMapSnapshot(id, owner,
                anchor.getLevel().dimension().location().toString(), anchor.getBlockPos().getX(),
                anchor.getBlockPos().getY(), anchor.getBlockPos().getZ(), anchor.tier(), allocatedPower,
                old == null ? 0 : old.usablePowerTenths(), old == null ? 0 : old.priority(), projection.radius(),
                desired.size(), old == null ? AnchorPowerState.UNPOWERED : old.powerState(),
                old == null ? AnchorConnectionState.ISOLATED : old.connectionState(),
                old == null ? AnchorVulnerabilityState.GRACE_PERIOD : old.vulnerabilityState(),
                old == null ? now : old.isolationStartTick()));
        dirtyNetworks.add(owner);
        return true;
    }

    private static String anchorId(FactionAnchorBlockEntity anchor) {
        return anchor.getLevel().dimension().location() + "/" + anchor.getBlockPos().asLong();
    }

    private int maximumAnchorPower(UUID owner) {
        FactionPower power = factions.power(owner);
        return power == null ? 0 : Math.min(power.maximum(), TerraFactionsConfig.MAX_ANCHOR_POWER.get());
    }

    private AnchorStatePayload anchorState(FactionAnchorBlockEntity anchor) {
        String factionName = factions.factionName(anchor.factionId());
        AnchorMapSnapshot state = factions.anchor(anchorId(anchor));
        if (state == null) {
            configureAnchorProjection(anchor, anchor.allocatedPower());
            state = factions.anchor(anchorId(anchor));
        }
        return new AnchorStatePayload(anchor.getBlockPos(), factionName == null ? "Unknown faction" : factionName,
                anchor.tier().displayName(), anchor.tier().powerTenthsPerClaim(), state.allocatedPower(),
                state.usablePowerTenths(), maximumAnchorPower(anchor.factionId()), state.projectedClaims(),
                state.projectedRadius(), state.powerState(), state.connectionState(), state.vulnerabilityState(),
                isolationSecondsRemaining(state));
    }

    private TerritoryKey anchorKey(FactionAnchorBlockEntity anchor) {
        ChunkPos chunk = new ChunkPos(anchor.getBlockPos());
        return canonicalKey(TerritoryKey.of(anchor.getLevel().dimension().location(), chunk.x, chunk.z));
    }

    private TerritoryKey anchorKey(AnchorMapSnapshot anchor) {
        return canonicalKey(new TerritoryKey(anchor.dimension(), Math.floorDiv(anchor.x(), 16),
                Math.floorDiv(anchor.z(), 16)));
    }

    private void processDirtyNetworks(MinecraftServer minecraftServer) {
        if (dirtyNetworks.isEmpty()) return;
        dirtyNetworks.clear();
        long now = minecraftServer.overworld().getGameTime();
        reconcileAnchorBorders();
        for (FactionSnapshot faction : factions.allFactions()) recalculateFactionNetwork(faction.id(), now);
    }

    private void reconcileAnchorBorders() {
        List<AnchorMapSnapshot> anchors = List.copyOf(factions.allAnchors());
        List<TerritoryRules.BorderInfluence> influences = new java.util.ArrayList<>();
        for (AnchorMapSnapshot anchor : anchors) {
            TerritoryRules.CircularProjection projection = projectionFor(anchor);
            long strength = effectiveBorderStrength(anchor);
            influences.add(new TerritoryRules.BorderInfluence(anchor.id(), anchor.factionId(),
                    anchorKey(anchor), projection.radius(), strength, projection.claims()));
            if (anchor.projectedRadius() != projection.radius()
                    || anchor.projectedClaims() != projection.claims().size()) {
                factions.putAnchor(new AnchorMapSnapshot(anchor.id(), anchor.factionId(), anchor.dimension(),
                        anchor.x(), anchor.y(), anchor.z(), anchor.tier(), anchor.allocatedPower(),
                        anchor.usablePowerTenths(), anchor.priority(), projection.radius(), projection.claims().size(),
                        anchor.powerState(), anchor.connectionState(), anchor.vulnerabilityState(),
                        anchor.isolationStartTick()));
            }
        }

        Map<TerritoryKey, TerritoryRules.BorderInfluence> winners = TerritoryRules.resolveBorderInfluence(
                influences, this::distanceSquared);
        for (TerritoryClaim claim : allTerritory().stream().filter(TerritoryClaim::projected).toList()) {
            TerritoryRules.BorderInfluence winner = winners.get(claim.key());
            if (winner == null) factions.removeClaim(claim.key());
            else if (!winner.factionId().equals(claim.factionId())) {
                factions.putProjectedClaim(claim.key(), winner.factionId());
            }
        }
        for (Map.Entry<TerritoryKey, TerritoryRules.BorderInfluence> entry : winners.entrySet()) {
            if (claimAt(entry.getKey()) == null) {
                // Manual core/capital claims remain fixed; only projected border claims are contested.
                factions.putProjectedClaim(entry.getKey(), entry.getValue().factionId());
            }
        }
    }

    private void detectPowerAndIsolationChanges(MinecraftServer minecraftServer) {
        Set<UUID> existing = new HashSet<>();
        Map<UUID, Long> coreUsage = new HashMap<>();
        for (TerritoryClaim claim : allTerritory()) {
            if (!claim.projected()) {
                coreUsage.merge(claim.factionId(), claim.powerCostTenths(), Long::sum);
            }
        }
        for (FactionSnapshot faction : factions.allFactions()) {
            existing.add(faction.id());
            FactionPower power = factions.power(faction.id());
            long grossPower = power == null ? 0L : (long) power.current() + power.claimUsage();
            long signature = grossPower * 31L + coreUsage.getOrDefault(faction.id(), 0L);
            if (!Long.valueOf(signature).equals(knownPowerBudgets.put(faction.id(), signature))) {
                dirtyNetworks.add(faction.id());
            }
        }
        knownPowerBudgets.keySet().removeIf(id -> !existing.contains(id));
        corePowerUsageCache.keySet().removeIf(id -> !existing.contains(id));
        corePowerUsageCache.putAll(coreUsage);

        long now = minecraftServer.overworld().getGameTime();
        long grace = TerraFactionsConfig.ANCHOR_ISOLATION_GRACE_TICKS.get();
        for (AnchorMapSnapshot anchor : factions.allAnchors()) {
            if (anchor.vulnerabilityState() == AnchorVulnerabilityState.GRACE_PERIOD
                    && now - anchor.isolationStartTick() >= grace) {
                dirtyNetworks.add(anchor.factionId());
            }
        }
    }

    private void recalculateFactionNetwork(UUID factionId, long now) {
        if (factions.snapshot(factionId) == null) return;
        List<AnchorMapSnapshot> anchors = factions.allAnchors().stream()
                .filter(anchor -> anchor.factionId().equals(factionId))
                .sorted(Comparator.comparingInt(AnchorMapSnapshot::priority).reversed()
                        .thenComparing(AnchorMapSnapshot::id))
                .toList();
        Map<TerritoryKey, TerritoryClaim> claims = new HashMap<>();
        for (TerritoryClaim claim : allTerritory()) {
            if (claim.factionId().equals(factionId)) claims.put(claim.key(), claim);
        }

        Set<String> connectedAnchors = connectedAnchors(factionId, claims, anchors);

        FactionPower factionPower = factions.power(factionId);
        long grossPowerTenths = factionPower == null ? 0L
                : ((long) factionPower.current() + factionPower.claimUsage()) * 10L;
        long remainingAnchorPower = Math.max(0L, grossPowerTenths - corePowerUsageTenths(factionId));
        long grace = TerraFactionsConfig.ANCHOR_ISOLATION_GRACE_TICKS.get();
        for (AnchorMapSnapshot anchor : anchors) {
            long required = AnchorNetworkRules.requiredPowerTenths(anchor.tier(), anchor.projectedClaims());
            int usable = (int) Math.min(Integer.MAX_VALUE, Math.min(required, remainingAnchorPower));
            remainingAnchorPower = Math.max(0L, remainingAnchorPower - usable);
            AnchorPowerState powerState = AnchorNetworkRules.powerState(required, usable);
            boolean connected = connectedAnchors.contains(anchor.id());
            long isolationStart = connected ? 0L
                    : anchor.connectionState() == AnchorConnectionState.ISOLATED
                    && anchor.isolationStartTick() > 0 ? anchor.isolationStartTick() : now;
            AnchorConnectionState connectionState = connected
                    ? AnchorConnectionState.CONNECTED : AnchorConnectionState.ISOLATED;
            AnchorVulnerabilityState vulnerabilityState = AnchorNetworkRules.vulnerabilityState(
                    powerState, connected, anchor.projectedClaims() > 0,
                    isolationStart, now, grace);
            factions.putAnchor(new AnchorMapSnapshot(anchor.id(), anchor.factionId(), anchor.dimension(),
                    anchor.x(), anchor.y(), anchor.z(), anchor.tier(), anchor.allocatedPower(), usable,
                    anchor.priority(), anchor.projectedRadius(), anchor.projectedClaims(), powerState,
                    connectionState, vulnerabilityState, isolationStart));
        }
    }

    private TerritoryRules.CircularProjection projectionFor(AnchorMapSnapshot anchor) {
        int maxClaims = (int) Math.min(Integer.MAX_VALUE, effectiveBorderStrength(anchor));
        return TerritoryRules.largestCircularProjection(anchorKey(anchor), maxClaims, this::canonicalKey);
    }

    private static long effectiveBorderStrength(AnchorMapSnapshot anchor) {
        return Math.max(0L, anchor.allocatedPower() * 10L / anchor.tier().powerTenthsPerClaim());
    }

    private Set<String> connectedAnchors(UUID factionId, Map<TerritoryKey, TerritoryClaim> claims,
                                         List<AnchorMapSnapshot> anchors) {
        TerritoryKey storedCapital = factions.capital(factionId);
        TerritoryKey capital = storedCapital == null ? null : canonicalKey(storedCapital);
        if (capital == null || !claims.containsKey(capital)) return Set.of();
        Set<TerritoryKey> reachable = TerritoryRules.connectedComponent(
                claims.keySet(), capital, this::canonicalKey);
        Set<String> capitalAnchors = anchors.stream()
                .filter(anchor -> anchorKey(anchor).equals(capital))
                .map(AnchorMapSnapshot::id).collect(java.util.stream.Collectors.toSet());
        Set<String> territoriallyReachable = anchors.stream()
                .filter(anchor -> reachable.contains(anchorKey(anchor)))
                .map(AnchorMapSnapshot::id).collect(java.util.stream.Collectors.toSet());
        return AnchorNetworkRules.connectedToCapital(anchors, capitalAnchors, territoriallyReachable,
                (first, second) -> distanceSquared(anchorKey(first), anchorKey(second)));
    }

    private long corePowerUsageTenths(UUID factionId) {
        return corePowerUsageCache.computeIfAbsent(factionId, id -> allTerritory().stream()
                .filter(claim -> claim.factionId().equals(factionId) && !claim.projected())
                .mapToLong(TerritoryClaim::powerCostTenths).sum());
    }

    private long isolationSecondsRemaining(AnchorMapSnapshot anchor) {
        if (anchor.connectionState() != AnchorConnectionState.ISOLATED || server == null) return 0L;
        long elapsed = server.overworld().getGameTime() - anchor.isolationStartTick();
        return Math.max(0L, TerraFactionsConfig.ANCHOR_ISOLATION_GRACE_TICKS.get() - elapsed) / 20L;
    }

    /** Handles native dashboard requests directly on the server thread. */
    public void handleUiAction(ServerPlayer player, FactionActionPayload payload) {
        CommandSourceStack source = player.createCommandSourceStack().withSuppressedOutput();
        try {
            switch (payload.action()) {
                case CLAIM_CORE -> claim(source, TerritoryType.CORE);
                case UNCLAIM -> unclaim(source);
                case SET_CAPITAL -> setCapital(source);
                case SET_OVERLAY -> setOverlay(source, payload.enabled());
                case SET_PROTECTION -> setFactionProtection(player, payload);
                case DECLARE_WAR, CHOOSE_WAR_GOAL, ADD_WAR_TARGET, REMOVE_WAR_TARGET,
                     SELECT_WAR_CAMP -> handleWarUiAction(player, payload);
                default -> factionCommands.handleUiAction(player, payload);
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage()));
        }
        factions.allFactions().forEach(faction -> dirtyNetworks.add(faction.id()));
        syncClientState(player, true);
    }

    private void setFactionProtection(ServerPlayer player, FactionActionPayload payload) {
        FactionIdentity actor = factions.factionForPlayer(player.getUUID());
        if (actor == null || !actor.rank().isLeadership()) {
            throw new IllegalStateException("Only faction leadership can configure territory protections");
        }
        ProtectionAction action = ProtectionAction.valueOf(payload.primary().toUpperCase(Locale.ROOT));
        TerritoryType territoryType = TerritoryType.valueOf(payload.secondary().toUpperCase(Locale.ROOT));
        if (territoryType == TerritoryType.CAPITAL) territoryType = TerritoryType.CORE;
        factions.setProtection(actor.id(), territoryType, action, payload.enabled());
        player.sendSystemMessage(Component.literal((territoryType == TerritoryType.CORE ? "Core " : "Border ")
                + action.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " protection "
                + (payload.enabled() ? "enabled." : "disabled.")));
    }

    private void handleWarUiAction(ServerPlayer player, FactionActionPayload payload) {
        FactionIdentity actor = factions.factionForPlayer(player.getUUID());
        if (actor == null || !actor.rank().isLeadership()) {
            throw new IllegalStateException("Only faction leadership can manage wars");
        }
        UUID opponentId = factions.factionByName(payload.primary().trim());
        if (opponentId == null || opponentId.equals(actor.id())) {
            throw new IllegalArgumentException("The selected opposing faction does not exist");
        }

        switch (payload.action()) {
            case DECLARE_WAR -> {
                WarGoalType goal = offensiveGoal(payload.secondary());
                WarGoalSnapshot selected = goalWithInitialTarget(goal, payload.tertiary(), opponentId);
                wars.declareWar(actor.id(), opponentId, selected);
            }
            case CHOOSE_WAR_GOAL -> {
                WarSnapshot war = requireWarWith(actor.id(), opponentId);
                WarGoalType goal = parseWarGoal(payload.secondary());
                WarGoalSnapshot selected = goalWithInitialTarget(goal, payload.tertiary(), opponentId);
                wars.chooseDefenderGoal(war.id(), actor.id(), selected);
            }
            case ADD_WAR_TARGET, REMOVE_WAR_TARGET -> {
                WarSnapshot war = requireWarWith(actor.id(), opponentId);
                if (payload.tertiary().isBlank()) throw new IllegalArgumentException("Select an anchor target");
                if (payload.action() == FactionActionPayload.Action.ADD_WAR_TARGET) {
                    wars.addWarGoalTarget(war.id(), actor.id(), payload.tertiary());
                } else {
                    wars.removeWarGoalTarget(war.id(), actor.id(), payload.tertiary());
                }
            }
            case SELECT_WAR_CAMP -> {
                WarSnapshot war = requireWarWith(actor.id(), opponentId);
                wars.selectWarCampWar(player, actor.id(), war.id());
                player.sendSystemMessage(Component.literal("The held War Camp is assigned against "
                        + factions.factionName(opponentId) + "."));
            }
            default -> throw new IllegalArgumentException("Unsupported war action");
        }
    }

    private WarSnapshot requireWarWith(UUID factionId, UUID opponentId) {
        WarSnapshot war = wars.getWarBetweenFactions(factionId, opponentId);
        if (war == null) throw new IllegalStateException("There is no unresolved war with that faction");
        return war;
    }

    private WarGoalSnapshot goalWithInitialTarget(WarGoalType goal, String targetId, UUID opponentId) {
        WarGoalSnapshot selected = WarGoalSnapshot.selected(goal);
        if (goal != WarGoalType.CONQUEST && goal != WarGoalType.PLUNDER) return selected;
        AnchorMapSnapshot anchor = targetId.isBlank() ? null : factions.anchor(targetId);
        if (anchor == null || !anchor.factionId().equals(opponentId)) {
            throw new IllegalArgumentException("Select one of the opposing faction's anchors first");
        }
        return selected.withTargets(Set.of(anchor.id()));
    }

    private static WarGoalType offensiveGoal(String name) {
        WarGoalType goal = parseWarGoal(name);
        if (!goal.requiresWarCamp()) throw new IllegalArgumentException("A declaration requires an offensive goal");
        return goal;
    }

    private static WarGoalType parseWarGoal(String name) {
        try {
            return WarGoalType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid war goal");
        }
    }

    /** Handles a chunk selected through JourneyMap, with all authority enforced server-side. */
    public void handleJourneyMapClaim(ServerPlayer player, JourneyMapClaimPayload payload) {
        CommandSourceStack source = player.createCommandSourceStack();
        TerritoryKey playerChunk = currentChunk(player);
        TerritoryKey target = canonicalKey(
                TerritoryKey.of(payload.dimension(), payload.chunkX(), payload.chunkZ()));
        int radius = TerraFactionsConfig.JOURNEYMAP_CLAIM_RADIUS.get();
        if (!target.dimension().equals(playerChunk.dimension())) {
            fail(source, "JourneyMap claims must be in your current dimension.");
            return;
        }
        if (ToroidalTerritoryCompat.chebyshevDistance(server, target, playerChunk) > radius) {
            fail(source, "That chunk is outside the JourneyMap claim radius of " + radius + " chunks.");
            return;
        }
        try {
            Actor actor = actor(source);
            switch (payload.action()) {
                case CLAIM_CORE -> claim(source, actor, TerritoryType.CORE, target);
                case UNCLAIM -> unclaim(source, actor, target);
                case LIBERATE -> liberate(source, actor, target);
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
        }
        syncClientState(player, true);
    }

    public TerritoryClaim claimAt(TerritoryKey key) {
        return factions.isReady() ? factions.claim(canonicalKey(key)) : null;
    }

    public boolean hasActivePlunderAccess(ServerPlayer player, TerritoryClaim claim) {
        if (claim == null || !wars.isReady()) return false;
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        return identity != null && wars.hasActivePlunderAccess(identity.id(), claim.key());
    }

    private void onFactionMovement(ServerPlayer player) {
        if (!factions.isReady() || player.getServer() == null) {
            return;
        }
        // Refresh twice per second. This keeps movement responsive without rebuilding faction summaries every tick.
        if (player.getServer().getTickCount() % 10 != 0) return;

        syncClientState(player, false);
    }

    private void syncClientState(ServerPlayer player, boolean force) {
        TerritoryClaim claim = claimAt(currentChunk(player));
        TerritoryRadarPayload payload = createHudPayload(player, claim);
        TerritoryRadarPayload previous = radarStates.put(player.getUUID(), payload);
        if (force || !payload.equals(previous)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
        FactionUiPayload uiPayload = createFactionUiPayload(player);
        FactionUiPayload previousUi = factionUiStates.put(player.getUUID(), uiPayload);
        if (force || !uiPayload.equals(previousUi)) {
            PacketDistributor.sendToPlayer(player, uiPayload);
        }
    }

    private FactionUiPayload createFactionUiPayload(ServerPlayer player) {
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        List<FactionUiPayload.FactionEntry> factionEntries = factions.allFactions().stream()
                .filter(faction -> identity == null || !faction.id().equals(identity.id()))
                .map(faction -> new FactionUiPayload.FactionEntry(
                        faction.name(), factions.tag(faction.id()), faction.color(),
                        factions.members(faction.id()).size(),
                        factions.relation(identity == null ? null : identity.id(), faction.id()).ordinal(),
                        factions.declaredRelation(identity == null ? null : identity.id(), faction.id()).ordinal(),
                        factions.declaredRelation(faction.id(), identity == null ? null : identity.id()).ordinal()))
                .toList();
        List<FactionUiPayload.AdminFactionEntry> adminFactionEntries = player.hasPermissions(3)
                ? factions.allFactions().stream().map(faction -> {
                    FactionPower factionPower = factions.power(faction.id());
                    return new FactionUiPayload.AdminFactionEntry(faction.name(), factions.tag(faction.id()),
                            faction.color(), factionPower.current(), factionPower.maximum(),
                            factionPower.specialPower());
                }).sorted(Comparator.comparing(FactionUiPayload.AdminFactionEntry::name,
                        String.CASE_INSENSITIVE_ORDER)).toList()
                : List.of();
        if (identity == null) {
            return new FactionUiPayload("", "", "", 0xAAAAAA, -1,
                    0, 0, 0, 0, 0, 0, 0, 0.0D, TerraFactionsConfig.BASE_POWER.get(),
                    TerraFactionsConfig.POWER_PER_MEMBER.get(), TerraFactionsConfig.CORE_CLAIM_COST.get(),
                    TerraFactionsConfig.BORDER_CLAIM_COST.get(), 0, 0, 0, 0, 0, "", false, false,
                    0, 0, 0, 0,
                    factions.radarEnabled(player.getUUID()), factions.chatMode(player.getUUID()).ordinal(),
                    List.of(), List.of(), factionEntries, List.of(), List.of(), adminFactionEntries);
        }

        FactionSnapshot faction = factions.snapshot(identity.id());
        FactionPower power = factions.power(identity.id());
        Set<UUID> memberIds = factions.members(identity.id());
        List<FactionUiPayload.MemberEntry> members = memberIds.stream()
                .map(memberId -> {
                    ServerPlayer onlinePlayer = player.getServer().getPlayerList().getPlayer(memberId);
                    String memberName = playerName(player.getServer(), memberId);
                    FactionIdentity member = factions.factionForPlayer(memberId);
                    return new FactionUiPayload.MemberEntry(memberName,
                            member == null ? FactionRank.MEMBER.ordinal() : member.rank().ordinal(),
                            onlinePlayer != null, power.deathLossByPlayer().getOrDefault(memberId, 0));
                })
                .sorted(Comparator.comparingInt(FactionUiPayload.MemberEntry::rankOrdinal)
                        .thenComparing(FactionUiPayload.MemberEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<FactionUiPayload.LossEntry> losses = power.deathLossByPlayer().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FactionUiPayload.LossEntry(
                        playerName(player.getServer(), entry.getKey()), entry.getValue(),
                        memberIds.contains(entry.getKey())))
                .sorted(Comparator.comparingInt(FactionUiPayload.LossEntry::amount).reversed()
                        .thenComparing(FactionUiPayload.LossEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int capitalClaims = (int) faction.claims().stream().filter(claim -> claim.type() == TerritoryType.CAPITAL).count();
        int coreClaims = (int) faction.claims().stream().filter(claim -> claim.type() == TerritoryType.CORE).count();
        int borderClaims = (int) faction.claims().stream().filter(claim -> claim.type() == TerritoryType.BORDER).count();
        int projectedBorderClaims = (int) allTerritory().stream()
                .filter(claim -> claim.factionId().equals(identity.id()) && claim.type() == TerritoryType.BORDER
                        && claim.projected())
                .count();
        int manualClaimUsage = (capitalClaims + coreClaims) * TerraFactionsConfig.CORE_CLAIM_COST.get()
                + (borderClaims - projectedBorderClaims) * TerraFactionsConfig.BORDER_CLAIM_COST.get();
        int projectedClaimUsage = Math.max(0, power.claimUsage() - manualClaimUsage);
        String capital = faction.capital() == null ? "" : faction.capital().x() + ", " + faction.capital().z()
                + " (" + faction.capital().dimension() + ")";
        List<FactionUiPayload.WarEntry> warEntries = createWarEntries(identity.id());
        List<FactionUiPayload.WarTargetEntry> warTargets = identity.rank().isLeadership()
                ? createWarTargets(identity.id()) : List.of();
        return new FactionUiPayload(faction.name(), faction.description(), factions.tag(identity.id()),
                faction.color(), identity.rank().ordinal(), power.current(), power.maximum(), power.claimUsage(),
                power.deathLoss(), power.specialPower(), power.temporaryPower(), power.suppressedPower(),
                power.suppressionPercent(), TerraFactionsConfig.BASE_POWER.get(),
                TerraFactionsConfig.POWER_PER_MEMBER.get(),
                TerraFactionsConfig.CORE_CLAIM_COST.get(), TerraFactionsConfig.BORDER_CLAIM_COST.get(),
                capitalClaims, coreClaims, borderClaims, projectedBorderClaims, projectedClaimUsage, capital,
                isVulnerable(identity.id(), TerritoryType.CORE),
                isVulnerable(identity.id(), TerritoryType.BORDER),
                factions.effectiveProtectionMask(identity.id(), TerritoryType.CORE),
                factions.effectiveProtectionMask(identity.id(), TerritoryType.BORDER),
                factions.configurableProtectionMask(TerritoryType.CORE),
                factions.configurableProtectionMask(TerritoryType.BORDER),
                factions.radarEnabled(player.getUUID()), factions.chatMode(player.getUUID()).ordinal(),
                members, losses, factionEntries, warEntries, warTargets, adminFactionEntries);
    }

    private List<FactionUiPayload.WarEntry> createWarEntries(UUID factionId) {
        return wars.getWarsForFaction(factionId).stream()
                .filter(war -> war.state() != WarState.ENDED)
                .map(war -> {
                    boolean attacker = war.attackerFactionId().equals(factionId);
                    UUID opponentId = attacker ? war.defenderFactionId() : war.attackerFactionId();
                    FactionSnapshot opponent = factions.snapshot(opponentId);
                    WarSideSnapshot own = war.side(factionId);
                    WarSideSnapshot enemy = war.side(opponentId);
                    WarCampSnapshot camp = own.warCampId() == null ? null : wars.getWarCamp(own.warCampId());
                    int ownGoal = own.warGoal() == null ? -1 : own.warGoal().type().ordinal();
                    int enemyGoal = enemy.warGoal() == null ? -1 : enemy.warGoal().type().ordinal();
                    int progress = own.warGoal() == null ? 0 : own.warGoal().progress();
                    int required = own.warGoal() == null ? 0 : own.warGoal().requiredObjectiveValue();
                    List<String> targets = own.warGoal() == null ? List.of()
                            : own.warGoal().targetAnchorIds().stream().sorted().limit(4096).toList();
                    return new FactionUiPayload.WarEntry(war.id().toString(),
                            opponent == null ? opponentId.toString() : opponent.name(),
                            opponent == null ? 0xAAAAAA : opponent.color(), war.state().ordinal(), attacker,
                            ownGoal, enemyGoal, progress, required, own.goalCompleted(), own.goalFailed(),
                            camp == null ? -1 : camp.state().ordinal(),
                            wars.getOccupations(war.id(), factionId).size(), war.preparationEndsAt(), targets);
                }).toList();
    }

    private List<FactionUiPayload.WarTargetEntry> createWarTargets(UUID factionId) {
        long now = server == null ? 0L : server.overworld().getGameTime();
        Set<String> occupiedAnchors = wars.allOccupations().stream()
                .filter(occupation -> occupation.occupyingFactionId().equals(factionId))
                .map(AnchorOccupationSnapshot::anchorId).collect(java.util.stream.Collectors.toSet());
        Set<String> breachedAnchors = wars.allPlunderBreaches().stream()
                .filter(breach -> breach.breachingFactionId().equals(factionId) && breach.active(now))
                .map(dev.terrafactions.war.PlunderBreachSnapshot::anchorId)
                .collect(java.util.stream.Collectors.toSet());
        return factions.allAnchors().stream()
                .filter(anchor -> !anchor.factionId().equals(factionId))
                .map(anchor -> {
                    return new FactionUiPayload.WarTargetEntry(anchor.id(),
                            java.util.Objects.requireNonNullElse(factions.factionName(anchor.factionId()), "Unknown"),
                            anchor.dimension(),
                            anchor.x(), anchor.y(), anchor.z(), anchor.tier().ordinal(),
                            anchor.allocatedPower(), occupiedAnchors.contains(anchor.id()),
                            breachedAnchors.contains(anchor.id()));
                }).sorted(Comparator.comparing(FactionUiPayload.WarTargetEntry::factionName,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(FactionUiPayload.WarTargetEntry::anchorId))
                .limit(4096)
                .toList();
    }

    private static String playerName(MinecraftServer server, UUID playerId) {
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
        return onlinePlayer != null
                ? onlinePlayer.getGameProfile().getName()
                : server.getProfileCache().get(playerId)
                        .map(profile -> profile.getName()).orElse(playerId.toString());
    }

    private TerritoryRadarPayload createHudPayload(ServerPlayer player, TerritoryClaim claim) {
        FactionIdentity viewer = factions.factionForPlayer(player.getUUID());
        String territoryName = "";
        String territoryFaction = "";
        int relationColor = 0xAAAAAA;
        boolean vulnerable = false;
        boolean isolated = false;
        FactionPower ownPower = viewer == null ? null : factions.power(viewer.id());
        boolean borderVulnerable = viewer != null && isVulnerable(viewer.id(), TerritoryType.BORDER);
        boolean coreVulnerable = viewer != null && isVulnerable(viewer.id(), TerritoryType.CORE);

        if (factions.radarEnabled(player.getUUID())) {
            if (claim == null) {
                territoryName = "Wilderness";
                territoryFaction = "Unclaimed territory";
            } else {
                FactionDisplay owner = factions.factionDisplay(claim.factionId());
                territoryName = switch (claim.type()) {
                    case CAPITAL -> "Capital";
                    case CORE -> "Core";
                    case BORDER -> "Border";
                };
                territoryFaction = owner == null ? "Unknown Faction" : owner.name();
                relationColor = relationColor(viewer, claim.factionId());
                AnchorVulnerabilityState state = vulnerabilityState(claim);
                vulnerable = state == AnchorVulnerabilityState.VULNERABLE;
                isolated = state == AnchorVulnerabilityState.GRACE_PERIOD;
            }
        }

        WarSnapshot hudWar = viewer == null ? null : preferredHudWar(viewer.id());
        WarSideSnapshot ownWarSide = hudWar == null ? null : hudWar.side(viewer.id());
        UUID warOpponentId = hudWar == null ? null : hudWar.attackerFactionId().equals(viewer.id())
                ? hudWar.defenderFactionId() : hudWar.attackerFactionId();
        WarSideSnapshot enemyWarSide = hudWar == null ? null : hudWar.side(warOpponentId);
        WarCampSnapshot warCamp = ownWarSide == null || ownWarSide.warCampId() == null ? null
                : wars.getWarCamp(ownWarSide.warCampId());
        return new TerritoryRadarPayload(territoryName, territoryFaction, relationColor, vulnerable, isolated,
                ownPower != null,
                viewer == null ? -1 : viewer.rank().ordinal(),
                ownPower == null ? 0 : ownPower.current(),
                ownPower == null ? 0 : ownPower.maximum(),
                borderVulnerable, coreVulnerable,
                warOpponentId == null ? "" : factions.factionName(warOpponentId),
                hudWar == null ? -1 : hudWar.state().ordinal(),
                ownWarSide == null || ownWarSide.warGoal() == null ? -1 : ownWarSide.warGoal().type().ordinal(),
                enemyWarSide == null || enemyWarSide.warGoal() == null ? -1 : enemyWarSide.warGoal().type().ordinal(),
                ownWarSide == null || ownWarSide.warGoal() == null ? 0 : ownWarSide.warGoal().progress(),
                ownWarSide == null || ownWarSide.warGoal() == null ? 0
                        : ownWarSide.warGoal().requiredObjectiveValue(),
                ownWarSide != null && ownWarSide.goalCompleted(), ownWarSide != null && ownWarSide.goalFailed(),
                warCamp == null ? -1 : warCamp.state().ordinal());
    }

    private WarSnapshot preferredHudWar(UUID factionId) {
        return wars.getWarsForFaction(factionId).stream()
                .filter(war -> war.state() != WarState.ENDED)
                .min(Comparator.comparingInt(war -> switch (war.state()) {
                    case ACTIVE -> 0;
                    case PREPARING -> 1;
                    case RESOLVING -> 2;
                    case ENDED -> 3;
                })).orElse(null);
    }

    private int relationColor(FactionIdentity viewer, UUID territoryFactionId) {
        if (viewer != null && viewer.id().equals(territoryFactionId)) return 0x5555FF;
        FactionRelation relation = factions.relation(viewer == null ? null : viewer.id(), territoryFactionId);
        return switch (relation) {
            case ENEMY -> 0xFF5555;
            case ALLIED -> 0x55FF55;
            case NEUTRAL -> 0xAAAAAA;
        };
    }

    public String factionTag(UUID factionId) {
        if (!factions.isReady()) {
            return null;
        }
        return factions.tag(factionId);
    }

    private int claim(CommandSourceStack source, TerritoryType type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Actor actor = actor(source);
        return claim(source, actor, type, currentChunk(actor.player()));
    }

    private int claim(CommandSourceStack source, Actor actor, TerritoryType type, TerritoryKey key) {
        key = canonicalKey(key);
        if (type == TerritoryType.BORDER) {
            return fail(source, "Border territory can only be projected by faction anchors.");
        }
        TerritoryClaim existing = claimAt(key);
        if (existing != null && !existing.factionId().equals(actor.factionId())) {
            return captureEnemyClaim(source, actor, type, key);
        }
        if (existing != null && existing.type() == type) {
            return fail(source, "That chunk is already " + type.name().toLowerCase() + " territory.");
        }
        if (existing != null && existing.type() == TerritoryType.CAPITAL) {
            return fail(source, "Move your capital before changing its claim type.");
        }

        TerritoryType resultType = hasClaims(actor.factionId()) ? type : TerritoryType.CAPITAL;

        Set<TerritoryKey> territory = territory(actor.factionId(), key.dimension());
        if (existing == null && TerraFactionsConfig.REQUIRE_SIDE_CONNECTIVITY.get()
                && !territory.isEmpty() && !TerritoryRules.touches(territory, key, this::canonicalKey)) {
            return fail(source, "New territory must share a side with your faction's existing territory.");
        }
        if (!hasCapacity(actor.factionId(), resultType, existing)) {
            return fail(source, "Your faction does not have enough available power for that claim.");
        }

        replace(existing, actor.factionId(), resultType, key);
        ensureCapital(actor.factionId());
        return success(source, "Claimed " + key.x() + ", " + key.z() + " as "
                + resultType.name().toLowerCase() + ".");
    }

    private int unclaim(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Actor actor = actor(source);
        return unclaim(source, actor, currentChunk(actor.player()));
    }

    private int unclaim(CommandSourceStack source, Actor actor, TerritoryKey key) {
        key = canonicalKey(key);
        TerritoryClaim existing = claimAt(key);
        if (existing == null || !existing.factionId().equals(actor.factionId())) {
            return fail(source, "Your faction does not own this chunk.");
        }
        if (existing.projected()) {
            return fail(source, "Anchor-projected borders are managed through their anchor allocation.");
        }
        if (existing.type() == TerritoryType.CAPITAL) {
            return fail(source, "Move your capital before unclaiming this chunk.");
        }
        Set<TerritoryKey> territory = territory(actor.factionId(), key.dimension());
        if (TerraFactionsConfig.REQUIRE_SIDE_CONNECTIVITY.get()
                && !TerritoryRules.remainsConnected(territory, key, this::canonicalKey)) {
            return fail(source, "Unclaiming this chunk would split your faction's territory.");
        }
        remove(existing);
        ensureCapital(actor.factionId());
        return success(source, "Unclaimed " + key.x() + ", " + key.z() + ".");
    }

    private int unclaimAll(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Actor actor = actor(source);
        List<TerritoryClaim> removable = allTerritory().stream()
                .filter(claim -> claim.factionId().equals(actor.factionId()) && !claim.projected())
                .toList();
        removable.forEach(this::remove);
        int removed = removable.size();
        factions.clearCapital(actor.factionId());
        dirtyNetworks.add(actor.factionId());
        return success(source, "Removed all " + removed + " core claims. Anchor borders were preserved.");
    }

    private int bulkClaim(CommandSourceStack source, TerritoryType requestedType, int radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (requestedType == TerritoryType.BORDER) {
            return fail(source, "Border territory can only be projected by faction anchors.");
        }
        Actor actor = actor(source);
        TerritoryKey center = currentChunk(actor.player());
        Set<TerritoryKey> square = TerritoryRules.centeredSquare(center, radius, this::canonicalKey);
        Set<TerritoryKey> ownedInDimension = territory(actor.factionId(), center.dimension());
        List<TerritoryKey> additions = square.stream().filter(key -> !ownedInDimension.contains(key)).toList();
        for (TerritoryKey key : additions) {
            TerritoryClaim existing = claimAt(key);
            if (existing != null) {
                return fail(source, "Bulk claim blocked by another faction at " + key.x() + ", " + key.z() + ".");
            }
        }
        if (additions.isEmpty()) {
            return fail(source, "Your faction already owns every chunk in that area.");
        }
        Set<TerritoryKey> resulting = new HashSet<>(ownedInDimension);
        resulting.addAll(additions);
        if (TerraFactionsConfig.REQUIRE_SIDE_CONNECTIVITY.get()
                && !TerritoryRules.isConnected(resulting, this::canonicalKey)) {
            return fail(source, "The bulk claim must connect to your faction's existing territory.");
        }

        boolean firstClaim = !hasClaims(actor.factionId());
        long addedCost = (long) additions.size() * requestedType.cost();
        if (firstClaim) {
            addedCost += TerritoryType.CAPITAL.cost() - requestedType.cost();
        }
        FactionPower power = factions.power(actor.factionId());
        long grossPower = power == null ? Long.MIN_VALUE : (long) power.current() + power.claimUsage();
        if (power == null || corePowerUsageTenths(actor.factionId()) + addedCost * 10L > grossPower * 10L) {
            return fail(source, "Your faction does not have enough available power for " + additions.size() + " claims.");
        }

        List<TerritoryKey> added = new java.util.ArrayList<>();
        try {
            if (firstClaim) {
                add(actor.factionId(), TerritoryType.CAPITAL, center);
                added.add(center);
            }
            for (TerritoryKey key : additions) {
                if (firstClaim && key.equals(center)) continue;
                add(actor.factionId(), requestedType, key);
                added.add(key);
            }
        } catch (RuntimeException exception) {
            added.forEach(factions::removeClaim);
            throw exception;
        }
        ensureCapital(actor.factionId());
        return success(source, "Claimed " + added.size() + " chunks as " + requestedType.name().toLowerCase()
                + " (radius " + radius + ", " + (radius * 2 + 1) + "x" + (radius * 2 + 1) + ").");
    }

    private static int unsupportedBulk(CommandSourceStack source) {
        return fail(source, "Bulk unclaim is not supported; remove claims individually or use /factions claim remove all.");
    }

    private static int unsupportedAutoClaim(CommandSourceStack source) {
        return fail(source, "Autoclaim is disabled. Use /factions claim core or /factions claim border.");
    }

    private int captureEnemyClaim(CommandSourceStack source, Actor actor, TerritoryType resultType, TerritoryKey key) {
        key = canonicalKey(key);
        TerritoryClaim target = claimAt(key);
        if (target == null || target.factionId().equals(actor.factionId())) {
            return fail(source, "Stand inside vulnerable enemy territory to claim it.");
        }
        if (!factions.isEnemy(target.factionId(), actor.factionId())) {
            return fail(source, "Only an enemy faction's territory can be attacked.");
        }
        if (!isVulnerable(target)) {
            return fail(source, "This " + target.type().name().toLowerCase() + " claim is not vulnerable.");
        }
        if (!TerritoryRules.touches(territory(actor.factionId(), key.dimension()), key, this::canonicalKey)) {
            return fail(source, "A captured claim must share a side with your faction's territory.");
        }
        if (!hasCapacity(actor.factionId(), resultType, null)) {
            return fail(source, "Your faction does not have enough power capacity for that claim type.");
        }
        UUID defenderId = target.factionId();
        replace(target, actor.factionId(), resultType, key);
        ensureCapital(defenderId);
        ensureCapital(actor.factionId());
        return success(source, "Overclaimed the chunk as " + resultType.name().toLowerCase() + " territory.");
    }

    private int liberate(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Actor actor = actor(source);
        return liberate(source, actor, currentChunk(actor.player()));
    }

    private int liberate(CommandSourceStack source, Actor actor, TerritoryKey key) {
        key = canonicalKey(key);
        TerritoryClaim target = claimAt(key);
        if (target == null || target.factionId().equals(actor.factionId())) {
            return fail(source, "Stand inside vulnerable enemy territory to liberate it.");
        }
        if (!factions.isEnemy(target.factionId(), actor.factionId())) {
            return fail(source, "Only an enemy faction's territory can be attacked.");
        }
        if (!isVulnerable(target)) {
            return fail(source, "This " + target.type().name().toLowerCase() + " claim is not vulnerable.");
        }
        remove(target);
        ensureCapital(target.factionId());
        return success(source, "Liberated " + key.x() + ", " + key.z() + "; it is now wilderness.");
    }

    private int setCapital(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Actor actor = actor(source);
        if (!actor.rank().isLeadership()) {
            return fail(source, "Only faction leadership can move the capital.");
        }
        TerritoryKey key = currentChunk(actor.player());
        TerritoryClaim claim = claimAt(key);
        if (claim == null || !claim.factionId().equals(actor.factionId())) {
            return fail(source, "The capital must be placed in territory your faction owns.");
        }
        if (claim.type() == TerritoryType.BORDER) {
            return fail(source, "The capital can only be moved to a core claim.");
        }
        if (claim.type() == TerritoryType.CAPITAL) {
            return fail(source, "This chunk is already your faction capital.");
        }
        boolean anchored = factions.allAnchors().stream().anyMatch(anchor ->
                anchor.factionId().equals(actor.factionId()) && anchorKey(anchor).equals(key));
        if (!anchored) return fail(source, "The capital can only be moved to a chunk containing your faction anchor.");
        TerritoryKey oldKey = factions.capital(actor.factionId());
        TerritoryClaim oldClaim = oldKey == null ? null : claimAt(oldKey);
        if (oldClaim != null && oldClaim.factionId().equals(actor.factionId())) {
            replace(oldClaim, actor.factionId(), TerritoryType.CORE, oldKey);
        }
        replace(claim, actor.factionId(), TerritoryType.CAPITAL, key);
        factions.setCapital(actor.factionId(), key);
        return success(source, "Moved your faction capital to " + key.x() + ", " + key.z() + ".");
    }

    private int setOverlay(CommandSourceStack source, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!ModList.get().isLoaded("journeymap")
                || !TerraFactionsJourneyMapPlugin.setOverlayEnabled(player, enabled)) {
            return fail(source, "The JourneyMap overlay service is not available.");
        }
        source.sendSuccess(() -> Component.literal("Faction overlays " + (enabled ? "enabled." : "disabled.")), false);
        return 1;
    }

    public boolean isVulnerable(TerritoryClaim claim) {
        return vulnerabilityState(claim) == AnchorVulnerabilityState.VULNERABLE;
    }

    public AnchorVulnerabilityState vulnerabilityState(TerritoryClaim claim) {
        if (claim.projected()) {
            List<AnchorMapSnapshot> covering = factions.allAnchors().stream()
                    .filter(anchor -> anchor.factionId().equals(claim.factionId()))
                    .filter(anchor -> anchor.dimension().equals(claim.key().dimension()))
                    .filter(anchor -> covers(anchor, claim.key()))
                    .toList();
            if (covering.stream().anyMatch(anchor ->
                    anchor.vulnerabilityState() == AnchorVulnerabilityState.PROTECTED)) {
                return AnchorVulnerabilityState.PROTECTED;
            }
            if (covering.stream().anyMatch(anchor ->
                    anchor.vulnerabilityState() == AnchorVulnerabilityState.GRACE_PERIOD)) {
                return AnchorVulnerabilityState.GRACE_PERIOD;
            }
            if (!covering.isEmpty()) return AnchorVulnerabilityState.VULNERABLE;
        }
        return isVulnerable(claim.factionId(), claim.type())
                ? AnchorVulnerabilityState.VULNERABLE : AnchorVulnerabilityState.PROTECTED;
    }

    private boolean covers(AnchorMapSnapshot anchor, TerritoryKey key) {
        TerritoryKey center = anchorKey(anchor);
        return distanceSquared(center, key) <= (long) anchor.projectedRadius() * anchor.projectedRadius();
    }

    public boolean isVulnerable(UUID factionId, TerritoryType type) {
        if (type == TerritoryType.BORDER) {
            List<AnchorMapSnapshot> anchors = factions.allAnchors().stream()
                    .filter(anchor -> anchor.factionId().equals(factionId)).toList();
            if (!anchors.isEmpty()) {
                return anchors.stream().anyMatch(anchor ->
                        anchor.vulnerabilityState() == AnchorVulnerabilityState.VULNERABLE);
            }
        }
        FactionPower power = factions.power(factionId);
        if (power == null) {
            return true;
        }
        long grossPowerTenths = ((long) power.current() + power.claimUsage()) * 10L;
        if (type != TerritoryType.BORDER) {
            return grossPowerTenths - corePowerUsageTenths(factionId) <= 0;
        }
        if (power.current() <= 0) {
            return true;
        }
        if (power.maximum() <= 0) {
            return false;
        }
        return (double) power.current() / power.maximum() <= TerraFactionsConfig.BORDER_VULNERABILITY_PERCENT.get();
    }

    private boolean hasCapacity(UUID factionId, TerritoryType addedType, TerritoryClaim replacedOwnClaim) {
        long required = corePowerUsageTenths(factionId);
        if (replacedOwnClaim != null && !replacedOwnClaim.projected()) {
            required -= replacedOwnClaim.powerCostTenths();
        }
        required += addedType.cost() * 10L;
        FactionPower power = factions.power(factionId);
        long grossPower = power == null ? Long.MIN_VALUE : (long) power.current() + power.claimUsage();
        return power != null && required <= grossPower * 10L;
    }

    private boolean hasClaims(UUID factionId) {
        return allTerritory().stream()
                .anyMatch(claim -> claim.factionId().equals(factionId) && !claim.projected());
    }

    private Set<TerritoryKey> territory(UUID factionId, String dimension) {
        Set<TerritoryKey> result = new HashSet<>();
        for (TerritoryClaim claim : allTerritory()) {
            if (claim.factionId().equals(factionId) && claim.key().dimension().equals(dimension)) {
                result.add(claim.key());
            }
        }
        return result;
    }

    private Collection<TerritoryClaim> allTerritory() {
        return factions.allClaims();
    }

    private void reconcileCapitals() {
        for (FactionSnapshot faction : factions.allFactions()) {
            ensureCapital(faction.id());
        }
    }

    private void ensureCapital(UUID factionId) {
        TerritoryKey current = factions.capital(factionId);
        List<TerritoryClaim> owned = allTerritory().stream()
                .filter(claim -> claim.factionId().equals(factionId) && !claim.projected())
                .sorted(Comparator.comparing((TerritoryClaim claim) -> claim.key().dimension())
                        .thenComparingInt(claim -> claim.key().x())
                        .thenComparingInt(claim -> claim.key().z()))
                .toList();
        if (owned.isEmpty()) {
            factions.clearCapital(factionId);
            return;
        }

        TerritoryClaim chosen = current == null ? null : owned.stream()
                .filter(claim -> claim.key().equals(current) && claim.type() != TerritoryType.BORDER)
                .findFirst().orElse(null);
        if (chosen == null) chosen = owned.stream().filter(claim -> claim.type() == TerritoryType.CAPITAL)
                .findFirst().orElse(null);
        if (chosen == null) chosen = owned.stream().filter(claim -> claim.type() == TerritoryType.CORE)
                .findFirst().orElse(owned.getFirst());

        TerritoryClaim capital = chosen;
        for (TerritoryClaim claim : owned) {
            TerritoryType desired = claim.key().equals(capital.key()) ? TerritoryType.CAPITAL
                    : claim.type() == TerritoryType.CAPITAL ? TerritoryType.CORE : claim.type();
            if (claim.type() != desired) replace(claim, factionId, desired, claim.key());
        }
        factions.setCapital(factionId, capital.key());
    }

    private void replace(TerritoryClaim oldClaim, UUID newOwner, TerritoryType newType, TerritoryKey key) {
        key = canonicalKey(key);
        if (oldClaim != null) {
            remove(oldClaim);
        }
        try {
            add(newOwner, newType, key);
        } catch (RuntimeException exception) {
            if (oldClaim != null) {
                try {
                    if (oldClaim.projected()) {
                        factions.putProjectedClaim(oldClaim.key(), oldClaim.factionId());
                    } else {
                        factions.putClaim(oldClaim.key(), oldClaim.factionId(), oldClaim.type());
                    }
                } catch (RuntimeException rollbackException) {
                    exception.addSuppressed(rollbackException);
                    TerraFactions.LOGGER.error("Could not roll back failed territory transfer at {} {}, {}",
                            key.dimension(), key.x(), key.z(), rollbackException);
                }
            }
            throw exception;
        }
    }

    private void add(UUID owner, TerritoryType type, TerritoryKey key) {
        factions.putClaim(canonicalKey(key), owner, type);
        corePowerUsageCache.remove(owner);
        dirtyNetworks.add(owner);
    }

    private void remove(TerritoryClaim claim) {
        factions.removeClaim(claim.key());
        if (!claim.projected()) corePowerUsageCache.remove(claim.factionId());
        dirtyNetworks.add(claim.factionId());
    }

    private Actor actor(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        if (identity == null) {
            throw NOT_IN_FACTION.create();
        }
        if (identity.rank() == FactionRank.GUEST) {
            throw GUEST_CANNOT_MANAGE.create();
        }
        return new Actor(player, identity.id(), identity.rank());
    }

    private TerritoryKey currentChunk(ServerPlayer player) {
        ChunkPos chunk = player.chunkPosition();
        return canonicalKey(TerritoryKey.of(player.level().dimension().location(), chunk.x, chunk.z));
    }

    private TerritoryKey canonicalKey(TerritoryKey key) {
        return ToroidalTerritoryCompat.fold(server, key);
    }

    private long distanceSquared(TerritoryKey first, TerritoryKey second) {
        return ToroidalTerritoryCompat.distanceSquared(server, first, second);
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private record Actor(ServerPlayer player, UUID factionId, FactionRank rank) {
    }

}
