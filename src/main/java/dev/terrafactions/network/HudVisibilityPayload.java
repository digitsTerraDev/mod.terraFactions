package dev.terrafactions.network;

import dev.terrafactions.TerraFactions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client preference for transient TerraFactions HUD elements such as capture boss bars. */
public record HudVisibilityPayload(boolean visible) implements CustomPacketPayload {
    public static final Type<HudVisibilityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "hud_visibility"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HudVisibilityPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.visible),
            buffer -> new HudVisibilityPayload(buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
