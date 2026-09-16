package dev.terrafactions.factions;

import dev.terrafactions.anchor.AnchorMapSnapshot;
import dev.terrafactions.anchor.AnchorNetworkRules;
import dev.terrafactions.anchor.AnchorPowerState;
import dev.terrafactions.anchor.AnchorConnectionState;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import dev.terrafactions.factions.FactionSnapshot.CapitalSnapshot;
import dev.terrafactions.factions.FactionSnapshot.ClaimSnapshot;
import dev.terrafactions.factions.NativeFactionData.FactionRecord;
import dev.terrafactions.factions.NativeFactionData.AnchorRecord;
import dev.terrafactions.factions.NativeFactionData.MemberRecord;
import dev.terrafactions.factions.NativeFactionData.PlayerSettings;
import dev.terrafactions.territory.TerraFactionsConfig;
import dev.terrafactions.territory.TerritoryClaim;
import dev.terrafactions.territory.TerritoryKey;
import dev.terrafactions.territory.TerritoryRules;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.territory.ProtectionAction;
import dev.terrafactions.territory.ProtectionPolicy;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

import dev.terrafactions.factions.LegacyFactionImporter.LegacyImport;

/** Native NeoForge faction authority. No Fabric or Connector types cross this boundary. */
public final class NativeFactionService {
    private NativeFactionData data;
    private ToIntFunction<UUID> temporaryPowerProvider = ignored -> 0;
    private ToDoubleFunction<UUID> powerSuppressionProvider = ignored -> 0.0D;

    public void initialize(MinecraftServer server) {
        data = server.overworld().getDataStorage().computeIfAbsent(
                NativeFactionData.factory(), NativeFactionData.DATA_NAME);
    }

    public void stop() {
        data = null;
        temporaryPowerProvider = ignored -> 0;
        powerSuppressionProvider = ignored -> 0.0D;
    }

    public boolean isReady() {
        return data != null;
    }

    public boolean isEmpty() {
        NativeFactionData state = requireData();
        return state.factions.isEmpty() && state.members.isEmpty() && state.claims.isEmpty();
    }

    public void importLegacy(LegacyImport legacy) {
        NativeFactionData state = requireData();
        if (!isEmpty()) throw new IllegalStateException("This world already contains native TerraFactions data");
        for (var imported : legacy.factions()) {
            FactionRecord faction = new FactionRecord(imported.id(), imported.name());
            faction.description = imported.description();
            faction.color = imported.color();
            faction.power = imported.power();
            faction.tag = imported.tag();
            faction.capital = imported.capital();
            faction.relations.putAll(imported.relations());
            state.factions.put(faction.id, faction);
        }
        for (var imported : legacy.members()) {
            if (state.factions.containsKey(imported.factionId())) {
                state.members.putIfAbsent(imported.playerId(),
                        new MemberRecord(imported.factionId(), imported.rank()));
            }
        }
        for (TerritoryClaim claim : legacy.claims()) {
            if (state.factions.containsKey(claim.factionId())) state.claims.putIfAbsent(claim.key(), claim);
        }
        legacy.settings().forEach((playerId, imported) -> state.settings.put(playerId,
                new PlayerSettings(imported.radar(), imported.chatMode())));
        state.audit();
        for (FactionRecord faction : state.factions.values()) clampPower(faction.id);
        state.setDirty();
    }

    public List<FactionSnapshot> allFactions() {
        NativeFactionData state = requireData();
        return state.factionRecords().stream()
                .sorted(Comparator.comparing(faction -> faction.name.toLowerCase(Locale.ROOT)))
                .map(this::snapshot)
                .toList();
    }

    public FactionSnapshot snapshot(UUID factionId) {
        FactionRecord faction = requireData().factions.get(factionId);
        return faction == null ? null : snapshot(faction);
    }

    private FactionSnapshot snapshot(FactionRecord faction) {
        List<ClaimSnapshot> claims = requireData().claims.values().stream()
                .filter(claim -> claim.factionId().equals(faction.id))
                .map(claim -> new ClaimSnapshot(claim.key().x(), claim.key().z(),
                        claim.key().dimension(), claim.type()))
                .toList();
        CapitalSnapshot capital = faction.capital == null ? null
                : new CapitalSnapshot(faction.capital.x(), faction.capital.z(), faction.capital.dimension());
        return new FactionSnapshot(faction.id, faction.name, faction.description, faction.color,
                claims, capital);
    }

