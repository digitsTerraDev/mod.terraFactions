package dev.terrafactions.war;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-world persistent storage for war lifecycle state. */
final class WarSavedData extends SavedData {
    static final String DATA_NAME = "terrafactions_wars";
    final Map<UUID, WarSnapshot> wars = new HashMap<>();
    final Map<UUID, WarCampSnapshot> warCamps = new HashMap<>();
    final Map<String, AnchorOccupationSnapshot> occupations = new HashMap<>();
    final Map<String, AnchorSiegeSnapshot> sieges = new HashMap<>();
    final Map<UUID, TemporaryPowerModifierSnapshot> powerModifiers = new HashMap<>();
    final Map<UUID, PlunderBreachSnapshot> plunderBreaches = new HashMap<>();

    WarSavedData() {
    }

    WarSavedData(CompoundTag root, HolderLookup.Provider registries) {
        ListTag entries = root.getList("wars", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            WarSnapshot war = readWar(entries.getCompound(index));
            if (war != null) wars.put(war.id(), war);
        }
        ListTag camps = root.getList("war_camps", Tag.TAG_COMPOUND);
        for (int index = 0; index < camps.size(); index++) {
            WarCampSnapshot camp = readCamp(camps.getCompound(index));
            if (camp == null) continue;
            WarSnapshot war = wars.get(camp.warId());
            WarSideSnapshot owner = war == null ? null : war.side(camp.ownerFactionId());
            if (owner != null && camp.id().equals(owner.warCampId())) warCamps.put(camp.id(), camp);
        }
        ListTag occupationEntries = root.getList("anchor_occupations", Tag.TAG_COMPOUND);
        for (int index = 0; index < occupationEntries.size(); index++) {
            AnchorOccupationSnapshot occupation = readOccupation(occupationEntries.getCompound(index));
            if (validOccupation(occupation)) occupations.put(occupation.anchorId(), occupation);
        }
        ListTag siegeEntries = root.getList("anchor_sieges", Tag.TAG_COMPOUND);
        for (int index = 0; index < siegeEntries.size(); index++) {
            AnchorSiegeSnapshot siege = readSiege(siegeEntries.getCompound(index));
            if (validSiege(siege)) sieges.put(siege.anchorId(), siege);
        }
        ListTag modifierEntries = root.getList("power_modifiers", Tag.TAG_COMPOUND);
        for (int index = 0; index < modifierEntries.size(); index++) {
            TemporaryPowerModifierSnapshot modifier = readPowerModifier(modifierEntries.getCompound(index));
            WarSnapshot sourceWar = modifier == null ? null : wars.get(modifier.sourceWarId());
            if (sourceWar != null && sourceWar.side(modifier.factionId()) != null) {
                powerModifiers.put(modifier.id(), modifier);
            }
        }
        ListTag breachEntries = root.getList("plunder_breaches", Tag.TAG_COMPOUND);
        for (int index = 0; index < breachEntries.size(); index++) {
            PlunderBreachSnapshot breach = readPlunderBreach(breachEntries.getCompound(index));
            if (validPlunderBreach(breach)) plunderBreaches.put(breach.id(), breach);
        }
    }

    static SavedData.Factory<WarSavedData> factory() {
        return new SavedData.Factory<>(WarSavedData::new, WarSavedData::new);
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        wars.values().stream().sorted(java.util.Comparator.comparing(WarSnapshot::declaredAt))
                .map(WarSavedData::writeWar).forEach(entries::add);
        root.put("wars", entries);
        ListTag camps = new ListTag();
        warCamps.values().stream().sorted(java.util.Comparator.comparing(WarCampSnapshot::placementTime))
                .map(WarSavedData::writeCamp).forEach(camps::add);
        root.put("war_camps", camps);
        ListTag occupationEntries = new ListTag();
        occupations.values().stream().sorted(java.util.Comparator.comparing(AnchorOccupationSnapshot::anchorId))
                .map(WarSavedData::writeOccupation).forEach(occupationEntries::add);
        root.put("anchor_occupations", occupationEntries);
        ListTag siegeEntries = new ListTag();
        sieges.values().stream().sorted(java.util.Comparator.comparing(AnchorSiegeSnapshot::anchorId))
                .map(WarSavedData::writeSiege).forEach(siegeEntries::add);
        root.put("anchor_sieges", siegeEntries);
        ListTag modifierEntries = new ListTag();
        powerModifiers.values().stream()
                .sorted(java.util.Comparator.comparing(TemporaryPowerModifierSnapshot::startTime))
                .map(WarSavedData::writePowerModifier).forEach(modifierEntries::add);
        root.put("power_modifiers", modifierEntries);
        ListTag breachEntries = new ListTag();
        plunderBreaches.values().stream().sorted(java.util.Comparator.comparing(PlunderBreachSnapshot::startTime))
                .map(WarSavedData::writePlunderBreach).forEach(breachEntries::add);
        root.put("plunder_breaches", breachEntries);
        return root;
    }

