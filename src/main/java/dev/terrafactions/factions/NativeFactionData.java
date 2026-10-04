package dev.terrafactions.factions;

import dev.terrafactions.anchor.AnchorTier;
import dev.terrafactions.anchor.AnchorPowerState;
import dev.terrafactions.anchor.AnchorConnectionState;
import dev.terrafactions.anchor.AnchorVulnerabilityState;
import dev.terrafactions.territory.TerritoryClaim;
import dev.terrafactions.territory.TerritoryKey;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.territory.ProtectionAction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The complete, per-save source of truth for standalone TerraFactions. */
public final class NativeFactionData extends SavedData {
    public static final String DATA_NAME = "terrafactions";

    final Map<UUID, FactionRecord> factions = new HashMap<>();
    final Map<UUID, MemberRecord> members = new HashMap<>();
    final Map<TerritoryKey, TerritoryClaim> claims = new HashMap<>();
    final Map<String, AnchorRecord> anchors = new HashMap<>();
    final Map<UUID, PlayerSettings> settings = new HashMap<>();

    public NativeFactionData() {
    }

    NativeFactionData(CompoundTag root, HolderLookup.Provider registries) {
        loadFactions(root.getList("factions", Tag.TAG_COMPOUND));
        loadMembers(root.getList("members", Tag.TAG_COMPOUND));
        loadClaims(root.getList("claims", Tag.TAG_COMPOUND));
        loadAnchors(root.getList("anchors", Tag.TAG_COMPOUND));
        loadSettings(root.getList("settings", Tag.TAG_COMPOUND));
        audit();
    }

    public static SavedData.Factory<NativeFactionData> factory() {
        return new SavedData.Factory<>(NativeFactionData::new, NativeFactionData::new);
    }

