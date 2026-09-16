package dev.terrafactions.war;

import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorNetworkRules;
import dev.terrafactions.compat.toroidal.ToroidalTerritoryCompat;
import dev.terrafactions.factions.NativeFactionService;
import dev.terrafactions.factions.FactionRelation;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.territory.TerritoryClaim;
import dev.terrafactions.territory.TerritoryKey;
import dev.terrafactions.territory.TerraFactionsConfig;
import dev.terrafactions.war.event.WarDeclaredEvent;
import dev.terrafactions.war.event.WarEndedEvent;
import dev.terrafactions.war.event.WarGoalCompletedEvent;
import dev.terrafactions.war.event.WarGoalSelectedEvent;
import dev.terrafactions.war.event.WarStartedEvent;
import dev.terrafactions.war.event.WarCampActivatedEvent;
import dev.terrafactions.war.event.WarCampDestroyedEvent;
import dev.terrafactions.war.event.WarCampPlacedEvent;
import dev.terrafactions.war.event.AnchorOccupiedEvent;
import dev.terrafactions.war.event.AnchorReleasedEvent;
import dev.terrafactions.war.event.AnchorAnnexedEvent;
import dev.terrafactions.war.event.IntegrationPowerAppliedEvent;
import dev.terrafactions.war.event.PlunderBreachEndedEvent;
import dev.terrafactions.war.event.PlunderBreachStartedEvent;
import dev.terrafactions.war.event.PowerSuppressionAppliedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Central, server-authoritative owner of formal war state and lifecycle transitions. */
public final class WarManager {
    private final NativeFactionService factions;
    private WarSavedData data;
    private MinecraftServer server;

    public WarManager(NativeFactionService factions) {
        this.factions = Objects.requireNonNull(factions);
    }

    public void initialize(MinecraftServer minecraftServer) {
        server = Objects.requireNonNull(minecraftServer);
        data = server.overworld().getDataStorage().computeIfAbsent(
                WarSavedData.factory(), WarSavedData.DATA_NAME);
        factions.setTemporaryPowerProvider(this::currentIntegrationPower);
        factions.setPowerSuppressionProvider(this::currentPunitiveSuppression);
        reconcileInvasionNetworks();
        for (WarSnapshot war : allWars()) {
            if (war.state() == WarState.RESOLVING) finishWarResolution(war.id(), currentTime());
        }
    }

    public void stop() {
        factions.setTemporaryPowerProvider(null);
        factions.setPowerSuppressionProvider(null);
        data = null;
        server = null;
    }

    public boolean isReady() {
        return data != null;
    }

    public void tick(long now) {
        if (!isReady()) return;
        pruneExpiredPowerModifiers(now);
        pruneExpiredPlunderBreaches(now);
        for (WarSnapshot war : allWars()) {
            if (war.state() == WarState.PREPARING && now >= war.preparationEndsAt()) {
                startWar(war.id(), now);
            } else if (war.state() == WarState.ACTIVE) {
                enforceWarDeadlines(war.id(), now);
            }
        }
        for (WarCampSnapshot camp : allWarCamps()) {
            if (camp.state() == WarCampState.ESTABLISHING && now >= camp.activationTime()) {
                activateWarCamp(camp.id());
            }
        }
    }

    public WarSnapshot declareWar(UUID attackerFactionId, UUID defenderFactionId, WarGoalType goalType) {
        return declareWar(attackerFactionId, defenderFactionId, WarGoalSnapshot.selected(goalType));
    }

    public WarSnapshot declareWar(UUID attackerFactionId, UUID defenderFactionId, WarGoalSnapshot attackerGoal) {
        requireFaction(attackerFactionId);
        requireFaction(defenderFactionId);
        if (attackerFactionId.equals(defenderFactionId)) {
            throw new IllegalArgumentException("A faction cannot declare war on itself");
        }
        attackerGoal = prepareSelectedGoal(Objects.requireNonNull(attackerGoal));
        if (!attackerGoal.requiresWarCamp()) {
            throw new IllegalArgumentException("A declaring faction must choose an offensive war goal");
        }
        if (getWarBetweenFactions(attackerFactionId, defenderFactionId) != null) {
            throw new IllegalStateException("Those factions already have an unresolved war");
        }
        if (!factions.hasAnchor(attackerFactionId)) {
            throw new IllegalStateException("Your faction must establish an anchor before declaring war");
        }
        if (!factions.hasAnchor(defenderFactionId)) {
            throw new IllegalStateException("That faction has no anchor and cannot be declared on");
        }
        if (server == null || factions.members(defenderFactionId).stream()
                .noneMatch(memberId -> server.getPlayerList().getPlayer(memberId) != null)) {
            throw new IllegalStateException("At least one member of the defending faction must be online");
        }

        long now = currentTime();
        long preparationEndsAt = saturatedAdd(now, TerraFactionsConfig.WAR_PREPARATION_DURATION_TICKS.get());
        UUID id = UUID.randomUUID();
        WarSnapshot war = new WarSnapshot(id, attackerFactionId, defenderFactionId, WarState.PREPARING,
                new WarSideSnapshot(attackerFactionId, attackerGoal, null, false, false),
                new WarSideSnapshot(defenderFactionId, null, null, false, false),
                now, preparationEndsAt, 0L, 0L);
        factions.setRelation(attackerFactionId, defenderFactionId, FactionRelation.ENEMY);
        put(war);
        NeoForge.EVENT_BUS.post(new WarDeclaredEvent(war));
        NeoForge.EVENT_BUS.post(new WarGoalSelectedEvent(war, attackerFactionId, attackerGoal.type()));
        return war;
    }

    public WarSnapshot chooseDefenderGoal(UUID warId, UUID defenderFactionId, WarGoalType goalType) {
        return chooseDefenderGoal(warId, defenderFactionId, WarGoalSnapshot.selected(goalType));
    }

