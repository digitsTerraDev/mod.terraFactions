package dev.terrafactions.network;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.factions.FactionRank;
import dev.terrafactions.war.WarCampState;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TerritoryRadarPayload(
        String territory,
        String faction,
        int relationColor,
        boolean vulnerable,
        boolean isolated,
        boolean factionInfoVisible,
        int factionRankOrdinal,
        int power,
        int maximumPower,
        boolean borderVulnerable,
        boolean coreVulnerable,
        String warOpponent,
        int warStateOrdinal,
        int ownWarGoalOrdinal,
        int enemyWarGoalOrdinal,
        int ownWarProgress,
        int ownWarRequired,
        boolean ownWarCompleted,
        boolean ownWarFailed,
        int warCampStateOrdinal)
        implements CustomPacketPayload {
    public static final Type<TerritoryRadarPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "territory_radar"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryRadarPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.territory);
                buffer.writeUtf(payload.faction);
                buffer.writeInt(payload.relationColor);
                buffer.writeBoolean(payload.vulnerable);
                buffer.writeBoolean(payload.isolated);
                buffer.writeBoolean(payload.factionInfoVisible);
                buffer.writeInt(payload.factionRankOrdinal);
                buffer.writeInt(payload.power);
                buffer.writeInt(payload.maximumPower);
                buffer.writeBoolean(payload.borderVulnerable);
                buffer.writeBoolean(payload.coreVulnerable);
                buffer.writeUtf(payload.warOpponent, 64);
                buffer.writeInt(payload.warStateOrdinal);
                buffer.writeInt(payload.ownWarGoalOrdinal);
                buffer.writeInt(payload.enemyWarGoalOrdinal);
                buffer.writeInt(payload.ownWarProgress);
                buffer.writeInt(payload.ownWarRequired);
                buffer.writeBoolean(payload.ownWarCompleted);
                buffer.writeBoolean(payload.ownWarFailed);
                buffer.writeInt(payload.warCampStateOrdinal);
            },
            buffer -> new TerritoryRadarPayload(
                    buffer.readUtf(), buffer.readUtf(), buffer.readInt(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(64), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readInt()));

    public static TerritoryRadarPayload hidden() {
        return new TerritoryRadarPayload("", "", 0, false, false, false, -1, 0, 0, false, false,
                "", -1, -1, -1, 0, 0, false, false, -1);
    }

    public boolean radarVisible() {
        return !territory.isEmpty();
    }

    public FactionRank factionRank() {
        FactionRank[] ranks = FactionRank.values();
        return factionRankOrdinal >= 0 && factionRankOrdinal < ranks.length
                ? ranks[factionRankOrdinal]
                : null;
    }

    public boolean warVisible() {
        return !warOpponent.isBlank();
    }

    public WarState warState() {
        return enumValue(WarState.values(), warStateOrdinal, null);
    }

    public WarGoalType ownWarGoal() {
        return enumValue(WarGoalType.values(), ownWarGoalOrdinal, null);
    }

    public WarGoalType enemyWarGoal() {
        return enumValue(WarGoalType.values(), enemyWarGoalOrdinal, null);
    }

    public WarCampState warCampState() {
        return enumValue(WarCampState.values(), warCampStateOrdinal, null);
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