    private void loadFactions(ListTag entries) {
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("id")) continue;
            UUID id = entry.getUUID("id");
            FactionRecord faction = new FactionRecord(id, entry.getString("name"));
            faction.createdAt = entry.contains("created_at") ? entry.getLong("created_at") : -1L;
            faction.description = entry.getString("description");
            faction.tag = entry.getString("tag");
            faction.color = entry.getInt("color");
            faction.power = entry.getInt("power");
            faction.specialPower = entry.getInt("special_power");
            faction.warWindowStartUtcMinute = entry.contains("war_window_utc_minute")
                    ? entry.getInt("war_window_utc_minute") : 720;
            faction.warWindowDurationMinutes = entry.contains("war_window_duration_minutes")
                    ? entry.getInt("war_window_duration_minutes") : 360;
            if (entry.contains("core_protections", Tag.TAG_INT)) {
                faction.coreProtections = entry.getInt("core_protections");
            }
            if (entry.contains("border_protections", Tag.TAG_INT)) {
                faction.borderProtections = entry.getInt("border_protections");
            }
            ListTag deathLosses = entry.getList("death_losses", Tag.TAG_COMPOUND);
            for (int j = 0; j < deathLosses.size(); j++) {
                CompoundTag loss = deathLosses.getCompound(j);
                if (loss.hasUUID("player") && loss.getInt("amount") > 0) {
                    faction.deathLosses.put(loss.getUUID("player"), loss.getInt("amount"));
                }
            }
            if (entry.contains("capital", Tag.TAG_COMPOUND)) {
                faction.capital = readKey(entry.getCompound("capital"));
            }
            ListTag invites = entry.getList("invites", Tag.TAG_COMPOUND);
            for (int j = 0; j < invites.size(); j++) {
                CompoundTag invite = invites.getCompound(j);
                if (invite.hasUUID("player")) faction.invites.add(invite.getUUID("player"));
            }
            ListTag relations = entry.getList("relations", Tag.TAG_COMPOUND);
            for (int j = 0; j < relations.size(); j++) {
                CompoundTag relation = relations.getCompound(j);
                if (relation.hasUUID("target")) {
                    faction.relations.put(relation.getUUID("target"),
                            enumValue(FactionRelation.class, relation.getString("status"), FactionRelation.NEUTRAL));
                }
            }
            factions.put(id, faction);
        }
    }

    private void loadMembers(ListTag entries) {
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (entry.hasUUID("player") && entry.hasUUID("faction")) {
                members.put(entry.getUUID("player"), new MemberRecord(entry.getUUID("faction"),
                        enumValue(FactionRank.class, entry.getString("rank"), FactionRank.MEMBER)));
            }
        }
    }

    private void loadClaims(ListTag entries) {
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("faction")) continue;
            TerritoryKey key = readKey(entry);
            UUID factionId = entry.getUUID("faction");
            TerritoryType type = enumValue(TerritoryType.class, entry.getString("type"), TerritoryType.BORDER);
            boolean projected = entry.getBoolean("projected");
            claims.put(key, new TerritoryClaim(key, factionId, type, projected,
                    entry.getString("source_anchor")));
        }
    }

    private void loadSettings(ListTag entries) {
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("player")) continue;
            settings.put(entry.getUUID("player"), new PlayerSettings(entry.getBoolean("radar"),
                    enumValue(FactionChatMode.class, entry.getString("chat"), FactionChatMode.GLOBAL)));
        }
    }

    private void loadAnchors(ListTag entries) {
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("faction")) continue;
            String id = entry.getString("id");
            anchors.put(id, new AnchorRecord(id, entry.getUUID("faction"), entry.getString("dimension"),
                    entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                    enumValue(AnchorTier.class, entry.getString("tier"), AnchorTier.BASIC),
                    entry.contains("allocated_power") ? entry.getInt("allocated_power")
                            : entry.getInt("dedicated_power"),
                    entry.getInt("usable_power_tenths"), entry.getInt("priority"),
                    entry.getInt("projected_radius"), entry.getInt("projected_claims"),
                    enumValue(AnchorPowerState.class, entry.getString("power_state"), AnchorPowerState.UNPOWERED),
                    enumValue(AnchorConnectionState.class, entry.getString("connection_state"), AnchorConnectionState.ISOLATED),
                    enumValue(AnchorVulnerabilityState.class, entry.getString("vulnerability_state"), AnchorVulnerabilityState.INACTIVE),
                    entry.getLong("isolation_start_tick"), entry.getBoolean("sky_exposed"),
                    entry.getBoolean("capital"), entry.getInt("siege_damage")));
        }
    }

    void audit() {
        members.entrySet().removeIf(entry -> !factions.containsKey(entry.getValue().factionId));
        claims.entrySet().removeIf(entry -> !factions.containsKey(entry.getValue().factionId()));
        anchors.entrySet().removeIf(entry -> !factions.containsKey(entry.getValue().factionId));
        settings.forEach((playerId, playerSettings) -> {
            if (!members.containsKey(playerId) && playerSettings.chatMode != FactionChatMode.GLOBAL) {
                playerSettings.chatMode = FactionChatMode.GLOBAL;
            }
        });
        for (FactionRecord faction : factions.values()) {
            faction.invites.removeIf(members::containsKey);
            faction.relations.keySet().removeIf(id -> id.equals(faction.id) || !factions.containsKey(id));
            if (faction.capital != null) {
                TerritoryClaim capitalClaim = claims.get(faction.capital);
                if (capitalClaim == null || !capitalClaim.factionId().equals(faction.id)) faction.capital = null;
            }
            if (!faction.tag.matches("[A-Z0-9_]{1,4}")) {
                faction.tag = FactionTags.defaultFor(faction.name, faction.id);
            }
            ensureSingleOwner(faction.id);
        }
    }

    private void ensureSingleOwner(UUID factionId) {
        var factionMembers = members.entrySet().stream()
                .filter(entry -> entry.getValue().factionId.equals(factionId))
                .sorted(Map.Entry.comparingByKey()).toList();
        if (factionMembers.isEmpty()) return;
        var owners = factionMembers.stream()
                .filter(entry -> entry.getValue().rank == FactionRank.OWNER).toList();
        Map.Entry<UUID, MemberRecord> owner = owners.isEmpty()
                ? factionMembers.stream().min((first, second) -> {
                    int rank = Integer.compare(first.getValue().rank.ordinal(), second.getValue().rank.ordinal());
                    return rank != 0 ? rank : first.getKey().compareTo(second.getKey());
                }).orElseThrow()
                : owners.getFirst();
        owner.getValue().rank = FactionRank.OWNER;
        owners.stream().skip(1).forEach(entry -> entry.getValue().rank = FactionRank.LEADER);
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.put("factions", saveFactions());
        root.put("members", saveMembers());
        root.put("claims", saveClaims());
        root.put("anchors", saveAnchors());
        root.put("settings", saveSettings());
        return root;
    }

    private ListTag saveFactions() {
        ListTag entries = new ListTag();
        for (FactionRecord faction : factions.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", faction.id);
            entry.putString("name", faction.name);
            entry.putString("description", faction.description);
            entry.putString("tag", faction.tag);
            entry.putInt("color", faction.color);
            entry.putInt("power", faction.power);
            entry.putInt("special_power", faction.specialPower);
            entry.putInt("war_window_utc_minute", faction.warWindowStartUtcMinute);
            entry.putInt("war_window_duration_minutes", faction.warWindowDurationMinutes);
            entry.putInt("core_protections", faction.coreProtections);
            entry.putInt("border_protections", faction.borderProtections);
            if (faction.createdAt >= 0L) entry.putLong("created_at", faction.createdAt);
            ListTag deathLosses = new ListTag();
            for (Map.Entry<UUID, Integer> loss : faction.deathLosses.entrySet()) {
                if (loss.getValue() <= 0) continue;
                CompoundTag value = new CompoundTag();
                value.putUUID("player", loss.getKey());
                value.putInt("amount", loss.getValue());
                deathLosses.add(value);
            }
            entry.put("death_losses", deathLosses);
            if (faction.capital != null) entry.put("capital", writeKey(faction.capital));
            ListTag invites = new ListTag();
            for (UUID playerId : faction.invites) {
                CompoundTag invite = new CompoundTag();
                invite.putUUID("player", playerId);
                invites.add(invite);
            }
            entry.put("invites", invites);
            ListTag relations = new ListTag();
            for (Map.Entry<UUID, FactionRelation> value : faction.relations.entrySet()) {
                CompoundTag relation = new CompoundTag();
                relation.putUUID("target", value.getKey());
                relation.putString("status", value.getValue().name());
                relations.add(relation);
            }
            entry.put("relations", relations);
            entries.add(entry);
        }
        return entries;
    }

    private ListTag saveMembers() {
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, MemberRecord> value : members.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", value.getKey());
            entry.putUUID("faction", value.getValue().factionId);
            entry.putString("rank", value.getValue().rank.name());
            entries.add(entry);
        }
        return entries;
    }

    private ListTag saveClaims() {
        ListTag entries = new ListTag();
        for (TerritoryClaim claim : claims.values()) {
            CompoundTag entry = writeKey(claim.key());
            entry.putUUID("faction", claim.factionId());
            entry.putString("type", claim.type().name());
            entry.putBoolean("projected", claim.projected());
            if (!claim.sourceAnchorId().isBlank()) entry.putString("source_anchor", claim.sourceAnchorId());
            entries.add(entry);
        }
        return entries;
    }

    private ListTag saveSettings() {
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, PlayerSettings> value : settings.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", value.getKey());
            entry.putBoolean("radar", value.getValue().radar);
            entry.putString("chat", value.getValue().chatMode.name());
            entries.add(entry);
        }
        return entries;
    }

    private ListTag saveAnchors() {
        ListTag entries = new ListTag();
        for (AnchorRecord anchor : anchors.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", anchor.id);
            entry.putUUID("faction", anchor.factionId);
            entry.putString("dimension", anchor.dimension);
            entry.putInt("x", anchor.x);
            entry.putInt("y", anchor.y);
            entry.putInt("z", anchor.z);
            entry.putString("tier", anchor.tier.name());
            entry.putInt("allocated_power", anchor.allocatedPower);
            entry.putInt("usable_power_tenths", anchor.usablePowerTenths);
            entry.putInt("priority", anchor.priority);
            entry.putInt("projected_radius", anchor.projectedRadius);
            entry.putInt("projected_claims", anchor.projectedClaims);
            entry.putString("power_state", anchor.powerState.name());
            entry.putString("connection_state", anchor.connectionState.name());
            entry.putString("vulnerability_state", anchor.vulnerabilityState.name());
            entry.putLong("isolation_start_tick", anchor.isolationStartTick);
            entry.putBoolean("sky_exposed", anchor.skyExposed);
            entry.putBoolean("capital", anchor.capital);
            entry.putInt("siege_damage", anchor.siegeDamage);
            entries.add(entry);
        }
        return entries;
    }

    private static TerritoryKey readKey(CompoundTag tag) {
        return new TerritoryKey(tag.getString("dimension"), tag.getInt("x"), tag.getInt("z"));
    }

    private static CompoundTag writeKey(TerritoryKey key) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", key.dimension());
        tag.putInt("x", key.x());
        tag.putInt("z", key.z());
        return tag;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    static final class FactionRecord {
        final UUID id;
        String name;
        String description = "";
        String tag;
        int color = 0xAAAAAA;
        int power;
        int specialPower;
        int warWindowStartUtcMinute = 720;
        int warWindowDurationMinutes = 360;
        int coreProtections = ProtectionAction.defaultMask(TerritoryType.CORE);
        int borderProtections = ProtectionAction.defaultMask(TerritoryType.BORDER);
        long createdAt = -1L;
        final Map<UUID, Integer> deathLosses = new HashMap<>();
        TerritoryKey capital;
        final Set<UUID> invites = new HashSet<>();
        final Map<UUID, FactionRelation> relations = new HashMap<>();

        FactionRecord(UUID id, String name) {
            this.id = id;
            this.name = name;
            this.tag = FactionTags.defaultFor(name, id);
        }
    }

    static final class MemberRecord {
        final UUID factionId;
        FactionRank rank;

        MemberRecord(UUID factionId, FactionRank rank) {
            this.factionId = factionId;
            this.rank = rank;
        }
    }

    static final class PlayerSettings {
        boolean radar;
        FactionChatMode chatMode;

        PlayerSettings() {
            this(true, FactionChatMode.GLOBAL);
        }

        PlayerSettings(boolean radar, FactionChatMode chatMode) {
            this.radar = radar;
            this.chatMode = chatMode;
        }
    }

    static final class AnchorRecord {
        final String id;
        final UUID factionId;
        final String dimension;
        final int x;
        final int y;
        final int z;
        final AnchorTier tier;
        final int allocatedPower;
        final int usablePowerTenths;
        final int priority;
        final int projectedRadius;
        final int projectedClaims;
        final AnchorPowerState powerState;
        final AnchorConnectionState connectionState;
        final AnchorVulnerabilityState vulnerabilityState;
        final long isolationStartTick;
        final boolean skyExposed;
        final boolean capital;
        final int siegeDamage;

        AnchorRecord(String id, UUID factionId, String dimension, int x, int y, int z, AnchorTier tier,
                     int allocatedPower, int usablePowerTenths, int priority, int projectedRadius, int projectedClaims,
                     AnchorPowerState powerState, AnchorConnectionState connectionState,
                     AnchorVulnerabilityState vulnerabilityState, long isolationStartTick,
                     boolean skyExposed, boolean capital, int siegeDamage) {
            this.id = id;
            this.factionId = factionId;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.allocatedPower = allocatedPower;
            this.usablePowerTenths = usablePowerTenths;
            this.priority = priority;
            this.projectedRadius = projectedRadius;
            this.projectedClaims = projectedClaims;
            this.powerState = powerState;
            this.connectionState = connectionState;
            this.vulnerabilityState = vulnerabilityState;
            this.isolationStartTick = isolationStartTick;
            this.skyExposed = skyExposed;
            this.capital = capital;
            this.siegeDamage = Math.max(0, siegeDamage);
        }
    }

    Collection<FactionRecord> factionRecords() {
        return factions.values();
    }
}
