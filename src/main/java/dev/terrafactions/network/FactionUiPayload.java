package dev.terrafactions.network;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.factions.FactionChatMode;
import dev.terrafactions.factions.FactionRank;
import dev.terrafactions.factions.FactionRelation;
import dev.terrafactions.war.WarCampState;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarState;
import dev.terrafactions.anchor.AnchorTier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Complete read-only snapshot used by the faction management screen. */
public record FactionUiPayload(
        String name, String description, String tag, int color, int rankOrdinal,
        int power, int maximumPower, int claimUsage, int deathLoss, int specialPower,
        int temporaryPower, int suppressedPower, double suppressionPercent,
        int basePower, int powerPerMember, int coreClaimCost, int borderClaimCost,
        int capitalClaims, int coreClaims, int borderClaims, int projectedBorderClaims,
        int projectedClaimUsage, String capital,
        boolean coreVulnerable, boolean borderVulnerable,
        int coreProtectionMask, int borderProtectionMask,
        int coreConfigurableProtectionMask, int borderConfigurableProtectionMask,
        boolean radarEnabled, int chatModeOrdinal,
        List<MemberEntry> members, List<LossEntry> losses,
        List<FactionEntry> factions, List<WarEntry> wars, List<WarTargetEntry> warTargets,
        List<AdminFactionEntry> adminFactions) implements CustomPacketPayload {

    public static final Type<FactionUiPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "faction_ui"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FactionUiPayload> STREAM_CODEC = StreamCodec.of(
            FactionUiPayload::write, FactionUiPayload::read);

    public FactionUiPayload {
        members = List.copyOf(members);
        losses = List.copyOf(losses);
        factions = List.copyOf(factions);
        wars = List.copyOf(wars);
        warTargets = List.copyOf(warTargets);
        adminFactions = List.copyOf(adminFactions);
    }

    public static FactionUiPayload empty() {
        return new FactionUiPayload("", "", "", 0xAAAAAA, -1,
                0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", false, false,
                0, 0, 0, 0, true, FactionChatMode.GLOBAL.ordinal(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean hasFaction() {
        return rankOrdinal >= 0;
    }

    public FactionRank rank() {
        return enumValue(FactionRank.values(), rankOrdinal, null);
    }

    public FactionChatMode chatMode() {
        return enumValue(FactionChatMode.values(), chatModeOrdinal, FactionChatMode.GLOBAL);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, FactionUiPayload value) {
        buffer.writeUtf(value.name);
        buffer.writeUtf(value.description);
        buffer.writeUtf(value.tag);
        buffer.writeInt(value.color);
        buffer.writeInt(value.rankOrdinal);
        buffer.writeInt(value.power);
        buffer.writeInt(value.maximumPower);
        buffer.writeInt(value.claimUsage);
        buffer.writeInt(value.deathLoss);
        buffer.writeInt(value.specialPower);
        buffer.writeInt(value.temporaryPower);
        buffer.writeInt(value.suppressedPower);
        buffer.writeDouble(value.suppressionPercent);
        buffer.writeInt(value.basePower);
        buffer.writeInt(value.powerPerMember);
        buffer.writeInt(value.coreClaimCost);
        buffer.writeInt(value.borderClaimCost);
        buffer.writeInt(value.capitalClaims);
        buffer.writeInt(value.coreClaims);
        buffer.writeInt(value.borderClaims);
        buffer.writeInt(value.projectedBorderClaims);
        buffer.writeInt(value.projectedClaimUsage);
        buffer.writeUtf(value.capital);
        buffer.writeBoolean(value.coreVulnerable);
        buffer.writeBoolean(value.borderVulnerable);
        buffer.writeInt(value.coreProtectionMask);
        buffer.writeInt(value.borderProtectionMask);
        buffer.writeInt(value.coreConfigurableProtectionMask);
        buffer.writeInt(value.borderConfigurableProtectionMask);
        buffer.writeBoolean(value.radarEnabled);
        buffer.writeInt(value.chatModeOrdinal);
        buffer.writeVarInt(value.members.size());
        value.members.forEach(entry -> entry.write(buffer));
        buffer.writeVarInt(value.losses.size());
        value.losses.forEach(entry -> entry.write(buffer));
        buffer.writeVarInt(value.factions.size());
        value.factions.forEach(entry -> entry.write(buffer));
        buffer.writeVarInt(value.wars.size());
        value.wars.forEach(entry -> entry.write(buffer));
        buffer.writeVarInt(value.warTargets.size());
        value.warTargets.forEach(entry -> entry.write(buffer));
        buffer.writeVarInt(value.adminFactions.size());
        value.adminFactions.forEach(entry -> entry.write(buffer));
    }

    private static FactionUiPayload read(RegistryFriendlyByteBuf buffer) {
        String name = buffer.readUtf();
        String description = buffer.readUtf(256);
        String tag = buffer.readUtf(4);
        int color = buffer.readInt();
        int rank = buffer.readInt();
        int power = buffer.readInt();
        int maximumPower = buffer.readInt();
        int claimUsage = buffer.readInt();
        int deathLoss = buffer.readInt();
        int specialPower = buffer.readInt();
        int temporaryPower = buffer.readInt();
        int suppressedPower = buffer.readInt();
        double suppressionPercent = buffer.readDouble();
        int basePower = buffer.readInt();
        int powerPerMember = buffer.readInt();
        int coreClaimCost = buffer.readInt();
        int borderClaimCost = buffer.readInt();
        int capitalClaims = buffer.readInt();
        int coreClaims = buffer.readInt();
        int borderClaims = buffer.readInt();
        int projectedBorderClaims = buffer.readInt();
        int projectedClaimUsage = buffer.readInt();
        String capital = buffer.readUtf();
        boolean coreVulnerable = buffer.readBoolean();
        boolean borderVulnerable = buffer.readBoolean();
        int coreProtectionMask = buffer.readInt();
        int borderProtectionMask = buffer.readInt();
        int coreConfigurableProtectionMask = buffer.readInt();
        int borderConfigurableProtectionMask = buffer.readInt();
        boolean radarEnabled = buffer.readBoolean();
        int chatMode = buffer.readInt();
        List<MemberEntry> members = readList(buffer, MemberEntry::read);
        List<LossEntry> losses = readList(buffer, LossEntry::read);
        List<FactionEntry> factions = readList(buffer, FactionEntry::read);
        List<WarEntry> wars = readList(buffer, WarEntry::read);
        List<WarTargetEntry> warTargets = readList(buffer, WarTargetEntry::read);
        List<AdminFactionEntry> adminFactions = readList(buffer, AdminFactionEntry::read);
        return new FactionUiPayload(name, description, tag, color, rank, power, maximumPower,
                claimUsage, deathLoss, specialPower, temporaryPower, suppressedPower, suppressionPercent,
                basePower, powerPerMember,
                coreClaimCost, borderClaimCost,
                capitalClaims, coreClaims, borderClaims, projectedBorderClaims, projectedClaimUsage, capital,
                coreVulnerable, borderVulnerable,
                coreProtectionMask, borderProtectionMask,
                coreConfigurableProtectionMask, borderConfigurableProtectionMask,
                radarEnabled, chatMode, members, losses, factions,
                wars, warTargets, adminFactions);
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buffer, Reader<T> reader) {
        int size = Math.min(buffer.readVarInt(), 4096);
        List<T> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) result.add(reader.read(buffer));
        return result;
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    public record MemberEntry(String name, int rankOrdinal, boolean online, int deathLoss) {
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name);
            buffer.writeInt(rankOrdinal);
            buffer.writeBoolean(online);
            buffer.writeInt(deathLoss);
        }

        static MemberEntry read(RegistryFriendlyByteBuf buffer) {
            return new MemberEntry(buffer.readUtf(), buffer.readInt(), buffer.readBoolean(), buffer.readInt());
        }

        public FactionRank rank() {
            return enumValue(FactionRank.values(), rankOrdinal, FactionRank.MEMBER);
        }
    }

    public record LossEntry(String name, int amount, boolean currentMember) {
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name);
            buffer.writeInt(amount);
            buffer.writeBoolean(currentMember);
        }

        static LossEntry read(RegistryFriendlyByteBuf buffer) {
            return new LossEntry(buffer.readUtf(), buffer.readInt(), buffer.readBoolean());
        }
    }

    public record FactionEntry(String name, String tag, int color, int memberCount, int relationOrdinal,
                               int outgoingDeclarationOrdinal, int incomingDeclarationOrdinal) {
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name);
            buffer.writeUtf(tag);
            buffer.writeInt(color);
            buffer.writeInt(memberCount);
            buffer.writeInt(relationOrdinal);
            buffer.writeInt(outgoingDeclarationOrdinal);
            buffer.writeInt(incomingDeclarationOrdinal);
        }

        static FactionEntry read(RegistryFriendlyByteBuf buffer) {
            return new FactionEntry(buffer.readUtf(), buffer.readUtf(4), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt());
        }

        public FactionRelation relation() {
            return enumValue(FactionRelation.values(), relationOrdinal, FactionRelation.NEUTRAL);
        }

        public FactionRelation outgoingDeclaration() {
            return enumValue(FactionRelation.values(), outgoingDeclarationOrdinal, FactionRelation.NEUTRAL);
        }

        public FactionRelation incomingDeclaration() {
            return enumValue(FactionRelation.values(), incomingDeclarationOrdinal, FactionRelation.NEUTRAL);
        }

        public boolean allyProposed() {
            return relation() == FactionRelation.NEUTRAL
                    && outgoingDeclaration() == FactionRelation.ALLIED
                    && incomingDeclaration() != FactionRelation.ALLIED;
        }

        public boolean allyRequested() {
            return relation() == FactionRelation.NEUTRAL
                    && incomingDeclaration() == FactionRelation.ALLIED
                    && outgoingDeclaration() != FactionRelation.ALLIED;
        }
    }

    public record AdminFactionEntry(String name, String tag, int color, int power, int maximumPower,
                                    int specialPower) {
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(name);
            buffer.writeUtf(tag);
            buffer.writeInt(color);
            buffer.writeInt(power);
            buffer.writeInt(maximumPower);
            buffer.writeInt(specialPower);
        }

        static AdminFactionEntry read(RegistryFriendlyByteBuf buffer) {
            return new AdminFactionEntry(buffer.readUtf(), buffer.readUtf(4), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt());
        }
    }

    public record WarEntry(String id, String opponentName, int opponentColor, int stateOrdinal,
                           boolean attacker, int ownGoalOrdinal, int enemyGoalOrdinal,
                           int ownProgress, int ownRequired, boolean ownCompleted, boolean ownFailed,
                           int campStateOrdinal, int occupiedAnchors, long preparationEndsAt,
                           List<String> targetAnchorIds) {
        public WarEntry {
            targetAnchorIds = List.copyOf(targetAnchorIds);
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(id, 36);
            buffer.writeUtf(opponentName, 64);
            buffer.writeInt(opponentColor);
            buffer.writeInt(stateOrdinal);
            buffer.writeBoolean(attacker);
            buffer.writeInt(ownGoalOrdinal);
            buffer.writeInt(enemyGoalOrdinal);
            buffer.writeInt(ownProgress);
            buffer.writeInt(ownRequired);
            buffer.writeBoolean(ownCompleted);
            buffer.writeBoolean(ownFailed);
            buffer.writeInt(campStateOrdinal);
            buffer.writeInt(occupiedAnchors);
            buffer.writeLong(preparationEndsAt);
            buffer.writeVarInt(targetAnchorIds.size());
            targetAnchorIds.forEach(target -> buffer.writeUtf(target, 256));
        }

        static WarEntry read(RegistryFriendlyByteBuf buffer) {
            String id = buffer.readUtf(36);
            String opponent = buffer.readUtf(64);
            int color = buffer.readInt();
            int state = buffer.readInt();
            boolean attacker = buffer.readBoolean();
            int ownGoal = buffer.readInt();
            int enemyGoal = buffer.readInt();
            int progress = buffer.readInt();
            int required = buffer.readInt();
            boolean completed = buffer.readBoolean();
            boolean failed = buffer.readBoolean();
            int camp = buffer.readInt();
            int occupied = buffer.readInt();
            long preparation = buffer.readLong();
            int targetCount = Math.min(buffer.readVarInt(), 4096);
            List<String> targets = new ArrayList<>(targetCount);
            for (int index = 0; index < targetCount; index++) targets.add(buffer.readUtf(256));
            return new WarEntry(id, opponent, color, state, attacker, ownGoal, enemyGoal,
                    progress, required, completed, failed, camp, occupied, preparation, targets);
        }

        public WarState state() {
            return enumValue(WarState.values(), stateOrdinal, WarState.ENDED);
        }

        public WarGoalType ownGoal() {
            return enumValue(WarGoalType.values(), ownGoalOrdinal, null);
        }

        public WarGoalType enemyGoal() {
            return enumValue(WarGoalType.values(), enemyGoalOrdinal, null);
        }

        public WarCampState campState() {
            return enumValue(WarCampState.values(), campStateOrdinal, null);
        }
    }

    public record WarTargetEntry(String anchorId, String factionName, String dimension,
                                 int x, int y, int z, int tierOrdinal, int allocatedPower,
                                 boolean occupied, boolean breached) {
        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(anchorId, 256);
            buffer.writeUtf(factionName, 64);
            buffer.writeUtf(dimension, 256);
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
            buffer.writeInt(tierOrdinal);
            buffer.writeInt(allocatedPower);
            buffer.writeBoolean(occupied);
            buffer.writeBoolean(breached);
        }

        static WarTargetEntry read(RegistryFriendlyByteBuf buffer) {
            return new WarTargetEntry(buffer.readUtf(256), buffer.readUtf(64), buffer.readUtf(256),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readBoolean(), buffer.readBoolean());
        }

        public AnchorTier tier() {
            return enumValue(AnchorTier.values(), tierOrdinal, AnchorTier.BASIC);
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(RegistryFriendlyByteBuf buffer);
    }
}