    public WarSnapshot chooseDefenderGoal(UUID warId, UUID defenderFactionId, WarGoalSnapshot goal) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.PREPARING) {
            throw new IllegalStateException("The defender can only choose a goal during preparation");
        }
        if (!war.defenderFactionId().equals(defenderFactionId)) {
            throw new IllegalArgumentException("Only the defending faction can choose its war goal");
        }
        goal = prepareSelectedGoal(Objects.requireNonNull(goal));
        WarSnapshot updated = war.withDefender(war.defender().withGoal(goal));
        put(updated);
        NeoForge.EVENT_BUS.post(new WarGoalSelectedEvent(updated, defenderFactionId, goal.type()));
        return updated;
    }

    public WarSnapshot startWar(UUID warId, long now) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.PREPARING) {
            throw new IllegalStateException("Only a preparing war can become active");
        }
        boolean defaultedDefense = war.defender().warGoal() == null;
        WarSnapshot ready = WarRules.withDefaultDefenderGoal(war);
        if (defaultedDefense) {
            put(ready);
            NeoForge.EVENT_BUS.post(new WarGoalSelectedEvent(
                    ready, ready.defenderFactionId(), WarGoalType.DEFENSE));
        }
        ready = prepareWarGoals(ready);
        WarSnapshot active = ready.withState(WarState.ACTIVE, now, 0L);
        put(active);
        NeoForge.EVENT_BUS.post(new WarStartedEvent(active));
        if (!WarRules.hasViableOffensiveGoal(active)) return resolveWar(active.id(), now);
        return active;
    }

    public WarSnapshot completeGoal(UUID warId, UUID factionId, String result) {
        WarSnapshot war = requireActiveWar(warId);
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.goalCompleted() || side.goalFailed()) return war;
        WarSnapshot updated = replaceSide(war, side.complete(result));
        put(updated);
        NeoForge.EVENT_BUS.post(new WarGoalCompletedEvent(updated, factionId, side.warGoal().type()));
        return updated;
    }

    public WarSnapshot addConquestTarget(UUID warId, UUID factionId, String anchorId) {
        return addWarGoalTarget(warId, factionId, anchorId);
    }

    public WarSnapshot removeConquestTarget(UUID warId, UUID factionId, String anchorId) {
        return removeWarGoalTarget(warId, factionId, anchorId);
    }

    public WarSnapshot addWarGoalTarget(UUID warId, UUID factionId, String anchorId) {
        return changeWarGoalTarget(warId, factionId, anchorId, true);
    }

    public WarSnapshot removeWarGoalTarget(UUID warId, UUID factionId, String anchorId) {
        return changeWarGoalTarget(warId, factionId, anchorId, false);
    }

    public WarCampSnapshot placeWarCamp(UUID ownerFactionId, String dimension, BlockPos position) {
        List<WarSnapshot> eligible = allWars().stream()
                .filter(war -> war.state() == WarState.ACTIVE)
                .filter(war -> eligibleForNewCamp(war, ownerFactionId)).toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("Your faction has no active offensive war that can receive a camp");
        }
        if (eligible.size() > 1) {
            throw new IllegalStateException("Your faction has multiple eligible wars; explicit camp selection is required");
        }
        return placeWarCamp(eligible.getFirst().id(), ownerFactionId, dimension, position);
    }

    /** Assigns an eligible war to the War Camp the player is currently holding. */
    public void selectWarCampWar(ServerPlayer player, UUID factionId, UUID warId) {
        net.minecraft.world.item.ItemStack heldCamp = WarCampItemAssignment.heldWarCamp(player);
        if (heldCamp.isEmpty()) {
            throw new IllegalStateException("You must hold a War Camp in your main hand or offhand");
        }
        WarSnapshot war = requireActiveWar(warId);
        if (!eligibleForNewCamp(war, factionId)) {
            throw new IllegalStateException("That war cannot receive a new War Camp from your faction");
        }
        UUID opponentId = war.attackerFactionId().equals(factionId)
                ? war.defenderFactionId() : war.attackerFactionId();
        WarCampItemAssignment.assign(heldCamp, warId, factions.factionName(opponentId));
    }

    public WarCampSnapshot placeWarCampForPlayer(UUID playerId, String dimension, BlockPos position) {
        return placeWarCampForPlayer(playerId, null, dimension, position);
    }

    public WarCampSnapshot placeWarCampForPlayer(UUID playerId, UUID itemWarId,
                                                  String dimension, BlockPos position) {
        FactionIdentity identity = factions.factionForPlayer(playerId);
        if (identity == null || !identity.rank().canBuild()) {
            throw new IllegalStateException("You must be a building member of a faction to place a War Camp");
        }
        if (itemWarId == null) return placeWarCamp(identity.id(), dimension, position);
        return placeWarCamp(itemWarId, identity.id(), dimension, position);
    }

    public WarCampSnapshot placeWarCamp(UUID warId, UUID ownerFactionId, String dimension, BlockPos position) {
        WarSnapshot war = requireActiveWar(warId);
        WarSideSnapshot side = requireSide(war, ownerFactionId);
        if (side.warGoal() == null || !side.warGoal().requiresWarCamp()) {
            throw new IllegalStateException("DEFENSE does not establish a War Camp");
        }
        if (side.goalCompleted() || side.goalFailed()) {
            throw new IllegalStateException("That faction's offensive has already ended");
        }
        if (side.warCampId() != null) {
            throw new IllegalStateException("That faction has already committed its War Camp for this war");
        }
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(position);
        validateCampLocation(war, ownerFactionId, dimension, position);

        long now = currentTime();
        long activation = saturatedAdd(now, TerraFactionsConfig.WAR_CAMP_ESTABLISHMENT_DURATION_TICKS.get());
        WarCampSnapshot camp = new WarCampSnapshot(UUID.randomUUID(), war.id(), ownerFactionId, dimension,
                position.getX(), position.getY(), position.getZ(), WarCampState.ESTABLISHING,
                now, activation, 0);
        WarSnapshot updated = replaceSide(war, side.withWarCamp(camp.id()));
        requireData().warCamps.put(camp.id(), camp);
        put(updated);
        NeoForge.EVENT_BUS.post(new WarCampPlacedEvent(updated, camp));
        if (activation <= now) return activateWarCamp(camp.id());
        return camp;
    }

    public WarCampSnapshot damageWarCamp(UUID attackingPlayerId, UUID campId, int damage) {
        if (damage <= 0) throw new IllegalArgumentException("War Camp damage must be positive");
        WarCampSnapshot camp = requireWarCamp(campId);
        if (camp.state() == WarCampState.DESTROYED) return camp;
        WarSnapshot war = requireActiveWar(camp.warId());
        FactionIdentity attacker = factions.factionForPlayer(attackingPlayerId);
        if (attacker == null || attacker.id().equals(camp.ownerFactionId())
                || war.side(attacker.id()) == null) {
            throw new IllegalStateException("Only the opposing war faction can sabotage this camp");
        }
        long progress = (long) camp.destructionProgress() + damage;
        WarCampSnapshot damaged = camp.withDestructionProgress(
                (int) Math.min(Integer.MAX_VALUE, progress));
        requireData().warCamps.put(camp.id(), damaged);
        requireData().setDirty();
        if (damaged.destructionProgress() >= TerraFactionsConfig.WAR_CAMP_DESTRUCTION_INTERACTIONS.get()) {
            return destroyWarCamp(camp.id());
        }
        return damaged;
    }

    public WarCampSnapshot destroyWarCamp(UUID campId) {
        WarCampSnapshot camp = requireWarCamp(campId);
        if (camp.state() == WarCampState.DESTROYED) return camp;
        WarSnapshot war = requireWar(camp.warId());
        WarCampSnapshot destroyed = camp.withState(WarCampState.DESTROYED);
        requireData().warCamps.put(camp.id(), destroyed);
        requireData().setDirty();
        NeoForge.EVENT_BUS.post(new WarCampDestroyedEvent(war, destroyed));

        if (war.state() == WarState.ACTIVE) {
            WarRules.CampDestructionOutcome outcome = WarRules.resolveCampDestruction(
                    war, camp.ownerFactionId());
            war = outcome.war();
            put(war);
            releaseOccupations(war.id(), camp.ownerFactionId(), AnchorReleaseReason.OFFENSIVE_FAILED);
            clearSieges(war.id(), camp.ownerFactionId());
            clearPlunderBreaches(war.id(), camp.ownerFactionId());
            if (outcome.completedDefenseFactionId() != null) {
                WarSideSnapshot defense = requireSide(war, outcome.completedDefenseFactionId());
                NeoForge.EVENT_BUS.post(new WarGoalCompletedEvent(
                        war, defense.factionId(), WarGoalType.DEFENSE));
            }
            if (!WarRules.hasViableOffensiveGoal(war)) {
                resolveWar(war.id(), currentTime());
            }
        }
        return destroyed;
    }

    public WarCampSnapshot getWarCamp(UUID campId) {
        return requireData().warCamps.get(campId);
    }

    public List<WarCampSnapshot> getWarCamps(UUID warId) {
        return allWarCamps().stream().filter(camp -> camp.warId().equals(warId)).toList();
    }

    public List<WarCampSnapshot> allWarCamps() {
        return requireData().warCamps.values().stream()
                .sorted(Comparator.comparingLong(WarCampSnapshot::placementTime)).toList();
    }

    public AnchorSiegeResult damageAnchorSiege(UUID attackingPlayerId, String anchorId, int damage) {
        if (damage <= 0) throw new IllegalArgumentException("Anchor siege damage must be positive");
        AnchorMapSnapshot target = factions.anchor(anchorId);
        if (target == null) throw new IllegalArgumentException("Faction anchor does not exist");
        FactionIdentity attacker = factions.factionForPlayer(attackingPlayerId);
        if (attacker == null) throw new IllegalStateException("You must belong to a faction to siege an anchor");

        AnchorOccupationSnapshot occupation = getOccupation(anchorId);
        if (occupation != null) {
            if (!occupation.originalOwnerFactionId().equals(attacker.id())) {
                throw new IllegalStateException(occupation.occupyingFactionId().equals(attacker.id())
                        ? "Your faction already occupies this anchor"
                        : "This anchor is already occupied in another invasion");
            }
            WarSnapshot war = requireActiveWar(occupation.warId());
            return advanceAnchorSiege(war, attacker.id(), target, occupation, damage);
        }

        WarSnapshot war = getWarBetweenFactions(attacker.id(), target.factionId());
        if (war == null || war.state() != WarState.ACTIVE) {
            throw new IllegalStateException("Your faction has no active war against this anchor's owner");
        }
        requireActiveOffensiveCamp(war, attacker.id());
        if (!isAnchorAttackEligible(war, attacker.id(), target)) {
            throw new IllegalStateException("This anchor is beyond the current invasion frontline");
        }
        return advanceAnchorSiege(war, attacker.id(), target, null, damage);
    }

    public boolean isAnchorAttackEligible(UUID warId, UUID attackingFactionId, String anchorId) {
        WarSnapshot war = requireActiveWar(warId);
        AnchorMapSnapshot target = factions.anchor(anchorId);
        return target != null && isAnchorAttackEligible(war, attackingFactionId, target);
    }

    public AnchorOccupationSnapshot getOccupation(String anchorId) {
        return requireData().occupations.get(anchorId);
    }

    public AnchorSiegeSnapshot getSiege(String anchorId) {
        return requireData().sieges.get(anchorId);
    }

    public List<AnchorOccupationSnapshot> getOccupations(UUID warId, UUID occupyingFactionId) {
        return allOccupations().stream().filter(occupation -> occupation.warId().equals(warId)
                && occupation.occupyingFactionId().equals(occupyingFactionId)).toList();
    }

    public List<AnchorOccupationSnapshot> allOccupations() {
        return requireData().occupations.values().stream()
                .sorted(Comparator.comparingLong(AnchorOccupationSnapshot::occupationStartTime))
                .toList();
    }

    public List<PlunderBreachSnapshot> getPlunderBreaches(UUID warId, UUID breachingFactionId) {
        return allPlunderBreaches().stream()
                .filter(breach -> breach.warId().equals(warId))
                .filter(breach -> breachingFactionId == null
                        || breach.breachingFactionId().equals(breachingFactionId))
                .toList();
    }

    public List<PlunderBreachSnapshot> allPlunderBreaches() {
        return requireData().plunderBreaches.values().stream()
                .sorted(Comparator.comparingLong(PlunderBreachSnapshot::startTime))
                .toList();
    }

    /** Returns whether this faction currently has a server-authoritative Plunder override in the chunk. */
    public boolean hasActivePlunderAccess(UUID factionId, TerritoryKey key) {
        if (!isReady() || server == null) return false;
        TerritoryKey canonical = canonicalKey(key);
        TerritoryClaim claim = factions.claim(canonical);
        if (claim == null) return false;
        long now = currentTime();
        return requireData().plunderBreaches.values().stream()
                .anyMatch(breach -> {
                    if (!breach.breachingFactionId().equals(factionId)
                            || !breach.originalOwnerFactionId().equals(claim.factionId())
                            || !breach.active(now)
                            || !breach.contains(canonical, this::distanceSquared)) return false;
                    WarSnapshot war = getWar(breach.warId());
                    WarSideSnapshot side = war == null ? null : war.side(factionId);
                    return side != null && side.warGoal() != null
                            && side.warGoal().type() == WarGoalType.PLUNDER && !side.goalFailed()
                            && (war.state() != WarState.ENDED || side.goalCompleted());
                });
    }

    public List<TemporaryPowerModifierSnapshot> getPowerModifiers(UUID factionId) {
        return requireData().powerModifiers.values().stream()
                .filter(modifier -> modifier.factionId().equals(factionId))
                .sorted(Comparator.comparingLong(TemporaryPowerModifierSnapshot::startTime))
                .toList();
    }

    public int currentIntegrationPower(UUID factionId) {
        if (!isReady() || server == null) return 0;
        double total = requireData().powerModifiers.values().stream()
                .filter(modifier -> modifier.factionId().equals(factionId)
                        && modifier.type() == PowerModifierType.CONQUEST_INTEGRATION)
                .mapToDouble(modifier -> modifier.currentAmount(currentTime())).sum();
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(total));
    }

    /** Strongest active Punitive effect; suppressions intentionally do not stack. */
    public double currentPunitiveSuppression(UUID factionId) {
        if (!isReady() || server == null) return 0.0D;
        return WarPunitiveRules.strongestSuppression(
                requireData().powerModifiers.values(), factionId, currentTime());
    }

    /** Revalidates every active invasion graph after anchor geometry changes. */
    public void reconcileInvasionNetworks() {
        if (!isReady()) return;
        for (WarSnapshot war : allWars()) {
            if (war.state() != WarState.ACTIVE) continue;
            reconcileInvasionNetwork(war, war.attackerFactionId());
            reconcileInvasionNetwork(war, war.defenderFactionId());
        }
        pruneInvalidSieges();
    }

    /** Removes an anchor from any siege/occupation and cascades lost invasion links. */
    public void handleAnchorRemoved(String anchorId) {
        if (!isReady()) return;
        AnchorOccupationSnapshot occupation = requireData().occupations.remove(anchorId);
        AnchorSiegeSnapshot siege = requireData().sieges.remove(anchorId);
        if (occupation == null && siege == null) return;
        requireData().setDirty();
        if (occupation != null) {
            postAnchorReleased(occupation, AnchorReleaseReason.ANCHOR_REMOVED);
            WarSnapshot war = getWar(occupation.warId());
            if (war != null && war.state() == WarState.ACTIVE) {
                reconcileInvasionNetwork(war, occupation.occupyingFactionId());
                evaluateWarGoals(war.id(), occupation.occupyingFactionId());
            }
        }
    }

    public WarSnapshot failGoal(UUID warId, UUID factionId, String result) {
        WarSnapshot war = requireActiveWar(warId);
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.goalCompleted() || side.goalFailed()) return war;
        WarSnapshot updated = replaceSide(war, side.fail(result));
        put(updated);
        if (side.warGoal() != null && side.warGoal().requiresWarCamp()) {
            releaseOccupations(warId, factionId, AnchorReleaseReason.OFFENSIVE_FAILED);
            clearSieges(warId, factionId);
            clearPlunderBreaches(warId, factionId);
        }
        return updated;
    }

    public WarSnapshot beginResolving(UUID warId) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.ACTIVE) {
            throw new IllegalStateException("Only an active war can begin resolving");
        }
        WarSnapshot resolving = war.withState(WarState.RESOLVING, war.startedAt(), 0L);
        put(resolving);
        return resolving;
    }

    public WarSnapshot resolveWar(UUID warId, long now) {
        WarSnapshot war = requireWar(warId);
        if (war.state() == WarState.ACTIVE) war = beginResolving(warId);
        if (war.state() != WarState.RESOLVING) {
            throw new IllegalStateException("Only an active or resolving war can be resolved");
        }
        return finishWarResolution(warId, now);
    }

    public WarSnapshot endWar(UUID warId, long now) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.RESOLVING) {
            throw new IllegalStateException("War results must be resolving before the war can end");
        }
        war = applyConquestResults(war, now);
        war = applyPunitiveResults(war, now);
        clearFailedPlunderBreaches(war);
        releaseOccupations(warId, null, AnchorReleaseReason.WAR_ENDED);
        clearSieges(warId, null);
        WarSnapshot ended = war.withState(WarState.ENDED, war.startedAt(), now);
        put(ended);
        retireWarCamps(warId);
        NeoForge.EVENT_BUS.post(new WarEndedEvent(ended));
        return ended;
    }

    /** Ends every unresolved war involving a faction without awarding unresolved objectives. */
    public void cancelWarsForFaction(UUID factionId, long now, String reason) {
        List<UUID> warIds = allWars().stream().filter(war -> war.state() != WarState.ENDED)
                .filter(war -> war.side(factionId) != null).map(WarSnapshot::id).toList();
        for (UUID warId : warIds) cancelWar(warId, now, reason);
    }

    /** Administratively ends one unresolved war without awarding unfinished objectives. */
    public WarSnapshot forceEndWar(UUID warId, long now, String reason) {
        WarSnapshot war = requireWar(warId);
        if (war.state() == WarState.ENDED) throw new IllegalStateException("That war has already ended");
        return cancelWar(warId, now, reason);
    }

    private WarSnapshot cancelWar(UUID warId, long now, String reason) {
        WarSnapshot war = requireWar(warId);
        if (war.state() == WarState.ENDED) return war;
        WarSideSnapshot attacker = failUnfinished(war.attacker(), reason);
        WarSideSnapshot defender = failUnfinished(war.defender(), reason);
        WarSnapshot cancelled = new WarSnapshot(war.id(), war.attackerFactionId(), war.defenderFactionId(),
                WarState.ENDED, attacker, defender, war.declaredAt(), war.preparationEndsAt(),
                war.startedAt(), now);
        releaseOccupations(warId, null, AnchorReleaseReason.WAR_ENDED);
        clearSieges(warId, null);
        clearPlunderBreaches(warId, null);
        put(cancelled);
        retireWarCamps(warId);
        NeoForge.EVENT_BUS.post(new WarEndedEvent(cancelled));
        return cancelled;
    }

    private static WarSideSnapshot failUnfinished(WarSideSnapshot side, String reason) {
        return side.warGoal() == null || side.goalCompleted() || side.goalFailed() ? side : side.fail(reason);
    }

    private void enforceWarDeadlines(UUID warId, long now) {
        WarSnapshot war = getWar(warId);
        if (war == null || war.state() != WarState.ACTIVE) return;
        if (!factions.hasAnchor(war.attackerFactionId()) || !factions.hasAnchor(war.defenderFactionId())) {
            cancelWar(warId, now, "A participating faction has no anchors");
            return;
        }
        if (WarRules.maximumDurationExpired(war, now, TerraFactionsConfig.WAR_MAX_DURATION_TICKS.get())) {
            WarSnapshot expired = war.withAttacker(failUnfinished(war.attacker(), "War duration expired"));
            expired = expired.withDefender(failUnfinished(expired.defender(), "War duration expired"));
            put(expired);
            resolveWar(warId, now);
            return;
        }
        for (UUID factionId : List.of(war.attackerFactionId(), war.defenderFactionId())) {
            WarSnapshot current = getWar(warId);
            if (current == null || current.state() != WarState.ACTIVE) return;
            if (WarRules.campPlacementDeadlineExpired(current, factionId, now,
                    TerraFactionsConfig.WAR_CAMP_PLACEMENT_DEADLINE_TICKS.get())) {
                failGoal(warId, factionId, "War Camp placement deadline expired");
                completeOpposingDefense(warId, factionId,
                        "The opposing faction did not establish its War Camp");
            }
        }
        WarSnapshot updated = getWar(warId);
        if (updated != null && updated.state() == WarState.ACTIVE
                && !WarRules.hasViableOffensiveGoal(updated)) resolveWar(warId, now);
    }

    private void completeOpposingDefense(UUID warId, UUID failedFactionId, String result) {
        WarSnapshot war = getWar(warId);
        if (war == null || war.state() != WarState.ACTIVE) return;
        WarSideSnapshot opponent = war.attackerFactionId().equals(failedFactionId)
                ? war.defender() : war.attacker();
        if (opponent.warGoal() != null && opponent.warGoal().type() == WarGoalType.DEFENSE
                && !opponent.goalCompleted() && !opponent.goalFailed()) {
            completeGoal(warId, opponent.factionId(), result);
        }
    }

    private void retireWarCamps(UUID warId) {
        for (WarCampSnapshot camp : getWarCamps(warId)) {
            if (camp.state() == WarCampState.DESTROYED) continue;
            requireData().warCamps.put(camp.id(), camp.withState(WarCampState.DESTROYED));
            if (server != null) {
                for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                    if (level.dimension().location().toString().equals(camp.dimension())) {
                        level.removeBlock(new BlockPos(camp.x(), camp.y(), camp.z()), false);
                        break;
                    }
                }
            }
        }
        requireData().setDirty();
    }

    public WarSnapshot getWar(UUID warId) {
        return requireData().wars.get(warId);
    }

    public WarSnapshot getWarBetweenFactions(UUID firstFactionId, UUID secondFactionId) {
        return requireData().wars.values().stream()
                .filter(war -> war.state() != WarState.ENDED)
                .filter(war -> (war.attackerFactionId().equals(firstFactionId)
                        && war.defenderFactionId().equals(secondFactionId))
                        || (war.attackerFactionId().equals(secondFactionId)
                        && war.defenderFactionId().equals(firstFactionId)))
                .max(Comparator.comparingLong(WarSnapshot::declaredAt)).orElse(null);
    }

    public List<WarSnapshot> getWarsForFaction(UUID factionId) {
        return allWars().stream().filter(war -> war.side(factionId) != null).toList();
    }

    public List<WarSnapshot> allWars() {
        return requireData().wars.values().stream()
                .sorted(Comparator.comparingLong(WarSnapshot::declaredAt).reversed()).toList();
    }

    private WarSnapshot changeWarGoalTarget(UUID warId, UUID factionId, String anchorId, boolean add) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.PREPARING) {
            throw new IllegalStateException("War targets can only change during war preparation");
        }
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || !usesAnchorTargets(side.warGoal().type())) {
            throw new IllegalStateException("Your selected war goal does not use anchor targets");
        }
        Set<String> targets = new HashSet<>(side.warGoal().targetAnchorIds());
        if (add) {
            AnchorMapSnapshot anchor = factions.anchor(anchorId);
            UUID opponentId = war.attackerFactionId().equals(factionId)
                    ? war.defenderFactionId() : war.attackerFactionId();
            if (anchor == null || !anchor.factionId().equals(opponentId)) {
                throw new IllegalArgumentException("The selected block is not an opposing faction anchor");
            }
            targets.add(anchorId);
        } else {
            targets.remove(anchorId);
        }
        WarSideSnapshot updatedSide = side.withGoal(side.warGoal().withTargets(targets));
        WarSnapshot updated = replaceSide(war, updatedSide);
        put(updated);
        return updated;
    }

    private WarSnapshot prepareWarGoals(WarSnapshot war) {
        WarSnapshot targeted = sanitizeTargetGoals(war);
        WarSideSnapshot attacker = preparePunitiveSide(targeted.attacker());
        WarSnapshot updated = targeted.withAttacker(attacker);
        return updated.withDefender(preparePunitiveSide(updated.defender()));
    }

    private WarSnapshot sanitizeTargetGoals(WarSnapshot war) {
        WarSideSnapshot attacker = sanitizeTargetSide(war, war.attacker());
        WarSnapshot updated = war.withAttacker(attacker);
        WarSideSnapshot defender = sanitizeTargetSide(updated, updated.defender());
        return updated.withDefender(defender);
    }

    private WarSideSnapshot sanitizeTargetSide(WarSnapshot war, WarSideSnapshot side) {
        if (side.warGoal() == null || !usesAnchorTargets(side.warGoal().type())) return side;
        UUID opponentId = war.attackerFactionId().equals(side.factionId())
                ? war.defenderFactionId() : war.attackerFactionId();
        Set<String> valid = side.warGoal().targetAnchorIds().stream()
                .filter(id -> {
                    AnchorMapSnapshot anchor = factions.anchor(id);
                    return anchor != null && anchor.factionId().equals(opponentId);
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        WarSideSnapshot sanitized = side.withGoal(side.warGoal().withTargets(valid));
        String goalName = side.warGoal().type().name().toLowerCase(java.util.Locale.ROOT);
        return valid.isEmpty() ? sanitized.fail("No valid " + goalName + " targets remained when the war started")
                : sanitized;
    }

    private WarSideSnapshot preparePunitiveSide(WarSideSnapshot side) {
        if (side.warGoal() == null || side.warGoal().type() != WarGoalType.PUNITIVE
                || side.warGoal().requiredObjectiveValue() > 0) return side;
        return side.withGoal(side.warGoal().withRequiredObjectiveValue(
                TerraFactionsConfig.PUNITIVE_REQUIRED_ANCHOR_POWER.get()));
    }

    private static WarGoalSnapshot prepareSelectedGoal(WarGoalSnapshot goal) {
        return goal.type() == WarGoalType.PUNITIVE && goal.requiredObjectiveValue() <= 0
                ? goal.withRequiredObjectiveValue(TerraFactionsConfig.PUNITIVE_REQUIRED_ANCHOR_POWER.get())
                : goal;
    }

    private static boolean usesAnchorTargets(WarGoalType type) {
        return type == WarGoalType.CONQUEST || type == WarGoalType.PLUNDER;
    }

    private WarSnapshot evaluateConquestGoal(UUID warId, UUID factionId) {
        WarSnapshot war = getWar(warId);
        if (war == null || war.state() != WarState.ACTIVE) return war;
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || side.warGoal().type() != WarGoalType.CONQUEST || side.goalFailed()) {
            return war;
        }
        WarGoalSnapshot goal = side.warGoal();
        Set<String> heldTargets = WarConquestRules.heldTargets(goal, allOccupations(), war.id(), factionId);
        int occupied = heldTargets.size();
        boolean completed = WarConquestRules.isComplete(goal, allOccupations(), war.id(), factionId);
        String result = completed ? "All declared conquest targets are occupied" : "";
        WarGoalSnapshot progress = goal.withProgress(occupied).withResult(result);
        if (progress.equals(goal) && completed == side.goalCompleted()) return war;
        WarSideSnapshot updatedSide = side.withGoalState(progress, completed);
        WarSnapshot updated = replaceSide(war, updatedSide);
        put(updated);
        if (completed && !side.goalCompleted()) {
            NeoForge.EVENT_BUS.post(new WarGoalCompletedEvent(updated, factionId, WarGoalType.CONQUEST));
            if (!WarRules.hasViableOffensiveGoal(updated)) return resolveWar(warId, currentTime());
        }
        return updated;
    }

    private WarSnapshot evaluatePlunderGoal(UUID warId, UUID factionId) {
        WarSnapshot war = getWar(warId);
        if (war == null || war.state() != WarState.ACTIVE) return war;
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || side.warGoal().type() != WarGoalType.PLUNDER
                || side.goalFailed()) return war;

        for (String targetId : side.warGoal().targetAnchorIds()) {
            AnchorOccupationSnapshot occupation = getOccupation(targetId);
            if (occupation != null && occupation.warId().equals(war.id())
                    && occupation.occupyingFactionId().equals(factionId)) {
                startPlunderBreach(war, occupation);
            }
        }

        WarGoalSnapshot goal = side.warGoal();
        Set<String> breachedTargets = WarPlunderRules.breachedTargets(
                goal, requireData().plunderBreaches.values(), war.id(), factionId);
        boolean completed = WarPlunderRules.isComplete(
                goal, requireData().plunderBreaches.values(), war.id(), factionId);
        String result = completed ? "All declared plunder targets were breached" : "";
        WarGoalSnapshot progress = goal.withProgress(breachedTargets.size()).withResult(result);
        if (progress.equals(goal) && completed == side.goalCompleted()) return war;
        WarSideSnapshot updatedSide = side.withGoalState(progress, completed);
        WarSnapshot updated = replaceSide(war, updatedSide);
        put(updated);
        if (completed && !side.goalCompleted()) {
            NeoForge.EVENT_BUS.post(new WarGoalCompletedEvent(updated, factionId, WarGoalType.PLUNDER));
            if (!WarRules.hasViableOffensiveGoal(updated)) return resolveWar(warId, currentTime());
        }
        return updated;
    }

    private WarSnapshot evaluateWarGoals(UUID warId, UUID factionId) {
        WarSnapshot war = evaluateConquestGoal(warId, factionId);
        if (war == null || war.state() != WarState.ACTIVE) return war;
        war = evaluatePlunderGoal(warId, factionId);
        if (war == null || war.state() != WarState.ACTIVE) return war;
        return evaluatePunitiveGoal(warId, factionId);
    }

    private WarSnapshot evaluatePunitiveGoal(UUID warId, UUID factionId) {
        WarSnapshot war = getWar(warId);
        if (war == null || war.state() != WarState.ACTIVE) return war;
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || side.warGoal().type() != WarGoalType.PUNITIVE
                || side.goalFailed()) return war;

        WarGoalSnapshot goal = side.warGoal();
        int disabledPower = WarPunitiveRules.occupiedAnchorPower(
                allOccupations(), factions.allAnchors(), war.id(), factionId);
        boolean completed = WarPunitiveRules.isComplete(goal, disabledPower);
        String result = completed ? "Required enemy anchor Power is occupied" : "";
        WarGoalSnapshot progress = goal.withProgress(disabledPower).withResult(result);
        if (progress.equals(goal) && completed == side.goalCompleted()) return war;
        WarSideSnapshot updatedSide = side.withGoalState(progress, completed);
        WarSnapshot updated = replaceSide(war, updatedSide);
        put(updated);
        if (completed && !side.goalCompleted()) {
            NeoForge.EVENT_BUS.post(new WarGoalCompletedEvent(updated, factionId, WarGoalType.PUNITIVE));
            if (!WarRules.hasViableOffensiveGoal(updated)) return resolveWar(warId, currentTime());
        }
        return updated;
    }

    private void startPlunderBreach(WarSnapshot war, AnchorOccupationSnapshot occupation) {
        UUID id = UUID.nameUUIDFromBytes(("plunder-breach:" + war.id() + ":"
                + occupation.occupyingFactionId() + ":" + occupation.anchorId())
                .getBytes(StandardCharsets.UTF_8));
        if (requireData().plunderBreaches.containsKey(id)) return;
        AnchorMapSnapshot anchor = factions.anchor(occupation.anchorId());
        if (anchor == null || !anchor.factionId().equals(occupation.originalOwnerFactionId())) return;
        long now = currentTime();
        TerritoryKey center = canonicalKey(anchorKey(anchor));
        PlunderBreachSnapshot breach = new PlunderBreachSnapshot(id, war.id(), anchor.id(),
                occupation.originalOwnerFactionId(), occupation.occupyingFactionId(), anchor.dimension(),
                center.x(), center.z(), anchor.projectedRadius(),
                now, saturatedAdd(now, TerraFactionsConfig.PLUNDER_BREACH_DURATION_TICKS.get()));
        requireData().plunderBreaches.put(id, breach);
        requireData().setDirty();
        NeoForge.EVENT_BUS.post(new PlunderBreachStartedEvent(war, breach));
    }

    private WarSnapshot finishWarResolution(UUID warId, long now) {
        return endWar(warId, now);
    }

    private WarSnapshot applyConquestResults(WarSnapshot war, long now) {
        WarSnapshot updated = applyConquestResult(war, war.attackerFactionId(), now);
        return applyConquestResult(updated, updated.defenderFactionId(), now);
    }

    private WarSnapshot applyPunitiveResults(WarSnapshot war, long now) {
        WarSnapshot updated = applyPunitiveResult(war, war.attackerFactionId(), now);
        return applyPunitiveResult(updated, updated.defenderFactionId(), now);
    }

    private WarSnapshot applyPunitiveResult(WarSnapshot war, UUID victoriousFactionId, long now) {
        WarSideSnapshot side = requireSide(war, victoriousFactionId);
        if (!side.goalCompleted() || side.warGoal() == null
                || side.warGoal().type() != WarGoalType.PUNITIVE) return war;
        UUID targetFactionId = war.attackerFactionId().equals(victoriousFactionId)
                ? war.defenderFactionId() : war.attackerFactionId();
        double suppression = TerraFactionsConfig.PUNITIVE_SUPPRESSION_PERCENT.get();
        if (suppression > 0.0D) {
            UUID id = UUID.nameUUIDFromBytes(("punitive-suppression:" + war.id() + ":"
                    + victoriousFactionId).getBytes(StandardCharsets.UTF_8));
            if (!requireData().powerModifiers.containsKey(id)) {
                TemporaryPowerModifierSnapshot modifier = new TemporaryPowerModifierSnapshot(id,
                        targetFactionId, war.id(), suppression, now,
                        saturatedAdd(now, TerraFactionsConfig.PUNITIVE_SUPPRESSION_DURATION_TICKS.get()),
                        PowerModifierType.PUNITIVE_SUPPRESSION);
                requireData().powerModifiers.put(id, modifier);
                requireData().setDirty();
                NeoForge.EVENT_BUS.post(new PowerSuppressionAppliedEvent(war, modifier));
            }
        }
        int percent = (int) Math.round(suppression * 100.0D);
        WarGoalSnapshot resolvedGoal = side.warGoal().withResult(
                "Suppressed enemy usable Power by " + percent + "%");
        WarSnapshot resolved = replaceSide(war, side.withGoalState(resolvedGoal, true));
        put(resolved);
        return resolved;
    }

    private WarSnapshot applyConquestResult(WarSnapshot war, UUID factionId, long now) {
        WarSideSnapshot side = requireSide(war, factionId);
        if (!side.goalCompleted() || side.warGoal() == null
                || side.warGoal().type() != WarGoalType.CONQUEST) return war;

        List<AnchorOccupationSnapshot> targets = new ArrayList<>();
        List<AnchorMapSnapshot> anchors = new ArrayList<>();
        for (String targetId : side.warGoal().targetAnchorIds().stream().sorted().toList()) {
            AnchorOccupationSnapshot occupation = getOccupation(targetId);
            AnchorMapSnapshot anchor = factions.anchor(targetId);
            if (occupation == null || anchor == null || !occupation.warId().equals(war.id())
                    || !occupation.occupyingFactionId().equals(factionId)
                    || (!anchor.factionId().equals(occupation.originalOwnerFactionId())
                    && !anchor.factionId().equals(factionId))) {
                WarSideSnapshot failed = side.withGoalState(side.warGoal(), false)
                        .fail("Conquest targets were not held at resolution");
                WarSnapshot failedWar = replaceSide(war, failed);
                put(failedWar);
                return failedWar;
            }
            targets.add(occupation);
            anchors.add(anchor);
        }

        long integrationTenths = 0L;
        int annexed = 0;
        for (int index = 0; index < targets.size(); index++) {
            AnchorOccupationSnapshot occupation = targets.get(index);
            AnchorMapSnapshot anchor = anchors.get(index);
            integrationTenths = saturatedPositiveAdd(integrationTenths,
                    AnchorNetworkRules.requiredPowerTenths(anchor.tier(), anchor.projectedClaims()));
            TerritoryKey center = canonicalKey(anchorKey(anchor));
            TerritoryClaim centerClaim = factions.claim(center);
            if (centerClaim != null && !centerClaim.projected()
                    && (centerClaim.factionId().equals(occupation.originalOwnerFactionId())
                    || centerClaim.factionId().equals(factionId))) {
                integrationTenths = saturatedPositiveAdd(integrationTenths, centerClaim.powerCostTenths());
            }
            annexed++;
            if (anchor.factionId().equals(occupation.originalOwnerFactionId())) {
                factions.transferAnchorTerritory(anchor.id(), occupation.originalOwnerFactionId(), factionId, now,
                        this::canonicalKey);
                NeoForge.EVENT_BUS.post(new AnchorAnnexedEvent(war, occupation));
            }
        }
        if (integrationTenths > 0L) applyIntegrationPower(war, factionId, integrationTenths / 10.0D, now);
        WarGoalSnapshot resolvedGoal = side.warGoal().withResult("Annexed " + annexed + " declared anchors");
        WarSnapshot resolved = replaceSide(war, side.withGoalState(resolvedGoal, true));
        put(resolved);
        return resolved;
    }

    private void applyIntegrationPower(WarSnapshot war, UUID factionId, double amount, long now) {
        UUID id = UUID.nameUUIDFromBytes(("conquest-integration:" + war.id() + ":" + factionId)
                .getBytes(StandardCharsets.UTF_8));
        if (requireData().powerModifiers.containsKey(id)) return;
        long expiration = saturatedAdd(now, TerraFactionsConfig.INTEGRATION_POWER_DECAY_DURATION_TICKS.get());
        TemporaryPowerModifierSnapshot modifier = new TemporaryPowerModifierSnapshot(id, factionId, war.id(),
                amount, now, expiration, PowerModifierType.CONQUEST_INTEGRATION);
        requireData().powerModifiers.put(id, modifier);
        requireData().setDirty();
        NeoForge.EVENT_BUS.post(new IntegrationPowerAppliedEvent(war, modifier));
    }

    private void pruneExpiredPowerModifiers(long now) {
        boolean removed = requireData().powerModifiers.entrySet().removeIf(entry ->
                entry.getValue().currentAmount(now) <= 0.0D
                        || factions.snapshot(entry.getValue().factionId()) == null);
        if (removed) requireData().setDirty();
    }

    private void pruneExpiredPlunderBreaches(long now) {
        List<PlunderBreachSnapshot> expired = requireData().plunderBreaches.values().stream()
                .filter(breach -> {
                    WarSnapshot war = getWar(breach.warId());
                    return factions.snapshot(breach.breachingFactionId()) == null
                            || factions.snapshot(breach.originalOwnerFactionId()) == null
                            || (!breach.active(now) && (war == null || war.state() == WarState.ENDED));
                }).toList();
        for (PlunderBreachSnapshot breach : expired) removePlunderBreach(breach);
    }

    private void clearFailedPlunderBreaches(WarSnapshot war) {
        if (war.attacker().goalFailed()) clearPlunderBreaches(war.id(), war.attackerFactionId());
        if (war.defender().goalFailed()) clearPlunderBreaches(war.id(), war.defenderFactionId());
    }

    private void clearPlunderBreaches(UUID warId, UUID breachingFactionId) {
        List<PlunderBreachSnapshot> clearing = requireData().plunderBreaches.values().stream()
                .filter(breach -> breach.warId().equals(warId))
                .filter(breach -> breachingFactionId == null
                        || breach.breachingFactionId().equals(breachingFactionId))
                .toList();
        for (PlunderBreachSnapshot breach : clearing) removePlunderBreach(breach);
    }

    private void removePlunderBreach(PlunderBreachSnapshot breach) {
        if (!requireData().plunderBreaches.remove(breach.id(), breach)) return;
        requireData().setDirty();
        WarSnapshot war = getWar(breach.warId());
        if (war != null) NeoForge.EVENT_BUS.post(new PlunderBreachEndedEvent(war, breach));
    }

    private WarSnapshot replaceSide(WarSnapshot war, WarSideSnapshot side) {
        return war.attackerFactionId().equals(side.factionId())
                ? war.withAttacker(side) : war.withDefender(side);
    }

    private WarCampSnapshot activateWarCamp(UUID campId) {
        WarCampSnapshot camp = requireWarCamp(campId);
        if (camp.state() != WarCampState.ESTABLISHING) return camp;
        WarSnapshot war = requireWar(camp.warId());
        if (war.state() != WarState.ACTIVE) return camp;
        WarCampSnapshot active = camp.withState(WarCampState.ACTIVE);
        requireData().warCamps.put(camp.id(), active);
        requireData().setDirty();
        NeoForge.EVENT_BUS.post(new WarCampActivatedEvent(war, active));
        return active;
    }

    private AnchorSiegeResult advanceAnchorSiege(WarSnapshot war, UUID attackingFactionId,
                                                  AnchorMapSnapshot target,
                                                  AnchorOccupationSnapshot occupation, int damage) {
        if (occupation != null && !target.factionId().equals(occupation.originalOwnerFactionId())) {
            throw new IllegalStateException("This occupation no longer matches the anchor's permanent owner");
        }
        AnchorSiegeSnapshot siege = requireData().sieges.get(target.id());
        if (siege != null && (!siege.warId().equals(war.id())
                || !siege.attackingFactionId().equals(attackingFactionId))) {
            throw new IllegalStateException("Another faction already has an active siege on this anchor");
        }
        long progress = (long) (siege == null ? 0 : siege.progress()) + damage;
        int required = TerraFactionsConfig.ANCHOR_SIEGE_INTERACTIONS.get();
        int boundedProgress = (int) Math.min(Integer.MAX_VALUE, progress);
        if (boundedProgress < required) {
            AnchorSiegeSnapshot updated = siege == null
                    ? new AnchorSiegeSnapshot(target.id(), war.id(), attackingFactionId,
                    boundedProgress, currentTime())
                    : siege.withProgress(boundedProgress);
            requireData().sieges.put(target.id(), updated);
            requireData().setDirty();
            return new AnchorSiegeResult(boundedProgress, required, false, false, false, occupation);
        }

        requireData().sieges.remove(target.id());
        if (occupation != null) {
            requireData().occupations.remove(target.id());
            requireData().setDirty();
            postAnchorReleased(occupation, AnchorReleaseReason.LIBERATED);
            reconcileInvasionNetwork(war, occupation.occupyingFactionId());
            return new AnchorSiegeResult(required, required, true, true, false, null);
        }

        AnchorOccupationSnapshot captured = new AnchorOccupationSnapshot(target.id(), target.factionId(),
                attackingFactionId, war.id(), currentTime());
        requireData().occupations.put(target.id(), captured);
        requireData().setDirty();
        NeoForge.EVENT_BUS.post(new AnchorOccupiedEvent(war, captured));
        reconcileInvasionNetwork(war, attackingFactionId);
        AnchorMapSnapshot resolvedAnchor = factions.anchor(target.id());
        boolean annexed = resolvedAnchor != null && resolvedAnchor.factionId().equals(attackingFactionId)
                && getOccupation(target.id()) == null;
        return new AnchorSiegeResult(required, required, true, false, annexed, captured);
    }

    private boolean isAnchorAttackEligible(WarSnapshot war, UUID attackingFactionId,
                                            AnchorMapSnapshot target) {
        WarSideSnapshot side = requireSide(war, attackingFactionId);
        UUID defendingFactionId = war.attackerFactionId().equals(attackingFactionId)
                ? war.defenderFactionId() : war.attackerFactionId();
        if (!target.factionId().equals(defendingFactionId) || getOccupation(target.id()) != null
                || side.goalCompleted() || side.goalFailed()) return false;
        WarCampSnapshot camp = requireActiveOffensiveCamp(war, attackingFactionId);
        List<AnchorMapSnapshot> occupiedAnchors = getOccupations(war.id(), attackingFactionId).stream()
                .map(occupation -> factions.anchor(occupation.anchorId()))
                .filter(Objects::nonNull).toList();
        return WarOccupationRules.attackEligible(camp, occupiedAnchors, target,
                TerraFactionsConfig.ANCHOR_OCCUPATION_RANGE_CHUNKS.get(), this::distanceSquared);
    }

    private WarCampSnapshot requireActiveOffensiveCamp(WarSnapshot war, UUID factionId) {
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || !side.warGoal().requiresWarCamp()
                || side.goalCompleted() || side.goalFailed()) {
            throw new IllegalStateException("Your faction does not have an active offensive in this war");
        }
        if (side.warCampId() == null) {
            throw new IllegalStateException("Your faction must establish its War Camp first");
        }
        WarCampSnapshot camp = requireData().warCamps.get(side.warCampId());
        if (camp == null || camp.state() != WarCampState.ACTIVE) {
            throw new IllegalStateException("Your faction's War Camp is not active");
        }
        return camp;
    }

    private WarCampSnapshot requireSupportingWarCamp(WarSnapshot war, UUID factionId) {
        WarSideSnapshot side = requireSide(war, factionId);
        if (side.warGoal() == null || !side.warGoal().requiresWarCamp() || side.goalFailed()) {
            throw new IllegalStateException("The faction no longer has a supported offensive");
        }
        if (side.warCampId() == null) throw new IllegalStateException("The offensive has no War Camp");
        WarCampSnapshot camp = requireData().warCamps.get(side.warCampId());
        if (camp == null || camp.state() != WarCampState.ACTIVE) {
            throw new IllegalStateException("The offensive War Camp is not active");
        }
        return camp;
    }

    private void reconcileInvasionNetwork(WarSnapshot war, UUID occupyingFactionId) {
        List<AnchorOccupationSnapshot> occupations = getOccupations(war.id(), occupyingFactionId);
        if (occupations.isEmpty()) {
            evaluateWarGoals(war.id(), occupyingFactionId);
            return;
        }
        WarCampSnapshot camp;
        try {
            camp = requireSupportingWarCamp(war, occupyingFactionId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            releaseOccupations(war.id(), occupyingFactionId, AnchorReleaseReason.NETWORK_DISCONNECTED);
            return;
        }

        Map<String, AnchorMapSnapshot> anchors = new HashMap<>();
        List<AnchorOccupationSnapshot> missing = new ArrayList<>();
        for (AnchorOccupationSnapshot occupation : occupations) {
            AnchorMapSnapshot anchor = factions.anchor(occupation.anchorId());
            if (anchor == null || !anchor.factionId().equals(occupation.originalOwnerFactionId())) {
                missing.add(occupation);
            } else {
                anchors.put(anchor.id(), anchor);
            }
        }
        for (AnchorOccupationSnapshot occupation : missing) {
            releaseOccupationRecord(occupation, AnchorReleaseReason.ANCHOR_REMOVED);
        }

        int range = TerraFactionsConfig.ANCHOR_OCCUPATION_RANGE_CHUNKS.get();
        java.util.Set<String> reachable = WarOccupationRules.connectedAnchorIds(
                camp, anchors.values(), range, this::distanceSquared);
        for (AnchorOccupationSnapshot occupation : occupations) {
            if (anchors.containsKey(occupation.anchorId()) && !reachable.contains(occupation.anchorId())) {
                releaseOccupationRecord(occupation, AnchorReleaseReason.NETWORK_DISCONNECTED);
            }
        }
        evaluateWarGoals(war.id(), occupyingFactionId);
    }

    private void releaseOccupations(UUID warId, UUID occupyingFactionId, AnchorReleaseReason reason) {
        List<AnchorOccupationSnapshot> releasing = new ArrayList<>(requireData().occupations.values().stream()
                .filter(occupation -> occupation.warId().equals(warId))
                .filter(occupation -> occupyingFactionId == null
                        || occupation.occupyingFactionId().equals(occupyingFactionId)).toList());
        for (AnchorOccupationSnapshot occupation : releasing) releaseOccupationRecord(occupation, reason);
    }

    private void releaseOccupationRecord(AnchorOccupationSnapshot occupation, AnchorReleaseReason reason) {
        if (!requireData().occupations.remove(occupation.anchorId(), occupation)) return;
        requireData().sieges.remove(occupation.anchorId());
        requireData().setDirty();
        postAnchorReleased(occupation, reason);
    }

    private void postAnchorReleased(AnchorOccupationSnapshot occupation, AnchorReleaseReason reason) {
        WarSnapshot war = getWar(occupation.warId());
        if (war != null) NeoForge.EVENT_BUS.post(new AnchorReleasedEvent(war, occupation, reason));
    }

    private void clearSieges(UUID warId, UUID attackingFactionId) {
        boolean removed = requireData().sieges.entrySet().removeIf(entry -> {
            AnchorSiegeSnapshot siege = entry.getValue();
            return siege.warId().equals(warId) && (attackingFactionId == null
                    || siege.attackingFactionId().equals(attackingFactionId));
        });
        if (removed) requireData().setDirty();
    }

    private void pruneInvalidSieges() {
        List<String> invalid = new ArrayList<>();
        for (AnchorSiegeSnapshot siege : requireData().sieges.values()) {
            WarSnapshot war = getWar(siege.warId());
            AnchorMapSnapshot target = factions.anchor(siege.anchorId());
            AnchorOccupationSnapshot occupation = getOccupation(siege.anchorId());
            if (war == null || war.state() != WarState.ACTIVE || target == null) {
                invalid.add(siege.anchorId());
            } else if (occupation != null) {
                if (!occupation.warId().equals(war.id())
                        || !occupation.originalOwnerFactionId().equals(siege.attackingFactionId())) {
                    invalid.add(siege.anchorId());
                }
            } else {
                try {
                    if (!isAnchorAttackEligible(war, siege.attackingFactionId(), target)) {
                        invalid.add(siege.anchorId());
                    }
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    invalid.add(siege.anchorId());
                }
            }
        }
        if (!invalid.isEmpty()) {
            invalid.forEach(requireData().sieges::remove);
            requireData().setDirty();
        }
    }

    private boolean eligibleForNewCamp(WarSnapshot war, UUID factionId) {
        return WarRules.canEstablishWarCamp(war, factionId);
    }

    private void validateCampLocation(WarSnapshot war, UUID ownerFactionId, String dimension, BlockPos position) {
        ChunkPos chunk = new ChunkPos(position);
        TerritoryKey key = canonicalKey(new TerritoryKey(dimension, chunk.x, chunk.z));
        TerritoryClaim location = factions.claim(key);
        if (location != null && !location.factionId().equals(ownerFactionId)) {
            throw new IllegalStateException(
                    "War Camps can only be established in wilderness or your faction's territory");
        }
        UUID enemyFactionId = war.attackerFactionId().equals(ownerFactionId)
                ? war.defenderFactionId() : war.attackerFactionId();
        List<AnchorMapSnapshot> enemyAnchors = factions.allAnchors().stream()
                .filter(anchor -> anchor.factionId().equals(enemyFactionId))
                .filter(anchor -> anchor.dimension().equals(dimension))
                .toList();
        if (enemyAnchors.isEmpty()) {
            throw new IllegalStateException("The enemy has no anchor in this dimension");
        }
        int connectionRange = TerraFactionsConfig.ANCHOR_OCCUPATION_RANGE_CHUNKS.get();
        boolean connects = enemyAnchors.stream().anyMatch(anchor -> WarOccupationRules.positionReachesAnchor(
                dimension, position.getX(), position.getZ(), anchor, connectionRange, this::distanceSquared));
        if (!connects) {
            throw new IllegalStateException(
                    "The War Camp is too far from every enemy anchor to form an invasion connection");
        }
    }

    private WarCampSnapshot requireWarCamp(UUID campId) {
        WarCampSnapshot camp = requireData().warCamps.get(campId);
        if (camp == null) throw new IllegalArgumentException("War Camp does not exist");
        return camp;
    }

    private static WarSideSnapshot requireSide(WarSnapshot war, UUID factionId) {
        WarSideSnapshot side = war.side(factionId);
        if (side == null) throw new IllegalArgumentException("Faction is not participating in this war");
        return side;
    }

    private WarSnapshot requireActiveWar(UUID warId) {
        WarSnapshot war = requireWar(warId);
        if (war.state() != WarState.ACTIVE) throw new IllegalStateException("The war is not active");
        return war;
    }

    private WarSnapshot requireWar(UUID warId) {
        WarSnapshot war = requireData().wars.get(warId);
        if (war == null) throw new IllegalArgumentException("War does not exist");
        return war;
    }

    private void requireFaction(UUID factionId) {
        if (factions.snapshot(factionId) == null) throw new IllegalArgumentException("Faction does not exist");
    }

    private TerritoryKey canonicalKey(TerritoryKey key) {
        return ToroidalTerritoryCompat.fold(server, key);
    }

    private long distanceSquared(TerritoryKey first, TerritoryKey second) {
        return ToroidalTerritoryCompat.distanceSquared(server, first, second);
    }

    private static TerritoryKey anchorKey(AnchorMapSnapshot anchor) {
        return new TerritoryKey(anchor.dimension(), Math.floorDiv(anchor.x(), 16),
                Math.floorDiv(anchor.z(), 16));
    }

    private void put(WarSnapshot war) {
        requireData().wars.put(war.id(), war);
        requireData().setDirty();
    }

    private long currentTime() {
        if (server == null) throw new IllegalStateException("War manager is not initialized");
        return server.overworld().getGameTime();
    }

    private WarSavedData requireData() {
        if (data == null) throw new IllegalStateException("War data is not loaded");
        return data;
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private static long saturatedPositiveAdd(long first, long second) {
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