    public FactionIdentity factionForPlayer(UUID playerId) {
        MemberRecord member = requireData().members.get(playerId);
        return member == null ? null : new FactionIdentity(member.factionId, member.rank);
    }

    public FactionPower power(UUID factionId) {
        FactionRecord faction = requireData().factions.get(factionId);
        if (faction == null) return null;
        int baseMaximum = baseMaximumPower(factionId);
        int temporaryPower = temporaryPower(factionId);
        double suppressionPercent = powerSuppression(factionId);
        int suppressedPower = (int) Math.min(Integer.MAX_VALUE,
                Math.ceil(baseMaximum * suppressionPercent));
        int maximum = Math.max(0, saturatedPositiveAdd(baseMaximum, temporaryPower) - suppressedPower);
        int claimUsage = claimUsage(factionId);
        int deathLoss = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, (long) baseMaximum - faction.power));
        long available = Math.min((long) maximum, (long) faction.power + temporaryPower) - claimUsage;
        return new FactionPower((int) Math.max(Integer.MIN_VALUE, available), maximum,
                claimUsage, deathLoss, faction.specialPower, temporaryPower, suppressedPower,
                suppressionPercent, faction.deathLosses);
    }

    public int claimUsage(UUID factionId) {
        NativeFactionData state = requireData();
        long usage = state.claims.values().stream()
                .filter(claim -> claim.factionId().equals(factionId))
                .mapToLong(TerritoryClaim::powerCostTenths).sum();
        usage += state.anchors.values().stream()
                .filter(anchor -> anchor.factionId.equals(factionId))
                .mapToLong(anchor -> AnchorNetworkRules.requiredPowerTenths(
                        anchor.tier, anchor.projectedClaims))
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, (usage + 9L) / 10L);
    }

    public int maximumPower(UUID factionId) {
        int baseMaximum = baseMaximumPower(factionId);
        int suppressedPower = (int) Math.min(Integer.MAX_VALUE,
                Math.ceil(baseMaximum * powerSuppression(factionId)));
        return Math.max(0, saturatedPositiveAdd(baseMaximum, temporaryPower(factionId)) - suppressedPower);
    }

    public void setTemporaryPowerProvider(ToIntFunction<UUID> provider) {
        temporaryPowerProvider = provider == null ? ignored -> 0 : provider;
    }

    public void setPowerSuppressionProvider(ToDoubleFunction<UUID> provider) {
        powerSuppressionProvider = provider == null ? ignored -> 0.0D : provider;
    }

    private int baseMaximumPower(UUID factionId) {
        long members = requireData().members.values().stream()
                .filter(member -> member.factionId.equals(factionId)).count();
        long maximum = TerraFactionsConfig.BASE_POWER.get()
                + members * (long) TerraFactionsConfig.POWER_PER_MEMBER.get()
                + requireFaction(factionId).specialPower;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, maximum));
    }

    public void adjustSpecialPower(UUID factionId, int amount) {
        FactionRecord faction = requireFaction(factionId);
        int previousMaximum = baseMaximumPower(factionId);
        long adjusted = (long) faction.specialPower + amount;
        faction.specialPower = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, adjusted));
        preservePowerDeficit(factionId, previousMaximum);
        requireData().setDirty();
    }

    public FactionDisplay factionDisplay(UUID factionId) {
        FactionRecord faction = requireData().factions.get(factionId);
        return faction == null ? null : new FactionDisplay(faction.name, faction.color);
    }

    public String factionName(UUID factionId) {
        FactionRecord faction = requireData().factions.get(factionId);
        return faction == null ? null : faction.name;
    }

    public UUID factionByName(String name) {
        return requireData().factionRecords().stream()
                .filter(faction -> faction.name.equalsIgnoreCase(name))
                .map(faction -> faction.id)
                .findFirst().orElse(null);
    }

    public UUID createFaction(UUID ownerId, String name, long createdAt) {
        NativeFactionData state = requireData();
        if (state.members.containsKey(ownerId)) throw new IllegalStateException("Player is already in a faction");
        if (factionByName(name) != null) throw new IllegalArgumentException("A faction with that name already exists");
        UUID id = UUID.randomUUID();
        FactionRecord faction = new FactionRecord(id, name);
        faction.createdAt = Math.max(0L, createdAt);
        state.factions.put(id, faction);
        state.members.put(ownerId, new MemberRecord(id, FactionRank.OWNER));
        faction.power = baseMaximumPower(id);
        state.setDirty();
        return id;
    }

    public long createdAt(UUID factionId) {
        return requireFaction(factionId).createdAt;
    }

    public boolean hasAnchor(UUID factionId) {
        return requireData().anchors.values().stream().anyMatch(anchor -> anchor.factionId.equals(factionId));
    }

    public void disband(UUID factionId) {
        NativeFactionData state = requireData();
        if (state.factions.remove(factionId) == null) return;
        List<UUID> formerMembers = state.members.entrySet().stream()
                .filter(entry -> entry.getValue().factionId.equals(factionId))
                .map(Map.Entry::getKey).toList();
        state.members.entrySet().removeIf(entry -> entry.getValue().factionId.equals(factionId));
        formerMembers.forEach(playerId -> settings(playerId).chatMode = FactionChatMode.GLOBAL);
        state.claims.entrySet().removeIf(entry -> entry.getValue().factionId().equals(factionId));
        state.anchors.entrySet().removeIf(entry -> entry.getValue().factionId.equals(factionId));
        for (FactionRecord faction : state.factions.values()) {
            faction.relations.remove(factionId);
        }
        state.setDirty();
    }

    public void invite(UUID factionId, UUID playerId) {
        FactionRecord faction = requireFaction(factionId);
        faction.invites.add(playerId);
        requireData().setDirty();
    }

    public boolean isInvited(UUID factionId, UUID playerId) {
        return requireFaction(factionId).invites.contains(playerId);
    }

    public void join(UUID factionId, UUID playerId) {
        NativeFactionData state = requireData();
        if (state.members.containsKey(playerId)) throw new IllegalStateException("Player is already in a faction");
        FactionRecord faction = requireFaction(factionId);
        if (!faction.invites.remove(playerId)) throw new IllegalStateException("Player is not invited");
        state.members.put(playerId, new MemberRecord(factionId, FactionRank.MEMBER));
        faction.power = Math.min(baseMaximumPower(factionId),
                faction.power + TerraFactionsConfig.POWER_PER_MEMBER.get());
        state.setDirty();
    }

    public void leave(UUID playerId) {
        NativeFactionData state = requireData();
        MemberRecord member = state.members.get(playerId);
        if (member == null) return;
        if (member.rank == FactionRank.OWNER) throw new IllegalStateException("The owner must transfer ownership or disband");
        int previousMaximum = baseMaximumPower(member.factionId);
        state.members.remove(playerId);
        settings(playerId).chatMode = FactionChatMode.GLOBAL;
        preservePowerDeficit(member.factionId, previousMaximum);
        state.setDirty();
    }

    public void kick(UUID factionId, UUID playerId) {
        NativeFactionData state = requireData();
        MemberRecord member = state.members.get(playerId);
        if (member == null || !member.factionId.equals(factionId)) throw new IllegalStateException("Player is not in that faction");
        if (member.rank == FactionRank.OWNER) throw new IllegalStateException("The faction owner cannot be kicked");
        int previousMaximum = baseMaximumPower(factionId);
        state.members.remove(playerId);
        settings(playerId).chatMode = FactionChatMode.GLOBAL;
        preservePowerDeficit(factionId, previousMaximum);
        state.setDirty();
    }

    public void setRank(UUID factionId, UUID playerId, FactionRank rank) {
        MemberRecord member = requireData().members.get(playerId);
        if (member == null || !member.factionId.equals(factionId)) throw new IllegalStateException("Player is not in that faction");
        member.rank = Objects.requireNonNull(rank);
        requireData().setDirty();
    }

    public void transferOwnership(UUID factionId, UUID currentOwnerId, UUID newOwnerId) {
        NativeFactionData state = requireData();
        MemberRecord currentOwner = state.members.get(currentOwnerId);
        MemberRecord newOwner = state.members.get(newOwnerId);
        if (currentOwner == null || !currentOwner.factionId.equals(factionId)
                || currentOwner.rank != FactionRank.OWNER) {
            throw new IllegalStateException("Only the current faction owner can transfer ownership");
        }
        if (newOwner == null || !newOwner.factionId.equals(factionId)) {
            throw new IllegalStateException("The new owner must belong to the faction");
        }
        if (currentOwnerId.equals(newOwnerId)) {
            throw new IllegalArgumentException("That player already owns the faction");
        }
        currentOwner.rank = FactionRank.LEADER;
        newOwner.rank = FactionRank.OWNER;
        state.setDirty();
    }

    public FactionRelation relation(UUID viewerFactionId, UUID otherFactionId) {
        if (viewerFactionId == null || otherFactionId == null) return FactionRelation.NEUTRAL;
        if (viewerFactionId.equals(otherFactionId)) return FactionRelation.ALLIED;
        FactionRecord viewer = requireData().factions.get(viewerFactionId);
        FactionRecord other = requireData().factions.get(otherFactionId);
        if (viewer == null || other == null) return FactionRelation.NEUTRAL;
        FactionRelation forward = viewer.relations.getOrDefault(otherFactionId, FactionRelation.NEUTRAL);
        FactionRelation reverse = other.relations.getOrDefault(viewerFactionId, FactionRelation.NEUTRAL);
        if (forward == FactionRelation.ENEMY || reverse == FactionRelation.ENEMY) return FactionRelation.ENEMY;
        return forward == FactionRelation.ALLIED && reverse == FactionRelation.ALLIED
                ? FactionRelation.ALLIED : FactionRelation.NEUTRAL;
    }

    /** Returns one faction's declaration toward another without resolving the reciprocal relationship. */
    public FactionRelation declaredRelation(UUID factionId, UUID targetFactionId) {
        if (factionId == null || targetFactionId == null) return FactionRelation.NEUTRAL;
        if (factionId.equals(targetFactionId)) return FactionRelation.ALLIED;
        FactionRecord faction = requireData().factions.get(factionId);
        if (faction == null || !requireData().factions.containsKey(targetFactionId)) {
            return FactionRelation.NEUTRAL;
        }
        return faction.relations.getOrDefault(targetFactionId, FactionRelation.NEUTRAL);
    }

    public boolean isEnemy(UUID firstFactionId, UUID secondFactionId) {
        return relation(firstFactionId, secondFactionId) == FactionRelation.ENEMY;
    }

    public void setRelation(UUID factionId, UUID targetId, FactionRelation relation) {
        if (factionId.equals(targetId)) throw new IllegalArgumentException("A faction cannot target itself");
        FactionRecord faction = requireFaction(factionId);
        requireFaction(targetId);
        if (relation == FactionRelation.NEUTRAL) faction.relations.remove(targetId);
        else faction.relations.put(targetId, relation);
        requireData().setDirty();
    }

    public boolean hasBlockPermission(UUID ownerId, UUID playerId) {
        FactionIdentity actor = factionForPlayer(playerId);
        if (actor == null) return false;
        if (ownerId.equals(actor.id())) return actor.rank().canBuild();
        return relation(ownerId, actor.id()) == FactionRelation.ALLIED;
    }

    public boolean protectionEnabled(UUID factionId, TerritoryType territoryType, ProtectionAction action) {
        FactionRecord faction = requireFaction(factionId);
        int configured = territoryType == TerritoryType.BORDER
                ? faction.borderProtections : faction.coreProtections;
        return TerraFactionsConfig.protectionPolicy(territoryType, action)
                .resolve(action.enabledIn(configured));
    }

    public int effectiveProtectionMask(UUID factionId, TerritoryType territoryType) {
        int mask = 0;
        for (ProtectionAction action : ProtectionAction.values()) {
            if (protectionEnabled(factionId, territoryType, action)) mask |= action.bit();
        }
        return mask;
    }

    public int configurableProtectionMask(TerritoryType territoryType) {
        int mask = 0;
        for (ProtectionAction action : ProtectionAction.values()) {
            if (TerraFactionsConfig.protectionPolicy(territoryType, action).configurable()) {
                mask |= action.bit();
            }
        }
        return mask;
    }

    public void setProtection(UUID factionId, TerritoryType territoryType,
                              ProtectionAction action, boolean enabled) {
        ProtectionPolicy policy = TerraFactionsConfig.protectionPolicy(territoryType, action);
        if (!policy.configurable()) {
            throw new IllegalStateException("That protection is forced "
                    + (policy == ProtectionPolicy.FORCED_ON ? "on" : "off") + " by the server");
        }
        FactionRecord faction = requireFaction(factionId);
        int configured = territoryType == TerritoryType.BORDER
                ? faction.borderProtections : faction.coreProtections;
        configured = enabled ? configured | action.bit() : configured & ~action.bit();
        if (territoryType == TerritoryType.BORDER) faction.borderProtections = configured;
        else faction.coreProtections = configured;
        requireData().setDirty();
    }

    public TerritoryClaim claim(TerritoryKey key) {
        return requireData().claims.get(key);
    }

    public Collection<TerritoryClaim> allClaims() {
        return List.copyOf(requireData().claims.values());
    }

    public Collection<AnchorMapSnapshot> allAnchors() {
        return requireData().anchors.values().stream()
                .map(anchor -> new AnchorMapSnapshot(anchor.id, anchor.factionId, anchor.dimension,
                        anchor.x, anchor.y, anchor.z, anchor.tier, anchor.allocatedPower,
                        anchor.usablePowerTenths, anchor.priority, anchor.projectedRadius, anchor.projectedClaims,
                        anchor.powerState, anchor.connectionState, anchor.vulnerabilityState,
                        anchor.isolationStartTick))
                .toList();
    }

    public AnchorMapSnapshot anchor(String id) {
        AnchorRecord anchor = requireData().anchors.get(id);
        return anchor == null ? null : new AnchorMapSnapshot(anchor.id, anchor.factionId, anchor.dimension,
                anchor.x, anchor.y, anchor.z, anchor.tier, anchor.allocatedPower, anchor.usablePowerTenths,
                anchor.priority, anchor.projectedRadius, anchor.projectedClaims, anchor.powerState,
                anchor.connectionState, anchor.vulnerabilityState, anchor.isolationStartTick);
    }

    public void putAnchor(AnchorMapSnapshot anchor) {
        requireFaction(anchor.factionId());
        AnchorRecord existing = requireData().anchors.get(anchor.id());
        if (existing != null && existing.factionId.equals(anchor.factionId())
                && existing.dimension.equals(anchor.dimension()) && existing.x == anchor.x()
                && existing.y == anchor.y() && existing.z == anchor.z() && existing.tier == anchor.tier()
                && existing.allocatedPower == anchor.allocatedPower()
                && existing.usablePowerTenths == anchor.usablePowerTenths() && existing.priority == anchor.priority()
                && existing.projectedRadius == anchor.projectedRadius()
                && existing.projectedClaims == anchor.projectedClaims()
                && existing.powerState == anchor.powerState()
                && existing.connectionState == anchor.connectionState()
                && existing.vulnerabilityState == anchor.vulnerabilityState()
                && existing.isolationStartTick == anchor.isolationStartTick()) {
            return;
        }
        requireData().anchors.put(anchor.id(), new AnchorRecord(anchor.id(), anchor.factionId(),
                anchor.dimension(), anchor.x(), anchor.y(), anchor.z(), anchor.tier(),
                anchor.allocatedPower(), anchor.usablePowerTenths(), anchor.priority(), anchor.projectedRadius(),
                anchor.projectedClaims(), anchor.powerState(), anchor.connectionState(),
                anchor.vulnerabilityState(), anchor.isolationStartTick()));
        requireData().setDirty();
    }

    public void removeAnchor(String id) {
        if (requireData().anchors.remove(id) != null) requireData().setDirty();
    }

    /** Transfers one anchor and its currently owned projected footprint before global pressure reconciliation. */
    public AnchorMapSnapshot transferAnchorTerritory(String id, UUID expectedOwner, UUID newOwner, long now) {
        return transferAnchorTerritory(id, expectedOwner, newOwner, now, key -> key);
    }

    /** Transfers an anchor footprint using the world's canonical chunk topology. */
    public AnchorMapSnapshot transferAnchorTerritory(String id, UUID expectedOwner, UUID newOwner, long now,
                                                     UnaryOperator<TerritoryKey> normalizer) {
        AnchorMapSnapshot anchor = anchor(id);
        if (anchor == null) throw new IllegalArgumentException("Faction anchor does not exist");
        if (!anchor.factionId().equals(expectedOwner)) {
            throw new IllegalStateException("Faction anchor is no longer owned by the conquest target");
        }
        requireFaction(newOwner);
        AnchorMapSnapshot transferred = new AnchorMapSnapshot(anchor.id(), newOwner, anchor.dimension(),
                anchor.x(), anchor.y(), anchor.z(), anchor.tier(), anchor.allocatedPower(), 0,
                anchor.priority(), anchor.projectedRadius(), anchor.projectedClaims(),
                AnchorPowerState.UNPOWERED, AnchorConnectionState.ISOLATED,
                AnchorVulnerabilityState.GRACE_PERIOD, now);
        putAnchor(transferred);

        TerritoryKey center = normalizer.apply(new TerritoryKey(anchor.dimension(),
                Math.floorDiv(anchor.x(), 16), Math.floorDiv(anchor.z(), 16)));
        TerritoryClaim centerClaim = requireData().claims.get(center);
        if (centerClaim != null && centerClaim.factionId().equals(expectedOwner)) {
            TerritoryType transferredType = centerClaim.projected() ? centerClaim.type() : TerritoryType.CORE;
            requireData().claims.put(center,
                    new TerritoryClaim(center, newOwner, transferredType, centerClaim.projected()));
            FactionRecord previousOwner = requireFaction(expectedOwner);
            if (center.equals(previousOwner.capital)) {
                previousOwner.capital = null;
                repairCapital(expectedOwner);
            }
        }
        for (TerritoryKey key : TerritoryRules.circularProjection(
                center, anchor.projectedRadius(), normalizer)) {
            TerritoryClaim claim = requireData().claims.get(key);
            if (claim != null && claim.projected() && claim.factionId().equals(expectedOwner)) {
                requireData().claims.put(key, new TerritoryClaim(key, newOwner, claim.type(), true));
            }
        }
        requireData().setDirty();
        return transferred;
    }

    private void repairCapital(UUID factionId) {
        FactionRecord faction = requireFaction(factionId);
        TerritoryClaim replacement = requireData().claims.values().stream()
                .filter(claim -> claim.factionId().equals(factionId) && !claim.projected())
                .sorted(Comparator.comparing((TerritoryClaim claim) -> claim.key().dimension())
                        .thenComparingInt(claim -> claim.key().x()).thenComparingInt(claim -> claim.key().z()))
                .findFirst().orElse(null);
        if (replacement == null) return;
        faction.capital = replacement.key();
        requireData().claims.put(replacement.key(), new TerritoryClaim(replacement.key(), factionId,
                TerritoryType.CAPITAL, false));
    }

    public void putClaim(TerritoryKey key, UUID factionId, TerritoryType type) {
        requireFaction(factionId);
        requireData().claims.put(key, new TerritoryClaim(key, factionId, type));
        requireData().setDirty();
    }

    public void putProjectedClaim(TerritoryKey key, UUID factionId) {
        requireFaction(factionId);
        requireData().claims.put(key, new TerritoryClaim(key, factionId, TerritoryType.BORDER, true));
        requireData().setDirty();
    }

    public TerritoryClaim removeClaim(TerritoryKey key) {
        TerritoryClaim removed = requireData().claims.remove(key);
        if (removed != null) requireData().setDirty();
        return removed;
    }

    public int removeAllClaims(UUID factionId) {
        int before = requireData().claims.size();
        requireData().claims.entrySet().removeIf(entry -> entry.getValue().factionId().equals(factionId));
        int removed = before - requireData().claims.size();
        if (removed > 0) requireData().setDirty();
        return removed;
    }

    public TerritoryKey capital(UUID factionId) {
        return requireFaction(factionId).capital;
    }

    public void setCapital(UUID factionId, TerritoryKey key) {
        requireFaction(factionId).capital = key;
        requireData().setDirty();
    }

    public void clearCapital(UUID factionId) {
        requireFaction(factionId).capital = null;
        requireData().setDirty();
    }

    public String tag(UUID factionId) {
        return requireFaction(factionId).tag;
    }

    public void setTag(UUID factionId, String tag) {
        requireFaction(factionId).tag = tag;
        requireData().setDirty();
    }

    public void setName(UUID factionId, String name) {
        requireFaction(factionId).name = name;
        requireData().setDirty();
    }

    public void setDescription(UUID factionId, String description) {
        requireFaction(factionId).description = description;
        requireData().setDirty();
    }

    public void setColor(UUID factionId, int color) {
        requireFaction(factionId).color = color & 0xFFFFFF;
        requireData().setDirty();
    }

    public FactionChatMode chatMode(UUID playerId) {
        return settings(playerId).chatMode;
    }

    public void setChatMode(UUID playerId, FactionChatMode mode) {
        if (mode != FactionChatMode.GLOBAL && factionForPlayer(playerId) == null) {
            throw new IllegalStateException("Faction chat requires a faction");
        }
        settings(playerId).chatMode = mode;
        requireData().setDirty();
    }

    public boolean radarEnabled(UUID playerId) {
        return settings(playerId).radar;
    }

    public void setRadarEnabled(UUID playerId, boolean enabled) {
        settings(playerId).radar = enabled;
        requireData().setDirty();
    }

    public void adjustPower(UUID factionId, int amount) {
        FactionRecord faction = requireFaction(factionId);
        long adjusted = (long) faction.power + amount;
        faction.power = (int) Math.max(Integer.MIN_VALUE, Math.min(baseMaximumPower(factionId), adjusted));
        requireData().setDirty();
    }

    public void recordDeath(UUID factionId, UUID playerId, int penalty) {
        if (penalty <= 0) return;
        FactionRecord faction = requireFaction(factionId);
        int before = faction.power;
        adjustPower(factionId, -penalty);
        int actualLoss = Math.max(0, before - faction.power);
        if (actualLoss > 0) {
            faction.deathLosses.merge(playerId, actualLoss, NativeFactionService::saturatedAdd);
            requireData().setDirty();
        }
    }

    public void regeneratePower() {
        int amount = TerraFactionsConfig.POWER_REGEN_AMOUNT.get();
        if (amount == 0) return;
        for (FactionRecord faction : requireData().factions.values()) {
            var losses = faction.deathLosses.entrySet().iterator();
            while (losses.hasNext()) {
                Map.Entry<UUID, Integer> loss = losses.next();
                int attributedLoss = loss.getValue();
                if (attributedLoss <= 0) {
                    losses.remove();
                    continue;
                }
                int before = faction.power;
                adjustPower(faction.id, Math.min(amount, attributedLoss));
                int restored = Math.max(0, faction.power - before);
                if (restored == 0) continue;
                int remaining = attributedLoss - restored;
                if (remaining == 0) losses.remove();
                else loss.setValue(remaining);
                requireData().setDirty();
            }
        }
    }

    public Set<UUID> members(UUID factionId) {
        return requireData().members.entrySet().stream()
                .filter(entry -> entry.getValue().factionId.equals(factionId))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void clampPower(UUID factionId) {
        FactionRecord faction = requireFaction(factionId);
        faction.power = Math.min(faction.power, baseMaximumPower(factionId));
    }

    private void preservePowerDeficit(UUID factionId, int previousMaximum) {
        FactionRecord faction = requireFaction(factionId);
        long deficit = Math.max(0L, previousMaximum - (long) faction.power);
        long adjusted = baseMaximumPower(factionId) - deficit;
        faction.power = (int) Math.max(Integer.MIN_VALUE, adjusted);
    }

    private int temporaryPower(UUID factionId) {
        return Math.max(0, temporaryPowerProvider.applyAsInt(factionId));
    }

    private double powerSuppression(UUID factionId) {
        return Math.max(0.0D, Math.min(1.0D, powerSuppressionProvider.applyAsDouble(factionId)));
    }

    private static int saturatedPositiveAdd(int first, int second) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) first + Math.max(0, second)));
    }

    private static int saturatedAdd(int first, int second) {
        long value = (long) first + second;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private PlayerSettings settings(UUID playerId) {
        return requireData().settings.computeIfAbsent(playerId, ignored -> {
            requireData().setDirty();
            return new PlayerSettings();
        });
    }

    private FactionRecord requireFaction(UUID factionId) {
        FactionRecord faction = requireData().factions.get(factionId);
        if (faction == null) throw new IllegalStateException("Faction does not exist");
        return faction;
    }

    private NativeFactionData requireData() {
        if (data == null) throw new IllegalStateException("Faction data is not loaded");
        return data;
    }
}
