package dev.terrafactions.network;

import dev.terrafactions.TerraFactions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Typed client request for a faction-dashboard mutation. */
public record FactionActionPayload(Action action, String primary, String secondary, String tertiary, boolean enabled)
        implements CustomPacketPayload {
    public static final Type<FactionActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "faction_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FactionActionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.action);
                buffer.writeUtf(payload.primary, 256);
                buffer.writeUtf(payload.secondary, 64);
                buffer.writeUtf(payload.tertiary, 256);
                buffer.writeBoolean(payload.enabled);
            },
            buffer -> new FactionActionPayload(buffer.readEnum(Action.class), buffer.readUtf(256),
                    buffer.readUtf(64), buffer.readUtf(256), buffer.readBoolean()));

    public FactionActionPayload(Action action) {
        this(action, "", "", "", false);
    }

    public FactionActionPayload(Action action, String primary, String secondary, boolean enabled) {
        this(action, primary, secondary, "", enabled);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        CREATE, JOIN, LEAVE, DISBAND,
        INVITE, KICK, SET_RANK,
        CLAIM_CORE, UNCLAIM, SET_CAPITAL,
        DECLARE_RELATION,
        SET_NAME, SET_DESCRIPTION, SET_COLOR, SET_TAG,
        SET_RADAR, SET_CHAT, SET_OVERLAY, SET_PROTECTION,
        IMPORT_PREVIEW, IMPORT_CONFIRM,
        ADMIN_GIVE_POWER, ADMIN_REMOVE_POWER,
        DECLARE_WAR, CHOOSE_WAR_GOAL, ADD_WAR_TARGET, REMOVE_WAR_TARGET, SELECT_WAR_CAMP
    }
}