    private static CompoundTag writePlunderBreach(PlunderBreachSnapshot breach) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", breach.id());
        tag.putUUID("war", breach.warId());
        tag.putString("anchor", breach.anchorId());
        tag.putUUID("original_owner", breach.originalOwnerFactionId());
        tag.putUUID("breaching_faction", breach.breachingFactionId());
        tag.putString("dimension", breach.dimension());
        tag.putInt("center_x", breach.centerChunkX());
        tag.putInt("center_z", breach.centerChunkZ());
        tag.putInt("radius", breach.radius());
        tag.putLong("start_time", breach.startTime());
        tag.putLong("expiration_time", breach.expirationTime());
        return tag;
    }

    private static PlunderBreachSnapshot readPlunderBreach(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("war") || !tag.hasUUID("original_owner")
                || !tag.hasUUID("breaching_faction")) return null;
        String anchorId = tag.getString("anchor");
        String dimension = tag.getString("dimension");
        if (anchorId.isBlank() || dimension.isBlank()) return null;
        try {
            return new PlunderBreachSnapshot(tag.getUUID("id"), tag.getUUID("war"), anchorId,
                    tag.getUUID("original_owner"), tag.getUUID("breaching_faction"), dimension,
                    tag.getInt("center_x"), tag.getInt("center_z"), tag.getInt("radius"),
                    tag.getLong("start_time"), tag.getLong("expiration_time"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CompoundTag writePowerModifier(TemporaryPowerModifierSnapshot modifier) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", modifier.id());
        tag.putUUID("faction", modifier.factionId());
        tag.putUUID("source_war", modifier.sourceWarId());
        tag.putDouble("initial_amount", modifier.initialAmount());
        tag.putLong("start_time", modifier.startTime());
        tag.putLong("expiration_time", modifier.expirationTime());
        tag.putString("type", modifier.type().name());
        return tag;
    }

    private static TemporaryPowerModifierSnapshot readPowerModifier(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("faction") || !tag.hasUUID("source_war")) return null;
        return new TemporaryPowerModifierSnapshot(tag.getUUID("id"), tag.getUUID("faction"),
                tag.getUUID("source_war"), tag.getDouble("initial_amount"), tag.getLong("start_time"),
                tag.getLong("expiration_time"), enumValue(PowerModifierType.class,
                tag.getString("type"), PowerModifierType.CONQUEST_INTEGRATION));
    }

    private boolean validOccupation(AnchorOccupationSnapshot occupation) {
        if (occupation == null) return false;
        WarSnapshot war = wars.get(occupation.warId());
        return war != null && war.state() != WarState.ENDED
                && war.side(occupation.originalOwnerFactionId()) != null
                && war.side(occupation.occupyingFactionId()) != null;
    }

    private boolean validSiege(AnchorSiegeSnapshot siege) {
        if (siege == null) return false;
        WarSnapshot war = wars.get(siege.warId());
        return war != null && war.state() == WarState.ACTIVE && war.side(siege.attackingFactionId()) != null;
    }

    private boolean validPlunderBreach(PlunderBreachSnapshot breach) {
        if (breach == null) return false;
        WarSnapshot war = wars.get(breach.warId());
        if (war == null || war.side(breach.originalOwnerFactionId()) == null) return false;
        WarSideSnapshot side = war.side(breach.breachingFactionId());
        return side != null && side.warGoal() != null && side.warGoal().type() == WarGoalType.PLUNDER
                && side.warGoal().targetAnchorIds().contains(breach.anchorId()) && !side.goalFailed();
    }

    private static CompoundTag writeOccupation(AnchorOccupationSnapshot occupation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("anchor", occupation.anchorId());
        tag.putUUID("original_owner", occupation.originalOwnerFactionId());
        tag.putUUID("occupier", occupation.occupyingFactionId());
        tag.putUUID("war", occupation.warId());
        tag.putLong("started_at", occupation.occupationStartTime());
        return tag;
    }

    private static AnchorOccupationSnapshot readOccupation(CompoundTag tag) {
        String anchorId = tag.getString("anchor");
        if (anchorId.isBlank() || !tag.hasUUID("original_owner") || !tag.hasUUID("occupier")
                || !tag.hasUUID("war")) return null;
        try {
            return new AnchorOccupationSnapshot(anchorId, tag.getUUID("original_owner"),
                    tag.getUUID("occupier"), tag.getUUID("war"), tag.getLong("started_at"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CompoundTag writeSiege(AnchorSiegeSnapshot siege) {
        CompoundTag tag = new CompoundTag();
        tag.putString("anchor", siege.anchorId());
        tag.putUUID("war", siege.warId());
        tag.putUUID("attacker", siege.attackingFactionId());
        tag.putInt("progress", siege.progress());
        tag.putLong("started_at", siege.startedAt());
        return tag;
    }

    private static AnchorSiegeSnapshot readSiege(CompoundTag tag) {
        String anchorId = tag.getString("anchor");
        if (anchorId.isBlank() || !tag.hasUUID("war") || !tag.hasUUID("attacker")) return null;
        return new AnchorSiegeSnapshot(anchorId, tag.getUUID("war"), tag.getUUID("attacker"),
                tag.getInt("progress"), tag.getLong("started_at"));
    }

    private static CompoundTag writeCamp(WarCampSnapshot camp) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", camp.id());
        tag.putUUID("war", camp.warId());
        tag.putUUID("owner", camp.ownerFactionId());
        tag.putString("dimension", camp.dimension());
        tag.putInt("x", camp.x());
        tag.putInt("y", camp.y());
        tag.putInt("z", camp.z());
        tag.putString("state", camp.state().name());
        tag.putLong("placement_time", camp.placementTime());
        tag.putLong("activation_time", camp.activationTime());
        tag.putInt("destruction_progress", camp.destructionProgress());
        return tag;
    }

    private static WarCampSnapshot readCamp(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("war") || !tag.hasUUID("owner")) return null;
        return new WarCampSnapshot(tag.getUUID("id"), tag.getUUID("war"), tag.getUUID("owner"),
                tag.getString("dimension"), tag.getInt("x"), tag.getInt("y"), tag.getInt("z"),
                enumValue(WarCampState.class, tag.getString("state"), WarCampState.DESTROYED),
                tag.getLong("placement_time"), tag.getLong("activation_time"),
                tag.getInt("destruction_progress"));
    }

    private static CompoundTag writeWar(WarSnapshot war) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", war.id());
        tag.putUUID("attacker", war.attackerFactionId());
        tag.putUUID("defender", war.defenderFactionId());
        tag.putString("state", war.state().name());
        tag.putLong("declared_at", war.declaredAt());
        tag.putLong("preparation_ends_at", war.preparationEndsAt());
        tag.putLong("started_at", war.startedAt());
        tag.putLong("ended_at", war.endedAt());
        tag.putLong("active_ends_at", war.activeEndsAt());
        tag.putLong("last_pressure_at", war.lastPressureAt());
        tag.put("attacker_side", writeSide(war.attacker()));
        tag.put("defender_side", writeSide(war.defender()));
        return tag;
    }

    private static WarSnapshot readWar(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("attacker") || !tag.hasUUID("defender")
                || !tag.contains("attacker_side", Tag.TAG_COMPOUND)
                || !tag.contains("defender_side", Tag.TAG_COMPOUND)) return null;
        try {
            return new WarSnapshot(tag.getUUID("id"), tag.getUUID("attacker"), tag.getUUID("defender"),
                    enumValue(WarState.class, tag.getString("state"), WarState.ENDED),
                    readSide(tag.getCompound("attacker_side")), readSide(tag.getCompound("defender_side")),
                    tag.getLong("declared_at"), tag.getLong("preparation_ends_at"),
                    tag.getLong("started_at"), tag.getLong("ended_at"),
                    tag.getLong("active_ends_at"), tag.getLong("last_pressure_at"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CompoundTag writeSide(WarSideSnapshot side) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("faction", side.factionId());
        if (side.warGoal() != null) tag.put("goal", writeGoal(side.warGoal()));
        if (side.warCampId() != null) tag.putUUID("war_camp", side.warCampId());
        tag.putBoolean("goal_completed", side.goalCompleted());
        tag.putBoolean("goal_failed", side.goalFailed());
        return tag;
    }

    private static WarSideSnapshot readSide(CompoundTag tag) {
        if (!tag.hasUUID("faction")) throw new IllegalArgumentException("Missing war side faction");
        WarGoalSnapshot goal = tag.contains("goal", Tag.TAG_COMPOUND)
                ? readGoal(tag.getCompound("goal")) : null;
        UUID camp = tag.hasUUID("war_camp") ? tag.getUUID("war_camp") : null;
        return new WarSideSnapshot(tag.getUUID("faction"), goal, camp,
                tag.getBoolean("goal_completed"), tag.getBoolean("goal_failed"));
    }

    private static CompoundTag writeGoal(WarGoalSnapshot goal) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", goal.type().name());
        tag.putInt("required_objective", goal.requiredObjectiveValue());
        tag.putInt("progress", goal.progress());
        tag.putString("result", goal.result());
        ListTag targets = new ListTag();
        for (String target : goal.targetAnchorIds()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("anchor", target);
            targets.add(entry);
        }
        tag.put("target_anchors", targets);
        return tag;
    }

    private static WarGoalSnapshot readGoal(CompoundTag tag) {
        Set<String> targets = new HashSet<>();
        ListTag entries = tag.getList("target_anchors", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            String target = entries.getCompound(index).getString("anchor");
            if (!target.isBlank()) targets.add(target);
        }
        return new WarGoalSnapshot(enumValue(WarGoalType.class, tag.getString("type"), WarGoalType.DEFENSE),
                targets, tag.getInt("required_objective"), tag.getInt("progress"), tag.getString("result"));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
